package com.dashlive.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer

/**
 * Player H.264 próprio para a câmera: RTSP por TCP (interleaved) e decodificação
 * direto na Surface com MediaCodec. Sem buffer, para o menor atraso possível.
 *
 * Os callbacks são chamados na thread de rede; quem usa deve passar para a UI.
 */
class NativePlayer(
    private val surface: Surface,
    private val url: String,
    private val onSize: (Int, Int) -> Unit,
    private val onLive: () -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    private var thread: Thread? = null

    private val startCode = byteArrayOf(0, 0, 0, 1)
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var alive = false
    private var drainThread: Thread? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var gotKey = false
    private var live = false
    private var auHasKey = false
    private var curTs = 0L
    private val au = ByteArrayOutputStream(64 * 1024)
    private val fu = ByteArrayOutputStream(64 * 1024)

    fun start() {
        running = true
        thread = Thread({ loop() }, "dashlive-rtsp").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { sock?.close() }
        runCatching { thread?.join(800) }
    }

    private fun fail(msg: String): Nothing = throw IllegalStateException(msg)

    private fun loop() {
        var rtsp: Rtsp? = null
        var error: String? = null
        try {
            val uri = URI(url)
            val port = if (uri.port > 0) uri.port else 554
            val r = Rtsp(uri.host, port)
            rtsp = r
            sock = r.socket
            r.open()

            val d = r.request("DESCRIBE", url, listOf("Accept: application/sdp"))
            if (d.code != 200) fail("DESCRIBE ${d.status}")
            val sdp = d.body
            if (!sdp.contains("H264", ignoreCase = true)) fail("a câmera não usa H.264")
            readSprop(sdp)

            val base = d.headers["content-base"] ?: url
            val video = parseTracks(sdp).firstOrNull { it.media == "video" } ?: fail("SDP sem vídeo")
            val s = r.request(
                "SETUP", trackUrl(base, video.control),
                listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
            )
            if (s.code != 200) fail("SETUP ${s.status}")
            val sessionHdr = s.headers["session"]?.substringBefore(';')?.let { "Session: $it" }
            val p = r.request("PLAY", base, listOfNotNull(sessionHdr, "Range: npt=0.000-"))
            if (p.code != 200) fail("PLAY ${p.status}")

            r.setTimeout(6000)
            readStream(r, r.stream(), url, sessionHdr)
        } catch (e: SocketTimeoutException) {
            error = "sem dados da câmera"
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            alive = false
            runCatching { drainThread?.join(600) }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            rtsp?.close()
        }
        if (running) {
            running = false
            Log.w(TAG, "$url: ${error ?: "conexão encerrada"}")
            onError(error ?: "conexão encerrada")
        }
    }

    private fun readSprop(sdp: String) {
        val line = sdp.lines().firstOrNull { it.contains("sprop-parameter-sets=") } ?: return
        line.substringAfter("sprop-parameter-sets=").substringBefore(';').trim().split(',').forEach { b64 ->
            val nal = runCatching { Base64.decode(b64.trim(), Base64.DEFAULT) }.getOrNull() ?: return@forEach
            if (nal.isEmpty()) return@forEach
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = H264Sps.lowLatency(nal)
                8 -> pps = nal
            }
        }
    }

    // ---------------------------------------------------------------- fluxo RTSP/RTP

    private fun readStream(r: Rtsp, inp: InputStream, keepUrl: String, sessionHdr: String?) {
        val buf = ByteArray(65536)
        var lastKeep = System.currentTimeMillis()
        while (running) {
            val b = inp.read()
            if (b < 0) fail("a câmera fechou a conexão")
            if (b == 0x24) { // '$' = quadro RTP intercalado
                val ch = inp.read()
                val hi = inp.read()
                val lo = inp.read()
                if (ch < 0 || hi < 0 || lo < 0) fail("a câmera fechou a conexão")
                val len = (hi shl 8) or lo
                readFully(inp, buf, len)
                if (ch == 0) rtp(buf, len)
            } else {
                skipTextResponse(inp, b)
            }
            val now = System.currentTimeMillis()
            if (now - lastKeep > 20_000) {
                r.send("OPTIONS", keepUrl, listOfNotNull(sessionHdr))
                lastKeep = now
            }
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) fail("a câmera fechou a conexão")
            off += n
        }
    }

    /** Resposta de texto (ex.: ao keep-alive) no meio do vídeo: lê e descarta. */
    private fun skipTextResponse(inp: InputStream, first: Int) {
        val head = StringBuilder()
        var last4 = first
        head.append(first.toChar())
        while (head.length < 8192) {
            val b = inp.read()
            if (b < 0) fail("a câmera fechou a conexão")
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var left = len
        while (left > 0) {
            val n = inp.skip(left.toLong()).toInt()
            if (n <= 0) { if (inp.read() < 0) fail("a câmera fechou a conexão"); left-- } else left -= n
        }
    }

    // ---------------------------------------------------------------- RTP / H.264

    private fun rtp(buf: ByteArray, len: Int) {
        if (len < 13) return
        val b0 = buf[0].toInt() and 0xFF
        val cc = b0 and 0x0F
        val hasExt = (b0 and 0x10) != 0
        val hasPad = (b0 and 0x20) != 0
        val marker = (buf[1].toInt() and 0x80) != 0
        curTs = ((buf[4].toLong() and 0xFF) shl 24) or ((buf[5].toLong() and 0xFF) shl 16) or
            ((buf[6].toLong() and 0xFF) shl 8) or (buf[7].toLong() and 0xFF)

        var off = 12 + cc * 4
        if (hasExt) {
            if (off + 4 > len) return
            off += 4 + (((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)) * 4
        }
        var end = len
        if (hasPad) end -= (buf[len - 1].toInt() and 0xFF)
        if (off >= end) return

        when (buf[off].toInt() and 0x1F) {
            in 1..23 -> nal(buf, off, end - off)
            24 -> { // STAP-A
                var p = off + 1
                while (p + 2 <= end) {
                    val size = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
                    p += 2
                    if (size <= 0 || p + size > end) break
                    nal(buf, p, size)
                    p += size
                }
            }
            28 -> { // FU-A
                if (end - off < 2) return
                val indicator = buf[off].toInt() and 0xFF
                val header = buf[off + 1].toInt() and 0xFF
                if ((header and 0x80) != 0) {
                    fu.reset()
                    fu.write((indicator and 0xE0) or (header and 0x1F))
                }
                fu.write(buf, off + 2, end - off - 2)
                if ((header and 0x40) != 0) {
                    val whole = fu.toByteArray()
                    fu.reset()
                    nal(whole, 0, whole.size)
                }
            }
        }
        if (marker) flushAu()
    }

    private fun nal(data: ByteArray, off: Int, size: Int) {
        if (size <= 0) return
        when (data[off].toInt() and 0x1F) {
            7 -> {
                val fixed = H264Sps.lowLatency(data.copyOfRange(off, off + size))
                sps = fixed
                au.write(startCode)
                au.write(fixed)
                return
            }
            8 -> pps = data.copyOfRange(off, off + size)
            5 -> auHasKey = true
            9 -> return // delimitador de unidade
        }
        au.write(startCode)
        au.write(data, off, size)
    }

    private fun flushAu() {
        if (au.size() == 0) return
        val bytes = au.toByteArray()
        au.reset()
        val key = auHasKey
        auHasKey = false

        if (codec == null && !initCodec()) return
        if (!gotKey) {
            if (!key) return
            gotKey = true
        }
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(4_000)
        if (idx < 0) {
            gotKey = false // decodificador atrasado: pula até o próximo quadro-chave
            return
        }
        run {
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            if (bytes.size <= ib.capacity()) {
                ib.put(bytes)
                c.queueInputBuffer(
                    idx, 0, bytes.size, curTs * 1_000_000L / 90_000L,
                    if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
            } else {
                c.queueInputBuffer(idx, 0, 0, 0, 0)
            }
        }
    }

    private fun initCodec(): Boolean {
        val s = sps ?: return false
        val p = pps ?: return false
        val (w, h) = H264Sps.size(s) ?: (1280 to 720)
        val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(startCode + s))
        fmt.setByteBuffer("csd-1", ByteBuffer.wrap(startCode + p))
        runCatching { fmt.setInteger("low-latency", 1) }
        runCatching { fmt.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1) } // Exynos (Samsung)
        runCatching { fmt.setInteger("vendor.qti-ext-dec-low-latency.enable", 1) } // Qualcomm
        runCatching { fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) }
        runCatching { fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, 120) }
        val c = MediaCodec.createDecoderByType("video/avc")
        try {
            c.configure(fmt, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            runCatching { c.release() }
            fail("decodificador: ${e.message}")
        }
        codec = c
        alive = true
        drainThread = Thread({ drainLoop(c) }, "dashlive-out").also { it.start() }
        onSize(w, h)
        return true
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (alive) {
            val i = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { return }
            when {
                i >= 0 -> {
                    runCatching { c.releaseOutputBuffer(i, true) } // mostra na hora
                    if (!live) {
                        live = true
                        Log.i(TAG, "$url: ao vivo")
                        onLive()
                    }
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    var w = f.getInteger(MediaFormat.KEY_WIDTH)
                    var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey("crop-left") && f.containsKey("crop-right") &&
                        f.containsKey("crop-top") && f.containsKey("crop-bottom")
                    ) {
                        w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                        h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                    }
                    onSize(w, h)
                }
                else -> {}
            }
        }
    }

    private companion object {
        const val TAG = "DashLive"
    }
}

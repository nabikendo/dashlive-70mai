package com.dashlive.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
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
    private var codec: MediaCodec? = null
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
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            rtsp?.close()
        }
        if (running) {
            running = false
            onError(error ?: "conexão encerrada")
        }
    }

    private fun readSprop(sdp: String) {
        val line = sdp.lines().firstOrNull { it.contains("sprop-parameter-sets=") } ?: return
        line.substringAfter("sprop-parameter-sets=").substringBefore(';').trim().split(',').forEach { b64 ->
            val nal = runCatching { Base64.decode(b64.trim(), Base64.DEFAULT) }.getOrNull() ?: return@forEach
            if (nal.isEmpty()) return@forEach
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
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
            7 -> sps = data.copyOfRange(off, off + size)
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
        val idx = c.dequeueInputBuffer(20_000)
        if (idx >= 0) {
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
        drain(c)
    }

    private fun initCodec(): Boolean {
        val s = sps ?: return false
        val p = pps ?: return false
        val (w, h) = parseSpsSize(s) ?: (1280 to 720)
        val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(startCode + s))
        fmt.setByteBuffer("csd-1", ByteBuffer.wrap(startCode + p))
        runCatching { fmt.setInteger("low-latency", 1) }
        runCatching { fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) }
        val c = MediaCodec.createDecoderByType("video/avc")
        try {
            c.configure(fmt, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            runCatching { c.release() }
            fail("decodificador: ${e.message}")
        }
        codec = c
        onSize(w, h)
        return true
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = c.dequeueOutputBuffer(info, 0)
            when {
                i >= 0 -> {
                    c.releaseOutputBuffer(i, true) // mostra na hora
                    if (!live) { live = true; onLive() }
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
                else -> return
            }
        }
    }

    // ---------------------------------------------------------------- SPS

    private class Bits(val d: ByteArray) {
        var pos = 0
        fun bit(): Int {
            val i = pos shr 3
            val v = if (i < d.size) (d[i].toInt() shr (7 - (pos and 7))) and 1 else 0
            pos++
            return v
        }
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var z = 0
            while (bit() == 0 && z < 32) z++
            return if (z == 0) 0 else (1 shl z) - 1 + u(z)
        }
        fun se(): Int { val k = ue(); return if (k % 2 == 1) (k + 1) / 2 else -(k / 2) }
    }

    /** Lê largura e altura do SPS (H.264). Devolve null se não conseguir. */
    private fun parseSpsSize(sps: ByteArray): Pair<Int, Int>? = try {
        val raw = ByteArrayOutputStream()
        var zeros = 0
        for (i in 1 until sps.size) {
            val x = sps[i].toInt() and 0xFF
            if (zeros >= 2 && x == 3) { zeros = 0; continue }
            raw.write(x)
            zeros = if (x == 0) zeros + 1 else 0
        }
        val b = Bits(raw.toByteArray())
        val profile = b.u(8); b.u(8); b.u(8); b.ue()
        var chroma = 1
        if (profile in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
            chroma = b.ue()
            if (chroma == 3) b.u(1)
            b.ue(); b.ue(); b.u(1)
            if (b.u(1) == 1) {
                val n = if (chroma != 3) 8 else 12
                for (i in 0 until n) if (b.u(1) == 1) {
                    val size = if (i < 6) 16 else 64
                    var last = 8
                    var next = 8
                    for (j in 0 until size) {
                        if (next != 0) next = (last + b.se() + 256) % 256
                        if (next != 0) last = next
                    }
                }
            }
        }
        b.ue()
        val poc = b.ue()
        if (poc == 0) b.ue()
        else if (poc == 1) { b.u(1); b.se(); b.se(); repeat(b.ue()) { b.se() } }
        b.ue(); b.u(1)
        val w = (b.ue() + 1) * 16
        val hMap = b.ue() + 1
        val frameMbsOnly = b.u(1)
        val h = (2 - frameMbsOnly) * hMap * 16
        if (frameMbsOnly == 0) b.u(1)
        b.u(1)
        var cl = 0; var cr = 0; var ct = 0; var cb = 0
        if (b.u(1) == 1) { cl = b.ue(); cr = b.ue(); ct = b.ue(); cb = b.ue() }
        val unitX = if (chroma == 0) 1 else 2
        val unitY = (if (chroma == 0) 1 else 2) * (2 - frameMbsOnly)
        val outW = w - unitX * (cl + cr)
        val outH = h - unitY * (ct + cb)
        if (outW in 16..8192 && outH in 16..8192) outW to outH else null
    } catch (e: Exception) {
        null
    }
}

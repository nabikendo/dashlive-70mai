package com.dashlive.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Resposta RTSP já lida. */
class Resp(val status: String, val headers: Map<String, String>, val body: String) {
    val code: Int get() = status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
}

/** Cliente RTSP mínimo sobre TCP, usado pelo diagnóstico e pelo player nativo. */
class Rtsp(private val ip: String, private val port: Int) {
    val socket = Socket()
    private lateinit var inp: InputStream
    private var cseq = 0

    fun open() {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(ip, port), 3000)
        socket.soTimeout = 3500
        inp = socket.getInputStream().buffered(65536)
    }

    fun stream(): InputStream = inp
    fun setTimeout(ms: Int) { socket.soTimeout = ms }
    fun close() { runCatching { socket.close() } }

    /** Envia sem esperar resposta (a resposta chega junto do fluxo de vídeo). */
    fun send(method: String, url: String, headers: List<String> = emptyList()) {
        cseq++
        val sb = StringBuilder("$method $url RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: DashLive\r\n")
        headers.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        socket.getOutputStream().apply { write(sb.toString().toByteArray()); flush() }
    }

    fun request(method: String, url: String, headers: List<String> = emptyList()): Resp {
        send(method, url, headers)
        return readResp()
    }

    private fun readResp(): Resp {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = inp.read()
            if (b < 0) break
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A || buf.size() > 16384) break
        }
        val lines = buf.toString("ISO-8859-1").split("\r\n")
        val headers = lines.drop(1).filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(body, off, len - off)
            if (n < 0) break
            off += n
        }
        return Resp(lines.firstOrNull().orEmpty(), headers, String(body, 0, off, Charsets.ISO_8859_1))
    }
}

data class Track(val media: String, val control: String)

fun parseTracks(sdp: String): List<Track> {
    val out = mutableListOf<Track>()
    var media: String? = null
    for (raw in sdp.lines()) {
        val l = raw.trim()
        if (l.startsWith("m=")) media = l.substring(2).substringBefore(' ')
        else if (l.startsWith("a=control:") && media != null) out += Track(media, l.removePrefix("a=control:"))
    }
    return out
}

fun trackUrl(base: String, control: String) = when {
    control.startsWith("rtsp://") -> control
    control == "*" -> base
    else -> base.trimEnd('/') + "/" + control
}

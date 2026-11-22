package com.dashlive.app

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Testa a câmera e diz onde o vídeo responde. Roda fora da thread principal.
 * Os caminhos RTSP vêm do app oficial da 70mai (v4.5.0).
 */
object Diagnostics {

    data class Result(val report: String, val workingUrl: String?)

    private val ports = listOf(80, 554, 8080, 8554, 8192, 23)
    private val rtspPaths = listOf(
        "/livestream/12", "/livestream/13",
        "/liveRTSP/av1", "/liveRTSP/av4", "/liveRTSP/v1", "/liveRTSP/av2", "/liveRTSP/av5",
    )
    private val httpPaths = listOf(
        "/", "/app/getdeviceattr.cgi", "/cgi-bin/getdeviceattr.cgi",
        "/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*",
    )

    fun run(ip: String): Result {
        val out = StringBuilder("Câmera: $ip\n")
        var working: String? = null

        out.append("\nPortas TCP\n")
        val open = mutableSetOf<Int>()
        for (p in ports) {
            val ok = portOpen(ip, p)
            if (ok) open += p
            out.append("  $p  ${if (ok) "ABERTA" else "fechada"}\n")
        }

        val rtspPort = if (554 in open) 554 else if (8554 in open) 8554 else null
        out.append("\nVídeo RTSP\n")
        if (rtspPort == null) {
            out.append("  porta de vídeo fechada; nenhum teste feito\n")
        } else {
            for (path in rtspPaths) {
                val line = rtsp(ip, rtspPort, path)
                out.append("  $path  →  $line\n")
                if (working == null && line.contains(" 200")) working = "rtsp://$ip:$rtspPort$path"
            }
        }

        var tcpOk = false
        var udpOk = false
        if (working != null && rtspPort != null) {
            val path = working.substringAfter(":$rtspPort")
            out.append("\nTeste completo de $path\n")
            out.append("  SDP:\n").append(sdpOf(ip, rtspPort, working)).append("\n")
            val tcp = tcpTest(ip, rtspPort, working)
            val udp = udpTest(ip, rtspPort, working)
            tcpOk = tcp.startsWith("OK")
            udpOk = udp.startsWith("OK")
            out.append("  via TCP: $tcp\n  via UDP: $udp\n")
        }

        out.append("\nHTTP\n")
        if (80 !in open && 8080 !in open) {
            out.append("  portas 80 e 8080 fechadas; nenhum teste feito\n")
        } else {
            val port = if (80 in open) 80 else 8080
            for (path in httpPaths) out.append("  $path  →  ${http(ip, port, path)}\n")
        }

        out.append("\n")
        out.append(
            when {
                working != null && tcpOk && !udpOk ->
                    "Vídeo encontrado em $working. A câmera só entrega por TCP; o app alterna para TCP sozinho."
                working != null && !tcpOk && udpOk ->
                    "Vídeo encontrado em $working. A câmera só entrega por UDP."
                working != null && tcpOk && udpOk ->
                    "Vídeo encontrado em $working e os dados chegam por TCP e UDP. Se ainda não há imagem, " +
                        "o problema é o formato do vídeo (veja o codec no SDP acima)."
                working != null ->
                    "O RTSP aceita a conexão, mas nenhum dado de vídeo chegou. Feche o app oficial da 70mai " +
                        "(só um aparelho recebe o vídeo por vez) e tente de novo."
                rtspPort != null ->
                    "A porta de vídeo abre, mas nenhum endereço entregou imagem. Veja os códigos acima: " +
                        "401/403 = a câmera exige autenticação ou pareamento com o app oficial; 404 = caminho errado."
                open.isEmpty() ->
                    "A câmera não respondeu em nenhuma porta. Confira se o tablet está mesmo no Wi-Fi da câmera " +
                        "e se o endereço acima é o da câmera."
                else ->
                    "A câmera responde, mas não abre a porta de vídeo. Ela pode exigir pareamento (botão de " +
                        "energia) pelo app oficial antes de liberar o vídeo."
            }
        )
        return Result(out.toString(), working)
    }

    private fun portOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 1500); true }
    } catch (e: Exception) {
        false
    }

    private fun rtsp(ip: String, port: Int, path: String): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 2500)
            s.soTimeout = 3500
            val req = "DESCRIBE rtsp://$ip:$port$path RTSP/1.0\r\nCSeq: 1\r\n" +
                "Accept: application/sdp\r\nUser-Agent: DashLive\r\n\r\n"
            s.getOutputStream().apply { write(req.toByteArray()); flush() }
            BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: "sem resposta"
        }
    } catch (e: Exception) {
        "erro (${e.javaClass.simpleName})"
    }

    private fun http(ip: String, port: Int, path: String): String = try {
        val c = URL("http://$ip:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = 3000
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)
            ?.bufferedReader()?.use { r ->
                val buf = CharArray(120)
                val n = r.read(buf)
                if (n > 0) String(buf, 0, n).replace('\n', ' ').replace('\r', ' ') else ""
            }.orEmpty()
        "$code $body".trim()
    } catch (e: Exception) {
        "erro (${e.javaClass.simpleName})"
    }

    // ---------------------------------------------------------------- RTSP completo

    private fun sdpOf(ip: String, port: Int, url: String): String {
        val r = Rtsp(ip, port)
        return try {
            r.open()
            val d = r.request("DESCRIBE", url, listOf("Accept: application/sdp"))
            d.body.lines().map { it.trim() }
                .filter {
                    it.startsWith("m=") || it.startsWith("a=rtpmap") || it.startsWith("a=fmtp") ||
                        it.startsWith("a=control") || it.startsWith("a=framerate")
                }
                .take(16).joinToString("\n") { "    " + it.take(110) }
                .ifBlank { "    (SDP vazio)" }
        } catch (e: Exception) {
            "    erro (${e.javaClass.simpleName})"
        } finally {
            r.close()
        }
    }

    /** Abre uma sessão RTSP, faz SETUP do vídeo com [transport] e dá PLAY. Devolve a sessão aberta. */
    private fun startSession(ip: String, port: Int, url: String, transport: String): Pair<Rtsp, String?> {
        val r = Rtsp(ip, port)
        r.open()
        val d = r.request("DESCRIBE", url, listOf("Accept: application/sdp"))
        val base = d.headers["content-base"] ?: url
        val v = parseTracks(d.body).firstOrNull { it.media == "video" }
            ?: return r to "sem trilha de vídeo no SDP"
        val s = r.request("SETUP", trackUrl(base, v.control), listOf("Transport: $transport"))
        if (s.code != 200) return r to "SETUP ${s.status}"
        val session = s.headers["session"]?.substringBefore(';')
        val p = r.request("PLAY", base, listOfNotNull(session?.let { "Session: $it" }, "Range: npt=0.000-"))
        if (p.code != 200) return r to "PLAY ${p.status}"
        return r to null
    }

    private fun tcpTest(ip: String, port: Int, url: String): String {
        var r: Rtsp? = null
        return try {
            val (rtsp, err) = startSession(ip, port, url, "RTP/AVP/TCP;unicast;interleaved=0-1")
            r = rtsp
            if (err != null) return err
            rtsp.setTimeout(1000)
            val inp = rtsp.stream()
            val buf = ByteArray(8192)
            var total = 0L
            val start = System.nanoTime()
            while (System.nanoTime() - start < 3_000_000_000L && total < 300_000) {
                val n = try { inp.read(buf) } catch (e: SocketTimeoutException) { 0 }
                if (n < 0) break
                total += n
            }
            if (total > 0) "OK, recebeu $total bytes em 3 s" else "PLAY ok, mas nenhum dado chegou"
        } catch (e: Exception) {
            "erro (${e.javaClass.simpleName}: ${e.message})"
        } finally {
            r?.close()
        }
    }

    private fun udpTest(ip: String, port: Int, url: String): String {
        var rtp: DatagramSocket? = null
        var rtcp: DatagramSocket? = null
        var r: Rtsp? = null
        return try {
            for (i in 0 until 20) {
                val p = 40000 + 2 * kotlin.random.Random.nextInt(0, 9000)
                try {
                    rtp = DatagramSocket(p)
                    rtcp = DatagramSocket(p + 1)
                    break
                } catch (e: Exception) {
                    rtp?.close(); rtcp?.close(); rtp = null; rtcp = null
                }
            }
            val sock = rtp ?: return "sem porta UDP livre no tablet"
            val (rtsp, err) = startSession(
                ip, port, url, "RTP/AVP;unicast;client_port=${sock.localPort}-${sock.localPort + 1}"
            )
            r = rtsp
            if (err != null) return err
            sock.soTimeout = 1000
            val pkt = DatagramPacket(ByteArray(2048), 2048)
            var n = 0
            var bytes = 0L
            val start = System.nanoTime()
            while (System.nanoTime() - start < 3_000_000_000L) {
                pkt.length = 2048
                try { sock.receive(pkt); n++; bytes += pkt.length } catch (e: SocketTimeoutException) { }
            }
            if (n > 0) "OK, recebeu $n pacotes ($bytes bytes) em 3 s" else "PLAY ok, mas nenhum pacote UDP chegou"
        } catch (e: Exception) {
            "erro (${e.javaClass.simpleName}: ${e.message})"
        } finally {
            r?.close(); rtp?.close(); rtcp?.close()
        }
    }
}

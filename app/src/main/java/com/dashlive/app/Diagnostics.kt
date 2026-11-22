package com.dashlive.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
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
                working != null -> "Vídeo encontrado em $working"
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
}

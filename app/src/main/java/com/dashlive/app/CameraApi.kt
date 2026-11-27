package com.dashlive.app

import android.net.Network
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Comandos HTTP da A200 (API HiSilicon em /cgi-bin/<nome>.cgi?&-<param>=<valor>).
 *
 * A câmera tem UM vídeo ao vivo: todos os caminhos RTSP entregam a lente que estiver ativa.
 * A lente é trocada por `setwifistream.cgi?&-sensor=0|1` (0 = frontal, 1 = traseira), comando
 * achado no app oficial (constantes `setwifistream.cgi` + parâmetro `sensor`, montado como
 * `?&-nome=valor`). Não exige pareamento e a câmera guarda a escolha.
 */
object CameraApi {

    /**
     * A rede [network] é a câmera? Pergunta `getdeviceattr.cgi` ao gateway (só a câmera responde
     * com ResultCode). Tenta algumas vezes: logo depois de ligar, o HTTP da câmera demora a subir.
     */
    fun isCamera(network: Network, ip: String, tries: Int = 5): Boolean {
        repeat(tries) { attempt ->
            try {
                val c = network.openConnection(URL("http://$ip/cgi-bin/getdeviceattr.cgi")) as HttpURLConnection
                c.connectTimeout = 1500
                c.readTimeout = 2000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val yes = body.contains("\"ResultCode\"") && body.contains("\"name\"")
                Log.i("DashLive", "rede com gateway $ip: ${if (yes) "é a câmera" else "não é a câmera"}")
                return yes
            } catch (e: Exception) {
                if (attempt < tries - 1) Thread.sleep(1500)
            }
        }
        Log.i("DashLive", "rede com gateway $ip: não respondeu como câmera")
        return false
    }

    /** Pede a lente do vídeo ao vivo. Devolve true se a câmera respondeu ResultCode 0. */
    fun setLens(ip: String, rear: Boolean): Boolean = try {
        val c = URL("http://$ip/cgi-bin/setwifistream.cgi?&-sensor=${if (rear) 1 else 0}")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 2500
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val ok = body.replace(" ", "").contains("\"ResultCode\":\"0\"")
        Log.i("DashLive", "lente ${if (rear) "traseira" else "frontal"}: ${if (ok) "ok" else body.take(80)}")
        ok
    } catch (e: Exception) {
        Log.w("DashLive", "troca de lente falhou: ${e.message}")
        false
    }

    /**
     * Estado da câmera por `getdvrstate.cgi` (funciona sem pareamento; testado na A200 em 27/11/2026):
     * `{"rec":"1","backcam":"1","sensor":"0","parking":"0","timelapse":"0"}`.
     */
    data class DvrState(val recording: Boolean, val rearConnected: Boolean, val rearActive: Boolean, val parking: Boolean) {
        companion object {
            /** Lê a resposta; null se não for ResultCode 0 ou não for JSON. */
            fun parse(body: String): DvrState? = try {
                val o = org.json.JSONObject(body.substring(body.indexOf('{')))
                if (o.optString("ResultCode") != "0") null else {
                    val r = o.getJSONObject("Result")
                    DvrState(
                        recording = r.optString("rec") == "1",
                        rearConnected = r.optString("backcam") == "1",
                        rearActive = r.optString("sensor") == "1",
                        parking = r.optString("parking") == "1",
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Pergunta o estado à câmera (gravando, traseira ligada, lente ativa); null se não respondeu. */
    fun dvrState(ip: String): DvrState? = try {
        val c = URL("http://$ip/cgi-bin/getdvrstate.cgi").openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 2500
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        DvrState.parse(body)
    } catch (e: Exception) {
        Log.w("DashLive", "estado da câmera falhou: ${e.message}")
        null
    }
}

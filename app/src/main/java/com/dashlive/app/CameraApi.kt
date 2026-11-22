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
}

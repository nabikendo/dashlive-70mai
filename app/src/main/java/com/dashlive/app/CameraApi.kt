package com.dashlive.app

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

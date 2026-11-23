package com.dashlive.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Baixa as ruas em volta de uma posição do OpenStreetMap (servidor público Overpass), pela rede que
 * tem internet (dados móveis ou Wi-Fi comum; nunca o Wi-Fi da câmera, que não tem internet).
 * Cada resposta fica guardada no aparelho: sem internet, usa a guardada mais perto (até [RADIUS] m),
 * então trajetos já feitos funcionam no tablet ligado só ao Wi-Fi da câmera.
 *
 * Privacidade: a posição vai para o Overpass a cada consulta (só latitude/longitude e o raio).
 */
object OsmClient {
    private val SERVERS = listOf(
        "https://overpass.kumi.systems/api/interpreter",
        // 23/11/2026: com os outros fora do ar (502/tempo esgotado), este respondeu em ~1 s
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )
    @Volatile private var lastGood: String? = null
    const val RADIUS = 450
    private const val MAX_CACHE = 150
    private const val CACHE_DAYS = 7

    /** Ruas num raio de [RADIUS] m de (lat, lon); devolve o mapa e se veio da internet. */
    fun load(ctx: Context, cm: ConnectivityManager, lat: Double, lon: Double): Pair<OsmMap, Boolean> {
        // já tem esse lugar guardado e recente: usa sem internet (rápido e não gasta os dados)
        nearestCache(ctx, lat, lon)?.let { (c, f) ->
            if (System.currentTimeMillis() - f.lastModified() < CACHE_DAYS * 86_400_000L) {
                runCatching { return OsmMap.parse(f.readText(), c.first, c.second) to false }
            }
        }
        // mapa offline da cidade (botão "Baixar mapa"): não precisa de internet
        runCatching { OsmPacks.find(ctx, lat, lon) }.getOrNull()?.let { pack ->
            val m = pack.extract(lat, lon, RADIUS)
            // pacote incompleto pode ter buraco aqui: aí tenta a internet
            if (m.ways.isNotEmpty()) {
                Log.i("DashLive", "mapa do pacote ${pack.info.name}: ${m.ways.size} ruas")
                return m to false
            }
        }
        val net = Updater.internetNetwork(cm)
        if (net != null) {
            runCatching { overpass(net, OsmMap.query(lat, lon, RADIUS), 30_000, tries = 1) }
                .onSuccess { json ->
                    val map = OsmMap.parse(json, lat, lon)
                    save(ctx, lat, lon, json)
                    return map to true
                }
        }
        val cached = nearestCache(ctx, lat, lon) ?: throw IOException("sem internet e sem mapa guardado aqui")
        return OsmMap.parse(cached.second.readText(), cached.first.first, cached.first.second) to false
    }

    /**
     * Pergunta ao Overpass. Os servidores públicos às vezes respondem 502 ou ficam lentos: tenta um
     * de cada vez, com pouca espera, começando pelo último que funcionou; [tries] voltas na lista
     * (com pausa crescente entre elas). [readTimeoutMs]: resposta pequena (~130 KB) mas servidor
     * cheio demora (medido: até ~25 s).
     */
    fun overpass(net: android.net.Network, query: String, readTimeoutMs: Int, tries: Int = 1): String {
        var last: Exception? = null
        for (round in 0 until tries) {
            if (round > 0) Thread.sleep(5_000L * round)
            val order = SERVERS.sortedBy { if (it == lastGood) 0 else 1 }
            for (server in order) {
                try {
                    val conn = net.openConnection(URL(server)) as HttpURLConnection
                    conn.connectTimeout = 8_000
                    // servidor fora do ar não deve prender o download: o 1º byte tem que vir logo
                    conn.readTimeout = if (server == lastGood) readTimeoutMs else minOf(readTimeoutMs, 45_000)
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("User-Agent", "DashLive-app")
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val body = "data=" + URLEncoder.encode(query, "UTF-8")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    if (conn.responseCode != 200) throw IOException("Overpass respondeu ${conn.responseCode}")
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    // resposta 200 com erro de tempo do servidor vem com "remark"
                    if (json.contains("\"remark\"") && json.contains("runtime error")) throw IOException("Overpass: tempo esgotado")
                    lastGood = server
                    return json
                } catch (e: Exception) {
                    last = e
                    Log.w("DashLive", "mapa ($server): ${e.message}")
                }
            }
        }
        throw last ?: IOException("sem servidor de mapa")
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "osm").apply { mkdirs() }

    private fun save(ctx: Context, lat: Double, lon: Double, json: String) {
        runCatching {
            val d = dir(ctx)
            File(d, "%.5f_%.5f.json".format(java.util.Locale.US, lat, lon)).writeText(json)
            val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
            files.take((files.size - MAX_CACHE).coerceAtLeast(0)).forEach { it.delete() }
        }
    }

    /** Resposta guardada cujo centro fica a menos de RADIUS/2 m daqui (a mais perto). */
    private fun nearestCache(ctx: Context, lat: Double, lon: Double): Pair<Pair<Double, Double>, File>? {
        val kx = cos(Math.toRadians(lat)) * OsmMap.M_PER_DEG_LON
        var best: Pair<Pair<Double, Double>, File>? = null
        var bestD = RADIUS / 2.0
        for (f in dir(ctx).listFiles().orEmpty()) {
            val parts = f.nameWithoutExtension.split('_')
            val la = parts.getOrNull(0)?.toDoubleOrNull() ?: continue
            val lo = parts.getOrNull(1)?.toDoubleOrNull() ?: continue
            val d = hypot((lo - lon) * kx, (la - lat) * OsmMap.M_PER_DEG_LAT)
            if (d < bestD) { bestD = d; best = (la to lo) to f }
        }
        return best
    }
}

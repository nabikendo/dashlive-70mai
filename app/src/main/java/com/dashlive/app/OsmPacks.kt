package com.dashlive.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mapas offline guardados no aparelho (botão "Baixar mapa" nos Ajustes). Pensado para o tablet só
 * Wi-Fi: no carro ele fica no Wi-Fi da câmera, sem internet; o mapa baixado em casa cobre a cidade.
 *
 * - A região vem do Nominatim (busca do OpenStreetMap): link/número da relação, ou a cidade onde
 *   o aparelho está (posição do GPS → nome e limites do município).
 * - As ruas vêm do Overpass em pedaços de [STEP]° (~5 km), limitados a [RADIUS_M] em volta do centro
 *   (municípios grandes: o resto é zona rural). Cada pedaço baixado fica guardado: se o servidor cair
 *   no meio, "Continuar" retoma de onde parou. No fim junta tudo em `<id>.json` (+ `<id>.meta.json`).
 * - Ficam em `filesDir/osm_pack` (não somem com "limpar cache"). A posição/região vai para o
 *   Nominatim e o Overpass (servidores públicos do OpenStreetMap).
 */
object OsmPacks {
    const val STEP = 0.05
    const val RADIUS_M = 12_000.0
    private const val UA = "DashLive-app"

    /** Região a baixar: [id] = "R<relação>" do OSM. */
    class Region(val id: String, val name: String, val lat: Double, val lon: Double,
                 val s: Double, val w: Double, val n: Double, val e: Double)

    @Volatile private var loaded: OsmPack? = null
    @Volatile private var infos: List<OsmPack.Info>? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "osm_pack").apply { mkdirs() }

    /** Número da relação a partir de um link do openstreetmap.org, "R336809" ou só o número. */
    fun relationId(text: String): Long? {
        val t = text.trim()
        Regex("relation/(\\d+)").find(t)?.let { return it.groupValues[1].toLongOrNull() }
        Regex("^[Rr]?(\\d+)$").find(t)?.let { return it.groupValues[1].toLongOrNull() }
        return null
    }

    private fun get(cm: ConnectivityManager, url: String): String {
        val net = Updater.internetNetwork(cm) ?: throw IOException("sem internet")
        val c = net.openConnection(URL(url)) as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "pt-BR")
        if (c.responseCode != 200) throw IOException("busca do mapa respondeu ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun regionFrom(o: org.json.JSONObject): Region {
        val bb = o.getJSONArray("boundingbox") // s, n, w, e (texto)
        val type = o.optString("osm_type")
        val id = (if (type == "relation") "R" else type.take(1).uppercase()) + o.getLong("osm_id")
        val addr = o.optJSONObject("address")
        val name = addr?.let { a ->
            listOf("city", "town", "municipality", "village").firstNotNullOfOrNull { a.optString(it).ifBlank { null } }
        } ?: o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') }
        return Region(id, name, o.getDouble("lat"), o.getDouble("lon"),
            bb.getString(0).toDouble(), bb.getString(2).toDouble(), bb.getString(1).toDouble(), bb.getString(3).toDouble())
    }

    /** Região de uma relação do OSM (ex.: município). */
    fun lookupRelation(cm: ConnectivityManager, rel: Long): Region {
        val arr = JSONArray(get(cm, "https://nominatim.openstreetmap.org/lookup?osm_ids=R$rel&format=json&addressdetails=1"))
        if (arr.length() == 0) throw IOException("região R$rel não encontrada")
        return regionFrom(arr.getJSONObject(0))
    }

    /** Município onde fica (lat, lon). */
    fun cityAt(cm: ConnectivityManager, lat: Double, lon: Double): Region {
        val u = "https://nominatim.openstreetmap.org/reverse?lat=%.5f&lon=%.5f&zoom=10&format=json&addressdetails=1"
            .format(java.util.Locale.US, lat, lon)
        val o = org.json.JSONObject(get(cm, u))
        if (o.has("error")) throw IOException("cidade não encontrada aqui")
        return regionFrom(o)
    }

    fun list(ctx: Context): List<OsmPack.Info> {
        infos?.let { return it }
        val l = dir(ctx).listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
            .mapNotNull { runCatching { OsmPack.Info.fromJson(it.readText()) }.getOrNull() }
            .sortedBy { it.name }
        infos = l
        return l
    }

    fun delete(ctx: Context, id: String) {
        val d = dir(ctx)
        File(d, "$id.json").delete(); File(d, "$id.meta.json").delete()
        File(d, "$id.parts").deleteRecursively()
        if (loaded?.info?.id == id) loaded = null
        infos = null
    }

    /** Pacote que cobre (lat, lon), já lido (fica na memória até trocar de região). */
    fun find(ctx: Context, lat: Double, lon: Double): OsmPack? {
        loaded?.let { if (it.info.covers(lat, lon)) return it }
        val info = list(ctx).firstOrNull { it.covers(lat, lon) } ?: return null
        val f = File(dir(ctx), "${info.id}.json")
        if (!f.exists()) return null
        val t0 = System.currentTimeMillis()
        val p = OsmPack.parse(f.readText(), info)
        Log.i("DashLive", "pacote ${info.name}: ${p.wayCount} ruas lidas em ${System.currentTimeMillis() - t0} ms")
        loaded = p
        return p
    }

    /**
     * Baixa (ou continua) a região. [progress] recebe (pedaços prontos, total, texto); [cancel] para
     * no fim do pedaço atual. Mesmo incompleto, o que veio fica salvo e usável.
     */
    fun download(
        ctx: Context, cm: ConnectivityManager, r: Region,
        cancel: AtomicBoolean, progress: (Int, Int, String) -> Unit,
    ): OsmPack.Info {
        val box = OsmPack.clip(r.s, r.w, r.n, r.e, r.lat, r.lon, RADIUS_M)
        val chunks = OsmPack.chunks(box[0], box[1], box[2], box[3], STEP)
        val parts = File(dir(ctx), "${r.id}.parts").apply { mkdirs() }
        val net = Updater.internetNetwork(cm) ?: throw IOException("sem internet")
        var done = chunks.indices.count { File(parts, "$it.json").exists() }
        var failed: Exception? = null
        for ((i, c) in chunks.withIndex()) {
            val f = File(parts, "$i.json")
            if (f.exists()) continue
            if (cancel.get()) break
            progress(done, chunks.size, "Baixando ${r.name}: pedaço ${done + 1} de ${chunks.size}…")
            try {
                val json = OsmClient.overpass(net, OsmMap.queryBox(c[0], c[1], c[2], c[3]), 120_000, tries = 3)
                f.writeText(json)
                done++
            } catch (e: Exception) {
                failed = e
                Log.w("DashLive", "pacote ${r.name}: pedaço $i falhou: ${e.message}")
                break // servidor fora: salva o que tem e deixa continuar depois
            }
        }
        progress(done, chunks.size, "Juntando o mapa…")
        val jsons = chunks.indices.mapNotNull { File(parts, "$it.json").takeIf { f -> f.exists() }?.readText() }
        if (jsons.isEmpty()) throw failed ?: IOException("nada baixado")
        val (merged, ways) = OsmPack.merge(jsons)
        val out = File(dir(ctx), "${r.id}.json")
        out.writeText(merged)
        val info = OsmPack.Info(r.id, r.name, box[0], box[1], box[2], box[3], ways, out.length(),
            System.currentTimeMillis(), chunks.size, done)
        File(dir(ctx), "${r.id}.meta.json").writeText(info.toJson())
        if (info.complete) parts.deleteRecursively()
        infos = null
        if (loaded?.info?.id == r.id) loaded = null
        if (failed != null && !info.complete) {
            throw IOException("Servidor de mapa fora do ar: ${done} de ${chunks.size} pedaços salvos. Toque em Continuar mais tarde.")
        }
        return info
    }
}

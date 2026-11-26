package com.dashlive.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

/**
 * Mapa offline de uma região (cidade) inteira, lido de uma vez. Guarda as ruas em lat/lon compactos
 * e, para cada posição, recorta o mesmo quadrado que o [OsmClient] pediria ao Overpass
 * ([extract]) — o resto da Estrada não percebe diferença. Kotlin puro (testado em OsmPackTest).
 */
class OsmPack(
    val info: Info,
    private val ways: List<RawWay>,
    private val pts: List<RawPt>,
    /** Prédios, muros/cercas (contorno com tags) e árvores (lat, lon) — vêm do pacote novo. */
    private val blds: List<RawWay> = emptyList(),
    private val walls: List<RawWay> = emptyList(),
    private val trees: List<DoubleArray> = emptyList(),
) {

    /** Dados do pacote (guardados em `<id>.meta.json`). [chunksDone] < [chunks] = incompleto. */
    class Info(
        val id: String, val name: String,
        val s: Double, val w: Double, val n: Double, val e: Double,
        val ways: Int, val bytes: Long, val savedAt: Long,
        val chunks: Int, val chunksDone: Int,
    ) {
        val complete get() = chunksDone >= chunks
        fun covers(lat: Double, lon: Double) = lat in s..n && lon in w..e

        fun toJson(): String = JSONObject()
            .put("id", id).put("name", name)
            .put("s", s).put("w", w).put("n", n).put("e", e)
            .put("ways", ways).put("bytes", bytes).put("savedAt", savedAt)
            .put("chunks", chunks).put("chunksDone", chunksDone)
            .toString()

        companion object {
            fun fromJson(t: String): Info {
                val o = JSONObject(t)
                return Info(
                    o.getString("id"), o.getString("name"),
                    o.getDouble("s"), o.getDouble("w"), o.getDouble("n"), o.getDouble("e"),
                    o.optInt("ways"), o.optLong("bytes"), o.optLong("savedAt"),
                    o.optInt("chunks", 1), o.optInt("chunksDone", 1),
                )
            }
        }
    }

    class RawWay(
        val id: Long, val nodes: LongArray, val lat: DoubleArray, val lon: DoubleArray, val tags: Map<String, String>,
        val s: Double, val w: Double, val n: Double, val e: Double,
    )

    class RawPt(val lat: Double, val lon: Double, val kind: OsmMap.PointKind)

    val wayCount get() = ways.size

    /** Ruas e pontos no quadrado de 2×[radius] m em volta de (lat, lon), como o Overpass devolveria. */
    fun extract(lat: Double, lon: Double, radius: Int): OsmMap {
        val dLat = radius / OsmMap.M_PER_DEG_LAT
        val kx = cos(Math.toRadians(lat)) * OsmMap.M_PER_DEG_LON
        val dLon = radius / kx
        val s = lat - dLat; val n = lat + dLat; val w = lon - dLon; val e = lon + dLon
        val out = ArrayList<OsmMap.Way>()
        for (r in ways) {
            if (r.n < s || r.s > n || r.e < w || r.w > e) continue
            val xy = FloatArray(r.lat.size * 2)
            for (k in r.lat.indices) {
                xy[k * 2] = ((r.lon[k] - lon) * kx).toFloat()
                xy[k * 2 + 1] = ((r.lat[k] - lat) * OsmMap.M_PER_DEG_LAT).toFloat()
            }
            out += OsmMap.Way(r.id, r.nodes, xy, r.tags)
        }
        val p = ArrayList<OsmMap.Point>()
        for (q in pts) {
            if (q.lat < s || q.lat > n || q.lon < w || q.lon > e) continue
            p += OsmMap.Point(((q.lon - lon) * kx).toFloat(), ((q.lat - lat) * OsmMap.M_PER_DEG_LAT).toFloat(), q.kind)
        }
        val bl = ArrayList<OsmMap.Building>()
        for (r in blds) {
            if (r.n < s || r.s > n || r.e < w || r.w > e) continue
            bl += OsmMap.Building(r.id, localXY(r, lat, lon, kx), OsmMap.buildingHeight(r.tags))
        }
        val wl = ArrayList<OsmMap.Wall>()
        for (r in walls) {
            if (r.n < s || r.s > n || r.e < w || r.w > e) continue
            wl += OsmMap.Wall(localXY(r, lat, lon, kx), OsmMap.wallHeight(r.tags))
        }
        val tr = ArrayList<FloatArray>()
        for (t in trees) {
            if (t[0] < s || t[0] > n || t[1] < w || t[1] > e) continue
            tr += floatArrayOf(((t[1] - lon) * kx).toFloat(), ((t[0] - lat) * OsmMap.M_PER_DEG_LAT).toFloat())
        }
        return OsmMap(lat, lon, out, p, bl, tr, wl)
    }

    private fun localXY(r: RawWay, lat: Double, lon: Double, kx: Double): FloatArray {
        val xy = FloatArray(r.lat.size * 2)
        for (k in r.lat.indices) {
            xy[k * 2] = ((r.lon[k] - lon) * kx).toFloat()
            xy[k * 2 + 1] = ((r.lat[k] - lat) * OsmMap.M_PER_DEG_LAT).toFloat()
        }
        return xy
    }

    companion object {
        /**
         * Junta respostas do Overpass (um pedaço por item de [jsons]) sem repetir rua/ponto que
         * aparece em dois pedaços vizinhos. Devolve o JSON único do pacote e o número de ruas.
         */
        fun merge(jsons: List<String>): Pair<String, Int> {
            val seen = HashSet<String>()
            val all = JSONArray()
            var ways = 0
            for (j in jsons) {
                val els = JSONObject(j).optJSONArray("elements") ?: continue
                for (i in 0 until els.length()) {
                    val el = els.getJSONObject(i)
                    val key = el.optString("type") + el.optLong("id")
                    if (!seen.add(key)) continue
                    if (el.optString("type") == "way") ways++
                    all.put(el)
                }
            }
            return JSONObject().put("elements", all).toString() to ways
        }

        /** Lê o JSON do pacote (formato do Overpass `out body geom`). */
        fun parse(json: String, info: Info): OsmPack {
            val els = JSONObject(json).getJSONArray("elements")
            val ways = ArrayList<RawWay>()
            val pts = ArrayList<RawPt>()
            val blds = ArrayList<RawWay>()
            val walls = ArrayList<RawWay>()
            val trees = ArrayList<DoubleArray>()
            for (i in 0 until els.length()) {
                val el = els.getJSONObject(i)
                val tags = HashMap<String, String>()
                el.optJSONObject("tags")?.let { t -> for (k in t.keys()) tags[k] = t.getString(k) }
                when (el.optString("type")) {
                    "way" -> {
                        val nodes = el.optJSONArray("nodes") ?: continue
                        val geom = el.optJSONArray("geometry") ?: continue
                        if (geom.length() != nodes.length() || geom.length() < 2) continue
                        val la = DoubleArray(geom.length()); val lo = DoubleArray(geom.length())
                        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
                        for (k in 0 until geom.length()) {
                            val g = geom.optJSONObject(k) ?: continue
                            la[k] = g.getDouble("lat"); lo[k] = g.getDouble("lon")
                            if (la[k] < s) s = la[k]; if (la[k] > n) n = la[k]
                            if (lo[k] < w) w = lo[k]; if (lo[k] > e) e = lo[k]
                        }
                        val rw = RawWay(el.getLong("id"), LongArray(nodes.length()) { nodes.getLong(it) }, la, lo, tags, s, w, n, e)
                        when {
                            tags["building"] != null && tags["highway"] == null -> blds += rw
                            tags["barrier"] == "wall" || tags["barrier"] == "fence" -> walls += rw
                            else -> ways += rw
                        }
                    }
                    "node" -> {
                        if (tags["natural"] == "tree") { trees += doubleArrayOf(el.getDouble("lat"), el.getDouble("lon")); continue }
                        val kind = OsmMap.pointKind(tags) ?: continue
                        pts += RawPt(el.getDouble("lat"), el.getDouble("lon"), kind)
                    }
                }
            }
            return OsmPack(info, ways, pts, blds, walls, trees)
        }

        /**
         * Pedaços (s, w, n, e) de no máximo [step] graus cobrindo o retângulo — consultas leves
         * para os servidores públicos, que recusam ou demoram com áreas grandes.
         */
        fun chunks(s: Double, w: Double, n: Double, e: Double, step: Double): List<DoubleArray> {
            val out = ArrayList<DoubleArray>()
            val rows = kotlin.math.ceil((n - s) / step).toInt().coerceAtLeast(1)
            val cols = kotlin.math.ceil((e - w) / step).toInt().coerceAtLeast(1)
            val dy = (n - s) / rows; val dx = (e - w) / cols
            for (r in 0 until rows) for (c in 0 until cols) {
                out += doubleArrayOf(s + r * dy, w + c * dx, s + (r + 1) * dy, w + (c + 1) * dx)
            }
            return out
        }

        /**
         * Retângulo da região limitado a [radiusM] em volta do centro (municípios grandes: só a parte
         * em volta da cidade; o resto é estrada de terra e pesaria muito).
         */
        fun clip(s: Double, w: Double, n: Double, e: Double, lat: Double, lon: Double, radiusM: Double): DoubleArray {
            val dLat = radiusM / OsmMap.M_PER_DEG_LAT
            val dLon = radiusM / (cos(Math.toRadians(lat)) * OsmMap.M_PER_DEG_LON)
            return doubleArrayOf(
                maxOf(s, lat - dLat), maxOf(w, lon - dLon), minOf(n, lat + dLat), minOf(e, lon + dLon),
            )
        }
    }
}

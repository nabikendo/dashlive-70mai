package com.dashlive.app

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Ruas em volta do carro, vindas do OpenStreetMap (Overpass, `out body geom`), em metros num plano
 * local: x = leste, y = norte, a partir de ([refLat], [refLon]). Kotlin puro (testado em OsmMapTest).
 *
 * O que a Estrada 3D usa daqui:
 *  - [match]: encaixa a posição do GPS na rua certa (a mais perto que vai na mesma direção);
 *  - [corridor]: o trecho dessa rua à frente (e um pouco atrás), seguindo a continuação mais reta
 *    nos cruzamentos;
 *  - [ways]/[points]: as outras ruas (laterais, cruzamentos) e faixa de pedestre, quebra-molas,
 *    semáforo e poste.
 */
class OsmMap(
    val refLat: Double,
    val refLon: Double,
    val ways: List<Way>,
    val points: List<Point>,
    /** Prédios (contorno no plano local e altura em metros); vazio quando o mapa veio sem prédios. */
    val buildings: List<Building> = emptyList(),
    /** Árvores (x, y) no plano local. */
    val trees: List<FloatArray> = emptyList(),
    /** Muros e cercas (contorno aberto e altura em metros). */
    val walls: List<Wall> = emptyList(),
) {

    enum class PointKind { CROSSING, BUMP, SIGNAL, LAMP, RADAR }

    class Point(val x: Float, val y: Float, val kind: PointKind)

    /** Prédio: contorno fechado (x, y), id do OSM e altura em metros. */
    class Building(val id: Long, val xy: FloatArray, val height: Float)

    /** Muro ou cerca: linha (x, y) e altura em metros. */
    class Wall(val xy: FloatArray, val height: Float)

    class Way(val id: Long, val nodes: LongArray, val xy: FloatArray, val tags: Map<String, String>) {
        val highway = tags["highway"] ?: ""
        /** +1 = só no sentido dos nós, -1 = só contra, 0 = mão dupla. */
        val oneway: Int = when {
            tags["oneway"] == "yes" || tags["oneway"] == "1" || tags["oneway"] == "true" -> 1
            tags["oneway"] == "-1" || tags["oneway"] == "reverse" -> -1
            tags["oneway"] == "no" -> 0
            highway == "motorway" || highway == "motorway_link" || tags["junction"] == "roundabout" -> 1
            else -> 0
        }
        /** Número total de faixas (tag ou o padrão do tipo de rua). */
        val lanes: Int = tags["lanes"]?.trim()?.toIntOrNull()?.coerceIn(1, 8) ?: when (highway) {
            "motorway", "trunk" -> if (oneway != 0) 2 else 4
            "primary", "secondary" -> if (oneway != 0) 2 else 2
            "service", "living_street", "track" -> if (oneway != 0) 1 else 2
            else -> if (oneway != 0) 1 else 2
        }
        /** Faixas no sentido dos nós (mão dupla). */
        val lanesForward: Int = tags["lanes:forward"]?.toIntOrNull() ?: if (oneway == 0) (lanes + 1) / 2 else lanes
        val lanesBackward: Int = tags["lanes:backward"]?.toIntOrNull() ?: if (oneway == 0) lanes - lanesForward else 0
        val bridge = tags["bridge"].let { it != null && it != "no" }
        val tunnel = tags["tunnel"].let { it != null && it != "no" }
        val lit = tags["lit"] == "yes"
        /** Proibido ultrapassar (faixa contínua no meio). */
        val noOvertaking = tags["overtaking"] == "no" || tags["overtaking:forward"] == "no" || tags["overtaking:backward"] == "no"
        val drivable = highway in DRIVABLE
        val count get() = xy.size / 2
        fun x(i: Int) = xy[i * 2]
        fun y(i: Int) = xy[i * 2 + 1]
    }

    /** Onde o carro está: rua, segmento (i → i+1), posição no segmento (0..1) e se anda contra os nós. */
    class Match(val way: Way, val seg: Int, val t: Float, val reverse: Boolean, val dist: Float) {
        val x: Float get() = way.x(seg) + (way.x(seg + 1) - way.x(seg)) * t
        val y: Float get() = way.y(seg) + (way.y(seg + 1) - way.y(seg)) * t
    }

    /**
     * Trecho de rua no sentido em que o carro anda. [xy] = pontos (x, y); [s] = distância de cada ponto
     * (0 = o carro, negativo = atrás); [seg] = rua de cada segmento e se é contra os nós ([rev]).
     */
    class Corridor(val xy: FloatArray, val s: FloatArray, val segWay: Array<Way>, val segRev: BooleanArray) {
        val count get() = s.size
        val start get() = s[0]
        val end get() = s[count - 1]

        /** Índice do segmento que contém a distância [d]. */
        fun segAt(d: Float): Int {
            var i = 0
            while (i < count - 2 && s[i + 1] < d) i++
            return i
        }

        /** Ponto (x, y) e direção (rumo em radianos, 0 = norte, horário) na distância [d]. */
        fun at(d: Float, out: FloatArray) {
            val i = segAt(d)
            val len = (s[i + 1] - s[i]).coerceAtLeast(1e-3f)
            val t = ((d - s[i]) / len).coerceIn(-5f, 5f)
            val x0 = xy[i * 2]; val y0 = xy[i * 2 + 1]; val x1 = xy[i * 2 + 2]; val y1 = xy[i * 2 + 3]
            out[0] = x0 + (x1 - x0) * t
            out[1] = y0 + (y1 - y0) * t
            out[2] = atan2(x1 - x0, y1 - y0)
        }

        /** Distância ao longo do trecho do ponto (x, y) projetado, e quão longe ele fica do trecho. */
        fun project(x: Float, y: Float): Pair<Float, Float> {
            var best = Float.MAX_VALUE
            var bestS = 0f
            for (i in 0 until count - 1) {
                val x0 = xy[i * 2]; val y0 = xy[i * 2 + 1]; val x1 = xy[i * 2 + 2]; val y1 = xy[i * 2 + 3]
                val dx = x1 - x0; val dy = y1 - y0
                val l2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
                val t = (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0f, 1f)
                val d = hypot(x - (x0 + dx * t), y - (y0 + dy * t))
                if (d < best) { best = d; bestS = s[i] + (s[i + 1] - s[i]) * t }
            }
            return bestS to best
        }

        /** Faixas no sentido do carro e no contrário, no segmento [i]. */
        fun lanesAhead(i: Int): Int = segWay[i].let { w -> if (w.oneway != 0) w.lanes else if (segRev[i]) w.lanesBackward else w.lanesForward }
        fun lanesBack(i: Int): Int = segWay[i].let { w -> if (w.oneway != 0) 0 else if (segRev[i]) w.lanesForward else w.lanesBackward }
    }

    private val byNode = HashMap<Long, MutableList<Way>>().also { m ->
        for (w in ways) for (n in w.nodes) m.getOrPut(n) { ArrayList() } += w
    }

    /** Converte latitude/longitude em metros neste mapa. */
    fun toXY(lat: Double, lon: Double, out: FloatArray) {
        out[0] = ((lon - refLon) * cos(Math.toRadians(refLat)) * M_PER_DEG_LON).toFloat()
        out[1] = ((lat - refLat) * M_PER_DEG_LAT).toFloat()
    }

    /**
     * Encaixa a posição (x, y) na rua: a mais perto (até [maxDist] m) cujo sentido combina com o
     * rumo do GPS ([bearing] em radianos, 0 = norte; null = parado, aceita qualquer sentido).
     */
    fun match(x: Float, y: Float, bearing: Float?, maxDist: Float = 30f): Match? {
        var best: Match? = null
        var bestScore = Float.MAX_VALUE
        for (w in ways) {
            if (!w.drivable) continue
            for (i in 0 until w.count - 1) {
                val x0 = w.x(i); val y0 = w.y(i); val dx = w.x(i + 1) - x0; val dy = w.y(i + 1) - y0
                val l2 = dx * dx + dy * dy
                if (l2 < 1e-4f) continue
                val t = (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0f, 1f)
                val d = hypot(x - (x0 + dx * t), y - (y0 + dy * t))
                if (d > maxDist) continue
                val segBearing = atan2(dx, dy)
                var reverse: Boolean
                var turn = 0f
                if (bearing == null) {
                    reverse = w.oneway == -1
                } else {
                    val fwd = angleDiff(bearing, segBearing)
                    reverse = fwd > PI / 2
                    if (w.oneway == 1 && reverse) continue   // contramão
                    if (w.oneway == -1 && !reverse) continue
                    turn = if (reverse) (PI - fwd).toFloat() else fwd
                }
                // túnel perde da rua de cima (o GPS não enxerga o túnel; se estiver nele, o sinal some)
                val score = d + turn * 12f + (if (w.tunnel) 15f else 0f)
                if (score < bestScore) { bestScore = score; best = Match(w, i, t, reverse, d) }
            }
        }
        return best
    }

    /** Trecho de [behind] m atrás até [ahead] m à frente da posição encaixada. */
    fun corridor(m: Match, ahead: Float = 260f, behind: Float = 40f): Corridor {
        val fx = ArrayList<Float>(); val fw = ArrayList<Way>(); val fr = ArrayList<Boolean>()
        // para a frente: do ponto do carro até o fim, seguindo para a continuação mais reta
        fx += m.x; fx += m.y
        var way = m.way
        var rev = m.reverse
        var idx = if (rev) m.seg else m.seg + 1 // próximo nó no sentido do carro
        var len = 0f
        val visited = HashSet<Long>()
        while (len < ahead) {
            val px = fx[fx.size - 2]; val py = fx[fx.size - 1]
            val nx = way.x(idx); val ny = way.y(idx)
            len += hypot(nx - px, ny - py)
            fx += nx; fx += ny; fw += way; fr += rev
            val next = if (rev) idx - 1 else idx + 1
            if (next in 0 until way.count) { idx = next; continue }
            // fim desta rua: escolhe a continuação mais reta no mesmo nó
            val node = way.nodes[idx]
            if (!visited.add(node)) break
            val dir = atan2(nx - px, ny - py)
            var bestW: Way? = null; var bestI = 0; var bestRev = false; var bestTurn = Float.MAX_VALUE
            for (o in byNode[node].orEmpty()) {
                if (o === way || !o.drivable) continue
                val k = o.nodes.indexOf(node)
                for (r in listOf(false, true)) {
                    if (o.oneway == 1 && r) continue
                    if (o.oneway == -1 && !r) continue
                    val j = if (r) k - 1 else k + 1
                    if (j !in 0 until o.count) continue
                    val turn = angleDiff(dir, atan2(o.x(j) - o.x(k), o.y(j) - o.y(k)))
                    if (turn < bestTurn) { bestTurn = turn; bestW = o; bestI = j; bestRev = r }
                }
            }
            if (bestW == null || bestTurn > Math.toRadians(70.0)) break
            way = bestW; idx = bestI; rev = bestRev
        }
        // para trás: só na mesma rua
        val bx = ArrayList<Float>()
        var bi = if (m.reverse) m.seg + 1 else m.seg
        var back = 0f
        var lx = m.x; var ly = m.y
        while (back < behind && bi in 0 until m.way.count) {
            val x = m.way.x(bi); val y = m.way.y(bi)
            back += hypot(x - lx, y - ly)
            bx += x; bx += y
            lx = x; ly = y
            bi += if (m.reverse) 1 else -1
        }
        // junta: atrás (do mais longe para o carro) + carro + frente
        val nBack = bx.size / 2
        val total = nBack + fx.size / 2
        val xy = FloatArray(total * 2)
        for (i in 0 until nBack) { xy[i * 2] = bx[(nBack - 1 - i) * 2]; xy[i * 2 + 1] = bx[(nBack - 1 - i) * 2 + 1] }
        for (i in 0 until fx.size) xy[nBack * 2 + i] = fx[i]
        val s = FloatArray(total)
        for (i in 1 until total) s[i] = s[i - 1] + hypot(xy[i * 2] - xy[i * 2 - 2], xy[i * 2 + 1] - xy[i * 2 - 1])
        val zero = s[nBack]
        for (i in 0 until total) s[i] -= zero
        val segWay = Array(total - 1) { i -> if (i < nBack) m.way else fw[(i - nBack).coerceAtMost(fw.size - 1)] }
        val segRev = BooleanArray(total - 1) { i -> if (i < nBack) m.reverse else fr[(i - nBack).coerceAtMost(fr.size - 1)] }
        return Corridor(xy, s, segWay, segRev)
    }

    /** Curva à frente: a que distância começa, para que lado (direita = true) e quantos graus. */
    class Turn(val dist: Float, val right: Boolean, val deg: Float)

    /** Ponto do mapa à frente, na rota, e a que distância do carro. */
    class Ahead(val dist: Float, val kind: PointKind)

    /**
     * Primeira mudança de rumo de pelo menos [minDeg] no trecho à frente (até [within] m), comparando
     * o rumo a cada [step] m com o rumo do carro. Pega curvas suaves e as de cruzamento.
     */
    fun nextTurn(c: Corridor, minDeg: Float = 30f, within: Float = 250f, step: Float = 5f): Turn? {
        val out = FloatArray(3)
        c.at(0f, out)
        val h0 = out[2]
        val minRad = Math.toRadians(minDeg.toDouble()).toFloat()
        val lim = minOf(within, c.end)
        var d = step
        while (d <= lim) {
            c.at(d, out)
            val diff = signedAngle(out[2], h0)
            if (abs(diff) >= minRad) return Turn(d, diff > 0, abs(diff) * 180f / PI.toFloat())
            d += step
        }
        return null
    }

    /** Pontos do mapa (lombada, semáforo, radar, ...) à frente, a até [off] m da rota, em ordem. */
    fun nextPoints(c: Corridor, within: Float = 300f, off: Float = 12f): List<Ahead> {
        val res = ArrayList<Ahead>()
        for (p in points) {
            val (s, d) = c.project(p.x, p.y)
            if (s > 0f && s <= within && d <= off) res += Ahead(s, p.kind)
        }
        return res.sortedBy { it.dist }
    }

    /** Frase curta do que vem à frente (curva, radar, semáforo, lombada), ou null se não há nada. */
    fun describeAhead(c: Corridor): String? {
        val parts = ArrayList<String>()
        nextTurn(c)?.let { parts += "curva à ${if (it.right) "direita" else "esquerda"} em ${it.dist.roundToInt()} m" }
        val pts = nextPoints(c)
        // radares: quantos há à frente e a que distância está o próximo
        val radars = pts.filter { it.kind == PointKind.RADAR }
        if (radars.size == 1) parts += "radar em ${radars[0].dist.roundToInt()} m"
        else if (radars.size > 1) parts += "${radars.size} radares, o próximo em ${radars[0].dist.roundToInt()} m"
        for ((kind, name) in listOf(PointKind.SIGNAL to "semáforo", PointKind.BUMP to "lombada")) {
            pts.firstOrNull { it.kind == kind }?.let { parts += "$name em ${it.dist.roundToInt()} m" }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" • ")
    }

    companion object {
        const val M_PER_DEG_LAT = 110_540.0
        const val M_PER_DEG_LON = 111_320.0

        /** Ruas por onde passa carro (o resto — calçada, ciclovia — não entra). */
        val DRIVABLE = setOf(
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
            "living_street", "service", "motorway_link", "trunk_link", "primary_link", "secondary_link",
            "tertiary_link", "road",
        )

        /** Diferença de rumos com sinal, de −π a π: positiva = girou para a direita (sentido horário). */
        fun signedAngle(a: Float, b: Float): Float {
            var d = (a - b) % (2 * PI).toFloat()
            if (d > PI) d -= (2 * PI).toFloat()
            if (d < -PI) d += (2 * PI).toFloat()
            return d
        }

        /** Diferença entre dois rumos, 0..π. */
        fun angleDiff(a: Float, b: Float): Float {
            var d = (a - b) % (2 * PI).toFloat()
            if (d < 0) d += (2 * PI).toFloat()
            return if (d > PI) (2 * PI).toFloat() - d else d
        }

        /**
         * Consulta do Overpass: ruas e pontos num quadrado de 2×[radius] m. Por retângulo (bbox) é bem
         * mais leve que "around" (medido em 22/11/2026: 2 s contra 37 s); ruas "service"
         * (estacionamentos, acessos) ficam de fora porque pesam muito e não ajudam na estrada.
         */
        fun query(lat: Double, lon: Double, radius: Int): String {
            val dLat = radius / M_PER_DEG_LAT
            val dLon = radius / (cos(Math.toRadians(lat)) * M_PER_DEG_LON)
            val bb = "%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.US, lat - dLat, lon - dLon, lat + dLat, lon + dLon)
            val types = (DRIVABLE - "service").joinToString("|")
            return "[out:json][timeout:20][bbox:$bb];(" +
                "way[highway~\"^($types)$\"];" +
                "way[building];" +
                "node[highway~\"^(crossing|traffic_signals|street_lamp|speed_camera)$\"];" +
                "node[traffic_calming];" +
                "node[natural=tree];" +
                "way[barrier~\"^(wall|fence)$\"];" +
                ");out body geom;"
        }

        /** Altura de muro (2 m) ou cerca (1,2 m), com a tag `height` quando for um número. */
        fun wallHeight(tags: Map<String, String>): Float =
            tags["height"]?.let { Regex("""\d+(\.\d+)?""").find(it)?.value?.toFloatOrNull() }
                ?: if (tags["barrier"] == "wall") 2f else 1.2f

        /** Altura do prédio: a tag `height`; senão 3 m por andar; senão 8 m. */
        fun buildingHeight(tags: Map<String, String>): Float {
            tags["height"]?.let { h -> Regex("""\d+(\.\d+)?""").find(h)?.value?.toFloatOrNull()?.takeIf { it > 1f }?.let { return it } }
            tags["building:levels"]?.toFloatOrNull()?.takeIf { it > 0f }?.let { return it * 3f + 1f }
            return 8f
        }

        /** Que tipo de ponto é um nó do OSM (lombada, semáforo, poste, faixa de pedestre) ou null. */
        fun pointKind(tags: Map<String, String>): PointKind? = when {
            tags["traffic_calming"] != null && tags["traffic_calming"] != "no" &&
                tags["traffic_calming"] != "island" && tags["traffic_calming"] != "choker" -> PointKind.BUMP
            tags["highway"] == "speed_camera" -> PointKind.RADAR
            tags["highway"] == "traffic_signals" -> PointKind.SIGNAL
            tags["highway"] == "street_lamp" -> PointKind.LAMP
            tags["highway"] == "crossing" || tags["crossing"] != null -> PointKind.CROSSING
            else -> null
        }

        /** Mesma consulta de [query], mas para um retângulo qualquer (pacote offline, em pedaços). */
        fun queryBox(s: Double, w: Double, n: Double, e: Double, timeout: Int = 90): String {
            val bb = "%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.US, s, w, n, e)
            val types = (DRIVABLE - "service").joinToString("|")
            return "[out:json][timeout:$timeout][bbox:$bb];(" +
                "way[highway~\"^($types)$\"];" +
                "node[highway~\"^(crossing|traffic_signals|street_lamp|speed_camera)$\"];" +
                "node[traffic_calming];" +
                "node[natural=tree];" +
                "way[building];" +
                "way[barrier~\"^(wall|fence)$\"];" +
                ");out body geom;"
        }

        /** Lê a resposta do Overpass. */
        fun parse(json: String, refLat: Double, refLon: Double): OsmMap {
            val root = JSONObject(json)
            val els = root.getJSONArray("elements")
            val ways = ArrayList<Way>()
            val pts = ArrayList<Point>()
            val bldgs = ArrayList<Building>()
            val trees = ArrayList<FloatArray>()
            val walls = ArrayList<Wall>()
            val kx = cos(Math.toRadians(refLat)) * M_PER_DEG_LON
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                val tags = HashMap<String, String>()
                e.optJSONObject("tags")?.let { t -> for (k in t.keys()) tags[k] = t.getString(k) }
                when (e.getString("type")) {
                    "way" -> {
                        val nodes = e.optJSONArray("nodes") ?: continue
                        val geom = e.optJSONArray("geometry") ?: continue
                        if (geom.length() != nodes.length() || geom.length() < 2) continue
                        val xy = FloatArray(geom.length() * 2)
                        for (k in 0 until geom.length()) {
                            val g = geom.getJSONObject(k)
                            xy[k * 2] = ((g.getDouble("lon") - refLon) * kx).toFloat()
                            xy[k * 2 + 1] = ((g.getDouble("lat") - refLat) * M_PER_DEG_LAT).toFloat()
                        }
                        // prédio: contorno reto (sem suavizar), vai para a lista de prédios
                        if (tags["building"] != null && tags["highway"] == null) {
                            bldgs += Building(e.getLong("id"), xy, buildingHeight(tags))
                            continue
                        }
                        if (tags["barrier"] == "wall" || tags["barrier"] == "fence") {
                            walls += Wall(xy, wallHeight(tags))
                            continue
                        }
                        // curvas suaves (o OSM tem pontos em zigue-zague); mesmo número de pontos
                        smoothLine(xy)
                        ways += Way(e.getLong("id"), LongArray(nodes.length()) { nodes.getLong(it) }, xy, tags)
                    }
                    "node" -> {
                        val x = ((e.getDouble("lon") - refLon) * kx).toFloat()
                        val y = ((e.getDouble("lat") - refLat) * M_PER_DEG_LAT).toFloat()
                        if (tags["natural"] == "tree") { trees += floatArrayOf(x, y); continue }
                        val kind = pointKind(tags) ?: continue
                        pts += Point(x, y, kind)
                    }
                }
            }
            return OsmMap(refLat, refLon, ways, pts, bldgs, trees, walls)
        }
    }
}

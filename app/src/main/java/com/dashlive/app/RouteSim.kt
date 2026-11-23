package com.dashlive.app

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rota de teste (LineString do GeoJSON): o carro "anda" pela linha numa velocidade fixa.
 * Só o build de teste usa (`--ez roadDemo true --ez routeDemo true`, com o arquivo em
 * `getExternalFilesDir(null)/rota.geojson`). Puro: sem Android, testado em `RouteSimTest`.
 */
class RouteSim private constructor(private val lats: DoubleArray, private val lons: DoubleArray) {

    /** Metros acumulados em cada ponto da rota. */
    private val cum = DoubleArray(lats.size)
    /** Rumo (graus, 0 = norte) de cada trecho; trecho de comprimento zero herda o anterior. */
    private val bearings = FloatArray(lats.size)

    /** Comprimento total da rota em metros. */
    val length: Double

    init {
        for (i in 1 until lats.size) cum[i] = cum[i - 1] + distance(lats[i - 1], lons[i - 1], lats[i], lons[i])
        length = cum.last()
        var last = 0f
        for (i in 1 until lats.size) {
            if (cum[i] - cum[i - 1] > 0.0) last = bearing(lats[i - 1], lons[i - 1], lats[i], lons[i])
            bearings[i] = last
        }
        bearings[0] = bearings.getOrElse(1) { 0f }
    }

    /** Posição a [meters] do início. Passou do fim = volta ao começo (a rota roda em loop). */
    fun at(meters: Double): Pos {
        if (length <= 0.0) return Pos(lats[0], lons[0], bearings[0])
        val m = ((meters % length) + length) % length
        var i = 1
        while (i < cum.size - 1 && cum[i] < m) i++
        val seg = cum[i] - cum[i - 1]
        val t = if (seg <= 0.0) 0.0 else ((m - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        return Pos(
            lats[i - 1] + (lats[i] - lats[i - 1]) * t,
            lons[i - 1] + (lons[i] - lons[i - 1]) * t,
            bearings[i],
        )
    }

    data class Pos(val lat: Double, val lon: Double, val bearing: Float)

    companion object {
        /** Velocidade da simulação (km/h), padrão combinado para o teste. */
        const val SPEED_KMH = 60.0

        /**
         * Lê um GeoJSON com uma LineString (Feature ou geometria pura). As coordenadas do GeoJSON
         * são [lon, lat]. Devolve null se não houver ao menos 2 pontos.
         */
        fun fromGeoJson(text: String): RouteSim? {
            val root = JSONObject(text)
            val geom = if (root.optString("type") == "Feature") root.getJSONObject("geometry") else root
            val coords = geom.getJSONArray("coordinates")
            if (coords.length() < 2) return null
            val lats = DoubleArray(coords.length())
            val lons = DoubleArray(coords.length())
            for (i in 0 until coords.length()) {
                val p = coords.getJSONArray(i)
                lons[i] = p.getDouble(0)
                lats[i] = p.getDouble(1)
            }
            return RouteSim(lats, lons)
        }

        /** Distância em metros entre dois pontos (fórmula de Haversine). */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        /** Rumo inicial de 1 ponto para outro, em graus (0 = norte, sentido horário). */
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dLon = Math.toRadians(lon2 - lon1)
            val y = sin(dLon) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
            return (((Math.toDegrees(atan2(y, x)) % 360.0) + 360.0) % 360.0).toFloat()
        }
    }
}

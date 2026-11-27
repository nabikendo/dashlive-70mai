package com.dashlive.app

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Um ponto da viagem gravada: tempo (ms desde o início), posição, velocidade (km/h) e rumo (graus). */
data class TripPoint(val t: Long, val lat: Double, val lon: Double, val speedKmh: Float?, val bearing: Float?)

/**
 * Viagens gravadas pela Estrada (uma linha por ponto, em CSV): `t_ms,lat,lon,speed_kmh,bearing`.
 * Puro (sem Android): gravação, leitura e posição em um instante, testado em `TripLogTest`.
 */
object TripLog {
    const val HEADER = "t_ms,lat,lon,speed_kmh,bearing"

    fun line(p: TripPoint): String = listOf(
        p.t.toString(),
        "%.7f".format(Locale.US, p.lat),
        "%.7f".format(Locale.US, p.lon),
        p.speedKmh?.let { "%.1f".format(Locale.US, it) } ?: "",
        p.bearing?.let { "%.1f".format(Locale.US, it) } ?: "",
    ).joinToString(",")

    /** Lê um arquivo gravado; linhas com defeito (e o cabeçalho) são puladas. */
    fun parse(text: String): List<TripPoint> = text.lineSequence().mapNotNull { raw ->
        val f = raw.trim().split(',')
        if (f.size < 5) return@mapNotNull null
        val t = f[0].toLongOrNull() ?: return@mapNotNull null
        val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
        TripPoint(t, lat, lon, f[3].toFloatOrNull(), f[4].toFloatOrNull())
    }.toList()

    /** Nome de arquivo com data e hora, para a lista de viagens (ordena por nome = por data). */
    fun fileName(nowMs: Long): String =
        "viagem_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs)) + ".csv"

    /**
     * Viagens gravadas na pasta, da mais nova para a mais antiga. Arquivo vazio (o app fechou antes do
     * primeiro ponto) fica de fora.
     */
    fun list(dir: File): List<File> = dir.listFiles { f -> f.name.startsWith("viagem_") && f.name.endsWith(".csv") && f.length() > 0 }
        ?.sortedByDescending { it.name }.orEmpty()

    /** Resumo de uma viagem: distância, tempo andando e parado, média e máxima (km/h). */
    data class Summary(val distanceM: Double, val movingMs: Long, val stoppedMs: Long, val avgKmh: Float, val maxKmh: Float)

    /** Abaixo disso o carro conta como parado (semáforo, trânsito). */
    const val STOPPED_KMH = 3f

    fun summary(pts: List<TripPoint>): Summary {
        var dist = 0.0; var moving = 0L; var stopped = 0L; var max = 0f
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dt = (b.t - a.t).coerceAtLeast(0L)
            val v = b.speedKmh
            // parado nos dois pontos: o deslocamento é ruído do GPS, não soma distância
            val still = v != null && v < STOPPED_KMH && (a.speedKmh ?: 0f) < STOPPED_KMH
            if (!still) dist += RouteSim.distance(a.lat, a.lon, b.lat, b.lon)
            if (v != null) {
                if (v > max) max = v
                if (v < STOPPED_KMH) stopped += dt else moving += dt
            }
        }
        val total = pts.lastOrNull()?.t ?: 0L
        val avg = if (total > 0) (dist / (total / 1000.0) * 3.6).toFloat() else 0f
        return Summary(dist, moving, stopped, avg, max)
    }

    /** Uma linha para a lista de viagens: "12,4 km • média 45 km/h • parado 3 min". */
    fun summaryText(s: Summary): String {
        val pt = Locale("pt", "BR")
        val stoppedMin = s.stoppedMs / 60_000.0
        return "%.1f km • média %.0f km/h • parado %.0f min".format(pt, s.distanceM / 1000.0, s.avgKmh, stoppedMin)
    }

    /** Posição da viagem em [t] ms desde o início (interpolada entre dois pontos); null depois do fim. */
    class Track(private val pts: List<TripPoint>) {
        val durationMs: Long get() = pts.lastOrNull()?.t ?: 0L

        fun at(t: Long): TripPoint? {
            if (pts.isEmpty()) return null
            if (t >= durationMs) return null
            if (t <= pts[0].t) return pts[0]
            var i = 0
            while (i < pts.size - 2 && pts[i + 1].t < t) i++
            val a = pts[i]; val b = pts[i + 1]
            val span = (b.t - a.t).coerceAtLeast(1L)
            val k = ((t - a.t).toDouble() / span).coerceIn(0.0, 1.0)
            val lat = a.lat + (b.lat - a.lat) * k
            val lon = a.lon + (b.lon - a.lon) * k
            val speed = if (a.speedKmh != null && b.speedKmh != null) a.speedKmh + (b.speedKmh - a.speedKmh) * k.toFloat() else a.speedKmh
            return TripPoint(t, lat, lon, speed, a.bearing ?: b.bearing)
        }

        companion object {
            fun load(file: File): Track = Track(parse(file.readText()))
        }
    }
}

/**
 * Grava pontos numa viagem nova, no máximo um por [minGapMs] (1 s). Quem chama decide quando parar.
 */
class TripRecorder(val file: File, private val startMs: Long, private val minGapMs: Long = 1000L) {
    private var out: BufferedWriter? = file.bufferedWriter().also { it.write(TripLog.HEADER); it.newLine() }
    private var last = Long.MIN_VALUE
    /** Quantos pontos já foram gravados. */
    var points = 0
        private set

    /** Grava se já passou [minGapMs] desde o último ponto. [nowMs] é o relógio do aparelho. */
    fun add(nowMs: Long, lat: Double, lon: Double, speedKmh: Float?, bearing: Float?) {
        val w = out ?: return
        if (last != Long.MIN_VALUE && nowMs - last < minGapMs) return
        last = nowMs
        points++
        w.write(TripLog.line(TripPoint(nowMs - startMs, lat, lon, speedKmh, bearing)))
        w.newLine()
        w.flush() // a cada ponto: se o app fechar, a viagem fica até o último segundo
    }

    fun stop() {
        runCatching { out?.close() }
        out = null
    }
}

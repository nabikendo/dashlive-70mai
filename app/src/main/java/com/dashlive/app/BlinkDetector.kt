package com.dashlive.app

/**
 * Reconhece um pisca pelo RITMO: recebe, ~12 vezes por segundo, quanto âmbar forte há num canto do
 * veículo (fração de 0 a 1) e diz se nos últimos [WINDOW_MS] isso acendeu e apagou de forma regular
 * com período de pisca (0,45 a 1,2 s; o normal é ~0,7 s). Luz de freio (fixa), farol, reflexo de
 * poste passando e ruído não têm esse ritmo. Kotlin puro (testado em BlinkDetectorTest). Experimental.
 */
class BlinkDetector {
    private val times = LongArray(CAP)
    private val values = FloatArray(CAP)
    private var n = 0
    private var head = 0

    /** Medida nova (tempo em ms, fração de pixels âmbar no canto). */
    fun add(timeMs: Long, value: Float) {
        times[head] = timeMs
        values[head] = value
        head = (head + 1) % CAP
        if (n < CAP) n++
    }

    /** Está piscando agora? */
    fun blinking(nowMs: Long): Boolean {
        // amostras da janela, da mais velha para a mais nova
        val t = ArrayList<Long>(); val v = ArrayList<Float>()
        for (k in 0 until n) {
            val i = (head - n + k + CAP) % CAP
            if (nowMs - times[i] <= WINDOW_MS) { t += times[i]; v += values[i] }
        }
        if (t.size < MIN_SAMPLES) return false
        var mx = 0f; var mn = Float.MAX_VALUE
        for (x in v) { if (x > mx) mx = x; if (x < mn) mn = x }
        if (mx < MIN_LIT) return false              // nunca acendeu de verdade
        if (mx - mn < 0.6f * mx) return false       // não apaga (freio, farol, poste)
        val mid = (mx + mn) / 2f
        // instantes em que acendeu (passou do meio para cima)
        val rises = ArrayList<Long>()
        for (k in 1 until v.size) if (v[k - 1] < mid && v[k] >= mid) rises += t[k]
        if (rises.size < 2) return false
        val periods = (1 until rises.size).map { (rises[it] - rises[it - 1]).toFloat() }
        val mean = periods.average().toFloat()
        if (mean < MIN_PERIOD_MS || mean > MAX_PERIOD_MS) return false
        val dev = periods.maxOf { kotlin.math.abs(it - mean) }
        return dev <= 0.35f * mean                  // ritmo regular
    }

    companion object {
        private const val CAP = 64
        const val WINDOW_MS = 2500L
        private const val MIN_SAMPLES = 12
        /** Pelo menos 1,5% do canto bem âmbar quando acende. */
        private const val MIN_LIT = 0.015f
        private const val MIN_PERIOD_MS = 450f
        private const val MAX_PERIOD_MS = 1200f
    }
}

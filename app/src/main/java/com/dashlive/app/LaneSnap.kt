package com.dashlive.app

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Onde pôr um veículo no sentido lateral (m, direita +), dadas as bordas da pista [e0, e1].
 * Dentro da pista: centro da faixa mais próxima. Fora da pista (estacionado, no acostamento):
 * fica onde a imagem indica, mas dentro de uma faixa de acostamento. Assim, carros estacionados em
 * fila não são empilhados numa única posição.
 * Puro (sem Android) para ser testado em `LaneSnapTest`.
 */
internal object LaneSnap {
    /** Distância mínima do centro do carro até a borda da pista (m): meia largura de um carro. */
    const val SHOULDER_MIN = 1.0f
    /** Distância máxima do centro do carro até a borda da pista (m). */
    const val SHOULDER_MAX = 3.2f
    /** Tolerância além da borda antes de considerar que o carro está fora da pista (m). */
    const val EDGE_TOL = 0.6f

    fun snap(o: Float, e0: Float, e1: Float, laneW: Float): Float {
        val n = ((e1 - e0) / laneW).roundToInt().coerceAtLeast(1)
        return when {
            o > e1 + EDGE_TOL -> o.coerceIn(e1 + SHOULDER_MIN, e1 + SHOULDER_MAX)
            o < e0 - EDGE_TOL -> o.coerceIn(e0 - SHOULDER_MAX, e0 - SHOULDER_MIN)
            else -> {
                val k = floor((o - e0) / laneW).toInt().coerceIn(0, n - 1)
                e0 + (k + 0.5f) * laneW
            }
        }
    }
}

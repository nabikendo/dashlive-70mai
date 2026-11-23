package com.dashlive.app

/**
 * Descarta caixas pequenas demais para serem um veículo (quase sempre um falso positivo: poste,
 * sinal, reflexo). Tamanho em fração do quadro (0 a 1). Puro, testado em `BoxFilterTest`.
 */
internal object BoxFilter {
    /** Largura mínima, em fração da largura do quadro. */
    const val MIN_W = 0.03f
    /** Altura mínima, em fração da altura do quadro. */
    const val MIN_H = 0.06f

    fun keep(w: Float, h: Float): Boolean = w >= MIN_W && h >= MIN_H
}

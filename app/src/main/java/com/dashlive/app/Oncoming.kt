package com.dashlive.app

/**
 * Carro vindo de frente ou indo na mesma direção, pela cor das luzes da traseira/frente.
 * De frente: faróis brancos e muito claros. De trás: lanternas vermelhas.
 * Puro (sem Android) para ser testado em `OncomingTest`. Números a calibrar com o vídeo real da A200.
 */
object Oncoming {

    /** Pixel de farol: quase branco (ou azul-branco muito claro). */
    fun isHeadlightPixel(r: Int, g: Int, b: Int): Boolean =
        maxOf(r, g, b) >= 225 && minOf(r, g, b) >= 180

    /** Pixel de lanterna: vermelho forte, verde e azul baixos. */
    fun isTaillightPixel(r: Int, g: Int, b: Int): Boolean =
        r >= 170 && g <= 90 && b <= 90 && r - g >= 110

    /**
     * Decide pelas frações de pixels de farol e de lanterna dentro da caixa do veículo.
     * De frente quando há farol visível e ele predomina sobre a lanterna.
     */
    fun isOncoming(headFrac: Float, tailFrac: Float): Boolean =
        headFrac >= MIN_HEAD && headFrac > tailFrac * RATIO

    /** Fração mínima de pixels de farol para considerar "de frente". */
    const val MIN_HEAD = 0.02f

    /** Quantas vezes os faróis precisam superar as lanternas. */
    const val RATIO = 2f
}

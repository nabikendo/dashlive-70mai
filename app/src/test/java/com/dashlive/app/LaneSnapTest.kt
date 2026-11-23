package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneSnapTest {

    private val e0 = -3.3f
    private val e1 = 3.3f
    private val w = 3.3f

    @Test
    fun dentroDaPistaVaiParaOCentroDaFaixa() {
        // pista de 2 faixas: centros em -1,65 e +1,65
        assertEquals(-1.65f, LaneSnap.snap(-1.0f, e0, e1, w), 1e-4f)
        assertEquals(1.65f, LaneSnap.snap(2.0f, e0, e1, w), 1e-4f)
    }

    @Test
    fun acostamentoDaDireitaFicaDentroDaFaixaDeAcostamento() {
        val v = LaneSnap.snap(5.0f, e0, e1, w)
        assertTrue(v >= e1 + LaneSnap.SHOULDER_MIN && v <= e1 + LaneSnap.SHOULDER_MAX)
        assertEquals(5.0f, v, 1e-4f) // a imagem já diz que está ali
    }

    @Test
    fun foraDoAcostamentoNaoPassaDoLimite() {
        assertEquals(e1 + LaneSnap.SHOULDER_MAX, LaneSnap.snap(9f, e0, e1, w), 1e-4f)
    }

    @Test
    fun carrosEstacionadosEmPosicoesDiferentesNaoSeEmpilham() {
        // duas filas de carros parados na beira: posições diferentes continuam diferentes
        val a = LaneSnap.snap(4.2f, e0, e1, w)
        val b = LaneSnap.snap(5.4f, e0, e1, w)
        assertNotEquals(a, b, 1e-4f)
    }

    @Test
    fun acostamentoDaEsquerdaEspelhado() {
        val v = LaneSnap.snap(-6.0f, e0, e1, w)
        assertTrue(v <= e0 - LaneSnap.SHOULDER_MIN && v >= e0 - LaneSnap.SHOULDER_MAX)
        assertEquals(-6.0f, v, 1e-4f)
    }

    @Test
    fun ruaDeUmaFaixaNaoQuebra() {
        // pista de uma faixa só: não pode dar erro nem sair da pista
        val v = LaneSnap.snap(0.5f, -1.65f, 1.65f, 3.3f)
        assertEquals(0f, v, 1e-4f)
    }
}

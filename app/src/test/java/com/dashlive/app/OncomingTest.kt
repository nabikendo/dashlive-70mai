package com.dashlive.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OncomingTest {

    @Test
    fun faroisBrancosSaoFarol() {
        assertTrue(Oncoming.isHeadlightPixel(250, 250, 240))
        assertTrue(Oncoming.isHeadlightPixel(235, 240, 255)) // azul-branco
    }

    @Test
    fun vermelhoSaoLanternaNaoFarol() {
        assertTrue(Oncoming.isTaillightPixel(220, 30, 30))
        assertFalse(Oncoming.isHeadlightPixel(220, 30, 30))
    }

    @Test
    fun cinzaEscuroNaoEhNada() {
        assertFalse(Oncoming.isHeadlightPixel(90, 90, 95))
        assertFalse(Oncoming.isTaillightPixel(90, 90, 95))
    }

    @Test
    fun muitosFaroisEPoucaLanternaEhDeFrente() {
        assertTrue(Oncoming.isOncoming(headFrac = 0.10f, tailFrac = 0.01f))
    }

    @Test
    fun muitasLanternasEhDeTras() {
        assertFalse(Oncoming.isOncoming(headFrac = 0.03f, tailFrac = 0.20f))
    }

    @Test
    fun poucoFarolNaoBasta() {
        assertFalse(Oncoming.isOncoming(headFrac = 0.005f, tailFrac = 0f))
    }

    @Test
    fun faroisEMesmaQuantidadeDeLanternasNaoEhDeFrente() {
        // o limite é 2x: com o mesmo tanto dos dois, fica como "de trás" (mais seguro)
        assertFalse(Oncoming.isOncoming(headFrac = 0.10f, tailFrac = 0.10f))
    }
}

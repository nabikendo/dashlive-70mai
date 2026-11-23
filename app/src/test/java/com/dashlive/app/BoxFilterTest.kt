package com.dashlive.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxFilterTest {

    @Test
    fun veiculoDeTamanhoNormalFica() {
        assertTrue(BoxFilter.keep(0.12f, 0.2f))
    }

    @Test
    fun caixaFinaDemaisSai() {
        assertFalse(BoxFilter.keep(0.01f, 0.2f))
    }

    @Test
    fun caixaBaixaDemaisSai() {
        assertFalse(BoxFilter.keep(0.1f, 0.02f))
    }

    @Test
    fun limitesExatosFicam() {
        assertTrue(BoxFilter.keep(BoxFilter.MIN_W, BoxFilter.MIN_H))
    }
}

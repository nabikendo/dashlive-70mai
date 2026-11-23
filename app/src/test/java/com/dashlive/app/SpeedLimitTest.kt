package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedLimitTest {

    @Test
    fun numeroSimplesEmKmh() {
        assertEquals(40, SpeedLimit.parse("40"))
        assertEquals(60, SpeedLimit.parse("60 km/h"))
        assertEquals(30, SpeedLimit.parse("30 kmh"))
    }

    @Test
    fun milhasConvertidasParaKmh() {
        assertEquals(40, SpeedLimit.parse("25 mph"))
    }

    @Test
    fun tagsSemNumeroViramNulo() {
        assertNull(SpeedLimit.parse("BR:urban"))
        assertNull(SpeedLimit.parse("signals"))
        assertNull(SpeedLimit.parse(null))
        assertNull(SpeedLimit.parse("  "))
    }

    @Test
    fun valoresAbsurdosSaem() {
        assertNull(SpeedLimit.parse("2"))
        assertNull(SpeedLimit.parse("999"))
    }
}

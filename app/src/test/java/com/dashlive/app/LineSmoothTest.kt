package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSmoothTest {

    @Test
    fun pontasFicamFixasENumeroNaoMuda() {
        val xy = floatArrayOf(0f, 0f, 1f, 3f, 2f, -3f, 3f, 0f)
        smoothLine(xy, 3)
        assertEquals(8, xy.size)
        assertEquals(0f, xy[0], 0f); assertEquals(0f, xy[1], 0f)
        assertEquals(3f, xy[6], 0f); assertEquals(0f, xy[7], 0f)
    }

    @Test
    fun zigueZagueFicaMaisSuave() {
        val xy = floatArrayOf(0f, 0f, 1f, 4f, 2f, -4f, 3f, 4f, 4f, 0f)
        val before = maxJump(xy)
        smoothLine(xy, 2)
        assertTrue(maxJump(xy) < before)
    }

    @Test
    fun retaNaoMuda() {
        val xy = floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f)
        val copy = xy.copyOf()
        smoothLine(xy, 2)
        for (i in xy.indices) assertEquals(copy[i], xy[i], 1e-6f)
    }

    @Test
    fun poucosPontosNaoQuebram() {
        val xy = floatArrayOf(5f, 5f, 6f, 6f)
        smoothLine(xy)
        assertEquals(5f, xy[0], 0f)
        assertEquals(6f, xy[2], 0f)
    }

    /** Maior salto (em m) entre dois pontos seguidos; quanto menor, mais suave. */
    private fun maxJump(xy: FloatArray): Float {
        var m = 0f
        for (i in 1 until xy.size / 2) {
            val dx = xy[i * 2] - xy[(i - 1) * 2]
            val dy = xy[i * 2 + 1] - xy[(i - 1) * 2 + 1]
            m = maxOf(m, kotlin.math.sqrt(dx * dx + dy * dy))
        }
        return m
    }
}

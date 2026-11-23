package com.dashlive.app.adas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.max

class LaneFinderTest {
    private val w = 480
    private val h = 270

    /** Asfalto cinza com faixas brancas retas que se encontram em (xv, yv). */
    private fun road(xs: List<Float>, xv: Float = 240f, yv: Float = 120f, asphalt: Int = 70, paint: Int = 225): IntArray {
        val px = IntArray(w * h) { gray(asphalt) }
        for (y in yv.toInt() + 1 until h) {
            val t = (y - yv) / (h - yv)
            val lw = max(2, (w * (0.004f + 0.02f * t)).toInt())
            for (x0 in xs) {
                val cx = xv + (x0 - xv) * t
                for (x in (cx - lw / 2f).toInt()..(cx + lw / 2f).toInt()) if (x in 0 until w) px[y * w + x] = gray(paint)
            }
        }
        return px
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun expectX(x0: Float, y: Float, xv: Float = 240f, yv: Float = 120f) = xv + (x0 - xv) * (y - yv) / (h - yv)

    @Test fun findsBothLanesOnStraightRoad() {
        val s = LaneFinder().find(road(listOf(130f, 350f)), w, h, 0, 240f)
        assertNotNull(s.left); assertNotNull(s.right)
        val y = 0.85f * h
        assertEquals(expectX(130f, y), s.left!!.x(y), 3f)
        assertEquals(expectX(350f, y), s.right!!.x(y), 3f)
    }

    @Test fun picksTheLanesNextToTheCarNotTheNeighbours() {
        // 4 faixas pintadas: a do seu carro é a do meio (entre 130 e 350)
        val s = LaneFinder().find(road(listOf(-90f, 130f, 350f, 570f)), w, h, 0, 240f)
        val y = 0.85f * h
        assertEquals(expectX(130f, y), s.left!!.x(y), 3f)
        assertEquals(expectX(350f, y), s.right!!.x(y), 3f)
    }

    @Test fun worksWithOnlyTheBottomRowsCopied() {
        val full = road(listOf(130f, 350f))
        val y0 = (LaneFinder.TOP * h).toInt()
        val part = full.copyOfRange(y0 * w, h * w)
        val s = LaneFinder().find(part, w, h, y0, 240f)
        assertEquals(expectX(130f, 0.85f * h), s.left!!.x(0.85f * h), 3f)
    }

    @Test fun noPaintNoLanes() {
        val s = LaneFinder().find(IntArray(w * h) { gray(80) }, w, h, 0, 240f)
        assertNull(s.left); assertNull(s.right)
    }

    @Test fun weakContrastIsIgnored() {
        // "faixa" quase da cor do asfalto (desgastada / sombra)
        val s = LaneFinder().find(road(listOf(130f, 350f), paint = 82), w, h, 0, 240f)
        assertNull(s.left); assertNull(s.right)
    }

    @Test fun fitRecoversAParabola() {
        val ys = (150..260 step 2).map { it.toFloat() }
        val xs = ys.map { 0.002f * it * it - 0.5f * it + 200f }
        val p = LaneFinder.fit(xs, ys, w, 110f)!!
        assertEquals(0.002f, p.a, 1e-4f)
        assertEquals(0.002f * 200 * 200 - 0.5f * 200 + 200f, p.x(200f), 0.5f)
    }

    @Test fun fitDropsOutliers() {
        val ys = (150..260 step 2).map { it.toFloat() }
        val xs = ys.mapIndexed { i, y -> if (i % 9 == 4) 400f else 0.8f * y }
        val p = LaneFinder.fit(xs, ys, w, 110f)!!
        assertEquals(0.8f * 230f, p.x(230f), 2f)
    }
}

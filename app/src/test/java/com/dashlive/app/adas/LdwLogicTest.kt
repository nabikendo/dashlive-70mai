package com.dashlive.app.adas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LdwLogicTest {
    private val w = 480
    private val h = 270

    /** Reta que passa por x0 na base da imagem e pelo ponto de fuga (xv, yv). */
    private fun line(x0: Float, xv: Float, yv: Float = 120f): LanePoly {
        val b = (x0 - xv) / (h - yv)
        return LanePoly(0f, b, xv - b * yv, 150f, 260f, 40)
    }

    /** Faixas de 130 e 350 na base; ponto de fuga em [xv] (240 = carro reto, < 240 = virado para a direita). */
    private fun lanes(xv: Float = 240f, shift: Float = 0f) =
        LaneSides(w, h, line(130f + shift, xv), line(350f + shift, xv))

    private fun run(l: LdwLogic, s: LaneSides?, t: Long, kmh: Float? = 80f, left: Boolean = false, right: Boolean = false) =
        l.update(s, kmh, left, right, t)

    @Test fun centredAndStraightIsQuiet() {
        val l = LdwLogic()
        var r = run(l, lanes(), 0)
        repeat(5) { r = run(l, lanes(), 100L * (it + 1)) }
        assertEquals(LdwLevel.NONE, r.level)
        assertEquals(0f, r.offset!!, 0.01f)
        assertEquals(0f, r.headingDeg!!, 0.1f)
    }

    @Test fun vanishingPointGivesHeading() {
        // ponto de fuga 40 px à esquerda do centro → virado ~10,7° para a direita (f ≈ 212 px em 480)
        assertEquals(200f, LdwLogic.vanishingX(line(130f, 200f), line(350f, 200f), h)!!, 0.5f)
        val r = LdwLogic().update(lanes(xv = 200f), 80f, false, false, 0)
        assertEquals(10.7f, r.headingDeg!!, 0.3f)
    }

    /** Carro deslocado para a direita (faixas 100 px à esquerda na base) e virado para a direita. */
    private fun driftingRight() = lanes(xv = 200f, shift = -60f)

    @Test fun driftingOutWarnsAfterThreeFrames() {
        val l = LdwLogic()
        val s = driftingRight()
        // offset ≈ 0,68 > 0,50 (Média), < 0,75; rumo para a direita → aviso
        assertEquals(LdwLevel.NONE, run(l, s, 0).level)
        assertEquals(LdwLevel.NONE, run(l, s, 100).level)
        val r = run(l, s, 200)
        assertEquals(LdwLevel.WARN, r.level)
        assertEquals(1, r.side)
        assertEquals(LdwLevel.WARN, r.sound)
    }

    @Test fun overTheLineIsCritical() {
        val l = LdwLogic()
        val s = lanes(xv = 200f, shift = -100f) // offset ≈ 0,91
        repeat(2) { run(l, s, it * 100L) }
        val r = run(l, s, 200)
        assertEquals(LdwLevel.CRITICAL, r.level)
        assertEquals(LdwLevel.CRITICAL, r.sound)
    }

    @Test fun offCentreButHeadingBackIsQuiet() {
        val l = LdwLogic()
        val s = lanes(xv = 280f, shift = -100f) // offset ≈ 0,77 à direita, mas virado para a esquerda
        repeat(5) { assertEquals(LdwLevel.NONE, run(l, s, it * 100L).level) }
    }

    @Test fun belowThirtyKmhIsQuiet() {
        val l = LdwLogic()
        repeat(5) { assertEquals(LdwLevel.NONE, run(l, driftingRight(), it * 100L, kmh = 25f).level) }
        repeat(5) { assertEquals(LdwLevel.NONE, run(l, driftingRight(), 1000 + it * 100L, kmh = null).level) }
    }

    @Test fun missingOneSideIsQuiet() {
        val l = LdwLogic()
        val one = LaneSides(w, h, line(70f, 200f), null)
        repeat(5) { assertEquals(LdwLevel.NONE, run(l, one, it * 100L).level) }
    }

    @Test fun turnSignalToThatSideSuppressesForTwoSeconds() {
        val l = LdwLogic()
        repeat(5) { assertEquals(LdwLevel.NONE, run(l, driftingRight(), it * 100L, right = true).level) }
        // seta desligou em t = 400; até 2,4 s continua quieto
        repeat(5) { assertEquals(LdwLevel.NONE, run(l, driftingRight(), 1000 + it * 300L).level) }
        // depois dos 2 s volta a avisar
        run(l, driftingRight(), 2500); run(l, driftingRight(), 2600)
        assertEquals(LdwLevel.WARN, run(l, driftingRight(), 2700).level)
    }

    @Test fun signalToTheOtherSideDoesNotSuppress() {
        val l = LdwLogic()
        run(l, driftingRight(), 0, left = true); run(l, driftingRight(), 100, left = true)
        assertEquals(LdwLevel.WARN, run(l, driftingRight(), 200, left = true).level)
    }

    @Test fun descendsAfterFiveQuietFramesAndCoolsDown() {
        val l = LdwLogic()
        repeat(3) { run(l, driftingRight(), it * 100L) }      // aviso + som em t = 200
        repeat(4) { assertEquals(LdwLevel.WARN, run(l, lanes(), 300 + it * 100L).level) }
        assertEquals(LdwLevel.NONE, run(l, lanes(), 700).level)
        // volta a sair em t = 800..1000 (< 2 s do último som): avisa sem tocar
        run(l, driftingRight(), 800); run(l, driftingRight(), 900)
        val r = run(l, driftingRight(), 1000)
        assertEquals(LdwLevel.WARN, r.level)
        assertNull(r.sound)
    }

    @Test fun sensitivityChangesThresholds() {
        val s = driftingRight() // offset ≈ 0,68
        assertEquals(0.68f, LdwLogic().update(s, 80f, false, false, 0).offset!!, 0.02f)
        val low = LdwLogic().apply { sensitivity = Sensitivity.LOW }      // 0,65 / 0,85
        repeat(2) { run(low, s, it * 100L) }
        assertEquals(LdwLevel.WARN, run(low, s, 200).level)
        val high = LdwLogic().apply { sensitivity = Sensitivity.HIGH }    // 0,35 / 0,55
        repeat(2) { run(high, s, it * 100L) }
        assertEquals(LdwLevel.CRITICAL, run(high, s, 200).level)
    }
}

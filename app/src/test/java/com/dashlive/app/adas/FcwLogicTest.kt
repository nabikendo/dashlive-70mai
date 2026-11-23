package com.dashlive.app.adas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcwLogicTest {
    private val w = 640
    private val h = 360
    private val f = FcwLogic.defaultFocal(w) // lente de 130°: ~282 px

    /** Caixa de [cls] a [d] metros, centrada em [cx] (fração), pela fórmula inversa da distância. */
    private fun at(d: Float, cls: FcwClass = FcwClass.CAR, cx: Float = 0.5f): FcwBox {
        val hFrac = cls.heightM * f / d / h
        val wFrac = hFrac * h / w * 1.2f
        val bottom = 0.75f
        return FcwBox(cls, cx - wFrac / 2, bottom - hFrac, cx + wFrac / 2, bottom)
    }

    private fun run(l: FcwLogic, boxes: List<FcwBox>, t: Long, kmh: Float? = 60f) = l.update(boxes, w, h, kmh, t)

    // ---------------------------------------------------------------- distância

    @Test fun distanceUsesRealHeightPerClass() {
        val f512 = 512f
        assertEquals(15f, FcwLogic.distance(FcwClass.CAR, 51.2f, f512), 0.01f)       // 1,5 m
        assertEquals(30f, FcwLogic.distance(FcwClass.TRUCK, 51.2f, f512), 0.01f)     // 3,0 m
        assertEquals(30f, FcwLogic.distance(FcwClass.BUS, 51.2f, f512), 0.01f)       // 3,0 m
        assertEquals(17f, FcwLogic.distance(FcwClass.PERSON, 51.2f, f512), 0.01f)    // 1,7 m
        assertEquals(12f, FcwLogic.distance(FcwClass.MOTORCYCLE, 51.2f, f512), 0.01f) // 1,2 m
    }

    @Test fun focalFor130DegreeLens() {
        // olho de peixe equidistante: 320 px / (65° em rad) ≈ 282 px
        assertEquals(282.07f, FcwLogic.defaultFocal(640), 0.05f)
    }

    @Test fun calibratedFocalOverridesDefault() {
        val l = FcwLogic().apply { focalPx = 2 * f }
        // mesma caixa, focal dobrada → distância dobra: 20 m em vez de 10 m (sai do Atenção para Informativo)
        val r1 = run(l, listOf(at(10f)), 0); val r2 = run(l, listOf(at(10f)), 100)
        assertEquals(FcwLevel.NONE, r1.level)
        assertEquals(FcwLevel.INFO, r2.level)
        assertEquals(20f, r2.target!!.distanceM, 0.05f)
    }

    // ---------------------------------------------------------------- TTC

    @Test fun ttcOnlyWhenApproaching() {
        assertEquals(2f, FcwLogic.ttc(10f, -5f)!!, 1e-4f)
        assertNull(FcwLogic.ttc(10f, 2f))
        assertNull(FcwLogic.ttc(10f, 0f))
    }

    @Test fun levelsByDistanceAndTtc() {
        val m = Sensitivity.MEDIUM
        assertEquals(FcwLevel.NONE, FcwLogic.rawLevel(40f, null, m))
        assertEquals(FcwLevel.INFO, FcwLogic.rawLevel(25f, null, m))
        assertEquals(FcwLevel.ATTENTION, FcwLogic.rawLevel(12f, null, m))
        assertEquals(FcwLevel.CRITICAL, FcwLogic.rawLevel(7f, null, m))
        assertEquals(FcwLevel.INFO, FcwLogic.rawLevel(40f, 2.0f, m))
        assertEquals(FcwLevel.ATTENTION, FcwLogic.rawLevel(40f, 1.2f, m))
        assertEquals(FcwLevel.CRITICAL, FcwLogic.rawLevel(40f, 0.5f, m))
    }

    @Test fun sensitivityScalesMeasuredTtc() {
        // 2,0 s: Alta ×0,7 = 1,4 → Atenção; Média → Informativo
        assertEquals(FcwLevel.ATTENTION, FcwLogic.rawLevel(40f, 2.0f, Sensitivity.HIGH))
        assertEquals(FcwLevel.INFO, FcwLogic.rawLevel(40f, 2.0f, Sensitivity.MEDIUM))
        // 1,2 s: Baixa ×1,3 = 1,56 → Informativo; Média → Atenção
        assertEquals(FcwLevel.INFO, FcwLogic.rawLevel(40f, 1.2f, Sensitivity.LOW))
    }

    @Test fun relativeSpeedFromIouMatchedFrames() {
        val l = FcwLogic()
        // carro de 1,5 m com f ≈ 282 px só passa dos 16 px até ~26 m
        run(l, listOf(at(24f)), 0)
        run(l, listOf(at(23f)), 100)
        // 100 ms depois a 22 m: v_rel = −10 m/s → TTC 2,2 s
        val r = run(l, listOf(at(22f)), 200)
        assertEquals(FcwLevel.INFO, r.level)
        assertEquals(22f, r.target!!.distanceM, 0.05f)
        assertEquals(2.2f, r.target!!.ttcS!!, 0.05f)
    }

    @Test fun carBeyond26mIsTooSmall() {
        val l = FcwLogic()
        // 27 m → 15,7 px < 16 px: ignorado, mesmo dentro dos 30 m do Informativo
        run(l, listOf(at(27f)), 0)
        assertEquals(FcwLevel.NONE, run(l, listOf(at(27f)), 100).level)
    }

    @Test fun approachingCarRaisesLevelByTtc() {
        // caminhão (3 m de altura, visível até ~51 m): acima de 30 m só o TTC dá nível
        val truck = FcwClass.TRUCK
        val l = FcwLogic()
        run(l, listOf(at(40f, truck)), 0)
        run(l, listOf(at(39f, truck)), 100)
        val r = run(l, listOf(at(38f, truck)), 200)
        assertEquals(FcwLevel.NONE, r.level) // −10 m/s: TTC 3,8 s ainda
        val l2 = FcwLogic()
        run(l2, listOf(at(36f, truck)), 0)
        run(l2, listOf(at(34f, truck)), 100)  // v_rel −20 m/s, TTC 1,7 s → Informativo (1º quadro)
        val r2 = run(l2, listOf(at(32f, truck)), 200) // 2º quadro → sobe
        assertEquals(FcwLevel.INFO, r2.level)
        assertEquals(1.6f, r2.target!!.ttcS!!, 0.05f)
    }

    @Test fun lostObjectStartsNewTrackWithoutSpeed() {
        val l = FcwLogic()
        run(l, listOf(at(40f)), 0)
        run(l, emptyList(), 300)
        // volta depois de 600 ms (> 0,5 s): trilha nova, sem v_rel; D 39 m não dá nível
        val r = run(l, listOf(at(39f)), 600)
        assertEquals(FcwLevel.NONE, r.level)
        assertNull(r.sound)
    }

    // ---------------------------------------------------------------- zona e velocidade

    @Test fun ignoresOutsideEgoZoneAndSmallBoxes() {
        val l = FcwLogic()
        val side = at(10f, cx = 0.85f)          // ~10 m para o lado
        val nextLane = at(20f, cx = 0.6f)       // 64 px do centro a 20 m → ~4,6 m: faixa vizinha
        val far = at(30f, FcwClass.MOTORCYCLE)   // 1,2·282/30 = 11 px < 16 px
        run(l, listOf(side, nextLane, far), 0)
        assertEquals(FcwLevel.NONE, run(l, listOf(side, nextLane, far), 100).level)
        // a mesma distância, mas no centro: entra
        assertEquals(4.6f, FcwLogic.lateral(nextLane, w, f, 20f), 0.1f)
        assertEquals(0f, FcwLogic.lateral(at(20f), w, f, 20f), 0.01f)
    }

    @Test fun noAlertBelow20KmhOrUnknownSpeed() {
        val l = FcwLogic()
        run(l, listOf(at(7f)), 0, kmh = 15f)
        assertEquals(FcwLevel.NONE, run(l, listOf(at(7f)), 100, kmh = 15f).level)
        run(l, listOf(at(7f)), 200, kmh = null)
        assertEquals(FcwLevel.NONE, run(l, listOf(at(7f)), 300, kmh = null).level)
        run(l, listOf(at(7f)), 400, kmh = 25f)
        assertEquals(FcwLevel.CRITICAL, run(l, listOf(at(7f)), 500, kmh = 25f).level)
    }

    // ---------------------------------------------------------------- histerese e cooldown

    @Test fun risesAfterTwoFramesWithSound() {
        val l = FcwLogic()
        val r1 = run(l, listOf(at(12f)), 0)
        assertEquals(FcwLevel.NONE, r1.level); assertNull(r1.sound)
        val r2 = run(l, listOf(at(12f)), 100)
        assertEquals(FcwLevel.ATTENTION, r2.level)
        assertEquals(FcwLevel.ATTENTION, r2.sound)
        // continua no mesmo nível: não toca de novo
        assertNull(run(l, listOf(at(12f)), 200).sound)
    }

    @Test fun singleFrameSpikeDoesNotRaise() {
        val l = FcwLogic()
        run(l, listOf(at(7f)), 0)
        assertEquals(FcwLevel.NONE, run(l, emptyList(), 100).level)
        assertEquals(FcwLevel.NONE, run(l, listOf(at(40f)), 200).level)
    }

    @Test fun descendsOnlyAfterTenFrames() {
        val l = FcwLogic()
        run(l, listOf(at(12f)), 0); run(l, listOf(at(12f)), 100)
        var t = 200L
        repeat(9) {
            assertEquals(FcwLevel.ATTENTION, run(l, emptyList(), t).level)
            t += 100
        }
        assertEquals(FcwLevel.NONE, run(l, emptyList(), t).level)
    }

    @Test fun cooldownPerLevel() {
        val l = FcwLogic()
        fun raise(t0: Long): FcwResult { run(l, listOf(at(25f)), t0); return run(l, listOf(at(25f)), t0 + 100) }
        fun drop(t0: Long) { for (i in 0 until 10) run(l, emptyList(), t0 + i * 100L) }

        assertEquals(FcwLevel.INFO, raise(0).sound)  // toca em t = 100
        drop(200)                                    // desce até t = 1100
        val again = raise(1200)                      // sobe em t = 1300 (< 3 s)
        assertEquals(FcwLevel.INFO, again.level)
        assertNull(again.sound)
        drop(1400)
        assertEquals(FcwLevel.INFO, raise(3100).sound) // t = 3200 ≥ 100 + 3000
    }

    @Test fun climbsToHigherLevelWithItsOwnSound() {
        val l = FcwLogic()
        run(l, listOf(at(25f)), 0)
        assertEquals(FcwLevel.INFO, run(l, listOf(at(25f)), 100).sound)
        run(l, listOf(at(7f)), 200)
        val r = run(l, listOf(at(7f)), 300)
        assertEquals(FcwLevel.CRITICAL, r.level)
        assertEquals(FcwLevel.CRITICAL, r.sound)
        assertTrue(r.target!!.distanceM < 8f)
    }
}

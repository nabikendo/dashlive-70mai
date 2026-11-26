package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadAlertsTest {

    @Test fun speedAlarmFiresOnceThenRepeats() {
        val a = SpeedAlarm(repeatMs = 5000L)
        assertFalse(a.update(0L, 60f, 60))          // dentro do limite + folga de 3
        assertTrue(a.update(1000L, 65f, 60))        // passou da folga: bipa
        assertFalse(a.update(3000L, 66f, 60))       // ainda acima, mas cedo
        assertTrue(a.update(6500L, 66f, 60))        // depois de 5 s, bipa de novo
    }

    @Test fun speedAlarmRearmsAfterSlowingDown() {
        val a = SpeedAlarm(repeatMs = 5000L)
        assertTrue(a.update(0L, 70f, 60))
        assertFalse(a.update(1000L, 50f, 60))      // voltou abaixo: rearma
        assertTrue(a.update(1500L, 70f, 60))       // passou de novo: bipa logo
    }

    @Test fun speedAlarmIgnoresUnknownSpeedOrLimit() {
        val a = SpeedAlarm()
        assertFalse(a.update(0L, null, 60))
        assertFalse(a.update(0L, 120f, null))
    }

    @Test fun radarAlarmFiresOncePerApproach() {
        val r = RadarAlarm(warnM = 300f, clearM = 350f)
        assertFalse(r.update(null))
        assertFalse(r.update(400f))
        assertTrue(r.update(290f))                 // entrou nos 300 m
        assertFalse(r.update(250f))                // já avisou
        assertFalse(r.update(330f))                // ainda perto, sem sair da faixa de rearme
        assertFalse(r.update(400f))                // saiu: rearma
        assertTrue(r.update(280f))                 // nova aproximação: avisa de novo
    }

    @Test fun tripSummaryCountsDistanceStopAndAverage() {
        // 0,001° de longitude no equador ≈ 111 m: dois trechos andados a 80 km/h
        val pts = listOf(
            TripPoint(0L, 0.0, 0.0, 0f, null),
            TripPoint(1000L, 0.0, 0.0, 0f, null),     // parado 1 s
            TripPoint(2000L, 0.0, 0.001, 80f, null),  // andou ~111 m
            TripPoint(3000L, 0.0, 0.002, 80f, null),  // andou ~111 m
        )
        val s = TripLog.summary(pts)
        assertEquals(222.0, s.distanceM, 3.0)
        assertEquals(1000L, s.stoppedMs)
        assertEquals(2000L, s.movingMs)
        assertEquals(80f, s.maxKmh, 0.01f)
        assertEquals(266f, s.avgKmh, 5f)          // 222 m em 3 s
    }
}

package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TripLogTest {

    private val text = """
        t_ms,lat,lon,speed_kmh,bearing
        0,-10.0000000,-48.0000000,40.0,90.0
        lixo sem vírgulas
        1000,-10.0010000,-48.0000000,60.0,
        2000,-10.0020000,-48.0000000,,0.0
    """.trimIndent()

    @Test fun parseSkipsHeaderAndBadLines() {
        val pts = TripLog.parse(text)
        assertEquals(3, pts.size)
        assertEquals(1000L, pts[1].t)
        assertNull(pts[1].bearing)
        assertNull(pts[2].speedKmh)
    }

    @Test fun lineRoundTrip() {
        val p = TripPoint(1500L, -10.5, -48.25, 72.3f, 181.0f)
        val back = TripLog.parse(TripLog.line(p)).single()
        assertEquals(p.t, back.t)
        assertEquals(p.lat, back.lat, 1e-6)
        assertEquals(p.lon, back.lon, 1e-6)
        assertEquals(72.3f, back.speedKmh!!, 0.05f)
        assertEquals(181.0f, back.bearing!!, 0.05f)
    }

    @Test fun trackInterpolatesAndEnds() {
        val tr = TripLog.Track(TripLog.parse(text))
        assertEquals(2000L, tr.durationMs)
        val mid = tr.at(500L)!!
        assertEquals(-10.0005, mid.lat, 1e-7)
        assertEquals(50f, mid.speedKmh!!, 1e-3f)
        assertEquals(90f, mid.bearing!!, 1e-3f)
        assertNull(tr.at(2000L))
        assertEquals(-10.0, tr.at(-5L)!!.lat, 1e-9)
    }

    @Test fun recorderKeepsOnePointPerGap() {
        val f = File.createTempFile("viagem_teste", ".csv")
        val r = TripRecorder(f, startMs = 1_000L)
        r.add(1_000L, -10.0, -48.0, 10f, 0f)
        r.add(1_400L, -10.1, -48.0, 11f, 0f)   // dentro de 1 s: descartado
        r.add(2_100L, -10.2, -48.0, 12f, 0f)
        r.stop()
        val pts = TripLog.parse(f.readText())
        assertEquals(2, pts.size)
        assertEquals(0L, pts[0].t)
        assertEquals(1100L, pts[1].t)
        assertTrue(TripLog.fileName(0L).startsWith("viagem_") && TripLog.fileName(0L).endsWith(".csv"))
        f.delete()
    }
}

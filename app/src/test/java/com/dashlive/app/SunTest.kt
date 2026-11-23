package com.dashlive.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class SunTest {
    private val lat = -23.55 // São Paulo
    private val lon = -46.63

    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun noonIsDay() {
        val el = Sun.elevation(lat, lon, at("2026-11-22T12:00:00-03:00"))
        assertTrue("meio-dia: sol alto ($el°)", el > 70)
    }

    @Test fun midnightIsNight() {
        assertFalse(Sun.isDay(lat, lon, at("2026-11-22T00:00:00-03:00")))
        assertFalse(Sun.isDay(lat, lon, at("2026-11-22T22:18:00-03:00")))
    }

    @Test fun sunriseAndSunset() {
        // em novembro em São Paulo o sol nasce ~5h15 e se põe ~18h40
        assertFalse(Sun.isDay(lat, lon, at("2026-11-22T04:45:00-03:00")))
        assertTrue(Sun.isDay(lat, lon, at("2026-11-22T05:45:00-03:00")))
        assertTrue(Sun.isDay(lat, lon, at("2026-11-22T18:30:00-03:00")))
        assertFalse(Sun.isDay(lat, lon, at("2026-11-22T19:30:00-03:00")))
    }
}

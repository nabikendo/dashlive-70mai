package com.dashlive.app

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Altura do sol no céu (graus) num lugar e hora, pela fórmula aproximada do Almanaque Náutico
 * (erro de ~1°, suficiente para saber se é dia). Kotlin puro, testado em SunTest.
 */
object Sun {

    /** Graus acima do horizonte (negativo = abaixo). */
    fun elevation(lat: Double, lon: Double, epochMs: Long): Double {
        val d = epochMs / 86_400_000.0 - 10_957.5 // dias desde 01/01/2000 12:00 UTC
        val g = Math.toRadians(norm(357.529 + 0.98560028 * d))
        val q = norm(280.459 + 0.98564736 * d)
        val l = Math.toRadians(norm(q + 1.915 * sin(g) + 0.020 * sin(2 * g)))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmst = norm((18.697374558 + 24.06570982441908 * d) * 15.0)
        val h = Math.toRadians(gmst + lon) - ra
        val la = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(h)))
    }

    /** É dia: o sol está acima de -2° (até um pouco depois do pôr do sol ainda há luz). */
    fun isDay(lat: Double, lon: Double, epochMs: Long) = elevation(lat, lon, epochMs) > -2.0

    private fun norm(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}

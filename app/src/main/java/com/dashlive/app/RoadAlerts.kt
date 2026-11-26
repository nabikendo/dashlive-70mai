package com.dashlive.app

/**
 * Alerta de limite de velocidade: dispara ao passar do limite (com [marginKmh] de folga) e de novo a
 * cada [repeatMs] enquanto continuar acima; voltar para dentro do limite rearma. Puro, testado em RoadAlertsTest.
 */
class SpeedAlarm(private val repeatMs: Long = 5_000L, private val marginKmh: Float = 3f) {
    private var lastAt = Long.MIN_VALUE

    fun update(nowMs: Long, speedKmh: Float?, limitKmh: Int?): Boolean {
        if (speedKmh == null || limitKmh == null || speedKmh <= limitKmh + marginKmh) {
            lastAt = Long.MIN_VALUE
            return false
        }
        if (lastAt != Long.MIN_VALUE && nowMs - lastAt < repeatMs) return false
        lastAt = nowMs
        return true
    }
}

/**
 * Radar à frente: dispara uma vez quando a distância cai para dentro de [warnM] metros. Só dispara de
 * novo depois que o carro se afasta ([clearM], com folga) ou não há mais radar à frente.
 */
class RadarAlarm(private val warnM: Float = 300f, private val clearM: Float = 350f) {
    private var inside = false

    fun update(distanceM: Float?): Boolean {
        if (distanceM == null || distanceM > clearM) {
            inside = false
            return false
        }
        if (distanceM <= warnM && !inside) {
            inside = true
            return true
        }
        return false
    }
}

/**
 * Velocidade do GPS sem o ruído de quando o carro está parado: dentro de casa o S24 FE marcou
 * 9 km/h parado (26/11/2026). Parado ou com erro maior que o valor = 0 km/h.
 */
object GpsSpeed {
    /** Abaixo disso (m/s) conta como parado: 1,4 m/s ≈ 5 km/h. */
    const val STILL_MPS = 1.4f

    /** [speedAccMps] e [horizAccM] são a precisão informada pelo GPS (null = não informada). */
    fun kmh(speedMps: Float, speedAccMps: Float?, horizAccM: Float?): Float {
        if (speedMps < STILL_MPS) return 0f
        if (speedAccMps != null && speedAccMps >= speedMps) return 0f // erro maior que a própria velocidade
        if (horizAccM != null && horizAccM > 25f && speedMps < 4f) return 0f // posição ruim e devagar: ruído
        return speedMps * 3.6f
    }
}

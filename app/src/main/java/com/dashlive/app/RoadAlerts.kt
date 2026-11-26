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

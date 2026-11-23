package com.dashlive.app

/**
 * Limite de velocidade lido da tag `maxspeed` do OpenStreetMap. Puro, testado em `SpeedLimitTest`.
 * Aceita "40", "40 km/h", "40 kmh" e "25 mph" (convertido para km/h). Tags como "BR:urban" ou
 * "signals" não dão número, então devolvem null.
 */
internal object SpeedLimit {
    private val re = Regex("""^\s*(\d{1,3})\s*(km/h|kmh|kph|mph)?\s*$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val m = re.find(raw) ?: return null
        val v = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val kmh = if (unit == "mph") Math.round(v * 1.609344).toInt() else v
        return kmh.takeIf { it in 5..150 }
    }
}

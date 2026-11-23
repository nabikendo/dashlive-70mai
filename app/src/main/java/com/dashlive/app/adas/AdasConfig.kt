package com.dashlive.app.adas

import android.content.SharedPreferences

/**
 * Ajustes do ADAS no mesmo `SharedPreferences` ("dashlive") do resto do app. Sem DataStore: o
 * projeto já usa prefs, e DataStore seria dependência nova sem ganho. Leituras são em memória
 * (servem na thread do detector).
 */
class AdasConfig(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean("adas_enabled", false)
        set(v) = prefs.edit().putBoolean("adas_enabled", v).apply()

    /** Aviso de saída de faixa (padrão desligado até o teste no carro). */
    var ldwEnabled: Boolean
        get() = prefs.getBoolean("adas_ldw_enabled", false)
        set(v) = prefs.edit().putBoolean("adas_ldw_enabled", v).apply()

    var fcwEnabled: Boolean
        get() = prefs.getBoolean("adas_fcw_enabled", true)
        set(v) = prefs.edit().putBoolean("adas_fcw_enabled", v).apply()

    var sensitivity: Sensitivity
        get() = runCatching { Sensitivity.valueOf(prefs.getString("adas_sensitivity", "MEDIUM")!!) }
            .getOrDefault(Sensitivity.MEDIUM)
        set(v) = prefs.edit().putString("adas_sensitivity", v.name).apply()

    /** Volume dos bipes, 0 a 100. */
    var volume: Int
        get() = prefs.getInt("adas_volume", 60).coerceIn(0, 100)
        set(v) = prefs.edit().putInt("adas_volume", v.coerceIn(0, 100)).apply()

    /** 1 a cada N quadros — reservado para o LDW. O FCW usa toda detecção que o RoadDetector já faz. */
    var frameSkip: Int
        get() = prefs.getInt("adas_frame_skip", 3).coerceIn(1, 10)
        set(v) = prefs.edit().putInt("adas_frame_skip", v.coerceIn(1, 10)).apply()

    /** Distância focal em px no tamanho do vídeo (640×360); 0 = padrão 0,8 × largura. */
    var calibrationFPx: Float
        get() = prefs.getFloat("adas_calibration_f_px", 0f)
        set(v) = prefs.edit().putFloat("adas_calibration_f_px", v).apply()

    /** Deslocamento do centro do carro na imagem (px) — para o LDW. */
    var calibrationXOffset: Float
        get() = prefs.getFloat("adas_calibration_x_offset", 0f)
        set(v) = prefs.edit().putFloat("adas_calibration_x_offset", v).apply()

    /** FCW deve rodar agora. */
    val fcwOn: Boolean get() = enabled && fcwEnabled

    /** LDW deve rodar agora. Começa DESLIGADO para quem já tinha o ADAS (era adiado): liga nos Ajustes. */
    val ldwOn: Boolean get() = enabled && ldwEnabled

    /** Algum aviso ligado (o laço de detecção precisa rodar). */
    val anyOn: Boolean get() = fcwOn || ldwOn
}

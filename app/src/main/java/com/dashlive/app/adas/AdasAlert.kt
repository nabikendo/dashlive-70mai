package com.dashlive.app.adas

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread

/**
 * Bipes do ADAS com ToneGenerator (sem arquivos de som), numa HandlerThread própria — nunca na
 * thread do detector. Celular/tablet no silencioso (RINGER_MODE_SILENT) = não toca.
 * O ToneGenerator não tem setVolume: o volume vai no construtor, então ele é refeito quando muda.
 */
class AdasAlert(context: Context) {
    private val thread = HandlerThread("adas-alert").apply { start() }
    private val h = Handler(thread.looper)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var tone: ToneGenerator? = null
    private var toneVolume = -1

    fun play(level: FcwLevel, volume: Int) {
        if (level == FcwLevel.NONE) return
        h.post {
            if (volume <= 0 || audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return@post
            val g = generator(volume) ?: return@post
            when (level) {
                FcwLevel.INFO -> g.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                FcwLevel.ATTENTION -> {
                    g.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
                    h.postDelayed({ tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150) }, 250) // 150 + 100 de pausa
                }
                FcwLevel.CRITICAL -> g.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                FcwLevel.NONE -> {}
            }
        }
    }

    /** Saída de faixa: aviso = 1 bipe de 150 ms; crítico = 2 bipes duplos de 200 ms com 100 ms de pausa. */
    fun playLdw(level: LdwLevel, volume: Int) {
        if (level == LdwLevel.NONE) return
        h.post {
            if (volume <= 0 || audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return@post
            val g = generator(volume) ?: return@post
            if (level == LdwLevel.WARN) g.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            else {
                g.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
                h.postDelayed({ tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200) }, 300)
            }
        }
    }

    private fun generator(volume: Int): ToneGenerator? {
        if (volume != toneVolume || tone == null) {
            tone?.release()
            // STREAM_MUSIC: toca no modo vibrar (o de notificação ficava mudo) e sai pelo som do carro
            // quando o aparelho está ligado nele. Modo silencioso continua sem som (regra acima).
            tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }.getOrNull()
            toneVolume = volume
        }
        return tone
    }

    fun release() {
        h.removeCallbacksAndMessages(null)
        h.post { tone?.release(); tone = null }
        thread.quitSafely()
    }
}

package com.dashlive.app.adas

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.dashlive.app.Kind
import com.dashlive.app.Seen

/** O que a tela mostra do ADAS: aviso de colisão e de saída de faixa. */
class AdasState(val fcw: FcwResult, val ldw: LdwResult) {
    companion object { val NONE = AdasState(FcwResult.NONE, LdwResult.NONE) }
}

/**
 * ADAS: FCW (colisão) e LDW (saída de faixa). Não roda modelo de IA próprio:
 * - FCW recebe a lista que o RoadDetector já devolveu, na MESMA thread do detector (`detectExec`).
 * - LDW usa o MESMO quadro (Bitmap 480×270 do TextureView) com a detecção de faixa sem modelo
 *   ([LaneFinder]): poucos ms, funciona em qualquer aparelho (o TwinLiteNet não deu 5 FPS no tablet).
 * Som na HandlerThread do [AdasAlert]; o resultado vai para a tela pelo [listener] (thread principal).
 * Criado no onCreate da MainActivity e fechado no onDestroy.
 */
class AdasEngine(context: Context, val config: AdasConfig) {
    private val fcw = FcwLogic()
    private val finder = LaneFinder()
    private val ldw = LdwLogic()
    private val alert = AdasAlert(context)
    private val main = Handler(Looper.getMainLooper())
    private var fcwActive = false
    private var ldwActive = false
    @Volatile private var lastFcw = FcwResult.NONE
    @Volatile private var lastLdw = LdwResult.NONE
    private var px = IntArray(0)

    /** Chamado na thread principal a cada quadro analisado (Estrada aberta: desenho; fora: futuro banner). */
    var listener: ((AdasState) -> Unit)? = null

    /**
     * FCW. Chamar SEMPRE na thread do detector. [videoW]/[videoH] = tamanho do vídeo (as caixas vêm
     * em frações); [egoKmh] = velocidade do seu carro (OBD ou GPS; null = desconhecida → não alerta).
     */
    fun onDetections(seen: List<Seen>, videoW: Int, videoH: Int, egoKmh: Float?, nowMs: Long) {
        if (!config.fcwOn) {
            if (fcwActive) { fcwActive = false; fcw.reset(); lastFcw = FcwResult.NONE; post() }
            return
        }
        fcwActive = true
        fcw.sensitivity = config.sensitivity
        fcw.focalPx = config.calibrationFPx
        // carro vindo de frente (contramão) não é alvo de colisão frontal: ignora
        val boxes = seen.filter { !it.oncoming }.mapNotNull { s ->
            val cls = when (s.kind) {
                Kind.CAR -> FcwClass.CAR
                Kind.TRUCK -> FcwClass.TRUCK
                Kind.BUS -> FcwClass.BUS
                Kind.MOTORCYCLE -> FcwClass.MOTORCYCLE
                Kind.PERSON -> FcwClass.PERSON
                Kind.BICYCLE -> null // fora da lista do briefing (COCO 0, 2, 3, 5, 7)
            }
            cls?.let { FcwBox(it, s.l, s.t, s.r, s.b) }
        }
        val r = fcw.update(boxes, videoW, videoH, egoKmh, nowMs)
        r.sound?.let { alert.play(it, config.volume) }
        lastFcw = r
        post()
    }

    /**
     * LDW, no mesmo quadro do detector (mesma thread). Copia só a parte de baixo da imagem.
     * [turnLeft]/[turnRight] = seta do carro pelo OBD (suprime o aviso para aquele lado).
     */
    fun onFrame(bmp: Bitmap, egoKmh: Float?, turnLeft: Boolean, turnRight: Boolean, nowMs: Long) {
        if (!config.ldwOn) {
            if (ldwActive) { ldwActive = false; ldw.reset(); finder.reset(); lastLdw = LdwResult.NONE; post() }
            return
        }
        ldwActive = true
        val w = bmp.width; val h = bmp.height
        val y0 = (LaneFinder.TOP * h).toInt().coerceIn(0, h - 1)
        val n = w * (h - y0)
        if (px.size != n) px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, y0, w, h - y0)
        // calibração em px do vídeo (640) → px da imagem analisada
        val xOff = config.calibrationXOffset * w / 640f
        val lanes = finder.find(px, w, h, y0, w / 2f + xOff)
        applyLdw(lanes, xOff, egoKmh, turnLeft, turnRight, nowMs)
    }

    /** Demo (build de teste): faixas de mentira, sem câmera. */
    fun onDemoLanes(lanes: LaneSides, egoKmh: Float?, nowMs: Long) {
        if (!config.ldwOn) return
        ldwActive = true
        applyLdw(lanes, 0f, egoKmh, false, false, nowMs)
    }

    private fun applyLdw(lanes: LaneSides, xOff: Float, egoKmh: Float?, tl: Boolean, tr: Boolean, nowMs: Long) {
        ldw.sensitivity = config.sensitivity
        ldw.xOffsetPx = xOff
        val r = ldw.update(lanes, egoKmh, tl, tr, nowMs)
        r.sound?.let { alert.playLdw(it, config.volume) }
        lastLdw = r
        post()
    }

    /** Também na thread do detector (ex.: trocou de lente, desligou a detecção). */
    fun reset() {
        fcw.reset(); ldw.reset(); finder.reset()
        val was = fcwActive || ldwActive
        lastFcw = FcwResult.NONE; lastLdw = LdwResult.NONE
        if (was) post()
    }

    private fun post() {
        val s = AdasState(lastFcw, lastLdw)
        main.post { listener?.invoke(s) }
    }

    fun close() {
        listener = null
        alert.release()
    }
}

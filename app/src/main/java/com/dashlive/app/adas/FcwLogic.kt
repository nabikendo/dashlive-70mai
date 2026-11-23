package com.dashlive.app.adas

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Classes do COCO que o FCW considera, com a altura real usada na distância monocular. */
enum class FcwClass(val heightM: Float) {
    CAR(1.5f), TRUCK(3.0f), BUS(3.0f), MOTORCYCLE(1.2f), PERSON(1.7f),
}

enum class FcwLevel { NONE, INFO, ATTENTION, CRITICAL }

/**
 * Multiplica o TTC MEDIDO antes de comparar com os limites: Alta (×0,7) faz o TTC parecer menor e
 * alerta mais cedo; Baixa (×1,3) alerta mais tarde. As distâncias fixas não mudam.
 */
enum class Sensitivity(val ttcFactor: Float) { LOW(1.3f), MEDIUM(1.0f), HIGH(0.7f) }

/** Caixa do detector em frações do quadro (0 a 1). */
class FcwBox(val cls: FcwClass, val l: Float, val t: Float, val r: Float, val b: Float)

/** Objeto em alerta: caixa (0 a 1), distância estimada e TTC (null = não está se aproximando). */
class FcwTarget(val box: FcwBox, val distanceM: Float, val ttcS: Float?)

/** [sound] = nível que deve tocar agora (só ao SUBIR de nível e fora do cooldown). */
class FcwResult(val level: FcwLevel, val target: FcwTarget?, val sound: FcwLevel?) {
    companion object { val NONE = FcwResult(FcwLevel.NONE, null, null) }
}

/**
 * Aviso de colisão frontal (FCW) com câmera única. Kotlin puro (testado em FcwLogicTest).
 *
 * - Lente da A200: 130° na horizontal. f padrão pelo modelo olho de peixe equidistante (o centro da
 *   imagem, onde fica o carro da frente, segue bem esse modelo): f = (w/2) / (65° em radianos) ≈ 282 px
 *   no vídeo de 640 (o 0,8·w = 512 px do briefing valia para lente comum e dava distâncias ~1,8× maiores).
 * - Zona do seu carro: deslocamento lateral do objeto |D·tan(ângulo)| < 1,8 m (meia faixa + folga),
 *   com ângulo = (centro_x − w/2)/f; caixa ≥ 16 px de altura (carro até ~26 m, caminhão até ~53 m).
 * - Distância: D = H_real · f_px / h_px; f_px = [focalPx] ou o padrão acima.
 * - Velocidade relativa: casa caixas entre quadros por IoU > 0,3; v_rel = (D_novo − D_antigo)/Δt;
 *   objeto sumido > 0,5 s é descartado. TTC = D / (−v_rel) quando v_rel < 0.
 * - Níveis: Informativo TTC < 2,5 s ou D < 30 m; Atenção TTC < 1,5 s ou D < 15 m;
 *   Crítico TTC < 0,8 s ou D < 8 m. Só com o seu carro acima de 20 km/h (velocidade desconhecida = não alerta).
 * - Histerese: sobe com 2 quadros seguidos, desce com 10 quadros sem a condição; cooldown de 3 s por nível no som.
 */
class FcwLogic {
    var sensitivity = Sensitivity.MEDIUM
    /** Distância focal em px no tamanho do vídeo; 0 = padrão (0,8 × largura). */
    var focalPx = 0f

    private class Track(var box: FcwBox, var d: Float, var at: Long, var vRel: Float?)

    private val tracks = ArrayList<Track>()
    private var level = FcwLevel.NONE
    private var upFrames = 0
    private var upLevel = FcwLevel.NONE
    private var downFrames = 0
    private var lastTarget: FcwTarget? = null
    private val lastSound = LongArray(FcwLevel.values().size) { Long.MIN_VALUE / 2 }

    fun reset() {
        tracks.clear()
        level = FcwLevel.NONE
        upFrames = 0; downFrames = 0
        lastTarget = null
    }

    /**
     * Um quadro do detector. [frameW]/[frameH] = tamanho do vídeo (px); [egoKmh] = velocidade do
     * seu carro (null = desconhecida); [nowMs] = relógio monotônico.
     */
    fun update(boxes: List<FcwBox>, frameW: Int, frameH: Int, egoKmh: Float?, nowMs: Long): FcwResult {
        val f = if (focalPx > 0f) focalPx else defaultFocal(frameW)

        // 1) objetos na zona do seu carro, com distância e velocidade relativa
        tracks.removeAll { nowMs - it.at > LOST_MS }
        val used = HashSet<Track>()
        var best: FcwTarget? = null
        var bestLevel = FcwLevel.NONE
        for (bx in boxes) {
            val hPx = (bx.b - bx.t) * frameH
            if (hPx < MIN_H_PX) continue
            val d = distance(bx.cls, hPx, f)
            if (abs(lateral(bx, frameW, f, d)) >= EGO_HALF_M) continue
            var match: Track? = null
            var bestIou = IOU_MIN
            for (tr in tracks) {
                if (tr in used) continue
                val o = iou(tr.box, bx)
                if (o > bestIou) { bestIou = o; match = tr }
            }
            val tr = if (match != null) {
                val dt = (nowMs - match.at) / 1000f
                if (dt > 0f) match.vRel = (d - match.d) / dt
                match.box = bx; match.d = d; match.at = nowMs
                match
            } else Track(bx, d, nowMs, null).also { tracks += it }
            used += tr
            val ttc = tr.vRel?.let { ttc(d, it) }
            val lv = rawLevel(d, ttc, sensitivity)
            val tgt = FcwTarget(bx, d, ttc)
            if (best == null || lv > bestLevel || (lv == bestLevel && d < best.distanceM)) {
                best = tgt; bestLevel = lv
            }
        }

        // 2) abaixo de 20 km/h (ou sem velocidade) não alerta
        if (egoKmh == null || egoKmh < MIN_EGO_KMH) {
            level = FcwLevel.NONE; upFrames = 0; downFrames = 0; lastTarget = null
            return FcwResult(FcwLevel.NONE, best, null)
        }

        // 3) histerese
        var sound: FcwLevel? = null
        when {
            bestLevel > level -> {
                downFrames = 0
                upLevel = if (upFrames == 0) bestLevel else minOf(upLevel, bestLevel)
                upFrames++
                if (upFrames >= UP_FRAMES) {
                    level = upLevel
                    upFrames = 0
                    if (nowMs - lastSound[level.ordinal] >= COOLDOWN_MS) {
                        sound = level
                        lastSound[level.ordinal] = nowMs
                    }
                }
            }
            bestLevel < level -> {
                upFrames = 0
                downFrames++
                if (downFrames >= DOWN_FRAMES) { level = bestLevel; downFrames = 0 }
            }
            else -> { upFrames = 0; downFrames = 0 }
        }
        if (best != null && bestLevel >= level) lastTarget = best
        if (level == FcwLevel.NONE) lastTarget = null
        return FcwResult(level, if (level == FcwLevel.NONE) null else lastTarget ?: best, sound)
    }

    companion object {
        /** Campo de visão horizontal da lente da 70mai A200. */
        const val FOV_DEG = 130f
        /** Meia largura da zona do seu carro (m): meia faixa de 3,5 m + folga. */
        const val EGO_HALF_M = 1.8f
        const val MIN_H_PX = 16f
        const val IOU_MIN = 0.3f
        const val LOST_MS = 500L
        const val MIN_EGO_KMH = 20f
        const val UP_FRAMES = 2
        const val DOWN_FRAMES = 10
        const val COOLDOWN_MS = 3000L

        /** f (px) da lente olho de peixe equidistante: (w/2) / (meio campo em radianos). 640 px → ~282. */
        fun defaultFocal(frameW: Int) = (frameW / 2f) / Math.toRadians(FOV_DEG / 2.0).toFloat()

        fun distance(cls: FcwClass, hPx: Float, focalPx: Float) = cls.heightM * focalPx / hPx

        /** Deslocamento lateral (m, direita +) do centro da caixa a [d] m: ângulo = px do centro / f. */
        fun lateral(bx: FcwBox, frameW: Int, focalPx: Float, d: Float): Float {
            val ang = ((bx.l + bx.r) / 2f - 0.5f) * frameW / focalPx
            return d * kotlin.math.tan(ang.coerceIn(-1.4f, 1.4f))
        }

        /** null quando não está se aproximando (v_rel ≥ 0). */
        fun ttc(d: Float, vRel: Float): Float? = if (vRel < 0f) d / -vRel else null

        fun rawLevel(d: Float, ttc: Float?, s: Sensitivity): FcwLevel {
            val t = ttc?.times(s.ttcFactor) ?: Float.POSITIVE_INFINITY
            return when {
                t < 0.8f || d < 8f -> FcwLevel.CRITICAL
                t < 1.5f || d < 15f -> FcwLevel.ATTENTION
                t < 2.5f || d < 30f -> FcwLevel.INFO
                else -> FcwLevel.NONE
            }
        }

        fun iou(a: FcwBox, b: FcwBox): Float {
            val iw = min(a.r, b.r) - max(a.l, b.l)
            val ih = min(a.b, b.b) - max(a.t, b.t)
            if (iw <= 0f || ih <= 0f) return 0f
            val inter = iw * ih
            val u = (a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter
            return if (u > 0f) inter / u else 0f
        }
    }
}

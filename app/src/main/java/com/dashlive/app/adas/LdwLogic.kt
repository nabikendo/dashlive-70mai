package com.dashlive.app.adas

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sign

enum class LdwLevel { NONE, WARN, CRITICAL }

/**
 * [side]: −1 saindo pela esquerda, +1 pela direita, 0 nenhum. [offset]: −1 = em cima da faixa da
 * esquerda, 0 = no meio, +1 = em cima da da direita. [headingDeg]: rumo do carro em relação à faixa
 * (positivo = indo para a direita). [lanes] = faixas achadas (para desenhar).
 */
class LdwResult(
    val level: LdwLevel, val side: Int, val offset: Float?, val headingDeg: Float?,
    val lanes: LaneSides?, val sound: LdwLevel?,
) {
    companion object { val NONE = LdwResult(LdwLevel.NONE, 0, null, null, null, null) }
}

/**
 * Aviso de saída de faixa (LDW). Kotlin puro (testado em LdwLogicTest).
 * - Offset em y = 0,85·h: (x_ego − centro da faixa) / (meia largura da faixa); x_ego = w/2 + calibração.
 * - Rumo: pelo ponto de fuga (onde as duas faixas se cruzam): o carro virado para a direita leva o ponto
 *   de fuga para a esquerda; rumo = atan((x_ego − x_fuga) / f), f da lente de 130° (olho de peixe).
 *   (O "atan2 entre y=0,5h e y=0,85h" do briefing mistura a perspectiva com o rumo: um carro
 *   paralelo mas fora do centro já dava "rumo" contrário. O ponto de fuga separa as duas coisas.)
 * - Aviso: |offset| > limiar, rumo para o mesmo lado com mais de 3°, acima de 30 km/h.
 *   Limiares aviso/crítico: Baixa 0,65/0,85; Média 0,50/0,75; Alta 0,35/0,55.
 * - Faltou um dos lados: não alerta. Seta (OBD) ligada para o lado da saída: não alerta (até 2 s depois).
 * - Histerese: sobe com 3 quadros, desce com 5; depois de tocar, 2 s sem tocar de novo.
 */
class LdwLogic {
    var sensitivity = Sensitivity.MEDIUM
    var xOffsetPx = 0f
    /** f (px) na largura da imagem analisada; 0 = lente de 130° ([FcwLogic.defaultFocal]). */
    var focalPx = 0f

    private var level = LdwLevel.NONE
    private var levelSide = 0
    private var up = 0
    private var upLevel = LdwLevel.NONE
    private var down = 0
    private var lastSoundAt = Long.MIN_VALUE / 2
    private var leftSignalAt = Long.MIN_VALUE / 2
    private var rightSignalAt = Long.MIN_VALUE / 2

    fun reset() { level = LdwLevel.NONE; levelSide = 0; up = 0; down = 0 }

    fun update(lanes: LaneSides?, egoKmh: Float?, turnLeft: Boolean, turnRight: Boolean, nowMs: Long): LdwResult {
        if (turnLeft) leftSignalAt = nowMs
        if (turnRight) rightSignalAt = nowMs
        var cand = LdwLevel.NONE
        var side = 0
        var offset: Float? = null
        var heading: Float? = null
        val l = lanes?.left; val r = lanes?.right
        if (lanes != null && l != null && r != null) {
            val w = lanes.w; val h = lanes.h
            val egoX = w / 2f + xOffsetPx
            val yb = 0.85f * h
            val xl = l.x(yb); val xr = r.x(yb)
            val half = (xr - xl) / 2f
            if (half > 0f) {
                val o = (egoX - (xl + xr) / 2f) / half
                offset = o
                val f = if (focalPx > 0f) focalPx else FcwLogic.defaultFocal(w)
                vanishingX(l, r, h)?.let { xv -> heading = Math.toDegrees(atan(((egoX - xv) / f).toDouble())).toFloat() }
                val hd = heading
                val dir = sign(o).toInt()
                val (warn, crit) = thresholds(sensitivity)
                val moving = hd != null && sign(hd).toInt() == dir && abs(hd) > MIN_HEADING_DEG
                val fast = egoKmh != null && egoKmh > MIN_KMH
                val signal = (dir < 0 && nowMs - leftSignalAt < SIGNAL_HOLD_MS) || (dir > 0 && nowMs - rightSignalAt < SIGNAL_HOLD_MS)
                if (moving && fast && !signal) {
                    cand = when {
                        abs(o) > crit -> LdwLevel.CRITICAL
                        abs(o) > warn -> LdwLevel.WARN
                        else -> LdwLevel.NONE
                    }
                    if (cand != LdwLevel.NONE) side = dir
                }
            }
        }
        // histerese
        var sound: LdwLevel? = null
        when {
            cand > level -> {
                down = 0
                upLevel = if (up == 0) cand else minOf(upLevel, cand)
                up++
                if (up >= UP_FRAMES) {
                    level = upLevel; levelSide = side; up = 0
                    if (nowMs - lastSoundAt >= COOLDOWN_MS) { sound = level; lastSoundAt = nowMs }
                }
            }
            cand < level -> {
                up = 0
                down++
                if (down >= DOWN_FRAMES) { level = cand; if (cand == LdwLevel.NONE) levelSide = 0; down = 0 }
            }
            else -> { up = 0; down = 0 }
        }
        return LdwResult(level, if (level == LdwLevel.NONE) 0 else levelSide, offset, heading, lanes, sound)
    }

    companion object {
        const val MIN_KMH = 30f
        const val MIN_HEADING_DEG = 3f
        const val UP_FRAMES = 3
        const val DOWN_FRAMES = 5
        const val COOLDOWN_MS = 2000L
        const val SIGNAL_HOLD_MS = 2000L

        fun thresholds(s: Sensitivity): Pair<Float, Float> = when (s) {
            Sensitivity.LOW -> 0.65f to 0.85f
            Sensitivity.MEDIUM -> 0.50f to 0.75f
            Sensitivity.HIGH -> 0.35f to 0.55f
        }

        /**
         * x onde as duas faixas se cruzam (ponto de fuga), pelas retas tangentes entre 0,6·h e 0,9·h.
         * null se forem quase paralelas na imagem.
         */
        fun vanishingX(l: LanePoly, r: LanePoly, h: Int): Float? {
            val y1 = 0.6f * h; val y2 = 0.9f * h
            val ml = (l.x(y2) - l.x(y1)) / (y2 - y1); val mr = (r.x(y2) - r.x(y1)) / (y2 - y1)
            if (abs(ml - mr) < 1e-3f) return null
            // x = l.x(y1) + ml·(y − y1) = r.x(y1) + mr·(y − y1)
            val yv = y1 + (r.x(y1) - l.x(y1)) / (ml - mr)
            return l.x(y1) + ml * (yv - y1)
        }
    }
}

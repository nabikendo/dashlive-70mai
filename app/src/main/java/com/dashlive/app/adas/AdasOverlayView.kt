package com.dashlive.app.adas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.dashlive.app.RoadView
import kotlin.math.sin

/**
 * Desenho do ADAS por cima da Estrada (RoadView/Road3DView). Fica ESCONDIDO fora da Estrada — lá o
 * aviso é só sonoro. Não substitui nenhuma superfície de vídeo: é uma View transparente a mais.
 *  - FCW: faixa no alto (nível, tipo, distância, TTC); "sobre o vídeo" também o retângulo no objeto;
 *    crítico = borda vermelha pulsando.
 *  - LDW: "sobre o vídeo" desenha as faixas achadas (verde; âmbar/vermelho do lado da saída); em
 *    qualquer estilo, brilho pulsando na borda do lado da saída + faixa "Saindo da faixa".
 */
class AdasOverlayView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private var state = AdasState.NONE
    private var videoW = 640
    private var videoH = 360
    /** true = estilo "sobre o vídeo" (dá para desenhar caixas e faixas); false = cena 3D. */
    var overVideo = false
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val path = Path()

    init { setWillNotDraw(false) }

    fun setVideoSize(w: Int, h: Int) { if (w > 0 && h > 0) { videoW = w; videoH = h } }

    fun show(s: AdasState) {
        val old = state
        state = s
        // faixas mudam a cada quadro: redesenha quando há algo para mostrar ou quando algo sumiu
        if (s.fcw.level != FcwLevel.NONE || s.ldw.level != LdwLevel.NONE || (overVideo && s.ldw.lanes != null) ||
            old.fcw.level != FcwLevel.NONE || old.ldw.level != LdwLevel.NONE || old.ldw.lanes != null
        ) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pulse = 0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 90.0).toFloat()
        val sc = RoadView.videoScale(videoW, videoH, w, h, true)
        val vw = videoW * sc; val vh = videoH * sc
        val ox = (w - vw) / 2f; val oy = (h - vh) / 2f
        var animate = false

        // ---------------- LDW
        val l = state.ldw
        val lanes = l.lanes
        if (overVideo && lanes != null) {
            for ((i, poly) in listOf(lanes.left, lanes.right).withIndex()) {
                poly ?: continue
                val sideOut = l.level != LdwLevel.NONE && l.side == (if (i == 0) -1 else 1)
                stroke.color = when {
                    !sideOut -> Color.argb(170, 0x5C, 0xE0, 0x8A)
                    l.level == LdwLevel.CRITICAL -> Color.rgb(0xFF, 0x2D, 0x2D)
                    else -> Color.rgb(0xFF, 0xB0, 0x00)
                }
                stroke.strokeWidth = (if (sideOut) 7f else 4f) * dp
                path.reset()
                var y = poly.yMin
                var first = true
                while (y <= poly.yMax + 0.1f) {
                    val sx = ox + poly.x(y) / lanes.w * vw
                    val sy = oy + y / lanes.h * vh
                    if (first) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                    first = false
                    y += 4f
                }
                canvas.drawPath(path, stroke)
            }
        }
        if (l.level != LdwLevel.NONE && l.side != 0) {
            val col = if (l.level == LdwLevel.CRITICAL) Color.rgb(0xFF, 0x2D, 0x2D) else Color.rgb(0xFF, 0xB0, 0x00)
            val a = (110 + 120 * pulse).toInt()
            val gw = 70f * dp
            val x0 = if (l.side < 0) 0f else w - gw
            fill.shader = LinearGradient(
                if (l.side < 0) 0f else w, 0f, if (l.side < 0) gw else w - gw, 0f,
                Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(x0, 0f, x0 + gw, h, fill)
            fill.shader = null
            banner(canvas, if (l.side < 0) "◀  Saindo da faixa" else "Saindo da faixa  ▶", col,
                l.level == LdwLevel.CRITICAL, h * 0.14f + (if (state.fcw.level != FcwLevel.NONE) 64f * dp else 0f), pulse, w)
            animate = true
        }

        // ---------------- FCW
        val r = state.fcw
        val tgt = r.target
        if (r.level != FcwLevel.NONE) {
            val color = colorOf(r.level)
            if (overVideo && tgt != null) {
                rect.set(ox + tgt.box.l * vw, oy + tgt.box.t * vh, ox + tgt.box.r * vw, oy + tgt.box.b * vh)
                stroke.color = color
                stroke.strokeWidth = 3f * dp
                canvas.drawRoundRect(rect, 6f * dp, 6f * dp, stroke)
                text.textSize = 15f * dp
                fill.color = Color.argb(200, 0, 0, 0)
                val label = "%.0f m".format(tgt.distanceM)
                val tw = text.measureText(label) + 12f * dp
                val ty = (rect.top - 6f * dp).coerceAtLeast(20f * dp)
                canvas.drawRoundRect(rect.centerX() - tw / 2, ty - 17f * dp, rect.centerX() + tw / 2, ty + 4f * dp, 5f * dp, 5f * dp, fill)
                canvas.drawText(label, rect.centerX(), ty - 1f * dp, text)
            }
            val msg = buildString {
                append(when (r.level) { FcwLevel.CRITICAL -> "FREIE!"; FcwLevel.ATTENTION -> "Atenção"; else -> "Veículo à frente" })
                if (tgt != null) {
                    append("  •  ").append(nameOf(tgt.box.cls)).append(" a %.0f m".format(tgt.distanceM))
                    tgt.ttcS?.let { if (it < 10f) append("  •  %.1f s".format(it)) }
                }
            }
            banner(canvas, msg, color, r.level == FcwLevel.CRITICAL, h * 0.14f, pulse, w, darkText = r.level == FcwLevel.INFO)
            if (r.level == FcwLevel.CRITICAL) {
                stroke.color = color
                stroke.alpha = (90 + 140 * pulse).toInt()
                stroke.strokeWidth = 10f * dp
                canvas.drawRect(5f * dp, 5f * dp, w - 5f * dp, h - 5f * dp, stroke)
                stroke.alpha = 255
                animate = true
            }
        }
        if (animate) postInvalidateOnAnimation()
    }

    private fun banner(canvas: Canvas, msg: String, color: Int, big: Boolean, top: Float, pulse: Float, w: Float, darkText: Boolean = false) {
        text.textSize = (if (big) 22f else 17f) * dp
        val bw = text.measureText(msg) + 32f * dp
        val bh = text.textSize + 18f * dp
        fill.color = color
        fill.alpha = if (big) (170 + 85 * pulse).toInt() else 225
        rect.set(w / 2 - bw / 2, top, w / 2 + bw / 2, top + bh)
        canvas.drawRoundRect(rect, bh / 2, bh / 2, fill)
        text.color = if (darkText) Color.BLACK else Color.WHITE
        canvas.drawText(msg, w / 2, rect.centerY() + text.textSize * 0.35f, text)
        text.color = Color.WHITE
    }

    private fun colorOf(l: FcwLevel) = when (l) {
        FcwLevel.CRITICAL -> Color.rgb(0xFF, 0x2D, 0x2D)
        FcwLevel.ATTENTION -> Color.rgb(0xFF, 0x7A, 0x00)
        else -> Color.rgb(0xFF, 0xC4, 0x00)
    }

    private fun nameOf(c: FcwClass) = when (c) {
        FcwClass.CAR -> "carro"
        FcwClass.TRUCK -> "caminhão"
        FcwClass.BUS -> "ônibus"
        FcwClass.MOTORCYCLE -> "moto"
        FcwClass.PERSON -> "pessoa"
    }
}

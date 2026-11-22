package com.dashlive.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Vista da estrada estilo Tesla: seu carro embaixo e, à frente, os veículos e pessoas
 * que o detector achou no vídeo. As posições são ESTIMATIVAS: a distância vem da altura da
 * base da caixa na imagem (chão plano) e a faixa desenhada é fixa, não lida do vídeo.
 */
class RoadView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    private class Track(
        val kind: Kind, var tx: Float, var tz: Float, var x: Float, var z: Float, var missed: Int = 0,
    )

    private val tracks = ArrayList<Track>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var phase = 0f
    private var lastFrame = 0L
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EAED.toInt() }
    private var msgLayout: StaticLayout? = null
    private var msgText: String? = null
    private var msgWidth = 0
    private var msgSize = 0f

    var message: String? = null
        set(v) { field = v; invalidate() }
    var fps = 0
        set(v) { field = v }

    /** Recebe o que o detector viu no quadro mais recente. */
    fun update(seen: List<Seen>) {
        val horizon = HORIZON
        val used = HashSet<Track>()
        for (s in seen) {
            val yb = ((s.b - horizon) / (1f - horizon)).coerceIn(0.03f, 1f)
            val z = (3.5f / yb).coerceAtMost(80f)
            val x = ((s.l + s.r) / 2f - 0.5f) * 2f * z
            var best: Track? = null
            var bd = 3f
            for (t in tracks) {
                if (t.kind != s.kind || t in used) continue
                val d = hypot(t.tx - x, (t.tz - z) * 0.5f)
                if (d < bd) { bd = d; best = t }
            }
            if (best == null) {
                best = Track(s.kind, x, z, x, z).also { tracks += it }
            }
            best.tx = x; best.tz = z; best.missed = 0
            used += best
        }
        val iter = tracks.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (t !in used) { t.missed++; if (t.missed > 4) iter.remove() }
        }
        postInvalidateOnAnimation()
    }

    fun clear() { tracks.clear(); invalidate() }

    override fun onDraw(cv: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        phase = (phase + dt * 0.9f) % 1f

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val hy = h * 0.16f          // linha do horizonte
        val ey = h * 0.86f          // onde fica o seu carro
        val pxPerM = w * 0.055f     // metros -> pixels na base

        // fundo
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xFF14171C.toInt(), 0xFF0B0D10.toInt(), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // pista (3 faixas, 3,5 m cada)
        val laneM = 3.5f
        fun sx(m: Float, s: Float) = cx + m * pxPerM * s
        fun sy(s: Float) = hy + (ey - hy) * s
        fun scale(z: Float) = 1f / (1f + z / 8f)
        val zFar = 90f
        val sFar = scale(zFar)
        path.reset()
        path.moveTo(sx(-1.5f * laneM, 1f), sy(1.12f)); path.lineTo(sx(1.5f * laneM, 1f), sy(1.12f))
        path.lineTo(sx(1.5f * laneM, sFar), sy(sFar)); path.lineTo(sx(-1.5f * laneM, sFar), sy(sFar)); path.close()
        p.color = 0xFF1E2227.toInt()
        cv.drawPath(path, p)

        // bordas e divisórias tracejadas
        p.style = Paint.Style.STROKE
        val lines = floatArrayOf(-1.5f, -0.5f, 0.5f, 1.5f)
        for ((i, ln) in lines.withIndex()) {
            val edge = i == 0 || i == lines.lastIndex
            p.color = if (edge) 0xFFB8BEC6.toInt() else 0x99B8BEC6.toInt()
            var zz = -((phase) * 8f) // desliza devagar: só efeito visual
            while (zz < zFar) {
                val z0 = zz.coerceAtLeast(0f)
                val z1 = (zz + if (edge) 8f else 4f).coerceAtMost(zFar)
                if (z1 > z0) {
                    p.strokeWidth = 1f + 5f * scale(z0)
                    cv.drawLine(sx(ln * laneM, scale(z0)), sy(scale(z0)), sx(ln * laneM, scale(z1)), sy(scale(z1)), p)
                }
                zz += if (edge) 8f else 12f
            }
        }

        // objetos: do mais longe para o mais perto
        for (t in tracks.sortedByDescending { it.z }) {
            val k = (dt * 8f).coerceAtMost(1f)
            t.x += (t.tx - t.x) * k
            t.z += (t.tz - t.z) * k
            drawObject(cv, t, ::sx, ::sy, ::scale, pxPerM)
        }

        // seu carro
        drawEgo(cv, cx, ey, pxPerM)

        // textos: abaixo dos selos "AO VIVO" e do nome da câmera (16 dp de margem + 40 dp de altura)
        val dp = resources.displayMetrics.density
        p.style = Paint.Style.FILL
        p.color = 0xFF9AA0A6.toInt()
        p.textSize = h * 0.032f
        p.textAlign = Paint.Align.LEFT
        cv.drawText("Simulação • posições estimadas • $fps det/s", 20 * dp, 68 * dp + p.textSize, p)
        message?.let { drawMessage(cv, it, w, h) }
        postInvalidateOnAnimation() // a faixa desliza continuamente
    }

    /** Mensagem no meio da tela, quebrando linha para caber (erros podem ser longos). */
    private fun drawMessage(cv: Canvas, text: String, w: Float, h: Float) {
        val maxW = (w * 0.84f).toInt().coerceAtLeast(1)
        tp.textSize = h * 0.032f
        val layout = msgLayout?.takeIf { msgText == text && msgWidth == maxW && msgSize == tp.textSize }
            ?: StaticLayout.Builder.obtain(text, 0, text.length, tp, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(6)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
                .also { msgLayout = it; msgText = text; msgWidth = maxW; msgSize = tp.textSize }
        val pad = tp.textSize * 0.6f
        val top = h * 0.5f - layout.height / 2f
        val left = (w - maxW) / 2f
        p.style = Paint.Style.FILL
        p.color = 0xCC0B0D10.toInt()
        rect.set(left - pad, top - pad, left + maxW + pad, top + layout.height + pad)
        cv.drawRoundRect(rect, pad, pad, p)
        cv.save()
        cv.translate(left, top)
        layout.draw(cv)
        cv.restore()
    }

    private fun drawEgo(cv: Canvas, cx: Float, ey: Float, pxPerM: Float) {
        val wpx = 1.8f * pxPerM
        p.style = Paint.Style.FILL
        p.color = 0x33FFFFFF
        rect.set(cx - wpx * 0.75f, ey - wpx * 0.2f, cx + wpx * 0.75f, ey + wpx * 1.4f)
        cv.drawRoundRect(rect, wpx * 0.3f, wpx * 0.3f, p) // brilho
        p.color = 0xFFE8EAED.toInt()
        rect.set(cx - wpx / 2f, ey, cx + wpx / 2f, ey + wpx * 1.2f)
        cv.drawRoundRect(rect, wpx * 0.2f, wpx * 0.2f, p)
        p.color = 0xFF9AA0A6.toInt()
        rect.set(cx - wpx * 0.38f, ey + wpx * 0.12f, cx + wpx * 0.38f, ey + wpx * 0.5f)
        cv.drawRoundRect(rect, wpx * 0.1f, wpx * 0.1f, p) // vidro
    }

    private fun drawObject(
        cv: Canvas, t: Track,
        sx: (Float, Float) -> Float, sy: (Float) -> Float, scale: (Float) -> Float, pxPerM: Float,
    ) {
        val s = scale(t.z)
        val x = sx(t.x, s)
        val y = sy(s)
        val wpx = t.kind.widthM * pxPerM * s
        val hpx = t.kind.heightM * pxPerM * s
        val near = t.z < 8f
        val mid = t.z < 16f
        val edgeColor = when { near -> 0xFFF28B82.toInt(); mid -> 0xFFFDD663.toInt(); else -> 0xFFC4C7CC.toInt() }
        p.style = Paint.Style.FILL
        when (t.kind) {
            Kind.PERSON -> {
                p.color = edgeColor
                cv.drawCircle(x, y - hpx * 0.88f, wpx * 0.28f, p)
                rect.set(x - wpx * 0.3f, y - hpx * 0.72f, x + wpx * 0.3f, y)
                cv.drawRoundRect(rect, wpx * 0.3f, wpx * 0.3f, p)
            }
            else -> {
                // sombra
                p.color = 0x55000000
                rect.set(x - wpx / 2f, y - wpx * 0.12f, x + wpx / 2f, y + wpx * 0.12f)
                cv.drawOval(rect, p)
                // carroceria (visto por trás)
                p.color = 0xFFD7DAE0.toInt()
                rect.set(x - wpx / 2f, y - hpx * 0.62f, x + wpx / 2f, y)
                cv.drawRoundRect(rect, wpx * 0.12f, wpx * 0.12f, p)
                // cabine
                p.color = 0xFF8E949C.toInt()
                rect.set(x - wpx * 0.38f, y - hpx, x + wpx * 0.38f, y - hpx * 0.55f)
                cv.drawRoundRect(rect, wpx * 0.1f, wpx * 0.1f, p)
                // lanternas
                p.color = edgeColor
                val lw = wpx * 0.16f
                cv.drawRect(x - wpx * 0.46f, y - hpx * 0.4f, x - wpx * 0.46f + lw, y - hpx * 0.3f, p)
                cv.drawRect(x + wpx * 0.46f - lw, y - hpx * 0.4f, x + wpx * 0.46f, y - hpx * 0.3f, p)
            }
        }
        if (t.z < 40f) {
            p.color = edgeColor
            p.textAlign = Paint.Align.CENTER
            p.textSize = (height * 0.026f).coerceAtLeast(16f)
            cv.drawText("~${t.z.toInt()} m", x, y + p.textSize * 1.3f, p)
        }
    }

    companion object {
        /** Linha do horizonte na imagem da câmera (fração da altura). Estimativa para câmera de para-brisa. */
        const val HORIZON = 0.45f
    }
}

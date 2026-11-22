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
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Simulação da estrada com o que o detector achou na câmera frontal. Dois estilos:
 * - [Style.SCENE]: cena 3D clara em perspectiva, com o seu carro visto de trás e de cima,
 *   os veículos e pessoas como blocos 3D e um caminho azul à frente;
 * - [Style.VIDEO]: o vídeo ao vivo continua aparecendo e o caminho azul e os veículos destacados
 *   são desenhados por cima.
 *
 * As posições são ESTIMATIVAS: a distância vem da altura da base da caixa na imagem (chão plano)
 * e a pista desenhada é reta e fixa, não lida do vídeo.
 */
class RoadView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    enum class Style { SCENE, VIDEO }

    /** Um objeto acompanhado entre quadros: posição no chão (x, z em metros) e caixa na imagem (0..1). */
    private class Track(
        val kind: Kind,
        var tx: Float, var tz: Float, var x: Float, var z: Float,
        var tl: Float, var tt: Float, var tr: Float, var tb: Float,
        var l: Float, var t: Float, var r: Float, var b: Float,
        var missed: Int = 0,
    )

    private val tracks = ArrayList<Track>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val toggleRect = RectF()
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density
    private var msgLayout: StaticLayout? = null
    private var msgText: String? = null
    private var msgWidth = 0
    private var msgSize = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private var bgShader: Shader? = null
    private var bgShaderH = 0f
    private var videoW = 16
    private var videoH = 9

    var message: String? = null
        set(v) { field = v; invalidate() }
    var fps = 0
    var style = Style.SCENE
        set(v) { field = v; invalidate() }
    /** Chamado quando o usuário troca o estilo pelo botão desenhado na tela. */
    var onStyleChange: ((Style) -> Unit)? = null
    /** Velocidade do GPS em km/h; null = sem GPS. */
    var speedKmh: Float? = null

    fun setVideoSize(w: Int, h: Int) {
        if (w > 0 && h > 0) { videoW = w; videoH = h }
    }

    // ---------------------------------------------------------------- dados do detector

    /** Recebe o que o detector viu no quadro mais recente. */
    fun update(seen: List<Seen>) {
        val used = HashSet<Track>()
        for (s in seen) {
            val yb = ((s.b - HORIZON) / (1f - HORIZON)).coerceIn(0.03f, 1f)
            val z = (3.5f / yb).coerceAtMost(80f)
            val x = ((s.l + s.r) / 2f - 0.5f) * 2f * LATERAL * z
            var best: Track? = null
            var bd = 3f
            for (t in tracks) {
                if (t.kind != s.kind || t in used) continue
                val d = hypot(t.tx - x, (t.tz - z) * 0.5f)
                if (d < bd) { bd = d; best = t }
            }
            val tr = best ?: Track(s.kind, x, z, x, z, s.l, s.t, s.r, s.b, s.l, s.t, s.r, s.b).also { tracks += it }
            tr.tx = x; tr.tz = z
            tr.tl = s.l; tr.tt = s.t; tr.tr = s.r; tr.tb = s.b
            tr.missed = 0
            used += tr
        }
        val iter = tracks.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (t !in used) { t.missed++; if (t.missed > 4) iter.remove() }
        }
        postInvalidateOnAnimation()
    }

    fun clear() { tracks.clear(); invalidate() }

    // ---------------------------------------------------------------- toque (botão de estilo)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!toggleRect.contains(e.x, e.y)) return false
        if (e.action == MotionEvent.ACTION_UP) {
            style = if (style == Style.SCENE) Style.VIDEO else Style.SCENE
            onStyleChange?.invoke(style)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // ---------------------------------------------------------------- desenho

    override fun onDraw(cv: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        // As faixas andam na velocidade real (GPS); parado ou sem GPS, ficam paradas
        phase = (phase + dt * ((speedKmh ?: 0f) / 3.6f)) % DASH_PERIOD

        val k = (dt * 8f).coerceAtMost(1f)
        for (t in tracks) {
            t.x += (t.tx - t.x) * k; t.z += (t.tz - t.z) * k
            t.l += (t.tl - t.l) * k; t.t += (t.tt - t.t) * k
            t.r += (t.tr - t.r) * k; t.b += (t.tb - t.b) * k
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (style == Style.SCENE) drawScene(cv, w, h) else drawOverlay(cv, w, h)
        drawHud(cv, w, h)
        message?.let { drawMessage(cv, it, w, h) }
        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- cena 3D

    // Câmera virtual atrás e acima do seu carro (metros); x = lado, y = altura, z = frente
    private val sinP = sin(Math.toRadians(PITCH_DEG)).toFloat()
    private val cosP = cos(Math.toRadians(PITCH_DEG)).toFloat()
    private var f = 1f
    private var cyc = 0f
    private var cxc = 0f

    /** Ajusta a câmera para o horizonte e a traseira do seu carro ficarem em [HORIZON_Y] e [EGO_Y]. */
    private fun setupCam(w: Float, h: Float) {
        val k = (CAM_H * cosP - CAM_BACK * sinP) / (CAM_H * sinP + CAM_BACK * cosP)
        val tanP = sinP / cosP
        val yh = h * HORIZON_Y
        val ye = h * EGO_Y
        f = (ye - yh) / (tanP + k)
        cyc = yh + f * tanP
        cxc = w / 2f
    }

    private fun depth(y: Float, z: Float) = -(y - CAM_H) * sinP + (z + CAM_BACK) * cosP
    private fun sx(x: Float, y: Float, z: Float) = cxc + f * x / depth(y, z)
    private fun sy(y: Float, z: Float): Float {
        val u = (y - CAM_H) * cosP + (z + CAM_BACK) * sinP
        return cyc - f * u / depth(y, z)
    }

    /** Quadrilátero no chão (y = 0) entre x0..x1 e z0..z1. */
    private fun ground(cv: Canvas, x0: Float, x1: Float, z0: Float, z1: Float, color: Int) {
        path.reset()
        path.moveTo(sx(x0, 0f, z0), sy(0f, z0))
        path.lineTo(sx(x1, 0f, z0), sy(0f, z0))
        path.lineTo(sx(x1, 0f, z1), sy(0f, z1))
        path.lineTo(sx(x0, 0f, z1), sy(0f, z1))
        path.close()
        p.style = Paint.Style.FILL
        p.color = color
        cv.drawPath(path, p)
    }

    private fun quad(cv: Canvas, color: Int, vararg v: Float) {
        // v = 4 vértices (x, y, z)
        path.reset()
        for (i in 0 until 4) {
            val x = v[i * 3]; val y = v[i * 3 + 1]; val z = v[i * 3 + 2]
            if (i == 0) path.moveTo(sx(x, y, z), sy(y, z)) else path.lineTo(sx(x, y, z), sy(y, z))
        }
        path.close()
        p.style = Paint.Style.FILL
        p.color = color
        cv.drawPath(path, p)
    }

    /**
     * Bloco 3D: desenha só as faces que a câmera vê (lado, topo, traseira). Com [ix]/[iz] o topo
     * fica mais estreito e mais curto que a base (cabine de carro, em trapézio).
     */
    private fun box(
        cv: Canvas, x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float,
        top: Int, rear: Int, side: Int, ix: Float = 0f, iz: Float = 0f,
    ) {
        if (z0 + CAM_BACK < 1f) return
        val tx0 = x0 + ix; val tx1 = x1 - ix; val tz0 = z0 + iz; val tz1 = z1 - iz
        if (x0 > 0f) quad(cv, side, x0, y0, z0, x0, y0, z1, tx0, y1, tz1, tx0, y1, tz0)
        if (x1 < 0f) quad(cv, side, x1, y0, z0, x1, y0, z1, tx1, y1, tz1, tx1, y1, tz0)
        if (y1 < CAM_H) quad(cv, top, tx0, y1, tz0, tx1, y1, tz0, tx1, y1, tz1, tx0, y1, tz1)
        quad(cv, rear, x0, y0, z0, x1, y0, z0, tx1, y1, tz0, tx0, y1, tz0)
    }

    private fun drawScene(cv: Canvas, w: Float, h: Float) {
        setupCam(w, h)
        // céu e chão claros
        if (bgShader == null || bgShaderH != h) {
            bgShader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFFFDFDFE.toInt(), 0xFFEFF1F4.toInt(), 0xFFE4E7EB.toInt()),
                floatArrayOf(0f, HORIZON_Y, 1f), Shader.TileMode.CLAMP,
            )
            bgShaderH = h
        }
        p.style = Paint.Style.FILL
        p.shader = bgShader
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null

        val half = 1.5f * LANE
        ground(cv, -half - 0.8f, half + 0.8f, -6f, FAR, 0xFFDCE0E5.toInt())       // asfalto claro
        ground(cv, -half - 0.10f, -half + 0.10f, -6f, FAR, 0xFFFFFFFF.toInt())    // bordas contínuas
        ground(cv, half - 0.10f, half + 0.10f, -6f, FAR, 0xFFFFFFFF.toInt())
        for (ln in floatArrayOf(-0.5f * LANE, 0.5f * LANE)) {                      // divisórias tracejadas
            var z = -6f - phase
            while (z < FAR) {
                ground(cv, ln - 0.08f, ln + 0.08f, z, z + DASH_LEN, 0xFF8E959D.toInt())
                z += DASH_PERIOD
            }
        }

        // caminho azul até o veículo à frente na sua faixa
        val lead = tracks.filter { abs(it.x) < 1.5f && it.z > 1f }.minByOrNull { it.z }
        val end = lead?.let { (DASHCAM_Z + it.z - 1.5f).coerceAtLeast(EGO_LEN + 1f) } ?: 70f
        val z0 = EGO_LEN - 0.5f // começa por baixo do seu carro, que é desenhado por cima
        val farA = (200 * (1f - (end - EGO_LEN) / 90f)).toInt().coerceIn(30, 200)
        p.shader = LinearGradient(0f, sy(0f, z0), 0f, sy(0f, end), 0xD02B7FFF.toInt(), (farA shl 24) or 0x2B7FFF, Shader.TileMode.CLAMP)
        ground(cv, -1.15f, 1.15f, z0, end, 0xFF2B7FFF.toInt())
        p.shader = null

        // veículos e pessoas, do mais longe para o mais perto
        for (t in tracks.sortedByDescending { it.z }) drawObject(cv, t, h)

        // névoa no horizonte (o longe some aos poucos)
        val yh = h * HORIZON_Y
        p.shader = LinearGradient(0f, yh - h * 0.02f, 0f, yh + h * 0.10f, 0xFFEFF1F4.toInt(), 0x00EFF1F4, Shader.TileMode.CLAMP)
        cv.drawRect(0f, yh - h * 0.02f, w, yh + h * 0.10f, p)
        p.shader = null

        drawEgo(cv)
    }

    private fun drawObject(cv: Canvas, t: Track, h: Float) {
        val x = t.x
        val zw = DASHCAM_Z + t.z // traseira do objeto em relação à traseira do seu carro
        val top = 0xFF8C929A.toInt(); val rear = 0xFF6B7178.toInt(); val side = 0xFF575C63.toInt()
        val hw = when (t.kind) {
            Kind.TRUCK, Kind.BUS -> 1.27f
            Kind.CAR -> 0.92f
            Kind.MOTORCYCLE -> 0.4f
            Kind.BICYCLE -> 0.35f
            Kind.PERSON -> 0.3f
        }
        ground(cv, x - hw - 0.25f, x + hw + 0.25f, zw - 0.2f, zw + lengthOf(t.kind) + 0.2f, 0x22000000) // sombra
        when (t.kind) {
            Kind.CAR -> {
                box(cv, x - 0.92f, x + 0.92f, 0.25f, 0.95f, zw, zw + 4.5f, top, rear, side, 0.03f, 0.15f)
                box(cv, x - 0.82f, x + 0.82f, 0.95f, 1.45f, zw + 0.9f, zw + 3.6f, 0xFF5F6368.toInt(), 0xFF4A4F55.toInt(), 0xFF43474C.toInt(), 0.14f, 0.45f)
                tailLights(cv, x, 0.92f, 0.62f, 0.78f, zw)
            }
            Kind.TRUCK -> {
                box(cv, x - 1.25f, x + 1.25f, 0.5f, 3.4f, zw, zw + 7f, 0xFFB5BAC0.toInt(), 0xFF9AA0A6.toInt(), 0xFF80868B.toInt())
                box(cv, x - 1.2f, x + 1.2f, 0.4f, 2.9f, zw + 7f, zw + 9.2f, top, rear, side)
                tailLights(cv, x, 1.25f, 0.7f, 0.9f, zw)
            }
            Kind.BUS -> {
                box(cv, x - 1.27f, x + 1.27f, 0.35f, 3.1f, zw, zw + 11f, top, rear, side)
                quad(cv, 0xFF3C4043.toInt(), x - 1.1f, 1.8f, zw - 0.01f, x + 1.1f, 1.8f, zw - 0.01f, x + 1.1f, 2.7f, zw - 0.01f, x - 1.1f, 2.7f, zw - 0.01f)
                tailLights(cv, x, 1.27f, 0.6f, 0.85f, zw)
            }
            Kind.MOTORCYCLE, Kind.BICYCLE -> {
                val bw = if (t.kind == Kind.MOTORCYCLE) 0.35f else 0.25f
                box(cv, x - bw, x + bw, 0.3f, 1.0f, zw, zw + 2f, top, rear, side)
                box(cv, x - 0.25f, x + 0.25f, 1.0f, 1.6f, zw + 0.6f, zw + 1.2f, 0xFF5F6368.toInt(), 0xFF4A4F55.toInt(), 0xFF43474C.toInt())
                head(cv, x, 1.75f, zw + 0.9f, 0xFF4A4F55.toInt())
            }
            Kind.PERSON -> {
                val c = 0xFFE37400.toInt()
                box(cv, x - 0.25f, x + 0.25f, 0f, 1.45f, zw, zw + 0.35f, 0xFFF29900.toInt(), c, 0xFFC26401.toInt())
                head(cv, x, 1.62f, zw + 0.17f, c)
            }
        }
        if (t.z < 40f && zw + CAM_BACK > 2f) {
            val d = t.z
            p.style = Paint.Style.FILL
            p.color = when { d < 8f -> 0xFFD93025.toInt(); d < 16f -> 0xFFE37400.toInt(); else -> 0xFF5F6368.toInt() }
            p.textAlign = Paint.Align.CENTER
            p.textSize = (h * 0.026f).coerceAtLeast(14f * dp)
            cv.drawText("~${d.roundToInt()} m", sx(x, 0f, zw), sy(0f, zw) + p.textSize * 1.25f, p)
        }
    }

    private fun lengthOf(k: Kind) = when (k) {
        Kind.CAR -> 4.5f; Kind.TRUCK -> 9.2f; Kind.BUS -> 11f
        Kind.MOTORCYCLE, Kind.BICYCLE -> 2f; Kind.PERSON -> 0.35f
    }

    private fun tailLights(cv: Canvas, x: Float, hw: Float, y0: Float, y1: Float, z: Float) {
        val red = 0xFFE8453C.toInt()
        val zl = z - 0.01f
        quad(cv, red, x - hw + 0.05f, y0, zl, x - hw + 0.40f, y0, zl, x - hw + 0.40f, y1, zl, x - hw + 0.05f, y1, zl)
        quad(cv, red, x + hw - 0.40f, y0, zl, x + hw - 0.05f, y0, zl, x + hw - 0.05f, y1, zl, x + hw - 0.40f, y1, zl)
    }

    private fun head(cv: Canvas, x: Float, y: Float, z: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.color = color
        cv.drawCircle(sx(x, y, z), sy(y, z), f * 0.14f / depth(y, z), p)
    }

    /** O seu carro: claro, visto de trás e de cima. */
    private fun drawEgo(cv: Canvas) {
        ground(cv, -1.1f, 1.1f, -0.2f, EGO_LEN + 0.2f, 0x1E000000) // sombra
        box(cv, -0.93f, 0.93f, 0.25f, 0.95f, 0f, EGO_LEN, 0xFFFFFFFF.toInt(), 0xFFE8EAED.toInt(), 0xFFC9CDD2.toInt(), 0.03f, 0.15f)
        box(cv, -0.84f, 0.84f, 0.95f, 1.42f, 0.9f, 3.7f, 0xFF3C4043.toInt(), 0xFF5F6368.toInt(), 0xFF4A4F55.toInt(), 0.16f, 0.5f)
        tailLights(cv, 0f, 0.93f, 0.66f, 0.80f, 0f)
    }

    // ---------------------------------------------------------------- sobre o vídeo

    /** Área onde o vídeo aparece (encaixado, com barras pretas se precisar). */
    private fun videoRect(w: Float, h: Float): RectF {
        val sc = minOf(w / videoW, h / videoH)
        val vw = videoW * sc
        val vh = videoH * sc
        return RectF((w - vw) / 2f, (h - vh) / 2f, (w + vw) / 2f, (h + vh) / 2f)
    }

    private fun drawOverlay(cv: Canvas, w: Float, h: Float) {
        val vr = videoRect(w, h)
        val yH = vr.top + HORIZON * vr.height()
        val yB = vr.top + 0.84f * vr.height() // acima do capô
        val cx = vr.centerX()
        // veículo à frente no meio da imagem: o caminho termina nele
        val lead = tracks.filter { (it.l + it.r) / 2f in 0.36f..0.64f && it.b > HORIZON + 0.02f }.maxByOrNull { it.b }
        val yEnd = lead?.let { (vr.top + it.b * vr.height()).coerceIn(yH + 4f, yB - 4f) } ?: (yH + 0.06f * vr.height())
        val bottomHalf = 0.17f * vr.width()
        val endHalf = bottomHalf * (yEnd - yH) / (yB - yH)
        path.reset()
        path.moveTo(cx - bottomHalf, yB)
        path.lineTo(cx + bottomHalf, yB)
        path.lineTo(cx + endHalf, yEnd)
        path.lineTo(cx - endHalf, yEnd)
        path.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, yB, 0f, yEnd, 0xB02B7FFF.toInt(), 0x402B7FFF, Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        p.shader = null

        // caixas dos veículos e pessoas
        val r = 10f * dp
        for (t in tracks) {
            val d = t.z
            val col = when { d < 8f -> 0xFFF28B82.toInt(); d < 16f -> 0xFFFDD663.toInt(); else -> 0xFF8AB4F8.toInt() }
            rect.set(vr.left + t.l * vr.width(), vr.top + t.t * vr.height(), vr.left + t.r * vr.width(), vr.top + t.b * vr.height())
            p.style = Paint.Style.FILL
            p.color = (col and 0x00FFFFFF) or 0x30000000
            cv.drawRoundRect(rect, r, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f * dp
            p.color = col
            cv.drawRoundRect(rect, r, r, p)
            if (d < 40f) {
                p.style = Paint.Style.FILL
                p.textAlign = Paint.Align.CENTER
                p.textSize = 15f * dp
                p.setShadowLayer(4f * dp, 0f, 0f, 0xCC000000.toInt())
                cv.drawText("~${d.roundToInt()} m", rect.centerX(), rect.top - 6f * dp, p)
                p.clearShadowLayer()
            }
        }
    }

    // ---------------------------------------------------------------- textos e botão

    private fun drawHud(cv: Canvas, w: Float, h: Float) {
        val onVideo = style == Style.VIDEO
        val main = if (onVideo) 0xFFFFFFFF.toInt() else 0xFF202124.toInt()
        val muted = if (onVideo) 0xDDFFFFFF.toInt() else 0xFF5F6368.toInt()
        if (onVideo) p.setShadowLayer(5f * dp, 0f, 0f, 0xCC000000.toInt())
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT

        // velocidade (GPS do tablet), abaixo do selo "AO VIVO"
        val left = 22f * dp
        p.color = main
        p.textSize = 54f * dp
        p.isFakeBoldText = true
        // parado, o GPS oscila entre 0 e 2 km/h: abaixo de 3 mostra 0
        val speedText = speedKmh?.let { if (it < 3f) "0" else it.roundToInt().toString() } ?: "--"
        val base = 72f * dp + p.textSize * 0.82f
        cv.drawText(speedText, left, base, p)
        p.isFakeBoldText = false
        p.color = muted
        p.textSize = 15f * dp
        cv.drawText(if (speedKmh == null) "km/h • sem GPS" else "km/h", left + 2f * dp, base + 22f * dp, p)

        // legenda discreta embaixo à esquerda
        p.textSize = 12f * dp
        cv.drawText("Simulação • posições estimadas • $fps det/s", left, h - 22f * dp, p)
        p.clearShadowLayer()

        // botão para trocar o estilo, embaixo à direita
        val label = if (onVideo) "Ver cena 3D" else "Ver sobre o vídeo"
        p.textSize = 15f * dp
        p.isFakeBoldText = true
        val tw = p.measureText(label)
        val bh = 44f * dp
        toggleRect.set(w - tw - 40f * dp - 16f * dp, h - bh - 14f * dp, w - 16f * dp, h - 14f * dp)
        p.color = if (onVideo) 0xCC202124.toInt() else 0xFF202124.toInt()
        cv.drawRoundRect(toggleRect, bh / 2f, bh / 2f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textAlign = Paint.Align.CENTER
        cv.drawText(label, toggleRect.centerX(), toggleRect.centerY() + p.textSize * 0.35f, p)
        p.isFakeBoldText = false
    }

    /** Mensagem no meio da tela, quebrando linha para caber (erros podem ser longos). */
    private fun drawMessage(cv: Canvas, text: String, w: Float, h: Float) {
        val maxW = (w * 0.84f).toInt().coerceAtLeast(1)
        tp.textSize = h * 0.032f
        tp.color = 0xFFE8EAED.toInt()
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
        p.color = 0xCC202124.toInt()
        rect.set(left - pad, top - pad, left + maxW + pad, top + layout.height + pad)
        cv.drawRoundRect(rect, pad, pad, p)
        cv.save()
        cv.translate(left, top)
        layout.draw(cv)
        cv.restore()
    }

    companion object {
        /** Linha do horizonte na imagem da câmera (fração da altura). Estimativa para câmera de para-brisa. */
        const val HORIZON = 0.45f
        /** Meia largura do campo de visão em "metros por metro" (grande-angular). Estimativa. */
        private const val LATERAL = 1.2f
        private const val LANE = 3.5f
        private const val FAR = 160f
        private const val DASH_LEN = 3f
        private const val DASH_PERIOD = 9f
        private const val EGO_LEN = 4.5f
        /** Posição da câmera do painel a partir da traseira do seu carro (m). */
        private const val DASHCAM_Z = 2.6f
        private const val CAM_H = 7.5f
        private const val CAM_BACK = 9f
        private const val PITCH_DEG = 26.0
        /** Na tela: horizonte e traseira do seu carro (fração da altura). */
        private const val HORIZON_Y = 0.24f
        private const val EGO_Y = 0.90f
    }
}

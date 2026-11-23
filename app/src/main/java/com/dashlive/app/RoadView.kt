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
    ) {
        /** Número fixo enquanto o objeto existir (a cena 3D usa para manter o mesmo carro). */
        val id = ++trackIds
        /** Pisca deste veículo (experimental): âmbar oscilando no canto esquerdo/direito. */
        val ambL = BlinkDetector()
        val ambR = BlinkDetector()
        var blinkL = false
        var blinkR = false
    }

    /**
     * Um objeto para a cena 3D: tipo, lado x (m, direita +), distância z (m) da câmera do painel e
     * pisca ligado (esquerda/direita; os dois = pisca-alerta).
     */
    class Obj(val id: Int, val kind: Kind, val x: Float, val z: Float, val blinkL: Boolean = false, val blinkR: Boolean = false)

    fun objects(): List<Obj> = tracks.map { Obj(it.id, it.kind, it.x, it.z, it.blinkL, it.blinkR) }

    /** A cena 3D (Road3DView) está desenhando a estrada por baixo: aqui só o painel por cima. */
    var scene3d = false
        set(v) { field = v; invalidate() }
    /** Nome da rua do mapa (ou a situação do mapa), mostrado no cartão da cena 3D. */
    var mapStatus: String? = null
    /** O OBD2 está informando os faróis (sem isso a cena 3D deixa o farol baixo aceso). */
    var lightsKnown = false

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
    /** Quanto subir o botão de estilo (px); em tela cheia o botão "sair" ocupa o canto de baixo. */
    var toggleLift = 0f
        set(v) { field = v; invalidate() }
    /** Velocidade do GPS em km/h; null = sem GPS. */
    var speedKmh: Float? = null
    /** Setas e faróis do carro (OBD2). As setas piscam no ritmo de [blinkOn]. */
    var turnLeft = false
    var turnRight = false
    var lowBeam = false
    var highBeam = false

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
            val now = android.os.SystemClock.uptimeMillis()
            tr.ambL.add(now, s.ambL); tr.ambR.add(now, s.ambR)
            tr.blinkL = tr.ambL.blinking(now)
            tr.blinkR = tr.ambR.blinking(now)
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
        val onDay = scene3d && dayLabel != null && dayRect.contains(e.x, e.y)
        if (!toggleRect.contains(e.x, e.y) && !onDay) return false
        if (e.action == MotionEvent.ACTION_UP) {
            if (onDay) {
                onDayButton?.invoke()
            } else {
                style = if (style == Style.SCENE) Style.VIDEO else Style.SCENE
                onStyleChange?.invoke(style)
            }
            performClick()
        }
        return true
    }

    /** Texto do botão de dia/noite da cena 3D ("Auto ☀", "Dia", "Noite"); null = sem botão. */
    var dayLabel: String? = null
        set(v) { field = v; invalidate() }
    var onDayButton: (() -> Unit)? = null
    private val dayRect = RectF()

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
        if (style == Style.VIDEO) drawOverlay(cv, w, h, dt) else if (!scene3d) drawScene(cv, w, h)
        drawHud(cv, w, h)
        drawIndicators(cv, w)
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
        // pisca âmbar por cima da lanterna do lado da seta
        if (blinkOn()) {
            val amber = 0xFFFFB300.toInt()
            if (turnLeft) quad(cv, amber, -0.92f, 0.62f, -0.02f, -0.60f, 0.62f, -0.02f, -0.60f, 0.86f, -0.02f, -0.92f, 0.86f, -0.02f)
            if (turnRight) quad(cv, amber, 0.60f, 0.62f, -0.02f, 0.92f, 0.62f, -0.02f, 0.92f, 0.86f, -0.02f, 0.60f, 0.86f, -0.02f)
        }
    }

    // ---------------------------------------------------------------- sobre o vídeo

    /**
     * Área do vídeo na tela. No estilo [Style.VIDEO] o vídeo PREENCHE o cartão (corta um pouco em
     * cima/embaixo, como uma câmera de cinema); a MainActivity usa a mesma regra no TextureView.
     */
    private fun videoRect(w: Float, h: Float): RectF {
        val sc = videoScale(videoW, videoH, w, h, style == Style.VIDEO)
        val vw = videoW * sc
        val vh = videoH * sc
        return RectF((w - vw) / 2f, (h - vh) / 2f, (w + vw) / 2f, (h + vh) / 2f)
    }

    // Brilhos suaves (BlurMaskFilter funciona com aceleração de hardware a partir do Android 9)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowRadius = 0f
    private var pulse = 0f
    private var vignette: Shader? = null
    private var vignetteKey = 0f
    private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    private fun glowPaint(radius: Float): Paint {
        if (radius != glowRadius) {
            glow.maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
            glowRadius = radius
        }
        glow.style = Paint.Style.FILL
        glow.shader = null
        return glow
    }

    /**
     * Estilo "sobre o vídeo", inspirado nos painéis de carros com piloto automático: a rua de verdade
     * ao fundo com cor de cinema, o seu carro visto de trás embaixo, uma faixa azul brilhante saindo
     * dele até o veículo à frente e os veículos marcados por um brilho no chão.
     */
    private fun drawOverlay(cv: Canvas, w: Float, h: Float, dt: Float) {
        val vr = videoRect(w, h)
        // chão plano: a linha da imagem e o lado de um ponto a z metros (mesmo modelo do update())
        fun gy(z: Float) = vr.top + (HORIZON + 3.5f / z * (1f - HORIZON)) * vr.height()
        fun gx(x: Float, z: Float) = vr.left + (0.5f + x / (2f * LATERAL * z)) * vr.width()
        fun zAt(y: Float): Float {
            val yb = ((y - vr.top) / vr.height() - HORIZON) / (1f - HORIZON)
            return 3.5f / yb.coerceAtLeast(0.02f)
        }

        // 1) cor de cinema: um pouco mais escuro, cantos escuros (o desenho por cima se destaca)
        p.style = Paint.Style.FILL
        p.color = 0x2A06101C
        cv.drawRect(0f, 0f, w, h, p)
        val vk = w * 7f + h
        if (vignette == null || vignetteKey != vk) {
            vignette = android.graphics.RadialGradient(
                w / 2f, h * 0.55f, maxOf(w, h) * 0.75f,
                intArrayOf(0x00000000, 0x22000000, 0xAA000000.toInt()), floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
            vignetteKey = vk
        }
        p.shader = vignette
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // 2) seu carro (posição fixa na tela) e onde começa o caminho
        val carW = minOf(0.17f * w, 0.36f * h)
        val carBottom = minOf(vr.bottom, h) - 0.035f * h
        val z0 = zAt(carBottom - 0.10f * carW)
        // largura do caminho no chão = largura do carro na base, para a perspectiva bater
        val halfM = (carW * 0.5f / vr.width()) * 2f * LATERAL * z0

        // veículo à frente na sua faixa: o caminho termina nele
        val lead = tracks.filter {
            abs(it.x) < 1.6f && it.b > HORIZON + 0.02f && vr.top + it.b * vr.height() < carBottom - 0.6f * carW
        }.maxByOrNull { it.b }
        val zEnd = (lead?.let { zAt(vr.top + it.b * vr.height()) + 0.5f } ?: 55f).coerceAtLeast(z0 + 2f)

        // 3) caminho azul: brilho largo, faixa principal e pulsos andando para a frente
        fun ribbon(z1: Float, z2: Float, half: Float) {
            path.reset()
            val steps = 14
            for (i in 0..steps) {
                val z = z1 + (z2 - z1) * i / steps
                if (i == 0) path.moveTo(gx(-half, z), gy(z)) else path.lineTo(gx(-half, z), gy(z))
            }
            for (i in steps downTo 0) {
                val z = z1 + (z2 - z1) * i / steps
                path.lineTo(gx(half, z), gy(z))
            }
            path.close()
        }
        val yStart = gy(z0)
        val yEnd = gy(zEnd)
        ribbon(z0, zEnd, halfM * 1.35f)
        val g = glowPaint(18f * dp)
        g.shader = LinearGradient(0f, yStart, 0f, yEnd, 0x902F8CFF.toInt(), 0x202F8CFF, Shader.TileMode.CLAMP)
        cv.drawPath(path, g)
        g.shader = null
        ribbon(z0, zEnd, halfM)
        p.shader = LinearGradient(0f, yStart, 0f, yEnd, 0xD8389BFF.toInt(), 0x50389BFF, Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        p.shader = null
        // pulsos de luz: mais rápidos quanto maior a velocidade
        pulse = (pulse + dt * (0.35f + (speedKmh ?: 0f) / 90f)) % 1f
        for (i in 0 until 3) {
            val s = (pulse + i / 3f) % 1f
            val zc = z0 + (zEnd - z0) * s * s // espaçados em perspectiva
            ribbon(zc, (zc + 0.9f + zc * 0.05f).coerceAtMost(zEnd), halfM * 0.96f)
            p.color = (((1f - s) * 0x34).toInt() shl 24) or 0xBFE0FF
            cv.drawPath(path, p)
        }
        // ponta: barra luminosa no fim do caminho (embaixo do veículo à frente, se houver)
        if (lead != null) {
            rect.set(gx(-halfM * 1.2f, zEnd), yEnd - 3f * dp, gx(halfM * 1.2f, zEnd), yEnd + 3f * dp)
            cv.drawRoundRect(rect, 3f * dp, 3f * dp, glowPaint(8f * dp).apply { color = 0xFF6FB6FF.toInt() })
        }

        // 4) veículos e pessoas: brilho no chão + cantos, sem caixa cheia
        for (t in tracks.sortedBy { it.b }) drawTarget(cv, t, vr, t === lead)

        // 5) seu carro por cima de tudo
        drawEgoRear(cv, w / 2f, carBottom, carW)
    }

    private fun drawTarget(cv: Canvas, t: Track, vr: RectF, isLead: Boolean) {
        val d = t.z
        val col = when {
            t.kind == Kind.PERSON || t.kind == Kind.BICYCLE -> 0xFFFFB74D.toInt()
            d < 8f -> 0xFFFF6B5E.toInt()
            d < 16f -> 0xFFFFD45E.toInt()
            else -> 0xFF8CC4FF.toInt()
        }
        val l = vr.left + t.l * vr.width(); val r = vr.left + t.r * vr.width()
        val tp0 = vr.top + t.t * vr.height(); val bt = vr.top + t.b * vr.height()
        val bw = r - l
        if (bw < 4f) return
        // brilho no chão, embaixo do veículo
        rect.set(l - bw * 0.12f, bt - bw * 0.07f, r + bw * 0.12f, bt + bw * 0.09f)
        val g = glowPaint((bw * 0.12f).coerceIn(4f * dp, 16f * dp))
        g.color = (col and 0x00FFFFFF) or (if (isLead) 0xE0000000.toInt() else 0x99000000.toInt())
        cv.drawOval(rect, g)
        // cantos finos (marcação discreta)
        val c = (bw.coerceAtMost(bt - tp0) * 0.22f).coerceIn(5f * dp, 22f * dp)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = (if (isLead) 3f else 2f) * dp
        p.color = (col and 0x00FFFFFF) or 0xE6000000.toInt()
        path.reset()
        path.moveTo(l, tp0 + c); path.lineTo(l, tp0); path.lineTo(l + c, tp0)
        path.moveTo(r - c, tp0); path.lineTo(r, tp0); path.lineTo(r, tp0 + c)
        path.moveTo(l, bt - c); path.lineTo(l, bt); path.lineTo(l + c, bt)
        path.moveTo(r - c, bt); path.lineTo(r, bt); path.lineTo(r, bt - c)
        cv.drawPath(path, p)
        p.strokeCap = Paint.Cap.BUTT
        // distância numa etiqueta pequena
        if (d < 40f && bw > 14f * dp) {
            val label = "${d.roundToInt()} m"
            p.style = Paint.Style.FILL
            p.textSize = 13f * dp
            p.isFakeBoldText = true
            val tw = p.measureText(label)
            val ph = 20f * dp
            val cx = (l + r) / 2f
            rect.set(cx - tw / 2f - 8f * dp, tp0 - ph - 6f * dp, cx + tw / 2f + 8f * dp, tp0 - 6f * dp)
            p.color = 0xB3101418.toInt()
            cv.drawRoundRect(rect, ph / 2f, ph / 2f, p)
            p.color = col
            p.textAlign = Paint.Align.CENTER
            cv.drawText(label, cx, rect.centerY() + p.textSize * 0.36f, p)
            p.isFakeBoldText = false
        }
        // pisca do outro veículo (experimental): bolinha âmbar piscando no canto do lado da seta
        if ((t.blinkL || t.blinkR) && blinkOn()) {
            val rr = (bw * 0.09f).coerceIn(5f * dp, 12f * dp)
            val cy = tp0 + (bt - tp0) * 0.6f
            for ((on, x) in listOf(t.blinkL to l, t.blinkR to r)) {
                if (!on) continue
                cv.drawCircle(x, cy, rr * 1.8f, glowPaint(rr).apply { color = 0xCCFFA000.toInt() })
                p.style = Paint.Style.FILL
                p.color = 0xFFFFC233.toInt()
                cv.drawCircle(x, cy, rr, p)
            }
        }
    }

    /** Seu carro visto de trás (genérico, cinza metálico), com sombra e lanterna em barra. */
    private fun drawEgoRear(cv: Canvas, cx: Float, bottom: Float, u: Float) {
        // sombra no chão
        rect.set(cx - 0.60f * u, bottom - 0.07f * u, cx + 0.60f * u, bottom + 0.06f * u)
        cv.drawOval(rect, glowPaint(0.06f * u).apply { color = 0xCC000000.toInt() })
        p.style = Paint.Style.FILL
        // pneus
        p.color = 0xFF15171A.toInt()
        rect.set(cx - 0.49f * u, bottom - 0.17f * u, cx - 0.33f * u, bottom); cv.drawRoundRect(rect, 0.03f * u, 0.03f * u, p)
        rect.set(cx + 0.33f * u, bottom - 0.17f * u, cx + 0.49f * u, bottom); cv.drawRoundRect(rect, 0.03f * u, 0.03f * u, p)
        // cabine (vidro traseiro em trapézio)
        val cb = bottom - 0.47f * u; val ct = bottom - 0.76f * u
        path.reset()
        path.moveTo(cx - 0.44f * u, cb)
        path.lineTo(cx - 0.31f * u, ct + 0.02f * u)
        path.quadTo(cx - 0.30f * u, ct, cx - 0.27f * u, ct)
        path.lineTo(cx + 0.27f * u, ct)
        path.quadTo(cx + 0.30f * u, ct, cx + 0.31f * u, ct + 0.02f * u)
        path.lineTo(cx + 0.44f * u, cb)
        path.close()
        p.shader = LinearGradient(0f, ct, 0f, cb, 0xFFB9C0C8.toInt(), 0xFF7E8690.toInt(), Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        p.shader = null
        path.reset()
        path.moveTo(cx - 0.38f * u, cb - 0.01f * u)
        path.lineTo(cx - 0.27f * u, ct + 0.04f * u)
        path.lineTo(cx + 0.27f * u, ct + 0.04f * u)
        path.lineTo(cx + 0.38f * u, cb - 0.01f * u)
        path.close()
        p.shader = LinearGradient(cx - 0.3f * u, ct, cx + 0.2f * u, cb, 0xFF4A5561.toInt(), 0xFF161A20.toInt(), Shader.TileMode.CLAMP)
        cv.drawPath(path, p)
        p.shader = null
        // carroceria
        rect.set(cx - 0.50f * u, bottom - 0.50f * u, cx + 0.50f * u, bottom - 0.08f * u)
        p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xFFDDE1E6.toInt(), 0xFF7B828B.toInt(), Shader.TileMode.CLAMP)
        cv.drawRoundRect(rect, 0.10f * u, 0.10f * u, p)
        p.shader = null
        // reflexo na tampa do porta-malas
        p.color = 0x55FFFFFF
        rect.set(cx - 0.44f * u, bottom - 0.495f * u, cx + 0.44f * u, bottom - 0.475f * u)
        cv.drawRoundRect(rect, 0.01f * u, 0.01f * u, p)
        // para-choque
        p.color = 0xFF2E3338.toInt()
        rect.set(cx - 0.47f * u, bottom - 0.17f * u, cx + 0.47f * u, bottom - 0.08f * u)
        cv.drawRoundRect(rect, 0.04f * u, 0.04f * u, p)
        // lanterna em barra, com brilho
        rect.set(cx - 0.47f * u, bottom - 0.385f * u, cx + 0.47f * u, bottom - 0.345f * u)
        cv.drawRoundRect(rect, 0.02f * u, 0.02f * u, glowPaint(0.05f * u).apply { color = 0x99FF2A2A.toInt() })
        p.color = 0xFFE8302A.toInt()
        cv.drawRoundRect(rect, 0.02f * u, 0.02f * u, p)
        p.color = 0xFFFF8A80.toInt()
        rect.set(cx - 0.45f * u, bottom - 0.372f * u, cx + 0.45f * u, bottom - 0.362f * u)
        cv.drawRect(rect, p)
        // pisca âmbar nas pontas da lanterna
        if (blinkOn()) {
            if (turnLeft) blinker(cv, cx - 0.49f * u, bottom - 0.40f * u, cx - 0.27f * u, bottom - 0.33f * u, u)
            if (turnRight) blinker(cv, cx + 0.27f * u, bottom - 0.40f * u, cx + 0.49f * u, bottom - 0.33f * u, u)
        }
    }

    private fun blinker(cv: Canvas, l: Float, t: Float, r: Float, b: Float, u: Float) {
        rect.set(l, t, r, b)
        cv.drawRoundRect(rect, 0.02f * u, 0.02f * u, glowPaint(0.08f * u).apply { color = 0xDDFFA000.toInt() })
        p.style = Paint.Style.FILL
        p.color = 0xFFFFC233.toInt()
        cv.drawRoundRect(rect, 0.02f * u, 0.02f * u, p)
    }

    // ---------------------------------------------------------------- luzes do painel

    /** No alto, no meio: ◀ farol ▶ (setas verdes piscando, farol baixo verde, farol alto azul). */
    private fun drawIndicators(cv: Canvas, w: Float) {
        if (!turnLeft && !turnRight && !lowBeam && !highBeam) return
        val s = 34f * dp
        val cy = 36f * dp // alinhado com o selo "AO VIVO"
        val cx = w / 2f
        val gap = 62f * dp
        rect.set(cx - gap - s / 2f - 14f * dp, cy - s / 2f - 7f * dp, cx + gap + s / 2f + 14f * dp, cy + s / 2f + 7f * dp)
        p.style = Paint.Style.FILL
        p.color = 0xB3101418.toInt()
        cv.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, p)
        val on = blinkOn()
        drawArrow(cv, cx - gap, cy, s, left = true, active = turnLeft, lit = on)
        drawArrow(cv, cx + gap, cy, s, left = false, active = turnRight, lit = on)
        if (highBeam || lowBeam) drawBeam(cv, cx, cy, s * 0.95f, high = highBeam)
    }

    private fun drawArrow(cv: Canvas, cx: Float, cy: Float, size: Float, left: Boolean, active: Boolean, lit: Boolean) {
        val k = size / 24f
        val x0 = cx - size / 2f
        val y0 = cy - size / 2f
        val pts = if (left) ARROW_LEFT else ARROW_RIGHT
        path.reset()
        path.moveTo(x0 + pts[0] * k, y0 + pts[1] * k)
        for (i in 2 until pts.size step 2) path.lineTo(x0 + pts[i] * k, y0 + pts[i + 1] * k)
        path.close()
        val on = active && lit
        if (on) cv.drawPath(path, glowPaint(7f * dp).apply { color = 0xCC34C759.toInt() })
        p.style = Paint.Style.FILL
        p.color = when {
            on -> 0xFF34C759.toInt()
            active -> 0x6634C759      // apagado no meio da piscada
            else -> 0x26FFFFFF        // seta desligada: só o contorno do lugar
        }
        cv.drawPath(path, p)
    }

    private fun drawBeam(cv: Canvas, cx: Float, cy: Float, size: Float, high: Boolean) {
        val k = size / 24f
        val x0 = cx - size / 2f
        val y0 = cy - size / 2f
        val col = if (high) 0xFF4C8DFF.toInt() else 0xFF34C759.toInt()
        path.reset()
        path.moveTo(x0 + 13f * k, y0 + 5f * k)
        path.cubicTo(x0 + 18.5f * k, y0 + 5f * k, x0 + 22.5f * k, y0 + 8f * k, x0 + 22.5f * k, y0 + 12f * k)
        path.cubicTo(x0 + 22.5f * k, y0 + 16f * k, x0 + 18.5f * k, y0 + 19f * k, x0 + 13f * k, y0 + 19f * k)
        path.close()
        p.style = Paint.Style.FILL
        p.color = col
        cv.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.2f * k
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0..2) {
            if (high) {
                val yy = y0 + (8f + 4f * i) * k
                cv.drawLine(x0 + 10f * k, yy, x0 + 3f * k, yy, p)
            } else {
                val yy = y0 + (7f + 4f * i) * k
                cv.drawLine(x0 + 10f * k, yy, x0 + 3f * k, yy + 3f * k, p)
            }
        }
        p.strokeCap = Paint.Cap.BUTT
        p.style = Paint.Style.FILL
    }

    // ---------------------------------------------------------------- textos e botão

    private fun drawHud(cv: Canvas, w: Float, h: Float) {
        val onVideo = style == Style.VIDEO || scene3d // fundo escuro: painel claro
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
        val left = 22f * dp
        // parado, o GPS oscila entre 0 e 2 km/h: abaixo de 3 mostra 0
        val speedText = speedKmh?.let { if (it < 3f) "0" else it.roundToInt().toString() } ?: "--"

        if (onVideo) {
            drawVideoCard(cv, h, speedText)
        } else {
            // velocidade (GPS do tablet), abaixo do selo "AO VIVO"
            p.color = 0xFF202124.toInt()
            p.textSize = 54f * dp
            p.isFakeBoldText = true
            val base = 72f * dp + p.textSize * 0.82f
            cv.drawText(speedText, left, base, p)
            p.isFakeBoldText = false
            p.color = 0xFF5F6368.toInt()
            p.textSize = 15f * dp
            cv.drawText(if (speedKmh == null) "km/h • sem GPS" else "km/h", left + 2f * dp, base + 22f * dp, p)

            // legenda discreta embaixo à esquerda
            p.textSize = 12f * dp
            cv.drawText("Simulação • posições estimadas • $fps det/s", left, h - 22f * dp, p)
        }

        // botão para trocar o estilo, embaixo à direita
        val label = if (style == Style.VIDEO) "Ver cena 3D" else "Ver sobre o vídeo"
        p.textSize = 15f * dp
        p.isFakeBoldText = true
        val tw = p.measureText(label)
        val bh = 44f * dp
        val yb = h - 14f * dp - toggleLift
        toggleRect.set(w - tw - 40f * dp - 16f * dp, yb - bh, w - 16f * dp, yb)
        p.color = if (onVideo) 0xCC202124.toInt() else 0xFF202124.toInt()
        cv.drawRoundRect(toggleRect, bh / 2f, bh / 2f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textAlign = Paint.Align.CENTER
        cv.drawText(label, toggleRect.centerX(), toggleRect.centerY() + p.textSize * 0.35f, p)
        // botão dia/noite, à esquerda do de estilo (só na cena 3D)
        val dl = dayLabel
        if (scene3d && dl != null) {
            val dw = p.measureText(dl) + 40f * dp
            dayRect.set(toggleRect.left - 10f * dp - dw, toggleRect.top, toggleRect.left - 10f * dp, toggleRect.bottom)
            p.color = 0xCC202124.toInt()
            cv.drawRoundRect(dayRect, bh / 2f, bh / 2f, p)
            p.color = 0xFFFFFFFF.toInt()
            cv.drawText(dl, dayRect.centerX(), dayRect.centerY() + p.textSize * 0.35f, p)
        }
        p.isFakeBoldText = false
    }

    /** Cartão escuro translúcido embaixo à esquerda: velocidade, hora e o que está sendo visto. */
    private fun drawVideoCard(cv: Canvas, h: Float, speedText: String) {
        val cw = 230f * dp
        val ch = 92f * dp
        val x0 = 16f * dp
        val y1 = h - 14f * dp
        rect.set(x0, y1 - ch, x0 + cw, y1)
        p.style = Paint.Style.FILL
        p.color = 0xB8121519.toInt()
        cv.drawRoundRect(rect, 18f * dp, 18f * dp, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * dp
        p.color = 0x33FFFFFF
        cv.drawRoundRect(rect, 18f * dp, 18f * dp, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT

        val pad = 16f * dp
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 40f * dp
        p.isFakeBoldText = true
        val base = rect.top + pad + p.textSize * 0.78f
        cv.drawText(speedText, x0 + pad, base, p)
        val sw = p.measureText(speedText)
        p.isFakeBoldText = false
        p.textSize = 14f * dp
        p.color = 0xCCFFFFFF.toInt()
        cv.drawText(if (speedKmh == null) "km/h • sem GPS" else "km/h", x0 + pad + sw + 8f * dp, base, p)
        // hora no canto direito do cartão
        p.textAlign = Paint.Align.RIGHT
        p.textSize = 15f * dp
        p.color = 0xFFFFFFFF.toInt()
        cv.drawText(clock.format(java.util.Date()), rect.right - pad, rect.top + pad + 12f * dp, p)
        p.textAlign = Paint.Align.LEFT
        p.textSize = 12f * dp
        p.color = 0x99FFFFFF.toInt()
        val n = tracks.size
        cv.drawText(
            "${if (n == 1) "1 objeto" else "$n objetos"} • $fps det/s • ${mapStatus ?: "estimativa"}",
            x0 + pad, rect.bottom - pad + 2f * dp, p,
        )
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
        private var trackIds = 0

        /** Meio período do pisca: ~0,36 s aceso e ~0,36 s apagado, como o do Ka (medido na rede). */
        const val BLINK_MS = 360L

        /** Fase do pisca, a mesma para a Estrada e para o cartão do carro. */
        fun blinkOn() = (android.os.SystemClock.uptimeMillis() / BLINK_MS) % 2 == 0L

        /** Setas no desenho de 24x24 (mesmo formato dos ícones ic_turn_left/right). */
        private val ARROW_LEFT = floatArrayOf(10f, 3.5f, 1.5f, 12f, 10f, 20.5f, 10f, 15.2f, 22.5f, 15.2f, 22.5f, 8.8f, 10f, 8.8f)
        private val ARROW_RIGHT = floatArrayOf(14f, 3.5f, 22.5f, 12f, 14f, 20.5f, 14f, 15.2f, 1.5f, 15.2f, 1.5f, 8.8f, 14f, 8.8f)

        /**
         * Escala do vídeo na tela. [fill] = preenche, mas corta no máximo ~25% de cada lado
         * (no cartão quase quadrado da tela normal, preencher tudo cortaria as faixas do lado).
         */
        fun videoScale(srcW: Int, srcH: Int, dstW: Float, dstH: Float, fill: Boolean): Float {
            val fit = minOf(dstW / srcW, dstH / srcH)
            return if (fill) minOf(maxOf(dstW / srcW, dstH / srcH), fit * 1.33f) else fit
        }

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

package com.dashlive.app.adas

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Curva de uma faixa na imagem: x = a·y² + b·y + c (px). */
class LanePoly(val a: Float, val b: Float, val c: Float, val yMin: Float, val yMax: Float, val points: Int) {
    fun x(y: Float) = a * y * y + b * y + c
}

/** Faixas da esquerda e da direita do seu carro (null = não achou). Medidas em px da imagem analisada. */
class LaneSides(val w: Int, val h: Int, val left: LanePoly?, val right: LanePoly?)

/**
 * Detecção de faixa SEM modelo (visão computacional clássica), para qualquer aparelho. O TwinLiteNet
 * rodou a 2 FPS na CPU e 3 FPS na GPU do Tab S9 FE (e errado na GPU), então esta é a detecção usada.
 * Kotlin puro (testado em LaneFinderTest), roda em poucos ms no quadro de 480×270 que o detector já pega.
 *
 * Como funciona, linha a linha na parte de baixo da imagem (55%–93% da altura; abaixo pode ser o capô):
 * 1. Brilho (Y) de cada pixel; faixa pintada = "claro entre escuros": filtro chapéu
 *    (média do centro − média dos lados) com largura que cresce para baixo (perspectiva).
 * 2. Picos acima do limiar viram candidatos; de cada lado do centro do carro fica o mais perto do
 *    centro — ou, se já havia faixa no quadro anterior, o mais perto da faixa prevista (segue ela).
 * 3. Ajuste de x = a·y² + b·y + c por mínimos quadrados, com uma rodada tirando pontos fora da curva.
 *    Exige [MIN_ROWS] linhas com ponto, cobrindo ao menos 30% da altura analisada.
 */
class LaneFinder {
    private var prev: LaneSides? = null

    fun reset() { prev = null }

    /**
     * [px] = pixels ARGB das linhas [y0] até [y0] + px.size/w (só a parte de baixo, para não copiar
     * a imagem inteira); [h] = altura total da imagem; [egoX] = coluna do centro do seu carro.
     */
    fun find(px: IntArray, w: Int, h: Int, y0: Int, egoX: Float): LaneSides {
        val rows = px.size / w
        val yTop = max((TOP * h).toInt(), y0)
        val yBot = min((BOTTOM * h).toInt(), y0 + rows - 1)
        val lum = IntArray(w)
        val pre = IntArray(w + 1)
        val lx = ArrayList<Float>(); val ly = ArrayList<Float>()
        val rx = ArrayList<Float>(); val ry = ArrayList<Float>()
        val p = prev
        var y = yTop
        while (y <= yBot) {
            val off = (y - y0) * w
            var sum = 0
            for (x in 0 until w) {
                val c = px[off + x]
                val l = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
                lum[x] = l; sum += l
                pre[x + 1] = pre[x] + l
            }
            val mean = sum / w
            // largura esperada da pintura nesta linha: ~0,6% da largura no alto, ~2,5% embaixo
            val t = (y - TOP * h) / ((BOTTOM - TOP) * h)
            val s = max(2, (w * (0.006f + 0.019f * t)).toInt())
            var bestL = Float.NaN; var bestR = Float.NaN
            var dL = Float.MAX_VALUE; var dR = Float.MAX_VALUE
            val predL = p?.left?.x(y.toFloat()); val predR = p?.right?.x(y.toFloat())
            var x = 2 * s
            while (x < w - 2 * s) {
                val center = (pre[x + s / 2 + 1] - pre[x - s / 2]) / (s / 2 * 2 + 1).toFloat()
                val sideL = (pre[x - s] - pre[x - 2 * s]) / s.toFloat()
                val sideR = (pre[x + 2 * s] - pre[x + s]) / s.toFloat()
                val resp = center - max(sideL, sideR)
                if (resp > MIN_CONTRAST && center > mean + MIN_ABOVE_MEAN) {
                    // pico: acha o trecho realmente claro (acima do meio-termo entre pintura e asfalto)
                    // e usa o meio dele — o filtro dispara um pouco antes, ainda no asfalto
                    val level = max(sideL, sideR) + resp / 2f
                    var a = x
                    while (a < x + s && a < w - 1 && lum[a] < level) a++
                    var e = a
                    while (e + 1 < w - 2 * s && lum[e + 1] >= level) e++
                    val mid = (a + e) / 2f
                    if (mid < egoX) {
                        val d = if (predL != null) abs(mid - predL) else egoX - mid
                        if (d < dL && (predL == null || d < TRACK_WIN * w)) { dL = d; bestL = mid }
                    } else {
                        val d = if (predR != null) abs(mid - predR) else mid - egoX
                        if (d < dR && (predR == null || d < TRACK_WIN * w)) { dR = d; bestR = mid }
                    }
                    x = e + s
                } else x++
            }
            if (!bestL.isNaN()) { lx += bestL; ly += y.toFloat() }
            if (!bestR.isNaN()) { rx += bestR; ry += y.toFloat() }
            y += STEP
        }
        val span = (yBot - yTop).toFloat()
        var left = fit(lx, ly, w, span)
        var right = fit(rx, ry, w, span)
        // as duas têm que fazer sentido juntas: esquerda à esquerda, largura plausível embaixo
        if (left != null && right != null) {
            val yb = 0.85f * h
            val width = right.x(yb) - left.x(yb)
            if (width < 0.2f * w || width > 1.1f * w) { left = null; right = null }
        }
        val r = LaneSides(w, h, left, right)
        // se perdeu um lado, procura de novo "do zero" no próximo quadro
        prev = if (left != null && right != null) r else null
        return r
    }

    companion object {
        const val TOP = 0.55f
        const val BOTTOM = 0.93f
        const val STEP = 2
        const val MIN_CONTRAST = 18f
        const val MIN_ABOVE_MEAN = 8
        const val MIN_ROWS = 12
        /** Janela (fração da largura) para seguir a faixa do quadro anterior. */
        const val TRACK_WIN = 0.08f

        /** Mínimos quadrados de x = a·y² + b·y + c, com uma rodada sem os pontos fora da curva. */
        fun fit(xs: List<Float>, ys: List<Float>, w: Int, span: Float): LanePoly? {
            if (xs.size < MIN_ROWS) return null
            var keep = xs.indices.toList()
            var poly: LanePoly? = null
            for (round in 0..2) {
                if (keep.size < MIN_ROWS) return null
                val yMin = keep.minOf { ys[it] }; val yMax = keep.maxOf { ys[it] }
                if (yMax - yMin < 0.3f * span) return null
                // y centrado e escalado para a conta não perder precisão
                val ym = (yMin + yMax) / 2f; val ys2 = max(1f, (yMax - yMin) / 2f)
                var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
                var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
                for (i in keep) {
                    val u = ((ys[i] - ym) / ys2).toDouble(); val x = xs[i].toDouble()
                    val u2 = u * u
                    s0 += 1; s1 += u; s2 += u2; s3 += u2 * u; s4 += u2 * u2
                    t0 += x; t1 += x * u; t2 += x * u2
                }
                // resolve [s4 s3 s2; s3 s2 s1; s2 s1 s0]·[A B C] = [t2 t1 t0]
                val det = s4 * (s2 * s0 - s1 * s1) - s3 * (s3 * s0 - s1 * s2) + s2 * (s3 * s1 - s2 * s2)
                if (abs(det) < 1e-9) return null
                val dA = t2 * (s2 * s0 - s1 * s1) - s3 * (t1 * s0 - s1 * t0) + s2 * (t1 * s1 - s2 * t0)
                val dB = s4 * (t1 * s0 - t0 * s1) - t2 * (s3 * s0 - s1 * s2) + s2 * (s3 * t0 - t1 * s2)
                val dC = s4 * (s2 * t0 - s1 * t1) - s3 * (s3 * t0 - t1 * s2) + t2 * (s3 * s1 - s2 * s2)
                val A = dA / det; val B = dB / det; val C = dC / det
                // volta para y em px: u = (y − ym)/ys2
                val a = (A / (ys2 * ys2)).toFloat()
                val b = (B / ys2 - 2 * A * ym / (ys2 * ys2)).toFloat()
                val c = (A * ym * ym / (ys2 * ys2) - B * ym / ys2 + C).toFloat()
                val pl = LanePoly(a, b, c, yMin, yMax, keep.size)
                poly = pl
                if (round < 2) {
                    // tira o que está longe da curva: até 2% da largura, ou 2× o erro típico (mediana)
                    // quando pontos muito errados puxaram a primeira curva
                    val res = keep.map { abs(pl.x(ys[it]) - xs[it]) }.sorted()
                    val tol = max(max(3f, 0.02f * w), 2f * res[res.size / 2])
                    keep = keep.filter { abs(pl.x(ys[it]) - xs[it]) <= tol }
                }
            }
            return poly
        }
    }
}

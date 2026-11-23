package com.dashlive.app

/**
 * Associação de veículos entre quadros. Puro (sem Android), testado em `TrackMatchTest`.
 * - [Hungarian]: atribuição de custo mínimo (algoritmo húngaro), para não trocar a identidade de
 *   dois carros que passam perto um do outro.
 * - [TrackMatch]: custo combinado (distância, sobreposição da caixa e tipo) e aceite por limite.
 * - [TrackRules]: quando um rastro vira "carro" (confirmado) e quando ele é descartado.
 */
internal object Hungarian {

    /**
     * Atribuição de custo mínimo. Devolve, para cada linha, a coluna escolhida (ou -1 se não há).
     * Aceita matrizes retangulares (linhas ≤ colunas, ou o contrário: transposta internamente).
     */
    fun solve(cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        if (n == 0) return IntArray(0)
        val m = cost[0].size
        if (m == 0) return IntArray(n) { -1 }
        val transposed = n > m
        val a: Array<DoubleArray> = if (transposed) Array(m) { i -> DoubleArray(n) { j -> cost[j][i] } } else cost
        val rows = a.size
        val cols = a[0].size
        val inf = Double.MAX_VALUE / 4
        val u = DoubleArray(rows + 1)
        val v = DoubleArray(cols + 1)
        val p = IntArray(cols + 1)
        val way = IntArray(cols + 1)
        for (i in 1..rows) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(cols + 1) { inf }
            val used = BooleanArray(cols + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = inf
                var j1 = 0
                for (j in 1..cols) {
                    if (!used[j]) {
                        val cur = a[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                        if (minv[j] < delta) { delta = minv[j]; j1 = j }
                    }
                }
                for (j in 0..cols) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val res = IntArray(n) { -1 }
        for (j in 1..cols) {
            if (p[j] != 0) {
                val r = p[j] - 1
                if (transposed) res[j - 1] = r else res[r] = j - 1
            }
        }
        return res
    }
}

internal object TrackMatch {
    /** Custo de um par que não pode casar (tipos diferentes). */
    const val FORBID = 1e6
    /** Custo máximo aceito para um casamento. */
    const val GATE = 3.0
    /** Peso da sobreposição da caixa no custo (quanto menor a sobreposição, maior o custo). */
    const val IOU_WEIGHT = 1.5

    /** Sobreposição (IoU) de duas caixas em coordenadas normalizadas (esquerda, topo, direita, base). */
    fun iou(ax0: Float, ay0: Float, ax1: Float, ay1: Float, bx0: Float, by0: Float, bx1: Float, by1: Float): Double {
        val ix = (minOf(ax1, bx1) - maxOf(ax0, bx0)).coerceAtLeast(0f)
        val iy = (minOf(ay1, by1) - maxOf(ay0, by0)).coerceAtLeast(0f)
        val inter = (ix * iy).toDouble()
        val areaA = ((ax1 - ax0) * (ay1 - ay0)).toDouble().coerceAtLeast(0.0)
        val areaB = ((bx1 - bx0) * (by1 - by0)).toDouble().coerceAtLeast(0.0)
        val union = areaA + areaB - inter
        return if (union <= 0.0) 0.0 else inter / union
    }

    /** Custo de casar uma detecção com um rastro: distância no chão + caixa pouco sobreposta. */
    fun cost(sameKind: Boolean, dist: Double, iou: Double): Double =
        if (!sameKind) FORBID else dist + (1.0 - iou) * IOU_WEIGHT

    /** Para cada detecção (linha), o índice do rastro casado, ou -1. Respeita o limite [GATE]. */
    fun assign(cost: Array<DoubleArray>): IntArray {
        if (cost.isEmpty() || cost[0].isEmpty()) return IntArray(cost.size) { -1 }
        val pick = Hungarian.solve(cost)
        return IntArray(cost.size) { i ->
            val j = pick[i]
            if (j >= 0 && cost[i][j] <= GATE) j else -1
        }
    }
}

internal object TrackRules {
    /** Quantas observações um objeto precisa antes de aparecer na Estrada. */
    const val CONFIRM_HITS = 3
    /** Quadros sem ser visto até descartar um objeto ainda não confirmado (sai rápido). */
    const val MAX_MISS_UNCONFIRMED = 2
    /** Quadros sem ser visto até descartar um objeto já confirmado. */
    const val MAX_MISS_CONFIRMED = 6

    fun confirmed(hits: Int): Boolean = hits >= CONFIRM_HITS

    fun dead(hits: Int, missed: Int): Boolean =
        missed > if (confirmed(hits)) MAX_MISS_CONFIRMED else MAX_MISS_UNCONFIRMED
}

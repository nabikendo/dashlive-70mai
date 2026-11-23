package com.dashlive.app

/**
 * Suaviza uma linha (xy intercalado) sem mudar o número de pontos: cada ponto interno vai para
 * uma média ponderada com os vizinhos (0,5 do próprio + 0,25 de cada lado). As pontas ficam fixas.
 * Não muda o número de pontos porque o mapa casa os trechos pelo índice dos nós.
 */
internal fun smoothLine(xy: FloatArray, passes: Int = 2) {
    val n = xy.size / 2
    if (n < 3) return
    val tmp = FloatArray(xy.size)
    repeat(passes) {
        tmp[0] = xy[0]; tmp[1] = xy[1]
        tmp[(n - 1) * 2] = xy[(n - 1) * 2]; tmp[(n - 1) * 2 + 1] = xy[(n - 1) * 2 + 1]
        for (i in 1 until n - 1) {
            for (c in 0..1) {
                val p = xy[i * 2 + c]
                val a = xy[(i - 1) * 2 + c]
                val b = xy[(i + 1) * 2 + c]
                tmp[i * 2 + c] = 0.5f * p + 0.25f * (a + b)
            }
        }
        tmp.copyInto(xy)
    }
}

package com.dashlive.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatchTest {

    @Test
    fun hungaroEscolheOMenorCustoTotal() {
        // gulosamente escolheria (0,0)=1 e depois (1,1)=9: total 10; o ótimo é (0,1)=2 + (1,0)=2 = 4
        val c = arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 9.0))
        assertArrayEquals(intArrayOf(1, 0), Hungarian.solve(c))
    }

    @Test
    fun hungaroRetangularMaisColunas() {
        val c = arrayOf(doubleArrayOf(5.0, 1.0, 9.0), doubleArrayOf(2.0, 8.0, 3.0))
        assertArrayEquals(intArrayOf(1, 0), Hungarian.solve(c))
    }

    @Test
    fun hungaroRetangularMaisLinhas() {
        val c = arrayOf(doubleArrayOf(5.0, 1.0), doubleArrayOf(2.0, 8.0), doubleArrayOf(7.0, 7.0))
        val r = Hungarian.solve(c)
        // 2 colunas: no máximo 2 linhas casadas, e as casadas são diferentes
        assertEquals(2, r.count { it >= 0 })
        assertEquals(r.filter { it >= 0 }.toSet().size, r.count { it >= 0 })
        assertEquals(1, r[0]) // linha 0 prefere a coluna 1 (custo 1)
    }

    @Test
    fun semLinhasOuColunas() {
        assertEquals(0, Hungarian.solve(arrayOf()).size)
        assertArrayEquals(intArrayOf(-1), Hungarian.solve(arrayOf(doubleArrayOf())))
    }

    @Test
    fun doisCarrosQuePassamPertoNaoTrocamIdentidade() {
        // rastros A (x=0) e B (x=1,5); detecções na mesma ordem, mas o custo manda cada uma ao seu rastro
        val costs = arrayOf(
            doubleArrayOf(TrackMatch.cost(true, 0.2, 0.8), TrackMatch.cost(true, 1.3, 0.1)),
            doubleArrayOf(TrackMatch.cost(true, 1.2, 0.1), TrackMatch.cost(true, 0.3, 0.8)),
        )
        assertArrayEquals(intArrayOf(0, 1), TrackMatch.assign(costs))
    }

    @Test
    fun tiposDiferentesNuncaCasam() {
        val c = arrayOf(doubleArrayOf(TrackMatch.cost(false, 0.0, 1.0)))
        assertArrayEquals(intArrayOf(-1), TrackMatch.assign(c))
    }

    @Test
    fun acimaDoLimiteNaoCasa() {
        val c = arrayOf(doubleArrayOf(TrackMatch.GATE + 0.5))
        assertArrayEquals(intArrayOf(-1), TrackMatch.assign(c))
    }

    @Test
    fun iouDeCaixasIguaisEIsoladas() {
        assertEquals(1.0, TrackMatch.iou(0.1f, 0.1f, 0.3f, 0.3f, 0.1f, 0.1f, 0.3f, 0.3f), 1e-6)
        assertEquals(0.0, TrackMatch.iou(0.0f, 0.0f, 0.1f, 0.1f, 0.5f, 0.5f, 0.6f, 0.6f), 1e-9)
    }

    @Test
    fun confirmaDepoisDeTresObservacoes() {
        assertFalse(TrackRules.confirmed(2))
        assertTrue(TrackRules.confirmed(TrackRules.CONFIRM_HITS))
    }

    @Test
    fun naoConfirmadoSaiMaisRapido() {
        // 3 quadros sem ver: não confirmado já sai; confirmado ainda fica
        assertTrue(TrackRules.dead(hits = 1, missed = 3))
        assertFalse(TrackRules.dead(hits = 5, missed = 3))
        assertTrue(TrackRules.dead(hits = 5, missed = TrackRules.MAX_MISS_CONFIRMED + 1))
    }
}

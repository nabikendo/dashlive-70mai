package com.dashlive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdTest {

    @Test fun speed() {
        assertEquals(60, Obd.speed("410D3C\r\r>"))
        assertEquals(0, Obd.speed("41 0D 00 \r>"))
    }

    @Test fun rpmWithSearchingLine() {
        // primeira resposta depois do ATSP0: o adaptador escreve SEARCHING... antes
        assertEquals(1726, Obd.rpm("SEARCHING...\r410C1AF8\r\r>"))
    }

    @Test fun coolantAndFuel() {
        assertEquals(90, Obd.coolant("410582\r>")) // 0x82 = 130 - 40
        assertEquals(50, Obd.fuel("412F80\r>"))
        assertEquals(100, Obd.fuel("412FFF\r>"))
    }

    @Test fun firstEcuWins() {
        // dois módulos respondendo: usa a primeira linha válida
        assertEquals(42, Obd.speed("410D2A\r410D2B\r>"))
    }

    @Test fun noData() {
        assertNull(Obd.speed("NO DATA\r>"))
        assertNull(Obd.rpm("410C1A\r>")) // falta um byte
        assertTrue(Obd.isNoAnswer("UNABLE TO CONNECT\r>"))
        assertTrue(Obd.isNoAnswer("CAN ERROR\r>"))
    }

    @Test fun volts() {
        assertEquals(12.6f, Obd.volts("12.6V\r>")!!, 0.001f)
        assertNull(Obd.volts("?\r>"))
    }

    @Test fun answersWithHeader() {
        // com ATH1 a resposta vem com o ID do módulo (7E8) e o byte de tamanho na frente
        assertEquals(0, Obd.speed("7E803410D00\r\r>"))
        assertEquals(793, Obd.rpm("7E804410C0C64\r>"))
        assertEquals(65, Obd.coolant("7E803410569\r>"))
    }

    @Test fun frameParsing() {
        val (id, b) = Obd.frame("3B34008C01210008032")!!
        assertEquals(0x3B3, id)
        assertEquals(8, b.size)
        assertEquals(0x40, b[0])
        assertEquals(0x32, b[7])
        assertNull(Obd.frame("3B3440BC00"))   // cortada pelo BUFFER FULL (tamanho ímpar)
        // cortada com tamanho par: o ID continua certo (o corte é no fim), mas faltam bytes
        assertNull(Obd.fordBody(Obd.frame("3B34008")!!.second))
        assertNull(Obd.frame("STOPPED"))
        assertNull(Obd.frame("BUFFER FULL"))
    }

    private fun body(hex: String) = Obd.fordBody(Obd.frame("3B3$hex")!!.second)!!

    @Test fun fordKaTurnSignalsAndLights() {
        // valores gravados no Ford Ka 2019 em 22/11/2026
        val off = body("4008C01210008032")
        assertFalse(off.left); assertFalse(off.right); assertFalse(off.lights)

        val leftLit = body("400BC0121000C032")
        assertTrue(leftLit.left); assertTrue(leftLit.leftLamp); assertFalse(leftLit.right)
        val leftDark = body("4009C01210008032")
        assertTrue(leftDark.left); assertFalse(leftDark.leftLamp)

        val rightLit = body("4008C012180080F2")
        assertTrue(rightLit.right); assertTrue(rightLit.rightLamp); assertFalse(rightLit.left)
        val rightDark = body("4008C01210008072")
        assertTrue(rightDark.right); assertFalse(rightDark.rightLamp)

        val lights = body("4408C00C10008032")
        assertTrue(lights.lights); assertFalse(lights.left); assertFalse(lights.right)

        assertNull(Obd.fordBody(intArrayOf(0x40, 0x08))) // mensagem cortada
    }

    @Test fun fordKaBeams() {
        fun beams(hex: String) = Obd.fordBeams(Obd.frame("3C3$hex")!!.second)!!
        assertEquals(false to false, beams("4008100000008100"))
        assertEquals(true to false, beams("4048100000008100"))
        assertEquals(false to true, beams("5C88100000000100"))
    }
}

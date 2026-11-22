package com.dashlive.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class H264SpsTest {

    /** SPS real da 70mai A200 (sprop-parameter-sets de /livestream/12): High 5.1, 640x360, VUI sem bitstream_restriction. */
    private val cameraSps = Base64.getDecoder().decode("Z2QAM6wVFKCgL/lm4CAgKAAAH0AAB1MAIA==")

    @Test
    fun readsCameraSps() {
        val info = H264Sps.parse(cameraSps)
        assertNotNull(info)
        info!!
        assertEquals(640, info.width)
        assertEquals(360, info.height)
        assertEquals(1, info.numRefFrames)
        assertTrue(info.vuiPresent)
        assertEquals(-1, info.maxNumReorderFrames)
    }

    @Test
    fun addsBitstreamRestrictionWhenVuiAlreadyExists() {
        val fixed = H264Sps.lowLatency(cameraSps)
        assertFalse(fixed.contentEquals(cameraSps))
        val before = H264Sps.parse(cameraSps)!!
        val after = H264Sps.parse(fixed)!!
        assertEquals(640, after.width)
        assertEquals(360, after.height)
        assertTrue(after.vuiPresent)
        assertEquals(0, after.maxNumReorderFrames)
        assertEquals(1, after.maxDecFrameBuffering)
        // tudo antes da restrição (cor, taxa de quadros...) continua igual
        assertEquals(before.restrictionFlagPos, after.restrictionFlagPos)
        for (i in 0 until before.restrictionFlagPos) {
            assertEquals("bit $i", bitAt(before.rbsp, i), bitAt(after.rbsp, i))
        }
    }

    @Test
    fun addsVuiWhenMissing() {
        val noVui = H264Sps.parse(cameraSps)!!.let { stripVui(cameraSps, it.vuiFlagPos) }
        assertFalse(H264Sps.parse(noVui)!!.vuiPresent)
        val after = H264Sps.parse(H264Sps.lowLatency(noVui))!!
        assertTrue(after.vuiPresent)
        assertEquals(0, after.maxNumReorderFrames)
        assertEquals(640, after.width)
        assertEquals(360, after.height)
    }

    @Test
    fun isIdempotent() {
        val once = H264Sps.lowLatency(cameraSps)
        assertArrayEquals(once, H264Sps.lowLatency(once))
    }

    @Test
    fun leavesGarbageUntouched() {
        val junk = byteArrayOf(0x67, 0x01, 0x02)
        assertArrayEquals(junk, H264Sps.lowLatency(junk))
        assertArrayEquals(ByteArray(0), H264Sps.lowLatency(ByteArray(0)))
    }

    private fun bitAt(d: ByteArray, i: Int) = (d[i shr 3].toInt() shr (7 - (i and 7))) and 1

    /** Monta o mesmo SPS com vui_parameters_present_flag = 0 (sem emulação: não há 00 00 0x no começo). */
    private fun stripVui(sps: ByteArray, vuiPos: Int): ByteArray {
        val rbsp = H264Sps.parse(sps)!!.rbsp
        val bits = ArrayList<Int>()
        for (i in 0 until vuiPos) bits += bitAt(rbsp, i)
        bits += 0 // sem VUI
        bits += 1 // bit final do RBSP
        while (bits.size % 8 != 0) bits += 0
        val out = ByteArray(bits.size / 8 + 1)
        out[0] = sps[0]
        for (i in bits.indices) if (bits[i] == 1) out[1 + (i shr 3)] = (out[1 + (i shr 3)].toInt() or (1 shl (7 - (i and 7)))).toByte()
        return out
    }
}

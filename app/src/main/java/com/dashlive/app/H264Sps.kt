package com.dashlive.app

import java.io.ByteArrayOutputStream

/**
 * Leitura e ajuste do SPS do H.264. Não usa nada do Android, para poder ser testado no PC
 * (app/src/test).
 */
object H264Sps {

    class Info(
        val width: Int,
        val height: Int,
        val numRefFrames: Int,
        /** SPS sem o byte de cabeçalho NAL e sem os bytes de prevenção de emulação. */
        val rbsp: ByteArray,
        /** Posição (em bits, no [rbsp]) do vui_parameters_present_flag. */
        val vuiFlagPos: Int,
        val vuiPresent: Boolean,
        /** Posição do bitstream_restriction_flag dentro do VUI; -1 sem VUI. */
        val restrictionFlagPos: Int,
        /** max_num_reorder_frames do VUI; -1 se o SPS não informa. */
        val maxNumReorderFrames: Int,
        /** max_dec_frame_buffering do VUI; -1 se o SPS não informa. */
        val maxDecFrameBuffering: Int,
    )

    /** Largura e altura visíveis (já sem o corte). Devolve null se não conseguir ler. */
    fun size(sps: ByteArray): Pair<Int, Int>? = parse(sps)?.let { it.width to it.height }

    fun parse(sps: ByteArray): Info? = try {
        val rbsp = unescape(sps)
        val b = Bits(rbsp)
        val profile = b.u(8); b.u(8); b.u(8); b.ue()
        var chroma = 1
        if (profile in HIGH_PROFILES) {
            chroma = b.ue()
            if (chroma == 3) b.u(1)
            b.ue(); b.ue(); b.u(1)
            if (b.u(1) == 1) {
                val n = if (chroma != 3) 8 else 12
                for (i in 0 until n) if (b.u(1) == 1) {
                    val size = if (i < 6) 16 else 64
                    var last = 8
                    var next = 8
                    for (j in 0 until size) {
                        if (next != 0) next = (last + b.se() + 256) % 256
                        if (next != 0) last = next
                    }
                }
            }
        }
        b.ue()
        val poc = b.ue()
        if (poc == 0) b.ue()
        else if (poc == 1) { b.u(1); b.se(); b.se(); repeat(b.ue()) { b.se() } }
        val numRef = b.ue(); b.u(1)
        val w = (b.ue() + 1) * 16
        val hMap = b.ue() + 1
        val frameMbsOnly = b.u(1)
        val h = (2 - frameMbsOnly) * hMap * 16
        if (frameMbsOnly == 0) b.u(1)
        b.u(1)
        var cl = 0; var cr = 0; var ct = 0; var cb = 0
        if (b.u(1) == 1) { cl = b.ue(); cr = b.ue(); ct = b.ue(); cb = b.ue() }
        val vuiFlagPos = b.pos
        val vui = b.u(1) == 1

        var restrictionPos = -1
        var reorder = -1
        var decBuf = -1
        if (vui) {
            if (b.u(1) == 1 && b.u(8) == 255) { b.u(16); b.u(16) } // aspect_ratio_info
            if (b.u(1) == 1) b.u(1)                                  // overscan
            if (b.u(1) == 1) {                                       // video_signal_type
                b.u(3); b.u(1)
                if (b.u(1) == 1) { b.u(8); b.u(8); b.u(8) }
            }
            if (b.u(1) == 1) { b.ue(); b.ue() }                      // chroma_loc_info
            if (b.u(1) == 1) { b.u(32); b.u(32); b.u(1) }            // timing_info
            val nalHrd = b.u(1) == 1
            if (nalHrd) skipHrd(b)
            val vclHrd = b.u(1) == 1
            if (vclHrd) skipHrd(b)
            if (nalHrd || vclHrd) b.u(1)                             // low_delay_hrd_flag
            b.u(1)                                                   // pic_struct_present_flag
            restrictionPos = b.pos
            if (b.u(1) == 1) {
                b.u(1); b.ue(); b.ue(); b.ue(); b.ue()
                reorder = b.ue()
                decBuf = b.ue()
            }
        }

        val unitX = if (chroma == 0) 1 else 2
        val unitY = (if (chroma == 0) 1 else 2) * (2 - frameMbsOnly)
        val outW = w - unitX * (cl + cr)
        val outH = h - unitY * (ct + cb)
        // Leu além do fim: o SPS não é o que parece; melhor não mexer
        if (b.pos > rbsp.size * 8 || outW !in 16..8192 || outH !in 16..8192) null
        else Info(outW, outH, numRef, rbsp, vuiFlagPos, vui, restrictionPos, reorder, decBuf)
    } catch (e: Exception) {
        null
    }

    /**
     * O SPS da câmera não diz quantos quadros o decodificador precisa guardar. Com nível 5.1, o
     * Android reserva até 16 (o Exynos do Tab S9 FE segura 23 na saída) e a imagem atrasa ~0,8 s.
     * Escreve no VUI "sem reordenar, guarde só os quadros de referência", o que tira esse atraso.
     *
     * Funciona com ou sem VUI no SPS original: a A200 manda VUI com cor e taxa de quadros, mas
     * sem bitstream_restriction. Tudo o que vem antes da restrição é copiado igual.
     * Se algo der errado, devolve o SPS original.
     */
    fun lowLatency(sps: ByteArray): ByteArray = try {
        val info = parse(sps)
        when {
            info == null || sps.isEmpty() -> sps
            info.maxNumReorderFrames == 0 && info.maxDecFrameBuffering in 1..maxOf(1, info.numRefFrames) -> sps
            else -> {
                val w = BitWriter()
                val keep = if (info.vuiPresent) info.restrictionFlagPos else info.vuiFlagPos
                for (i in 0 until keep) w.bit((info.rbsp[i shr 3].toInt() shr (7 - (i and 7))) and 1)
                if (!info.vuiPresent) {
                    w.bit(1)                   // vui_parameters_present_flag
                    repeat(8) { w.bit(0) }     // aspect, overscan, sinal de vídeo, croma, tempo, 2x HRD, pic_struct
                }
                w.bit(1)                       // bitstream_restriction_flag
                w.bit(1)                       // motion_vectors_over_pic_boundaries_flag
                w.ue(0); w.ue(0)               // max_bytes_per_pic_denom, max_bits_per_mb_denom (sem limite)
                w.ue(16); w.ue(16)             // log2_max_mv_length_horizontal / vertical (máximo)
                w.ue(0)                        // max_num_reorder_frames
                w.ue(maxOf(1, info.numRefFrames)) // max_dec_frame_buffering
                escape(sps[0], w.finish())
            }
        }
    } catch (e: Exception) {
        sps
    }

    // ---------------------------------------------------------------- bits

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun skipHrd(b: Bits) {
        val cpbCnt = b.ue() + 1
        b.u(4); b.u(4)
        repeat(cpbCnt) { b.ue(); b.ue(); b.u(1) }
        b.u(5); b.u(5); b.u(5); b.u(5)
    }

    /** Tira o cabeçalho NAL e os bytes 0x03 de prevenção de emulação. */
    private fun unescape(nal: ByteArray): ByteArray {
        val raw = ByteArrayOutputStream()
        var zeros = 0
        for (i in 1 until nal.size) {
            val x = nal[i].toInt() and 0xFF
            if (zeros >= 2 && x == 3) { zeros = 0; continue }
            raw.write(x)
            zeros = if (x == 0) zeros + 1 else 0
        }
        return raw.toByteArray()
    }

    /** Põe de volta o cabeçalho NAL e os bytes de prevenção de emulação. */
    private fun escape(header: Byte, rbsp: ByteArray): ByteArray {
        val res = ByteArrayOutputStream()
        res.write(header.toInt())
        var zeros = 0
        for (x in rbsp) {
            val v = x.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) { res.write(3); zeros = 0 }
            res.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return res.toByteArray()
    }

    private class Bits(val d: ByteArray) {
        var pos = 0
        fun bit(): Int {
            val i = pos shr 3
            val v = if (i < d.size) (d[i].toInt() shr (7 - (pos and 7))) and 1 else 0
            pos++
            return v
        }
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var z = 0
            while (bit() == 0 && z < 32) z++
            return if (z == 0) 0 else (1 shl z) - 1 + u(z)
        }
        fun se(): Int { val k = ue(); return if (k % 2 == 1) (k + 1) / 2 else -(k / 2) }
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun bit(v: Int) {
            cur = (cur shl 1) or (v and 1)
            if (++n == 8) { out.write(cur); cur = 0; n = 0 }
        }
        fun ue(v: Int) {
            val x = v + 1
            val len = 32 - Integer.numberOfLeadingZeros(x)
            repeat(len - 1) { bit(0) }
            for (i in len - 1 downTo 0) bit((x shr i) and 1)
        }
        fun finish(): ByteArray {
            bit(1) // bit final do RBSP
            while (n != 0) bit(0)
            return out.toByteArray()
        }
    }
}

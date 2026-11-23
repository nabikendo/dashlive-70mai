package com.dashlive.app

/**
 * Leitura das respostas do ELM327 (OBD2 modo 01). Kotlin puro, testado em app/src/test.
 * O adaptador é configurado sem eco, sem espaços e sem cabeçalho (ATE0, ATS0, ATH0):
 * pedindo "010D" a resposta vem como "410D3C" (+ ">" no fim).
 */
object Obd {

    const val PID_COOLANT = 0x05
    const val PID_RPM = 0x0C
    const val PID_SPEED = 0x0D
    const val PID_FUEL = 0x2F

    /** Bytes de dados da resposta ao [pid] (depois de "41 <pid>"), ou null se o carro não respondeu. */
    fun dataBytes(pid: Int, raw: String): IntArray? {
        val prefix = "41%02X".format(pid)
        for (line in raw.uppercase().split('\r', '\n', '>')) {
            val s = line.replace(" ", "").trim()
            if (s.isEmpty() || !s.all { it in '0'..'9' || it in 'A'..'F' }) continue
            val i = s.indexOf(prefix)
            if (i < 0) continue
            val hex = s.substring(i + prefix.length)
            if (hex.length < 2) continue
            return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
        }
        return null
    }

    /** Velocidade em km/h. */
    fun speed(raw: String): Int? = dataBytes(PID_SPEED, raw)?.getOrNull(0)

    /** Rotação do motor (RPM). */
    fun rpm(raw: String): Int? {
        val d = dataBytes(PID_RPM, raw) ?: return null
        if (d.size < 2) return null
        return (d[0] * 256 + d[1]) / 4
    }

    /** Temperatura da água do motor em °C. */
    fun coolant(raw: String): Int? = dataBytes(PID_COOLANT, raw)?.getOrNull(0)?.let { it - 40 }

    /** Nível do tanque em %. */
    fun fuel(raw: String): Int? = dataBytes(PID_FUEL, raw)?.getOrNull(0)?.let { (it * 100 + 127) / 255 }

    /** Tensão da bateria pelo comando ATRV ("12.6V"). */
    fun volts(raw: String): Float? =
        Regex("""(\d{1,2}\.\d)\s*V""").find(raw.uppercase())?.groupValues?.get(1)?.toFloatOrNull()

    /** O carro respondeu "não sei/não consigo" (ignição desligada, protocolo errado...). */
    fun isNoAnswer(raw: String): Boolean {
        val u = raw.uppercase()
        return "UNABLE" in u || "NO DATA" in u || "ERROR" in u || "STOPPED" in u || "?" in u
    }

    // ---------------------------------------------------------------- mensagens da rede (ATMA)

    /**
     * Uma linha do ATMA com cabeçalho e bytes crus (ATH1, ATCAF0), ex. "3B34008C01210008032"
     * → ID 0x3B3 e os bytes. Linhas cortadas pelo BUFFER FULL ou textos ("STOPPED") dão null.
     */
    fun frame(line: String): Pair<Int, IntArray>? {
        val s = line.replace(" ", "").uppercase()
        if (s.length < 5 || s.length > 19 || (s.length - 3) % 2 != 0) return null
        if (!s.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val id = s.substring(0, 3).toInt(16)
        return id to IntArray((s.length - 3) / 2) { s.substring(3 + it * 2, 5 + it * 2).toInt(16) }
    }

    /**
     * Setas e faróis do Ford Ka 2019 (mensagem 0x3B3 do BCM, ~2 por segundo). Achado gravando a rede
     * do carro em 22/11/2026 (ver CLAUDE.md). [leftLamp]/[rightLamp] acompanham o pisca (acende/apaga).
     */
    data class Body(
        val left: Boolean,
        val right: Boolean,
        val leftLamp: Boolean,
        val rightLamp: Boolean,
        val lights: Boolean,
    )

    fun fordBody(b: IntArray): Body? {
        if (b.size < 8) return null
        return Body(
            left = (b[1] and 0x01) != 0,
            right = (b[7] and 0x40) != 0,
            leftLamp = (b[1] and 0x02) != 0,
            rightLamp = (b[7] and 0x80) != 0,
            lights = (b[0] and 0x04) != 0,
        )
    }

    /** Farol baixo e alto do Ford Ka 2019 (mensagem 0x3C3, só a cada ~3 s). */
    fun fordBeams(b: IntArray): Pair<Boolean, Boolean>? {
        if (b.size < 2) return null
        return ((b[1] and 0x40) != 0) to ((b[1] and 0x80) != 0)
    }

    const val ID_FORD_BODY = 0x3B3
    const val ID_FORD_BEAMS = 0x3C3
}

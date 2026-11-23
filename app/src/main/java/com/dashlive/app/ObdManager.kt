package com.dashlive.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Adaptador OBD2 ELM327 por Bluetooth comum (SPP). Roda numa thread própria: conecta, configura o
 * adaptador e repete um ciclo de ~0,7 s:
 *  1. pergunta velocidade e RPM (e, a cada ~10 s, temperatura, tanque e bateria);
 *  2. escuta a rede do carro por um instante, só as mensagens de setas e faróis do Ford Ka
 *     (0x3B3 e 0x3C3, ver [Obd.fordBody]). O ELM327 não faz as duas coisas ao mesmo tempo.
 * Se cair, tenta de novo (cada vez mais espaçado) até [stop].
 *
 * O adaptador precisa estar pareado antes nas configurações de Bluetooth do Android.
 */
@SuppressLint("MissingPermission") // a MainActivity só cria isto com BLUETOOTH_CONNECT concedida
class ObdManager(
    private val ctx: Context,
    private val address: String,
    private val onState: (text: String, connected: Boolean) -> Unit,
    private val onData: (Data) -> Unit,
) {
    data class Data(
        val speed: Int? = null,
        val rpm: Int? = null,
        val coolant: Int? = null,
        val fuel: Int? = null,
        val volts: Float? = null,
        /** Setas e faróis (mensagem 0x3B3); [bodyAt] = quando chegou a última (elapsedRealtime). */
        val left: Boolean = false,
        val right: Boolean = false,
        val lights: Boolean = false,
        val bodyAt: Long = 0L,
        /** Farol baixo/alto (mensagem 0x3C3, a cada ~3 s); [beamsAt] = quando chegou a última. */
        val lowBeam: Boolean = false,
        val highBeam: Boolean = false,
        val beamsAt: Long = 0L,
    )

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var data = Data()
    /** O adaptador está com o filtro/formato da escuta (e não com o das perguntas OBD). */
    private var listening = false

    /** Gravação de sinais pedida pela tela ("Descobrir sinais"). */
    private class Sniff(val file: File, val seconds: Int, val onDone: (String) -> Unit)
    @Volatile private var sniff: Sniff? = null
    /** Etapa mostrada ao usuário durante a gravação (anotada em cada linha do arquivo). */
    @Volatile var sniffPhase = ""

    val connected: Boolean get() = socket?.isConnected == true

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "obd").also { it.start() }
    }

    fun stop() {
        running = false
        closeSocket()
        thread?.interrupt()
        thread = null
    }

    /** Grava por [seconds] tudo o que passa na rede do carro (ATMA) em [file]. */
    fun recordSignals(file: File, seconds: Int, onDone: (String) -> Unit) {
        sniff = Sniff(file, seconds, onDone)
    }

    // ---------------------------------------------------------------- laço principal

    /**
     * Cada tentativa com o adaptador desligado deixa o rádio do tablet ocupado procurando por ele, e
     * no Tab S9 FE isso derruba o Wi-Fi da câmera por ~15 s (medido em 22/11/2026). Por isso, sem
     * resposta, as tentativas ficam cada vez mais espaçadas (10 s, 20 s, 40 s... até 2 min).
     */
    private fun loop() {
        var fails = 0
        while (running) {
            try {
                onState("Conectando ao adaptador…", false)
                connect()
                fails = 0
                if (!setup()) {
                    onState("Adaptador ok, mas o carro não respondeu. Ligue a ignição.", false)
                    closeSocket()
                    pause(8000)
                    continue
                }
                onState("Conectado", true)
                poll()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "OBD: ${e.message}")
                if (!running) break
                closeSocket()
                val wait = (10_000L shl fails.coerceAtMost(4)).coerceAtMost(120_000L)
                fails++
                onState("Adaptador não encontrado (carro desligado?). Nova tentativa em ${wait / 1000} s.", false)
                pause(wait)
            }
        }
        closeSocket()
    }

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { running = false }
    }

    private fun connect() {
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("tablet sem Bluetooth")
        if (!bt.isEnabled) throw IOException("Bluetooth desligado")
        val dev: BluetoothDevice = bt.getRemoteDevice(address)
        // Adaptadores baratos às vezes só aceitam o canal 1 direto (segunda tentativa)
        val tries = listOf<() -> BluetoothSocket>(
            { dev.createRfcommSocketToServiceRecord(SPP) },
            { dev.javaClass.getMethod("createRfcommSocket", Int::class.java).invoke(dev, 1) as BluetoothSocket },
        )
        var last: Exception? = null
        for (make in tries) {
            if (!running) throw InterruptedException()
            try {
                val s = make()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return
            } catch (e: Exception) {
                last = e
                closeSocket()
            }
        }
        throw IOException(last?.message ?: "não conectou")
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    /**
     * Configura o ELM327 e confirma que o carro responde. Cabeçalho LIGADO (ATH1) o tempo todo:
     * a escuta precisa do ID das mensagens, e o [Obd] acha a resposta OBD mesmo com o ID na frente.
     */
    private fun setup(): Boolean {
        cmd("ATZ", 3000)          // reinicia o adaptador (tira filtros e formato da escuta)
        listening = false
        cmd("ATE0"); cmd("ATL0"); cmd("ATS0"); cmd("ATH1")
        cmd("ATAT1")              // espera adaptativa (mais rápido)
        cmd("ATSP0")              // protocolo automático (o Ka 2019 usa CAN 500k)
        val r = cmd("0100", 10_000) // primeira pergunta: o adaptador procura o protocolo
        return r.replace(" ", "").contains("4100") && !r.uppercase().contains("UNABLE")
    }

    private fun poll() {
        var n = 0
        var quiet = 0 // escutas seguidas sem setas/faróis (outro carro ou ignição desligada)
        while (running) {
            sniff?.let { s -> sniff = null; doSniff(s) }
            askMode()
            data = data.copy(speed = Obd.speed(cmd("010D")), rpm = Obd.rpm(cmd("010C")))
            if (n % 14 == 0) {
                data = data.copy(
                    coolant = Obd.coolant(cmd("0105")) ?: data.coolant,
                    fuel = Obd.fuel(cmd("012F")) ?: data.fuel,
                    volts = Obd.volts(cmd("ATRV")) ?: data.volts,
                )
            }
            // carro sem essas mensagens: escuta só de vez em quando, para a velocidade andar mais rápido
            if (quiet < 15 || n % 15 == 0) {
                quiet = if (listenBody(BODY_LISTEN_MS)) 0 else quiet + 1
            } else {
                Thread.sleep(120)
            }
            onData(data)
            n++
        }
    }

    /** Volta o adaptador para as perguntas OBD: formato ISO-TP e só respostas dos módulos (7E8-7EF). */
    private fun askMode() {
        if (!listening) return
        cmd("ATCAF1"); cmd("ATCF 7E8"); cmd("ATCM 7F8")
        listening = false
    }

    /**
     * Escuta [ms] só as mensagens 0x3B3 e 0x3C3 (o filtro 383/78F deixa passar 0x383, 0x393 ... 0x3F3;
     * na rede do Ka são poucas e raras, então o ELM327 não enche a memória). Devolve true se chegou
     * alguma das duas.
     */
    private fun listenBody(ms: Long): Boolean {
        if (!listening) {
            cmd("ATCAF0"); cmd("ATCF 383"); cmd("ATCM 78F")
            listening = true
        }
        var got = false
        monitor(ms) { line ->
            val (id, b) = Obd.frame(line) ?: return@monitor
            val now = SystemClock.elapsedRealtime()
            when (id) {
                Obd.ID_FORD_BODY -> Obd.fordBody(b)?.let {
                    data = data.copy(left = it.left, right = it.right, lights = it.lights, bodyAt = now)
                    got = true
                }
                Obd.ID_FORD_BEAMS -> Obd.fordBeams(b)?.let { (low, high) ->
                    data = data.copy(lowBeam = low, highBeam = high, beamsAt = now)
                    got = true
                }
            }
        }
        return got
    }

    // ---------------------------------------------------------------- escuta da rede (ATMA)

    /**
     * Escuta a rede por até [ms] e para. Numa rede movimentada o ELM327 enche a memória, escreve
     * "BUFFER FULL" e volta sozinho ao ">". Cuidado: um Enter sozinho no ">" REPETE o último comando;
     * para parar a escuta manda-se um espaço (se já tiver parado, o espaço é ignorado no próximo).
     */
    private fun monitor(ms: Long, onLine: (String) -> Unit) {
        readAvailable()
        write("ATMA")
        if (!readLines(ms, onLine)) {
            val o = output ?: throw IOException("sem conexão")
            o.write(' '.code)
            o.flush()
            readLines(1500, onLine)
        }
    }

    /** Lê linhas até o ">" ou até [ms]; devolve true se o adaptador parou (chegou o ">"). */
    private fun readLines(ms: Long, onLine: (String) -> Unit): Boolean {
        val until = SystemClock.elapsedRealtime() + ms
        val buf = StringBuilder()
        while (SystemClock.elapsedRealtime() < until) {
            val got = readAvailable()
            if (got.isEmpty()) { Thread.sleep(3); continue }
            buf.append(got)
            var nl = buf.indexOf("\r")
            while (nl >= 0) {
                val line = buf.substring(0, nl).trim()
                buf.delete(0, nl + 1)
                if (line.isNotEmpty()) onLine(line)
                nl = buf.indexOf("\r")
            }
            if (buf.indexOf(">") >= 0) return true
        }
        if (socket?.isConnected != true) throw IOException("conexão caiu")
        return false
    }

    // ---------------------------------------------------------------- descobrir sinais

    /**
     * Grava tudo o que passa na rede do carro, com a hora e a etapa (seta, farol...), alternando o
     * filtro do adaptador. Sem filtro, a rede do Ka (88 IDs) enche a memória do ELM327 a cada ~16
     * mensagens e as raras quase não aparecem. 3B0-3BF e 3C0-3CF: onde estão setas e faróis no Ka;
     * sem filtro: amostra geral, para achar outros sinais.
     */
    private fun doSniff(s: Sniff) {
        var lines = 0
        var bursts = 0
        val t0 = SystemClock.elapsedRealtime()
        try {
            s.file.parentFile?.mkdirs()
            s.file.bufferedWriter().use { out ->
                out.write("# DashLive ${BuildConfig.VERSION_NAME} - sinais OBD (ATMA), ${java.util.Date()}\n")
                out.write("# ms\tetapa\tmensagem (ID de 3 dígitos + bytes, sem espaços)\n")
                // ID visível, sem espaços (cabem mais mensagens) e bytes crus (sem formatação ISO-TP)
                cmd("ATH1"); cmd("ATS0"); cmd("ATCAF0")
                listening = true // o setup() do fim devolve o adaptador ao normal
                val windows = listOf(Triple("3B0", "7F0", 1200L), Triple("3C0", "7F0", 600L), Triple("000", "000", 300L))
                out.write("# filtros: ${windows.joinToString { "CF ${it.first}/CM ${it.second}" }}\n")
                val end = t0 + s.seconds * 1000L
                var w = 0
                while (running && SystemClock.elapsedRealtime() < end) {
                    val (filter, mask, ms) = windows[w % windows.size]
                    w++
                    cmd("ATCF $filter"); cmd("ATCM $mask")
                    bursts++
                    monitor(ms) { line ->
                        out.write("${SystemClock.elapsedRealtime() - t0}\t$sniffPhase\t$line\n")
                        lines++
                    }
                }
                out.write("# fim: $lines mensagens em $bursts escutas\n")
            }
        } catch (e: Exception) {
            s.onDone("Falha na gravação: ${e.message}")
            return
        } finally {
            // reinicia o adaptador: tira os filtros e volta a ler velocidade e RPM
            runCatching { setup() }
        }
        s.onDone("Gravadas $lines mensagens.")
    }

    // ---------------------------------------------------------------- comunicação

    private fun write(c: String) {
        val o = output ?: throw IOException("sem conexão")
        o.write((c + "\r").toByteArray(Charsets.US_ASCII))
        o.flush()
    }

    /** Manda um comando e devolve a resposta (até o ">" do adaptador). */
    private fun cmd(c: String, timeoutMs: Long = 2000): String {
        // descarta sobras de uma resposta anterior
        readAvailable()
        write(c)
        return readUntilPrompt(timeoutMs)
    }

    private fun readAvailable(): String {
        val i = input ?: throw IOException("sem conexão")
        val n = i.available()
        if (n <= 0) return ""
        val b = ByteArray(n)
        val r = i.read(b)
        return if (r > 0) String(b, 0, r, Charsets.US_ASCII) else ""
    }

    private fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            val s = readAvailable()
            if (s.isNotEmpty()) {
                sb.append(s)
                if (s.contains('>')) break
            } else {
                Thread.sleep(8)
            }
        }
        if (socket?.isConnected != true) throw IOException("conexão caiu")
        return sb.toString()
    }

    companion object {
        private const val TAG = "DashLive"
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        /** Tempo de escuta das setas/faróis por ciclo (a 0x3B3 chega ~2 vezes por segundo). */
        private const val BODY_LISTEN_MS = 350L
    }
}

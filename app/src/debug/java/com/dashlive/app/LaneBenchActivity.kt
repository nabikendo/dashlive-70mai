package com.dashlive.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * SÓ NO BUILD DE TESTE: mede o TwinLiteNet (assets/twinlite.tflite, Hugging Face litert-community)
 * com o Interpreter 2.13 na CPU. Não faz parte do app; o LDW está adiado.
 *   adb shell am start -n com.dashlive.app.debug/com.dashlive.app.LaneBenchActivity --ei threads 4 --ei runs 50
 * Resultado no logcat (tag DashLive) e na tela.
 */
class LaneBenchActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 16f; setPadding(32, 32, 32, 32) }
        setContentView(text)
        val threads = intent.getIntExtra("threads", 4)
        val runs = intent.getIntExtra("runs", 50)
        Thread { bench(threads, runs) }.start()
    }

    private fun say(s: String) {
        Log.i("DashLive", "LaneBench: $s")
        runOnUiThread { text.append(s + "\n") }
    }

    private fun bench(threads: Int, runs: Int) {
        try {
            val t0 = SystemClock.elapsedRealtime()
            val model = assets.openFd("twinlite.tflite").use { fd ->
                FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
            // GPU (tensorflow-lite-gpu 2.13) medida em 23/11/2026: 3,1 FPS e saída errada (27% "área" numa
            // parede); a dependência saiu do app. Só CPU aqui.
            val opts = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true)
            val itp = Interpreter(model, opts)
            say("abriu em ${SystemClock.elapsedRealtime() - t0} ms, CPU threads=$threads")
            for (i in 0 until itp.inputTensorCount) {
                val t = itp.getInputTensor(i); say("entrada $i: ${t.name()} ${t.shape().contentToString()} ${t.dataType()}")
            }
            for (i in 0 until itp.outputTensorCount) {
                val t = itp.getOutputTensor(i); say("saída $i: ${t.name()} ${t.shape().contentToString()} ${t.dataType()}")
            }

            // Quadro real salvo pelo detector (road_frame.png), senão cinza
            val src = File(getExternalFilesDir(null), "road_frame.png")
            val bmp = (if (src.exists()) BitmapFactory.decodeFile(src.path) else null)
                ?.let { Bitmap.createScaledBitmap(it, 640, 360, true) }
            say(if (bmp != null) "entrada: road_frame.png" else "entrada: cinza (sem road_frame.png)")
            val input = ByteBuffer.allocateDirect(3 * 360 * 640 * 4).order(ByteOrder.nativeOrder())
            val px = IntArray(640 * 360)
            bmp?.getPixels(px, 0, 640, 0, 0, 640, 360) ?: px.fill(0xFF808080.toInt())
            for (c in 0 until 3) {           // NCHW, RGB/255
                val sh = 16 - 8 * c
                for (p in px) input.putFloat(((p shr sh) and 255) / 255f)
            }
            val outs = HashMap<Int, Any>()
            for (i in 0 until itp.outputTensorCount) {
                outs[i] = ByteBuffer.allocateDirect(itp.getOutputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
            }

            repeat(5) { input.rewind(); outs.values.forEach { (it as ByteBuffer).rewind() }; itp.runForMultipleInputsOutputs(arrayOf(input), outs) }
            val times = LongArray(runs)
            for (r in 0 until runs) {
                input.rewind(); outs.values.forEach { (it as ByteBuffer).rewind() }
                val s = SystemClock.elapsedRealtimeNanos()
                itp.runForMultipleInputsOutputs(arrayOf(input), outs)
                times[r] = (SystemClock.elapsedRealtimeNanos() - s) / 1_000_000
            }
            val sorted = times.sorted()
            val mean = times.average()
            say("runs=$runs média=%.1f ms mediana=%d ms min=%d max=%d → %.2f FPS".format(
                mean, sorted[runs / 2], sorted.first(), sorted.last(), 1000.0 / mean))

            // Conferência rápida: quantos pixels cada cabeça marca como classe 1 (argmax)
            for ((i, b) in outs) {
                val fb = (b as ByteBuffer).apply { rewind() }.asFloatBuffer()
                val plane = 360 * 640
                var on = 0
                for (p in 0 until plane) if (fb.get(plane + p) > fb.get(p)) on++
                say("saída $i: classe 1 em %.1f%% dos pixels".format(on * 100.0 / plane))
            }
            itp.close()
            say("FIM")
        } catch (e: Throwable) {
            Log.e("DashLive", "LaneBench falhou", e)
            say("ERRO: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

package com.dashlive.app

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico de gravação: uma linha no CSV para cada objeto de cada quadro analisado, e uma foto
 * do quadro a cada [PHOTO_EVERY_MS]. Fica em <arquivos do app>/diagnostico/<data-hora>/, para
 * ser enviado depois e analisado.
 */
class DiagLog private constructor(val dir: File) {

    private val csv = File(dir, "dados.csv")
    private val photos = File(dir, "fotos").apply { mkdirs() }
    private var lastPhotoMs = 0L
    private var frames = 0
    private var photoCount = 0

    init {
        csv.writeText(
            "hora_ms,fonte,velocidade_kmh,quadro,tipo,esquerda,topo,direita,base,confianca,de_frente,pisca_esq,pisca_dir\n"
        )
    }

    /** Registra um quadro: [seen] são os objetos vistos; [bmp] é o quadro que o detector recebeu. */
    fun frame(nowMs: Long, source: String, speedKmh: Float?, seen: List<Seen>, bmp: Bitmap?) {
        frames++
        val speed = speedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        val sb = StringBuilder()
        for (s in seen) {
            sb.append(nowMs).append(',').append(source).append(',').append(speed).append(',')
                .append(frames).append(',').append(s.kind).append(',')
                .append(String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f,%.2f", s.l, s.t, s.r, s.b, s.score)).append(',')
                .append(s.oncoming).append(',')
                .append(String.format(Locale.US, "%.3f,%.3f", s.ambL, s.ambR)).append('\n')
        }
        if (sb.isNotEmpty()) csv.appendText(sb.toString())
        if (bmp != null && nowMs - lastPhotoMs >= PHOTO_EVERY_MS) {
            lastPhotoMs = nowMs
            photoCount++
            runCatching {
                FileOutputStream(File(photos, "quadro_%04d_%d.jpg".format(photoCount, nowMs))).use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, it)
                }
            }
        }
    }

    /** Pasta onde os dados estão, para o caminho aparecer ao parar. */
    fun path(): String = dir.absolutePath

    companion object {
        private const val PHOTO_EVERY_MS = 3000L

        /** Começa um diagnóstico novo numa pasta com data e hora. Null se não houver armazenamento. */
        fun start(context: Context): DiagLog? {
            val base = context.getExternalFilesDir(null) ?: return null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(base, "diagnostico/$stamp").apply { mkdirs() }
            return runCatching { DiagLog(dir) }.getOrNull()
        }
    }
}

package com.dashlive.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/** Algo visto no vídeo: tipo, confiança e caixa em coordenadas de 0 a 1 (esquerda, topo, direita, base). */
class Seen(val kind: Kind, val score: Float, val l: Float, val t: Float, val r: Float, val b: Float) {
    /** Fração de pixels âmbar forte no canto esquerdo/direito das lanternas (para achar o pisca). */
    var ambL = 0f
    var ambR = 0f
}

enum class Kind(val widthM: Float, val heightM: Float) {
    CAR(1.8f, 1.5f), TRUCK(2.5f, 3.2f), BUS(2.5f, 3.2f),
    MOTORCYCLE(0.8f, 1.4f), BICYCLE(0.6f, 1.4f), PERSON(0.6f, 1.7f),
}

/**
 * Detector de objetos (EfficientDet-Lite0, treinado no COCO) rodando no tablet.
 * O arquivo do modelo é incluído no APK pela Action de build; sem ele [available] é falso.
 */
class RoadDetector(context: Context) {
    private var detector: ObjectDetector? = null
    var error: String? = null
        private set

    init {
        val inApk = runCatching { context.assets.list("")?.contains(MODEL) == true }.getOrDefault(false)
        if (!inApk) {
            error = "Modelo de detecção não incluído neste APK"
        } else try {
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(15)
                .setScoreThreshold(0.35f)
                .build()
            detector = ObjectDetector.createFromFileAndOptions(context, MODEL, opts)
        } catch (e: Throwable) {
            // A Task Library embrulha o erro real ("Error getting native address..."); mostra a causa
            var cause: Throwable = e
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            Log.e("DashLive", "detector não iniciou", e)
            error = "A detecção não pôde iniciar: " + (cause.message ?: cause.javaClass.simpleName)
        }
    }

    val available: Boolean get() = detector != null

    fun detect(bmp: Bitmap): List<Seen> {
        val d = detector ?: return emptyList()
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val out = ArrayList<Seen>()
        for (det in d.detect(TensorImage.fromBitmap(bmp))) {
            val cat = det.categories.firstOrNull() ?: continue
            val kind = when (cat.label.lowercase()) {
                "car" -> Kind.CAR
                "truck" -> Kind.TRUCK
                "bus" -> Kind.BUS
                "motorcycle" -> Kind.MOTORCYCLE
                "bicycle" -> Kind.BICYCLE
                "person" -> Kind.PERSON
                else -> continue
            }
            val bb = det.boundingBox
            val s = Seen(
                kind, cat.score,
                (bb.left / w).coerceIn(0f, 1f), (bb.top / h).coerceIn(0f, 1f),
                (bb.right / w).coerceIn(0f, 1f), (bb.bottom / h).coerceIn(0f, 1f),
            )
            if (kind != Kind.PERSON) {
                // lanternas: cantos de baixo da traseira (30% da largura de cada lado, 30–90% da altura)
                val x0 = (s.l * w).toInt(); val x1 = (s.r * w).toInt()
                val y0 = ((s.t + (s.b - s.t) * 0.3f) * h).toInt(); val y1 = ((s.t + (s.b - s.t) * 0.9f) * h).toInt()
                val side = ((x1 - x0) * 0.3f).toInt()
                if (side >= 4 && y1 - y0 >= 3) {
                    s.ambL = amber(bmp, x0, x0 + side, y0, y1)
                    s.ambR = amber(bmp, x1 - side, x1, y0, y1)
                }
            }
            out += s
        }
        return out
    }

    /** Fração dos pixels do retângulo que são âmbar forte (pisca aceso: laranja-amarelo, não vermelho nem branco). */
    private fun amber(bmp: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        val ax = x0.coerceIn(0, bmp.width - 1); val bx = x1.coerceIn(ax + 1, bmp.width)
        val ay = y0.coerceIn(0, bmp.height - 1); val by = y1.coerceIn(ay + 1, bmp.height)
        val w = bx - ax; val h = by - ay
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, ax, ay, w, h)
        var c = 0
        for (p in px) {
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (r >= 170 && g in 90..215 && b <= 120 && r - b >= 90 && g * 100 >= r * 45) c++
        }
        return c.toFloat() / px.size
    }

    fun close() {
        runCatching { detector?.close() }
        detector = null
    }

    companion object {
        const val MODEL = "efficientdet_lite0.tflite"
    }
}

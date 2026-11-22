package com.dashlive.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/** Algo visto no vídeo: tipo, confiança e caixa em coordenadas de 0 a 1 (esquerda, topo, direita, base). */
class Seen(val kind: Kind, val score: Float, val l: Float, val t: Float, val r: Float, val b: Float)

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
        try {
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(15)
                .setScoreThreshold(0.35f)
                .build()
            detector = ObjectDetector.createFromFileAndOptions(context, MODEL, opts)
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
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
            out += Seen(
                kind, cat.score,
                (bb.left / w).coerceIn(0f, 1f), (bb.top / h).coerceIn(0f, 1f),
                (bb.right / w).coerceIn(0f, 1f), (bb.bottom / h).coerceIn(0f, 1f),
            )
        }
        return out
    }

    fun close() {
        runCatching { detector?.close() }
        detector = null
    }

    companion object {
        const val MODEL = "efficientdet_lite0.tflite"
    }
}

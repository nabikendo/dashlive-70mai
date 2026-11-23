package com.dashlive.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface

/**
 * Câmera traseira do próprio celular, para testar a Estrada sem ligar a A200. Só no build de teste
 * (`--ez phoneCam true`): o vídeo entra no mesmo TextureView que o vídeo da A200 e segue o mesmo
 * caminho de detecção, então o que o app registra é o que ele registraria com a câmera do carro.
 */
class PhoneCamera(
    private val ctx: Context,
    private val texture: SurfaceTexture,
    private val onSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null

    /** Abre a câmera traseira e começa a mostrar o vídeo na textura. Exige a permissão de câmera. */
    @SuppressLint("MissingPermission")
    fun start() {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return onError("sem câmera traseira no celular")
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // tamanho de preview até 1280 px de largura, o maior que cabe
        val size = map?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width <= 1280 }?.maxByOrNull { it.width * it.height } ?: Size(640, 480)
        texture.setDefaultBufferSize(size.width, size.height)
        onSize(size.width, size.height)
        val t = HandlerThread("phone-cam").apply { start() }
        thread = t
        val h = Handler(t.looper)
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                createSession(cam, Surface(texture), h)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close() }
            override fun onError(cam: CameraDevice, error: Int) {
                cam.close()
                onError("câmera do celular falhou (código $error)")
            }
        }, h)
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: CameraDevice, surface: Surface, h: Handler) {
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
        cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                s.setRepeatingRequest(req, null, h)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                onError("sessão da câmera do celular falhou")
            }
        }, h)
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        thread?.quitSafely()
        thread = null
    }
}

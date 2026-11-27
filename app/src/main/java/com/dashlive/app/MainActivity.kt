package com.dashlive.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.dashlive.app.adas.AdasConfig
import com.dashlive.app.adas.AdasEngine
import com.dashlive.app.adas.AdasState
import com.dashlive.app.adas.LanePoly
import com.dashlive.app.adas.LaneSides
import com.dashlive.app.adas.Sensitivity
import com.dashlive.app.databinding.ActivityMainBinding
import java.io.File

/**
 * Vídeo ao vivo da 70mai A200 (DR2210, chip HiSilicon).
 * Endereço achado no app oficial: rtsp://192.168.0.1:554/livestream/12
 * (12 = canal principal; 13 = segundo canal).
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private enum class State { CONNECTING, LIVE, ERROR }

    private lateinit var b: ActivityMainBinding
    private var player: ExoPlayer? = null
    private val prefs by lazy { getSharedPreferences("dashlive", Context.MODE_PRIVATE) }
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetwork: Network? = null
    private var fullscreen = false
    /** Em tela cheia, o "voltar" do sistema sai da tela cheia em vez de fechar o app. */
    private val exitFullscreenOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullscreen(false)
    }
    /** Algum vídeo chegou desde o último (re)início? Separa "câmera ocupada" de "câmera fora do ar". */
    private var sawVideo = false
    /** O tablet está num Wi-Fi que não é a câmera (ex.: o de casa quando a câmera desliga). */
    private var offCameraWifi = false
    /** Muda a cada (re)início; descarta inícios atrasados que ainda esperavam a troca de lente. */
    private var playGen = 0
    /** Quedas da câmera principal com a segunda ligada (horários), para detectar câmera de um vídeo só. */
    private val dualDrops = ArrayDeque<Long>()
    /** Alterna UDP/TCP a cada falha, porque algumas câmeras só entregam por um deles. */
    private var autoTcp = false

    // Player nativo (H.264 + MediaCodec) é o principal; ExoPlayer fica como reserva.
    // Enquanto nenhum dos dois mostrou imagem, alterna entre eles a cada falha.
    private var useNative = true // o valor salvo é lido no onCreate
    private var engineProven = false
    private var nativePlayer: NativePlayer? = null
    private var nativeSurface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    /** Diagnóstico de gravação ligado (Ajustes → Diagnóstico); null = desligado. */
    @Volatile private var diagLog: DiagLog? = null
    /** Câmera do celular no lugar da A200 (só build de teste, `--ez phoneCam true`). */
    private var phoneCam: PhoneCamera? = null
    private var phoneCamMode = false
    /** Graus que a imagem da câmera do celular gira para ficar em pé (0, 90, 180, 270). */
    private var phoneRot = 0
    /** Contador para reduzir a detecção quando o aparelho esquenta. */
    private var thermalTick = 0

    // Câmera da direita (a outra do par frontal/traseira), sempre pelo player nativo
    private var rearPlayer: NativePlayer? = null
    private var rearSurface: Surface? = null
    private var rearW = 0
    private var rearH = 0

    // Simulação da estrada (detecção de objetos no vídeo da frente)
    private var roadMode = false
    @Volatile private var roadDetector: RoadDetector? = null
    private val detectExec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    // ADAS (só FCW por enquanto): usa as detecções do RoadDetector na própria detectExec.
    // Criados no onCreate (nunca em inicializador de campo: prefs/contexto ainda não existem).
    private lateinit var adasConfig: AdasConfig
    private var adas: AdasEngine? = null
    private val adasOn: Boolean get() = ::adasConfig.isInitialized && adasConfig.anyOn
    /** Velocidade do seu carro para o ADAS (OBD tem prioridade; GPS quando o OBD não está fresco). */
    @Volatile private var egoKmh: Float? = null
    @Volatile private var egoAt = 0L
    private fun egoSpeed(): Float? = egoKmh?.takeIf { SystemClock.elapsedRealtime() - egoAt < 3000 }
    private val detecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var detCount = 0
    private var detFps = 0
    private var detSecStart = 0L
    private var dumpNextFrame = false
    private val detectTick = object : Runnable {
        override fun run() {
            // roda com a Estrada aberta OU com o ADAS ligado (aí só detecção + som, sem desenho)
            if (!roadMode && !adasOn) return
            if (roadMode) {
                if (SystemClock.elapsedRealtime() - dayCheckedAt > 60_000) updateDay()
                // rua + limite de velocidade (aviso quando passa do limite)
                val limit = b.road3d.speedLimitKmh
                val speed = b.roadView.speedKmh
                val stopAhead = roadDetector?.stopSignSeenAt?.let { it > 0 && SystemClock.elapsedRealtime() - it < 2000 } == true
                val hot = if (thermalLevel() >= 3) "aparelho quente, detecção reduzida • " else ""
                val ahead = b.road3d.aheadInfo?.let { " • $it" } ?: ""
                val base = hot + (if (stopAhead) "PARE à frente • " else "") + (b.road3d.streetInfo ?: mapState ?: "") + ahead
                // avisos sonoros: passou do limite (um bipe) e radar que se aproxima (dois bipes)
                val nowMs = SystemClock.elapsedRealtime()
                if (speedAlarm.update(nowMs, speed, limit)) adas?.playSpeedAlert(radar = false)
                if (radarAlarm.update(b.road3d.nearestRadarM)) adas?.playSpeedAlert(radar = true)
                b.roadView.mapStatus = when {
                    limit == null -> base
                    speed != null && speed > limit + 3 -> "$base • ACIMA DO LIMITE ($limit km/h)"
                    else -> "$base • limite $limit km/h"
                }
                // teste: o carro "anda sozinho" pela rua do mapa; pede mais mapa quando sai da área
                if (roadDemo && routeSim == null && SystemClock.uptimeMillis() % 3000 < 90) b.road3d.carLatLon()?.let { feedMap(it.first, it.second) }
                // sem posição nova há 4 s (túnel, garagem): não mostra velocidade velha
                // (na rota de teste a velocidade vem da rota, não do GPS)
                if (gpsOn && !obdFresh && !fakeMap && !replaying && SystemClock.elapsedRealtime() - lastFixAt > 4000) b.roadView.speedKmh = null
            }
            if (roadDemo) {
                val t = SystemClock.uptimeMillis() / 1000f
                val scene = demoScene(t)
                if (roadMode) {
                    b.roadView.message = null
                    b.roadView.update(scene)
                    if (routeSim == null) b.roadView.speedKmh = 62f // com a rota, a velocidade vem da rota
                    // setas e faróis de mentira: esquerda 4 s, direita 4 s, nenhuma 4 s; farol alto a cada 12 s
                    val c = t % 12f
                    b.roadView.turnLeft = c < 4f
                    b.roadView.turnRight = c >= 4f && c < 8f
                    b.roadView.highBeam = t % 24f >= 12f
                    b.roadView.lowBeam = !b.roadView.highBeam
                }
                // demo do ADAS: mesma cena de mentira, a 62 km/h se não houver velocidade real
                if (adasOn) {
                    val ego = egoSpeed() ?: 62f
                    val lanes = demoLanes(t)
                    detectExec.execute {
                        val now = SystemClock.uptimeMillis()
                        adas?.onDetections(scene, 640, 360, ego, now)
                        adas?.onDemoLanes(lanes, ego, now)
                    }
                }
            } else {
                grabAndDetect()
            }
            handler.postDelayed(this, 80)
        }
    }

    private var pendingRelease: Updater.Release? = null
    private var downloadedApk: File? = null
    private var downloading = false

    private var ip: String
        get() = prefs.getString("ip", "")!!
        set(v) = prefs.edit().putString("ip", v).apply()
    /** Fonte de vídeo da tela grande e da direita (caminho RTSP na câmera). */
    private var mainPath: String
        get() = prefs.getString("mainPath", SOURCES[0].second)!!
        set(v) = prefs.edit().putString("mainPath", v).apply()
    private var rearPath: String
        get() = prefs.getString("rearPath", SOURCES[1].second)!!
        set(v) = prefs.edit().putString("rearPath", v).apply()
    /** Lente do vídeo ao vivo: a câmera tem um vídeo só e troca a lente por comando (ver CameraApi). */
    private var rearLens: Boolean
        get() = prefs.getBoolean("rearLens", false)
        set(v) = prefs.edit().putBoolean("rearLens", v).apply()
    /**
     * Segunda câmera ao lado: DESLIGADA de vez (23/11/2026). A A200 manda um vídeo por vez — com duas
     * sessões, uma fica sem imagem —, então a opção saiu dos Ajustes. O código do 2º vídeo fica parado.
     */
    private val dual = false

    /** Câmera de ré quando a seta liga (padrão: desligado). Ajustes → Câmera de ré. */
    private var autoRearOn: Boolean
        get() = prefs.getBoolean("autoRear", false)
        set(v) = prefs.edit().putBoolean("autoRear", v).apply()
    /** Segundos que a câmera de ré fica depois de apagar a seta (0, 3, 5 ou 10). */
    private var autoRearHold: Int
        get() = prefs.getInt("autoRearHold", 3)
        set(v) = prefs.edit().putInt("autoRearHold", v).apply()
    /** A câmera de ré está ativa agora por causa da seta (não muda a escolha salva em Câmeras). */
    private var autoRearActive = false
    /** Alternância frontal/traseira ligada e intervalo em segundos (sem reconectar). */
    private var altLens: Boolean
        get() = prefs.getBoolean("altLens", false)
        set(v) = prefs.edit().putBoolean("altLens", v).apply()
    private var altSeconds: Int
        get() = prefs.getInt("altSeconds", 5)
        set(v) = prefs.edit().putInt("altSeconds", v).apply()
    /** Lente que a alternância está mostrando agora (true = traseira). */
    private var altPhase = false
    /** Lente que a câmera deve ter agora: a escolhida em Câmeras ou a de ré pela seta. */
    private val rearNow: Boolean get() = rearLens || autoRearActive || (altLens && altPhase)

    /** Troca a lente pela seta (sem mudar a escolha salva). Desligar volta para a lente escolhida. */
    private fun setAutoRear(on: Boolean) {
        if (autoRearActive == on) return
        autoRearActive = on
        val ip = camIp
        val rear = rearNow
        Thread { CameraApi.setLens(ip, rear) }.start()
        b.roadView.rearView = rear
        restartDetect()
    }

    /** Chamado quando a seta liga ou desliga (OBD2). Ao apagar, espera o tempo escolhido. */
    private fun onTurnSignal(active: Boolean) {
        if (!autoRearOn) return
        handler.removeCallbacks(autoRearOff)
        if (active) setAutoRear(true)
        else if (autoRearActive) handler.postDelayed(autoRearOff, autoRearHold * 1000L)
    }

    private val autoRearOff = Runnable { if (!turnL && !turnR) setAutoRear(false) }
    private var forceTcp: Boolean
        get() = prefs.getBoolean("tcp", false)
        set(v) = prefs.edit().putBoolean("tcp", v).apply()
    private var customUrl: String
        get() = prefs.getString("custom", "")!!
        set(v) = prefs.edit().putString("custom", v).apply()

    /** IP digitado nos Ajustes; vazio = usa o gateway do Wi-Fi da câmera (automático). */
    private var detectedIp: String? = null
    private val camIp: String
        get() = ip.ifBlank { detectedIp ?: "192.168.0.1" }

    private val streamUrl: String
        get() = customUrl.ifBlank { "rtsp://$camIp:554$mainPath" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        useNative = prefs.getBoolean("native", true)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        hideSystemBars()
        phone = resources.configuration.smallestScreenWidthDp < 600 ||
            (BuildConfig.DEBUG && intent.getBooleanExtra("phoneUi", false))
        applyCompactLayout()
        for (i in 0 until b.dock.childCount) (b.dock.getChildAt(i) as? LinearLayout)?.let { dockFeel(it) }

        b.btnChannel.setOnClickListener { pickCamera() }
        b.rearCard.setOnClickListener { pickSource(main = false) }
        b.rearTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (rearPlayer == null) startRear()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitRear()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopRear()
                rearSurface?.release()
                rearSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        b.rearTexture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitRear() }
        b.btnReload.setOnClickListener { startPlayback() }
        onBackPressedDispatcher.addCallback(this, exitFullscreenOnBack)
        b.btnFullscreen.setOnClickListener { setFullscreen(true) }
        b.btnExitFs.setOnClickListener { setFullscreen(false) }
        b.playerView.setOnClickListener { if (fullscreen) setFullscreen(false) }
        b.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (useNative && nativePlayer == null) startPlayback()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitVideo()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopNative()
                nativeSurface?.release()
                nativeSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        b.textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        b.btnSettings.setOnClickListener { showSettings() }
        b.btnAuto.setOnClickListener { openAndroidAuto() }
        b.btnRoad.setOnClickListener { toggleRoad() }
        // segurar o botão Estrada: reproduz uma viagem gravada (ou para a reprodução)
        b.btnRoad.setOnLongClickListener { showTrips(); true }
        // câmera do celular: pelo teste (--ez phoneCam true) ou pelo menu Câmeras (guardado até trocar)
        phoneCamMode = (BuildConfig.DEBUG && intent.getBooleanExtra("phoneCam", false)) || prefs.getBoolean("phoneCamTest", false)
        if (phoneCamMode) useNative = true // o detector e o vídeo andam pelo caminho nativo
        roadDemo = BuildConfig.DEBUG && intent.getBooleanExtra("roadDemo", false)
        // teste da rua do mapa sem sair do lugar: --ef mapLat -23.56 --ef mapLon -46.65 --ef mapBearing 310
        fakeMap = roadDemo && intent.hasExtra("mapLat")
        if (fakeMap) {
            val la = intent.getFloatExtra("mapLat", 0f).toDouble()
            val lo = intent.getFloatExtra("mapLon", 0f).toDouble()
            b.road3d.onLocation(la, lo, intent.getFloatExtra("mapBearing", 0f))
            feedMap(la, lo)
        }
        // rota de teste (só build de teste): anda pelo rota.geojson a 60 km/h, com o mapa baixado
        // adb push rota.geojson /sdcard/Android/data/com.dashlive.app.debug/files/rota.geojson
        if (roadDemo && intent.getBooleanExtra("routeDemo", false)) {
            routeSim = runCatching {
                RouteSim.fromGeoJson(File(getExternalFilesDir(null), "rota.geojson").readText())
            }.onFailure { toast("Rota não carregada: ${it.message}") }.getOrNull()
            if (routeSim != null) fakeMap = true
        }
        // câmera do celular (teste): abre no modo 2D com o vídeo, em tamanho grande
        b.roadView.style = if (phoneCamMode || prefs.getString("roadStyle", "scene") == "video") RoadView.Style.VIDEO else RoadView.Style.SCENE
        b.roadView.onStyleChange = { s ->
            prefs.edit().putString("roadStyle", if (s == RoadView.Style.VIDEO) "video" else "scene").apply()
            fitVideo()
            updateRoad3d()
        }
        b.road3d.source = b.roadView
        b.roadView.onDayButton = {
            val next = when (prefs.getString("roadDay", "auto")) { "auto" -> "day"; "day" -> "night"; else -> "auto" }
            prefs.edit().putString("roadDay", next).apply()
            updateDay()
        }
        b.road3d.onError = { msg ->
            road3dOk = false
            updateRoad3d()
            toast("A Estrada 3D não abriu neste aparelho ($msg). Usando a cena desenhada.")
        }
        b.btnDiag.setOnClickListener { runDiagnostics() }
        b.btnUpdate.setOnClickListener {
            // celular: não há o cartão da direita; mostra a versão nova num diálogo
            val rel = pendingRelease
            if (phone && rel != null) {
                AlertDialog.Builder(this).setTitle(b.updateTitle.text)
                    .setMessage(rel.notes.ifBlank { "Melhorias e correções." })
                    .setPositiveButton("Baixar e instalar") { _, _ -> toast("Baixando a atualização…"); onUpdateButton() }
                    .setNegativeButton("Depois", null).show()
            } else checkForUpdate(manual = true)
        }
        b.btnUpdateNow.setOnClickListener { onUpdateButton() }
        if (BuildConfig.DEBUG) {
            b.btnUpdate.visibility = View.GONE
            b.updateCard.visibility = View.GONE
        }
        b.carCard.setOnClickListener { showObdDialog() }
        adasConfig = AdasConfig(prefs)
        adas = AdasEngine(this, adasConfig).also { e -> e.listener = { r -> b.adasOverlay.show(r) } }
        if (obdAddr.isNotBlank()) showCarStatus(obdName, false)
        refreshInfo()
        handler.post { showLastCrash() }
    }

    override fun onDestroy() {
        super.onDestroy()
        b.road3d.destroy()
        adas?.close()
        adas = null
        roadDetector?.close()
        roadDetector = null
        detectExec.shutdown()
    }

    override fun onStart() {
        super.onStart()
        if (routeSim != null) {
            routeStartAt = SystemClock.elapsedRealtime()
            handler.removeCallbacks(routeTick)
            handler.post(routeTick)
        }
        restartDetect()
        if ((roadMode || adasOn) && !roadDemo) startSpeed()
        updateRoad3d()
        startObd()
        bindToCameraWifi()
        if (!BuildConfig.DEBUG && pendingRelease == null) checkForUpdate(manual = false)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        // a câmera guarda a última lente: se a de ré estava ligada pela seta, volta a escolhida
        if (autoRearActive) {
            autoRearActive = false
            val ip = camIp
            val rear = rearNow
            Thread { CameraApi.setLens(ip, rear) }.start()
        }
        releasePlayer()
        stopSpeed()
        stopObd()
        b.road3d.stop()
        wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        wifiCallback = null
        cm.bindProcessToNetwork(null)
    }

    // ---------------------------------------------------------------- registro de quedas

    /** Guarda o erro de uma queda para mostrar na próxima abertura. */
    private fun installCrashLogger() {
        val f = File(filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { f.writeText("DashLive ${BuildConfig.VERSION_NAME}\n" + android.util.Log.getStackTraceString(e)) }
            prev?.uncaughtException(t, e)
        }
    }

    private fun showLastCrash() {
        val f = File(filesDir, "crash.txt")
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (text.isBlank()) return
        val pad = (20 * resources.displayMetrics.density).toInt()
        val tv = android.widget.TextView(this).apply {
            this.text = text.take(3000)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(getColor(R.color.text))
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("O app fechou na última vez")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Copiar") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("erro", text))
                toast("Copiado")
            }
            .show()
    }

    // ---------------------------------------------------------------- vídeo

    /**
     * O Wi-Fi da câmera não tem internet; sem isto o Android pode mandar o
     * tráfego por outra rede e o vídeo nunca abre.
     */
    private fun bindToCameraWifi() {
        setState(State.CONNECTING, "Procurando a câmera", "Conecte o aparelho ao Wi-Fi 70mai Dash Cam A200-…")
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // O pedido casa com QUALQUER Wi-Fi (inclusive o de casa, quando a câmera desliga):
                // confirma que é a câmera antes de usar o gateway como endereço dela.
                Thread {
                    val gw = gatewayOf(network)
                    val manual = ip.isNotBlank() || customUrl.isNotBlank()
                    val isCam = manual || (gw != null && CameraApi.isCamera(network, gw))
                    handler.post { if (isCam) onCameraWifi(network, gw) else onOtherWifi(network) }
                }.start()
            }

            override fun onLost(network: Network) {
                if (network == wifiNetwork) {
                    wifiNetwork = null
                    cm.bindProcessToNetwork(null)
                    handler.post {
                        handler.removeCallbacksAndMessages(RETRY)
                        releasePlayer()
                        setState(
                            State.ERROR, "Câmera desconectada",
                            "Conecte o aparelho ao Wi-Fi 70mai Dash Cam A200-…"
                        )
                    }
                }
            }
        }
        wifiCallback = cb
        cm.requestNetwork(request, cb)
        // Alguns aparelhos não chamam onAvailable: tenta mesmo assim depois de 4 s
        handler.postDelayed({ if (player == null && nativePlayer == null) startPlayback() }, 4000)
    }

    private fun onCameraWifi(network: Network, gateway: String?) {
        offCameraWifi = false
        b.statusCard.setOnClickListener(null)
        if (roadMode && !roadDemo) b.roadView.message = "Procurando veículos."
        b.overlay.setOnClickListener(null)
        b.overlay.isClickable = false
        wifiNetwork = network
        cm.bindProcessToNetwork(network)
        detectedIp = gateway
        refreshInfo()
        startPlayback()
    }

    /** Wi-Fi que não é a câmera: não adianta tentar o vídeo (antes ficava batendo no roteador de casa). */
    private fun onOtherWifi(network: Network) {
        if (phoneCamMode) return // teste com a câmera do celular: não há Wi-Fi da câmera a procurar
        if (wifiNetwork != null && wifiNetwork != network) return // a câmera continua conectada
        offCameraWifi = true
        if (roadMode && !roadDemo) b.roadView.message = null
        playGen++ // descarta inícios que ainda esperavam a troca de lente
        wifiNetwork = null
        cm.bindProcessToNetwork(null)
        handler.removeCallbacksAndMessages(RETRY)
        releasePlayer()
        setState(
            State.ERROR, "Fora do Wi-Fi da câmera",
            "O aparelho está em outra rede Wi-Fi (o Android prefere a que tem internet). " +
                "Toque aqui para escolher o Wi-Fi 70mai Dash Cam A200-…"
        )
        // Toque no aviso abre o painel de Wi-Fi do Android por cima do app
        b.overlay.setOnClickListener { openWifiPanel() }
        b.statusCard.setOnClickListener { openWifiPanel() }
    }

    private fun openWifiPanel() {
        val panel = if (Build.VERSION.SDK_INT >= 29) android.provider.Settings.Panel.ACTION_WIFI
        else android.provider.Settings.ACTION_WIFI_SETTINGS
        runCatching { startActivity(android.content.Intent(panel)) }
    }

    /** A câmera é o roteador da própria rede Wi-Fi: seu IP é o gateway. */
    private fun gatewayOf(n: Network): String? {
        val lp = cm.getLinkProperties(n) ?: return null
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }
            ?.gateway?.hostAddress?.let { if (it != "0.0.0.0") return it }
        if (Build.VERSION.SDK_INT >= 30) {
            lp.dhcpServerAddress?.hostAddress?.let { return it }
        }
        return null
    }

    private fun startPlayback() {
        handler.removeCallbacksAndMessages(RETRY)
        if (offCameraWifi && !phoneCamMode) return // espera o Wi-Fi da câmera voltar (onAvailable)
        releasePlayer()
        if (phoneCamMode) { startPhoneCam(); return }
        sawVideo = false
        val gen = ++playGen
        if (customUrl.isNotBlank()) { startStreams(); return }
        // Garante a lente escolhida (o app oficial pode ter trocado) antes de abrir o vídeo
        setState(State.CONNECTING, "Conectando", "${lensName()} • $camIp")
        val ip = camIp
        val rear = rearNow
        Thread {
            CameraApi.setLens(ip, rear)
            runOnUiThread { if (gen == playGen) startStreams() }
        }.start()
        // alternância ligada: retoma o revezamento (ex.: depois de voltar ao app)
        if (altLens && !roadMode) { handler.removeCallbacks(altTick); handler.postDelayed(altTick, altSeconds * 1000L) }
    }

    private fun startStreams() {
        if (offCameraWifi) return
        if (useNative) startNative() else startExo()
        // A segunda câmera entra um instante depois, para a principal abrir primeiro
        handler.postAtTime({ startRear() }, REAR, SystemClock.uptimeMillis() + 1200)
    }

    private fun swapCameras() {
        val m = mainPath
        mainPath = rearPath
        rearPath = m
        refreshInfo()
        startPlayback()
    }

    /** Botão Câmeras: frontal ou traseira (troca a lente na câmera); endereços RTSP ficam no "avançado". */
    private fun pickCamera() {
        // getdvrstate diz se a traseira está ligada na câmera
        val rearOff = dvr?.rearConnected == false
        val items = arrayOf(
            "Câmera frontal",
            if (rearOff) "Câmera traseira (desconectada)" else "Câmera traseira",
            "Endereço de vídeo (avançado)",
            "Celular: câmera traseira (teste)",
            if (rearOff) "Alternar frontal e traseira (traseira desconectada)" else "Alternar frontal e traseira",
        )
        val cur = when {
            altLens -> 4
            phoneCamMode -> 3
            customUrl.isNotBlank() -> -1
            rearLens -> 1
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("Câmera")
            .setSingleChoiceItems(items, cur) { d, which ->
                d.dismiss()
                if (which == 2) { pickSource(main = true); return@setSingleChoiceItems }
                if (rearOff && (which == 1 || which == 4)) { toast("A câmera traseira não está conectada na A200"); return@setSingleChoiceItems }
                if (which == 3) { usePhoneCam(); return@setSingleChoiceItems }
                if (which == 4) { pickAltInterval(); return@setSingleChoiceItems }
                stopAlternating()
                // sai da câmera do celular, se estava nela
                if (phoneCamMode) { phoneCamMode = false; prefs.edit().putBoolean("phoneCamTest", false).apply() }
                rearLens = which == 1
                customUrl = ""
                refreshInfo()
                startPlayback()
                if (roadMode) { roadMode = false; toggleRoad() } // reaplica a Estrada para a lente nova
                else restartDetect() // ADAS: só com a lente frontal
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /** Intervalo da alternância entre frontal e traseira (sem reconectar: a troca leva ~0,4 s). */
    private fun pickAltInterval() {
        val secs = intArrayOf(3, 5, 10)
        val names = secs.map { "a cada $it s" }.toTypedArray()
        val cur = secs.indexOf(altSeconds).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Alternar frontal e traseira")
            .setSingleChoiceItems(names, cur) { d, which ->
                d.dismiss()
                altSeconds = secs[which]
                startAlternating()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Liga a alternância: a frente e a traseira se revezam na mesma transmissão. Não funciona na Estrada. */
    private fun startAlternating() {
        if (phoneCamMode || customUrl.isNotBlank()) { toast("Alternância só com a A200 (sem Endereço personalizado)"); return }
        altLens = true
        altPhase = rearLens
        refreshInfo()
        startPlayback() // a transmissão começa na lente escolhida; depois a alternância segue
        handler.removeCallbacks(altTick)
        handler.postDelayed(altTick, altSeconds * 1000L)
    }

    /** Desliga a alternância e volta para a lente salva em Câmeras. */
    private fun stopAlternating() {
        if (!altLens) return
        altLens = false
        handler.removeCallbacks(altTick)
        val ip = camIp
        val rear = rearNow
        Thread { CameraApi.setLens(ip, rear) }.start()
        refreshInfo()
    }

    /** Troca de lente a cada [altSeconds] s, sem reconectar. */
    private val altTick = object : Runnable {
        override fun run() {
            if (!altLens) return
            altPhase = !altPhase
            val ip = camIp
            val rear = altPhase
            Thread { CameraApi.setLens(ip, rear) }.start()
            handler.postDelayed(this, altSeconds * 1000L)
        }
    }

    /** Troca para a câmera traseira do celular (teste); a escolha fica salva até voltar para a A200. */
    private fun usePhoneCam() {
        phoneCamMode = true
        useNative = true // o detector e o vídeo andam pelo caminho nativo
        prefs.edit().putBoolean("phoneCamTest", true).apply()
        customUrl = ""
        refreshInfo()
        startPlayback()
        if (roadMode) { roadMode = false; toggleRoad() } // reaplica a Estrada para a fonte nova
        else restartDetect()
    }

    /** Último estado lido da câmera (`getdvrstate.cgi`); null = não sabe. */
    @Volatile private var dvr: CameraApi.DvrState? = null

    private val dvrTick = object : Runnable {
        override fun run() {
            if (phoneCamMode || customUrl.isNotBlank() || offCameraWifi) return
            val ip = camIp
            Thread {
                val st = CameraApi.dvrState(ip)
                runOnUiThread { applyDvr(st) }
            }.start()
            handler.postDelayed(this, 10_000L)
        }
    }

    /** Mostra se a câmera está gravando e corrige a lente se ela estiver diferente da escolhida. */
    private fun applyDvr(st: CameraApi.DvrState?) {
        dvr = st
        b.liveText.text = when {
            st == null -> "AO VIVO"
            !st.recording -> "AO VIVO • SEM GRAVAR"
            else -> "AO VIVO • REC"
        }
        // o app oficial (ou outro aparelho) trocou a lente: volta para a escolhida no DashLive.
        // Na alternância a lente muda de propósito; sem traseira conectada não há o que trocar.
        if (st != null && !altLens && st.rearConnected && st.rearActive != rearNow) {
            val ip = camIp
            val rear = rearNow
            android.util.Log.i("DashLive", "lente diferente na câmera; voltando para a ${if (rear) "traseira" else "frontal"}")
            Thread { CameraApi.setLens(ip, rear) }.start()
        }
    }

    private fun lensName() = when {
        altLens -> "Alternando frontal e traseira (a cada $altSeconds s)"
        rearLens -> "Traseira"
        else -> "Frontal"
    }

    /** Nome da tela grande: a lente e, se não for o caminho padrão, o caminho RTSP. */
    private fun mainName() = when {
        customUrl.isNotBlank() -> "Endereço personalizado"
        mainPath == SOURCES[0].second -> lensName()
        else -> "${lensName()} • ${sourceName(mainPath)}"
    }

    /** Lista os caminhos RTSP da câmera (na A200 todos entregam a lente ativa). */
    private fun pickSource(main: Boolean) {
        val names = SOURCES.map { it.first }.toMutableList()
        if (dual) names += "Trocar a tela grande com a da direita"
        val cur = SOURCES.indexOfFirst { it.second == (if (main) mainPath else rearPath) }
        AlertDialog.Builder(this)
            .setTitle(if (main) "Câmera da tela grande" else "Câmera da direita")
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                d.dismiss()
                if (which >= SOURCES.size) { swapCameras(); return@setSingleChoiceItems }
                if (main) mainPath = SOURCES[which].second else rearPath = SOURCES[which].second
                customUrl = ""
                refreshInfo()
                startPlayback()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun startRear() {
        handler.removeCallbacksAndMessages(REAR)
        stopRear()
        b.rearCard.visibility = if (dual) View.VISIBLE else View.GONE
        if (!dual) return
        val st = b.rearTexture.surfaceTexture
        if (!b.rearTexture.isAvailable || st == null) return // o listener reinicia quando ficar pronto
        b.rearOverlay.visibility = View.VISIBLE
        b.rearOverlay.text = "Conectando…"
        val surface = rearSurface ?: Surface(st).also { rearSurface = it }
        val p = NativePlayer(
            surface, "rtsp://$camIp:554$rearPath",
            onSize = { w, h -> runOnUiThread { rearW = w; rearH = h; fitRear() } },
            onLive = { runOnUiThread { sawVideo = true; b.rearOverlay.visibility = View.GONE } },
            onError = { msg ->
                runOnUiThread {
                    stopRear()
                    b.rearOverlay.visibility = View.VISIBLE
                    b.rearOverlay.text = "Sem sinal desta câmera\n$msg"
                    handler.postAtTime({ startRear() }, REAR, SystemClock.uptimeMillis() + 5000)
                }
            },
        )
        rearPlayer = p
        p.start()
    }

    private fun stopRear() {
        rearPlayer?.stop()
        rearPlayer = null
    }

    private fun startNative() {
        b.playerView.visibility = View.GONE
        b.textureView.visibility = View.VISIBLE
        refreshInfo()
        setState(State.CONNECTING, "Conectando", "$streamUrl  (nativo)")
        val st = b.textureView.surfaceTexture
        if (!b.textureView.isAvailable || st == null) return // o listener reinicia quando ficar pronto
        val surface = nativeSurface ?: Surface(st).also { nativeSurface = it }
        val p = NativePlayer(
            surface, streamUrl,
            onSize = { w, h -> runOnUiThread { videoW = w; videoH = h; b.roadView.setVideoSize(w, h); b.adasOverlay.setVideoSize(w, h); fitVideo() } },
            onLive = {
                runOnUiThread {
                    sawVideo = true
                    markWorked()
                    setState(State.LIVE, "Ao vivo", "${mainName()} • $camIp")
                }
            },
            onError = { msg -> runOnUiThread { onNativeFailed(msg) } },
        )
        nativePlayer = p
        p.start()
    }

    private fun onNativeFailed(msg: String) {
        stopNative()
        if (!engineProven) useNative = false
        if (dual && singleStreamCamera()) return
        scheduleRetry("Sem sinal da câmera: $msg")
    }

    /**
     * A A200 entrega um vídeo por vez: com a segunda câmera ligada, a sessão dela derruba a da
     * principal (que fica sem dados) e as duas caem em revezamento para sempre. Se a principal cai
     * duas vezes em 1 minuto depois de algum vídeo ter chegado (ou seja, a câmera está lá), a
     * segunda câmera é desligada e a principal volta sozinha. Devolve true se desligou.
     */
    private fun singleStreamCamera(): Boolean {
        if (!sawVideo) return false
        val now = SystemClock.uptimeMillis()
        dualDrops.addLast(now)
        while (dualDrops.isNotEmpty() && now - dualDrops.first() > 60_000) dualDrops.removeFirst()
        if (dualDrops.size < 2) return false
        dualDrops.clear()
        startPlayback()
        return true
    }

    private fun stopNative() {
        nativePlayer?.stop()
        nativePlayer = null
    }

    /**
     * Câmera do celular (só build de teste, `--ez phoneCam true`): o vídeo da traseira do aparelho
     * vai para o mesmo TextureView da A200, então a detecção, o FCW e a Estrada rodam como no carro.
     */
    private fun startPhoneCam() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        // o TextureView começa escondido: sem ficar visível, a superfície não existe
        b.playerView.visibility = View.GONE
        b.textureView.visibility = View.VISIBLE
        val st = b.textureView.surfaceTexture
        if (!b.textureView.isAvailable || st == null) return // o listener reinicia quando ficar pronto
        setState(State.LIVE, "Câmera do celular (teste)", "traseira do aparelho")
        sawVideo = true
        val displayDeg = windowManager.defaultDisplay.rotation * 90
        phoneCam = PhoneCamera(
            this, st, displayDeg,
            onRotation = { r -> runOnUiThread { phoneRot = r; fitVideo() } },
            rotOverride = intent.getIntExtra("phoneRot", -1).takeIf { it >= 0 },
            onSize = { w, h ->
                runOnUiThread {
                    // tamanho "de pé": troca largura e altura quando a imagem está girada 90°
                    val sw = phoneRot % 180 == 90
                    videoW = if (sw) h else w; videoH = if (sw) w else h
                    b.roadView.setVideoSize(videoW, videoH); b.adasOverlay.setVideoSize(videoW, videoH); fitVideo()
                }
            },
            onError = { msg -> runOnUiThread { toast(msg) } },
        ).also { it.start() }
    }

    /** Nome amigável da fonte de vídeo. */
    private fun sourceName(path: String) = SOURCES.firstOrNull { it.second == path }?.first ?: path

    private fun markWorked() {
        engineProven = true
        prefs.edit().putBoolean("native", useNative).apply()
    }

    /** Mantém a proporção do vídeo dentro do cartão (barras pretas se precisar). */
    /** Na Estrada "sobre o vídeo" o vídeo preenche o cartão (o RoadView usa a mesma regra). */
    private fun fitVideo() = fit(
        b.textureView, videoW, videoH,
        // câmera do celular: preenche a tela (corta as laterais) em vez de deixar faixas pretas
        cover = (roadMode && b.roadView.style == RoadView.Style.VIDEO) || phoneCamMode,
        rot = if (phoneCamMode) phoneRot else 0,
    )

    private fun fitRear() = fit(b.rearTexture, rearW, rearH)

    private fun fit(v: TextureView, srcW: Int, srcH: Int, cover: Boolean = false, rot: Int = 0) {
        if (srcW <= 0 || srcH <= 0 || v.width == 0 || v.height == 0) return
        val vw = v.width.toFloat()
        val vh = v.height.toFloat()
        val scale = RoadView.videoScale(srcW, srcH, vw, vh, cover)
        val m = Matrix()
        m.setScale(srcW * scale / vw, srcH * scale / vh, vw / 2, vh / 2)
        // câmera do celular girada (90°, 180° ou 270°): gira a imagem na tela para ficar em pé
        if (rot != 0) m.postRotate(rot.toFloat(), vw / 2, vh / 2)
        v.setTransform(m)
    }

    private fun startExo() {
        b.textureView.visibility = View.GONE
        b.playerView.visibility = View.VISIBLE
        refreshInfo()
        setState(State.CONNECTING, "Conectando", streamUrl + if (forceTcp || autoTcp) "  (TCP)" else "  (UDP)")

        // Buffers curtos = menos atraso no ao vivo
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 250, 250)
            .build()

        val p = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(forceTcp || autoTcp)
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(streamUrl))

        p.setMediaSource(source)
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> setState(State.CONNECTING, "Carregando", streamUrl)
                    Player.STATE_READY -> { markWorked(); setState(State.LIVE, "Ao vivo", "${mainName()} • $camIp") }
                    Player.STATE_ENDED -> scheduleRetry("Transmissão encerrada")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                autoTcp = !autoTcp
                if (!engineProven) useNative = true
                val why = (error.cause?.message ?: error.message).orEmpty().take(110)
                scheduleRetry("Sem sinal da câmera (${error.errorCodeName})\n$why")
            }
        })
        p.prepare()
        b.playerView.player = p
        player = p
    }

    private fun scheduleRetry(msg: String) {
        setState(State.ERROR, msg, "Tentando de novo em 3 s…")
        handler.postAtTime({ startPlayback() }, RETRY, SystemClock.uptimeMillis() + 3000)
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(REAR)
        stopRear()
        stopNative()
        phoneCam?.close()
        phoneCam = null
        b.playerView.player = null
        player?.release()
        player = null
    }

    // ---------------------------------------------------------------- interface

    private fun setState(state: State, title: String, subtitle: String) {
        b.title.text = title
        b.subtitle.text = subtitle
        b.dot.setBackgroundResource(
            when (state) {
                State.LIVE -> R.drawable.bg_ok_dot
                State.CONNECTING -> R.drawable.bg_warn_dot
                State.ERROR -> R.drawable.bg_live_dot
            }
        )
        val live = state == State.LIVE
        b.livePill.visibility = if (live) View.VISIBLE else View.GONE
        // ao vivo com a A200: pergunta à câmera se está gravando e qual lente está ativa (a cada 10 s)
        handler.removeCallbacks(dvrTick)
        if (live && !phoneCamMode && customUrl.isBlank()) handler.post(dvrTick) else applyDvr(null)
        b.overlay.visibility = if (live || (roadMode && !phone)) View.GONE else View.VISIBLE
        b.overlay.text = "$title\n$subtitle"
    }

    private fun refreshInfo() {
        b.mainLabel.text = mainName()
        b.rearLabel.text = sourceName(rearPath)
        b.valChannel.text = mainName()
    }

    /** Endereço, motor de vídeo e versão: ficam em Ajustes → Sobre (não poluem a tela principal). */
    private fun aboutLines(): List<Pair<String, String>> = listOf(
        "Endereço" to (if (customUrl.isBlank()) camIp else "personalizado"),
        "Motor de vídeo" to when {
            useNative -> "Nativo / TCP"
            forceTcp || autoTcp -> "ExoPlayer / TCP"
            else -> "ExoPlayer / UDP"
        },
        "Versão do app" to BuildConfig.VERSION_NAME,
    )

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        exitFullscreenOnBack.isEnabled = on
        val v = if (on) View.GONE else View.VISIBLE
        b.sidePanel.visibility = if (phone) View.GONE else v
        b.dock.visibility = v
        b.mainLabel.visibility = if (on || phone) View.GONE else View.VISIBLE
        b.btnExitFs.visibility = if (on) View.VISIBLE else View.GONE
        b.roadView.toggleLift = if (on) 72f * resources.displayMetrics.density else 0f
        val pad = if (on) 0 else ((if (phone) 6 else 12) * resources.displayMetrics.density).toInt()
        b.main.setPadding(pad, pad, pad, pad)
        b.videoCard.setBackgroundColor(getColor(R.color.video_bg))
        if (!on) b.videoCard.setBackgroundResource(R.drawable.bg_video)
    }

    // ---------------------------------------------------------------- celular e navegação

    /**
     * Celular (tela menor que 600 dp, ex.: S24 FE): menos distrações. Só o vídeo/Estrada e uma barra
     * fina com os ícones essenciais (Câmeras, Reconectar, Tela cheia, Estrada, Ajustes). O painel da
     * direita some; OBD, diagnóstico, atualização e Android Auto ficam nos Ajustes. No build de teste
     * dá para ver no tablet: `--ez phoneUi true`.
     */
    private var phone = false

    private fun applyCompactLayout() {
        if (!phone) return
        val d = resources.displayMetrics.density
        b.sidePanel.visibility = View.GONE
        b.mainLabel.visibility = View.GONE
        (b.dock.layoutParams as LinearLayout.LayoutParams).apply { width = (60 * d).toInt(); marginEnd = (6 * d).toInt() }
        for (v in listOf(b.btnAuto, b.btnDiag, b.btnUpdate)) v.visibility = View.GONE
        // só ícones (o nome vai para a descrição de acessibilidade, que já existe)
        for (i in 0 until b.dock.childCount) {
            val btn = b.dock.getChildAt(i) as? LinearLayout ?: continue
            for (k in 0 until btn.childCount) (btn.getChildAt(k) as? TextView)?.visibility = View.GONE
        }
        b.clock.textSize = 14f
        val pad = (6 * d).toInt()
        b.main.setPadding(pad, pad, pad, pad)
    }

    /**
     * Toque nos botões da barra: vibração curta e o botão "afunda" um pouco enquanto pressionado
     * (resposta imediata, antes de a ação terminar).
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun dockFeel(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start()
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    /** Mensagem de estado em cima da Estrada: pílula pequena embaixo, para não cobrir a cena. */
    private fun styleOverlay() {
        val d = resources.displayMetrics.density
        val lp = b.overlay.layoutParams as android.widget.FrameLayout.LayoutParams
        if (roadMode) {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (84 * d).toInt()
            b.overlay.textSize = 13f
            b.overlay.maxLines = 2
            b.overlay.setBackgroundResource(R.drawable.bg_pill)
            b.overlay.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        } else {
            lp.gravity = android.view.Gravity.CENTER
            lp.bottomMargin = 0
            b.overlay.textSize = 18f
            b.overlay.maxLines = Int.MAX_VALUE
            b.overlay.background = null
            b.overlay.setPadding(0, 0, 0, 0)
        }
        b.overlay.layoutParams = lp
    }

    /** "Outros" nos Ajustes: o que no celular sai da tela principal. */
    private fun addOtherActions(box: LinearLayout, dismiss: () -> Unit) {
        val d = resources.displayMetrics.density
        section(box, "Outros")
        val row = android.widget.GridLayout(this).apply { columnCount = 2 }
        fun chip(label: String, act: () -> Unit) {
            row.addView(android.widget.Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener { dismiss(); act() }
            }, android.widget.GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(0, (4 * d).toInt(), (8 * d).toInt(), 0)
            })
        }
        chip("Carro (OBD2)…") { showObdDialog() }
        chip("Diagnóstico") { runDiagnostics() }
        chip("Verificar atualização") { checkForUpdate(manual = true) }
        chip("Android Auto") { openAndroidAuto() }
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    /** Título de seção dos Ajustes. */
    private fun section(box: LinearLayout, title: String) {
        val d = resources.displayMetrics.density
        box.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(getColor(R.color.primary))
            setPadding(0, ((if (box.childCount == 0) 4 else 22) * d).toInt(), 0, (4 * d).toInt())
        })
    }

    private fun showSettings() {
        val d = resources.displayMetrics.density
        val pad = (22 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, (8 * d).toInt())
        }
        // rola no celular (a lista ficou maior que a tela deitada)
        val scroll = android.widget.ScrollView(this).apply { addView(box) }

        section(box, "Assistência ao motorista")
        val fcwBox = CheckBox(this).apply {
            text = "Aviso de colisão frontal (acima de 20 km/h)"
            isChecked = adasConfig.fcwOn
        }
        val ldwBox = CheckBox(this).apply {
            text = "Aviso de saída de faixa (acima de 30 km/h)"
            isChecked = adasConfig.ldwOn
        }
        box.addView(fcwBox); box.addView(ldwBox)
        box.addView(TextView(this).apply {
            text = "Experimental: só com a câmera frontal. É uma ajuda, não um sistema de segurança."
            textSize = 13f
            alpha = 0.7f
        })
        val sensGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val sensIds = Sensitivity.values().associateWith { View.generateViewId() }
        for ((s, id) in sensIds) {
            sensGroup.addView(RadioButton(this).apply {
                this.id = id
                text = when (s) { Sensitivity.LOW -> "Baixa"; Sensitivity.MEDIUM -> "Média"; Sensitivity.HIGH -> "Alta" }
            })
        }
        sensGroup.check(sensIds.getValue(adasConfig.sensitivity))
        val volLabel = TextView(this).apply { text = "Volume do aviso: ${adasConfig.volume}" }
        val volBar = SeekBar(this).apply {
            max = 100
            progress = adasConfig.volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { volLabel.text = "Volume do aviso: $p" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(TextView(this).apply { text = "Sensibilidade"; setPadding(0, (10 * d).toInt(), 0, 0) })
        box.addView(sensGroup); box.addView(volLabel); box.addView(volBar)

        section(box, "Câmera de ré")
        val autoRearBox = CheckBox(this).apply {
            text = "Mostrar a câmera de ré ao ligar a seta"
            isChecked = autoRearOn
        }
        box.addView(autoRearBox)
        val holdOptions = listOf(0, 3, 5, 10)
        val holdIds = holdOptions.associateWith { View.generateViewId() }
        val holdGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for ((sec, id) in holdIds) {
            holdGroup.addView(RadioButton(this).apply {
                this.id = id
                text = if (sec == 0) "Só enquanto a seta está ligada" else "Mais $sec s depois de apagar a seta"
            })
        }
        holdGroup.check(holdIds[autoRearHold] ?: holdIds.getValue(3))
        box.addView(TextView(this).apply { text = "Tempo com a câmera de ré depois da seta"; setPadding(0, (10 * d).toInt(), 0, 0) })
        box.addView(holdGroup)
        box.addView(TextView(this).apply {
            text = "Padrão: desligado. A câmera volta para a frontal quando o tempo acaba. Precisa do OBD2 para ler as setas."
            textSize = 13f
            alpha = 0.7f
        })

        section(box, "Diagnóstico")
        val diagBox = CheckBox(this).apply {
            text = "Gravar diagnóstico (dados e fotos) para análise"
            isChecked = diagLog != null
        }
        box.addView(diagBox)
        box.addView(TextView(this).apply {
            text = "Liga ao usar a Estrada. Guarda, a cada quadro, a fonte, a velocidade e os objetos vistos, e uma foto a cada 3 s. Fica na pasta do app (diagnostico)."
            textSize = 13f
            alpha = 0.7f
        })

        section(box, "Sobre")
        for ((k, v) in aboutLines()) {
            box.addView(TextView(this).apply { text = "$k: $v"; textSize = 15f })
        }

        section(box, "Mapa da Estrada")
        box.addView(TextView(this).apply {
            text = "Guarde o mapa da sua cidade para ter a rua real sem internet (o tablet no carro fica no Wi-Fi da câmera)."
            textSize = 13f
            alpha = 0.7f
        })
        val mapsBtn = android.widget.Button(this).apply { text = "Mapas offline…"; isAllCaps = false }
        box.addView(mapsBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * d).toInt() })

        section(box, "Câmera (avançado)")
        val ipField = EditText(this).apply { hint = "IP da câmera (vazio = automático)"; setText(ip) }
        val urlField = EditText(this).apply {
            hint = "URL RTSP personalizada (opcional)"
            setText(customUrl)
        }
        val tcpBox = CheckBox(this).apply {
            text = "Forçar RTSP por TCP (use se a imagem não abrir)"
            isChecked = forceTcp
        }
        box.addView(ipField); box.addView(urlField); box.addView(tcpBox)

        val applyAdas = {
            adasConfig.enabled = fcwBox.isChecked || ldwBox.isChecked
            adasConfig.fcwEnabled = fcwBox.isChecked
            adasConfig.ldwEnabled = ldwBox.isChecked
            adasConfig.sensitivity = sensIds.entries.firstOrNull { it.value == sensGroup.checkedRadioButtonId }?.key
                ?: Sensitivity.MEDIUM
            adasConfig.volume = volBar.progress
            // diagnóstico: liga/desliga a gravação dos dados e fotos
            if (diagBox.isChecked && diagLog == null) {
                diagLog = DiagLog.start(this)
                toast(if (diagLog != null) "Diagnóstico ligado" else "Sem armazenamento para o diagnóstico")
            } else if (!diagBox.isChecked && diagLog != null) {
                toast("Diagnóstico salvo em: ${diagLog!!.path()}")
                diagLog = null
            }
            autoRearOn = autoRearBox.isChecked
            autoRearHold = holdIds.entries.firstOrNull { it.value == holdGroup.checkedRadioButtonId }?.key ?: 3
            if (!autoRearOn && autoRearActive) setAutoRear(false)
            if (adasOn) startSpeed() else if (!roadMode) stopSpeed()
            restartDetect()
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(scroll)
            .setPositiveButton("Salvar") { _, _ ->
                applyAdas()
                // só reconecta o vídeo se mudou algo da câmera (antes reconectava sempre)
                val newIp = ipField.text.toString().trim()
                val newUrl = urlField.text.toString().trim()
                if (newIp != ip || newUrl != customUrl || tcpBox.isChecked != forceTcp) {
                    ip = newIp; customUrl = newUrl; forceTcp = tcpBox.isChecked
                    refreshInfo(); startPlayback()
                }
            }
            .setNeutralButton("Câmera padrão") { _, _ ->
                ip = ""; customUrl = ""; forceTcp = false; mainPath = SOURCES[0].second; rearPath = SOURCES[1].second
                refreshInfo(); startPlayback()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        mapsBtn.setOnClickListener { dlg.dismiss(); showOfflineMaps() }
        addOtherActions(box) { dlg.dismiss() }
        dlg.show()
    }

    /** Mapas offline (Ajustes → Mapas offline…). */
    private fun showOfflineMaps() {
        OfflineMapsDialog(this, cm, position = {
            val la = prefs.getFloat("lastLat", Float.NaN); val lo = prefs.getFloat("lastLon", Float.NaN)
            if (!la.isNaN()) la.toDouble() to lo.toDouble()
            else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                        ?.let { it.latitude to it.longitude }
                }.getOrNull()
            } else null
        }, onChanged = {
            // a Estrada pede a rua de novo (agora do mapa guardado)
            mapLat = Double.NaN; mapLon = Double.NaN; mapTriedAt = 0L
        }).show()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, b.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- atualização

    /** [manual] = o usuário tocou em "Atualizar"; mostra também as mensagens de erro e "tudo em dia". */
    private fun checkForUpdate(manual: Boolean) {
        if (downloading) return
        if (manual) toast("Verificando atualização…")
        Thread {
            val result = Updater.check(cm, BuildConfig.VERSION_NAME)
            runOnUiThread {
                when (result) {
                    is Updater.Check.Available -> showUpdate(result.release)
                    Updater.Check.UpToDate ->
                        if (manual) toast("Você já está na versão mais recente (${BuildConfig.VERSION_NAME})")
                    Updater.Check.NoInternet ->
                        if (manual) toast(
                            "Sem internet. Saia do Wi-Fi da câmera e conecte a um Wi-Fi com internet para atualizar."
                        )
                    is Updater.Check.Failed ->
                        if (manual) toast("Não foi possível verificar: ${result.reason}")
                }
            }
        }.start()
    }

    private fun showUpdate(release: Updater.Release) {
        pendingRelease = release
        downloadedApk = null
        val mb = if (release.sizeBytes > 0) " • %.1f MB".format(release.sizeBytes / 1048576.0) else ""
        b.updateCard.visibility = View.VISIBLE
        b.updateTitle.text = "Nova versão ${release.tag}$mb"
        b.updateNotes.text = release.notes.ifBlank { "Melhorias e correções." }
        b.updateProgress.visibility = View.GONE
        b.btnUpdateNowText.text = "Baixar e instalar"
        b.lblUpdate.text = "Atualizar •"
        // celular: o botão só aparece quando há versão nova
        if (phone) b.btnUpdate.visibility = View.VISIBLE
    }

    private fun onUpdateButton() {
        val release = pendingRelease ?: return
        // Já baixado (ex.: o usuário voltou da tela de permissão): só instala
        downloadedApk?.let { if (it.exists()) { Updater.install(this, it); return } }
        if (downloading) return

        downloading = true
        b.updateProgress.visibility = View.VISIBLE
        b.updateProgress.isIndeterminate = true
        b.btnUpdateNowText.text = "Baixando…"
        Thread {
            try {
                val apk = Updater.download(this, cm, release) { pct ->
                    runOnUiThread {
                        if (pct >= 0) {
                            b.updateProgress.isIndeterminate = false
                            b.updateProgress.progress = pct
                            b.btnUpdateNowText.text = "Baixando… $pct%"
                        }
                    }
                }
                runOnUiThread {
                    downloading = false
                    downloadedApk = apk
                    b.btnUpdateNowText.text = "Instalar agora"
                    b.updateProgress.visibility = View.GONE
                    if (!Updater.install(this, apk)) {
                        toast("Permita instalar apps do DashLive e volte para concluir.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    b.updateProgress.visibility = View.GONE
                    b.btnUpdateNowText.text = "Tentar de novo"
                    toast("Falha no download: ${e.message}")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- diagnóstico

    private fun runDiagnostics() {
        toast("Testando a câmera… leva uns 20 segundos")
        val target = camIp
        Thread {
            val r = Diagnostics.run(target)
            runOnUiThread { showDiagnostics(r) }
        }.start()
    }

    private fun showDiagnostics(r: Diagnostics.Result) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = android.widget.TextView(this).apply {
            this.text = r.report
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(getColor(R.color.text))
            setPadding(pad, pad, pad, pad)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        val builder = AlertDialog.Builder(this)
            .setTitle("Diagnóstico da câmera")
            .setView(scroll)
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Copiar") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("diagnostico", r.report))
                toast("Copiado")
            }
        r.workingUrl?.let { url ->
            builder.setPositiveButton("Usar e conectar") { _, _ ->
                customUrl = url
                refreshInfo(); startPlayback()
            }
        }
        builder.show()
    }

    /**
     * Android Auto no tablet é feito pelo app Open Headunit (projeto separado, licença AGPL).
     * O DashLive só o abre; sem ele instalado, mostra o caminho para baixar.
     */
    private fun openAndroidAuto() {
        val intent = packageManager.getLaunchIntentForPackage(HEADUNIT_PKG)
        if (intent != null) {
            startActivity(intent)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Android Auto no tablet")
            .setMessage(
                "O Android Auto roda pelo app Open Headunit, que ainda não está instalado.\n\n" +
                    "1. Baixe o .apk em github.com/andreknieriem/open-headunit (aba Releases) e instale.\n" +
                    "2. Instale o app Android Auto no celular e ligue o celular ao tablet por cabo USB.\n" +
                    "3. Volte aqui e toque em Android Auto: o DashLive abre o Open Headunit."
            )
            .setPositiveButton("Abrir página de download") { _, _ ->
                runCatching {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/andreknieriem/open-headunit/releases/latest")
                        )
                    )
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ---------------------------------------------------------------- simulação da estrada

    private fun toggleRoad() {
        roadMode = !roadMode
        if (roadMode) startRecorder() else { stopRecorder(); stopReplay() }
        if (roadMode) updateDay() // define dia/noite já ao abrir (para o modo noturno)
        applyNightDim()
        if (!phone) b.overlay.visibility = if (roadMode || b.livePill.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        b.roadView.visibility = if (roadMode) View.VISIBLE else View.GONE
        b.adasOverlay.visibility = if (roadMode) View.VISIBLE else View.GONE
        b.lblRoad.text = if (roadMode) "Vídeo" else "Estrada"
        // botão destacado enquanto a Estrada está aberta (sabe-se onde está sem ler o rótulo)
        b.btnRoad.setBackgroundResource(if (roadMode) R.drawable.bg_rail_active else R.drawable.bg_press)
        styleOverlay()
        handler.removeCallbacks(detectTick)
        b.roadView.clear()
        fitVideo()
        updateRoad3d()
        if (roadMode) {
            dumpNextFrame = true
            b.roadView.message = when {
                offCameraWifi && !roadDemo -> null // o aviso de Wi-Fi já está no vídeo
                useNative -> "Procurando veículos…"
                else -> "A simulação precisa do player nativo"
            }
            if (!roadDemo) startSpeed()
        } else if (!adasOn) {
            stopSpeed()
        }
        restartDetect()
    }

    /**
     * Liga/desliga o laço de detecção: roda com a Estrada aberta OU com o ADAS ligado, só com a
     * lente frontal (a demo roda com qualquer lente). Sem ADAS, limpa o aviso da tela.
     */
    private fun restartDetect() {
        handler.removeCallbacks(detectTick)
        // ADAS (FCW/LDW) só com a lente frontal; a Estrada roda também com a de ré
        if (roadMode || (adasOn && (!rearNow || roadDemo))) handler.post(detectTick)
        if (!adasOn || (rearNow && !roadDemo)) {
            b.adasOverlay.show(AdasState.NONE)
            detectExec.execute { adas?.reset() }
        }
    }

    /** A Estrada 3D (Filament) abriu neste aparelho; se falhar, fica a cena desenhada em 2D. */
    private var road3dOk = true

    // ---------------------------------------------------------------- rua real (OpenStreetMap)

    private var mapLat = Double.NaN
    private var mapLon = Double.NaN
    private var mapFetching = false
    private var mapTriedAt = 0L
    /** Situação do mapa para o painel quando ainda não há rua encaixada. */
    private var mapState: String? = null
    /** Teste com posição de mentira (o GPS de verdade é ignorado). */
    private var fakeMap = false

    /**
     * Pede as ruas em volta quando o carro sai da área já baixada (raio de 450 m; pede de novo a
     * 250 m do centro). No máximo uma consulta a cada 15 s.
     */
    private fun feedMap(lat: Double, lon: Double) {
        if (mapFetching) return
        if (!mapLat.isNaN()) {
            val d = FloatArray(1)
            Location.distanceBetween(mapLat, mapLon, lat, lon, d)
            if (d[0] < 250f) return
        }
        if (SystemClock.elapsedRealtime() - mapTriedAt < 15_000) return
        mapTriedAt = SystemClock.elapsedRealtime()
        mapFetching = true
        if (mapLat.isNaN()) mapState = "baixando o mapa…"
        Thread {
            val r = runCatching { OsmClient.load(this, cm, lat, lon) }
            runOnUiThread {
                mapFetching = false
                r.onSuccess { (map, online) ->
                    mapLat = lat; mapLon = lon
                    mapState = if (online) "mapa ok" else "mapa guardado"
                    b.road3d.setMap(map)
                }.onFailure { mapState = "mapa: ${it.message}" }
            }
        }.start()
    }

    /** Estrada ligada no estilo "cena": mostra o 3D por baixo do painel do RoadView. */
    private fun updateRoad3d() {
        val on = road3dOk && roadMode && b.roadView.style == RoadView.Style.SCENE
        b.adasOverlay.overVideo = b.roadView.style == RoadView.Style.VIDEO
        b.road3d.visibility = if (on) View.VISIBLE else View.GONE
        b.roadView.scene3d = on
        if (on) { updateDay(); b.road3d.start() } else b.road3d.stop()
    }

    private var dayCheckedAt = 0L

    /**
     * Dia ou noite na cena 3D: "auto" usa a altura do sol na última posição do GPS (sem posição
     * ainda: das 6h às 18h); "day"/"night" forçam pelo botão da Estrada.
     */
    /**
     * Modo noturno: com a Estrada aberta à noite, o brilho da tela baixa (o motorista não fica
     * ofuscado). Fora disso, volta ao brilho do sistema. Ligado por padrão (pref nightDim).
     */
    private fun applyNightDim() {
        val dim = roadMode && !b.road3d.day && prefs.getBoolean("nightDim", true)
        val target = if (dim) 0.2f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        val lp = window.attributes
        if (lp.screenBrightness != target) {
            lp.screenBrightness = target
            window.attributes = lp
        }
    }

    private fun updateDay() {
        dayCheckedAt = SystemClock.elapsedRealtime()
        val mode = prefs.getString("roadDay", "auto")
        val lat = prefs.getFloat("lastLat", Float.NaN)
        val lon = prefs.getFloat("lastLon", Float.NaN)
        val autoDay = if (!lat.isNaN() && !lon.isNaN()) Sun.isDay(lat.toDouble(), lon.toDouble(), System.currentTimeMillis())
        else java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 6..17
        val day = when (mode) { "day" -> true; "night" -> false; else -> autoDay }
        b.road3d.day = day
        applyNightDim()
        b.roadView.dayLabel = when (mode) {
            "day" -> "Dia ☀"
            "night" -> "Noite ☾"
            else -> if (day) "Auto ☀" else "Auto ☾"
        }
    }

    /**
     * Só no build de teste (`adb shell am start ... --ez roadDemo true`): objetos de mentira para
     * conferir o desenho da Estrada sem precisar de trânsito na frente da câmera.
     */
    private var roadDemo = false

    /** Rota de teste (`--ez routeDemo true`, com roadDemo): o carro anda pela rota do arquivo. */
    private var routeSim: RouteSim? = null
    private var routeStartAt = 0L
    private val routeTick = object : Runnable {
        override fun run() {
            val sim = routeSim ?: return
            val metres = (SystemClock.elapsedRealtime() - routeStartAt) / 1000.0 * RouteSim.SPEED_KMH / 3.6
            val p = sim.at(metres)
            b.road3d.onLocation(p.lat, p.lon, p.bearing)
            feedMap(p.lat, p.lon)
            egoKmh = RouteSim.SPEED_KMH.toFloat(); egoAt = SystemClock.elapsedRealtime()
            if (roadMode) b.roadView.speedKmh = RouteSim.SPEED_KMH.toFloat()
            handler.postDelayed(this, 500)
        }
    }

    private fun demoScene(t: Float): List<Seen> {
        fun at(kind: Kind, x: Float, z: Float, wM: Float, hM: Float): Seen {
            val yb = 3.5f / z
            val b = RoadView.HORIZON + yb * (1f - RoadView.HORIZON)
            val cx = 0.5f + x / (2f * 1.2f * z)
            val half = wM / (2f * 2f * 1.2f * z)
            val ht = hM * yb * (1f - RoadView.HORIZON) / 1.3f
            return Seen(kind, 0.9f, cx - half, b - ht, cx + half, b)
        }
        val wave = kotlin.math.sin(t * 0.6f)
        // ultrapassagem: um carro na faixa da esquerda vem da frente, passa por você e some atrás
        // (a cada 16 s); na última parte ele "volta" para a sua faixa com o pisca ligado
        val ph = (t % 16f) / 16f
        // começa atrás do caminhão (z 26) para não sobrepor; depois de passar (ph 0,45 a 0,65) volta para a sua faixa
        val passZ = 18f - 22f * ph
        val lane = -3.5f + 3.7f * ((ph - 0.45f) / 0.2f).coerceIn(0f, 1f)
        val overtake = if (passZ > 1.5f) at(Kind.CAR, lane, passZ, 1.8f, 1.5f).apply {
            ambR = if (ph > 0.45f && (SystemClock.uptimeMillis() / 370) % 2 == 0L) 0.08f else 0.005f
        } else null
        // carro vindo de frente na faixa da esquerda (faróis brancos): aparece a 40 m e chega até você (a cada 9 s)
        val pc = (t % 9f) / 9f
        val oncomingZ = 40f - 36f * pc
        val oncomingCar = if (oncomingZ > 4f) at(Kind.CAR, -3.4f, oncomingZ, 1.8f, 1.5f).apply { oncoming = true } else null
        return listOfNotNull(
            overtake,
            oncomingCar,
            at(Kind.CAR, 0.2f, 14f + 4f * wave, 1.8f, 1.5f),
            at(Kind.TRUCK, -3.6f, 26f, 2.5f, 3.4f),
            // este carro "dá seta para a direita" (âmbar acende e apaga a cada ~0,37 s)
            at(Kind.CAR, 3.4f, 9f - 2f * wave, 1.8f, 1.5f).apply {
                ambR = if ((SystemClock.uptimeMillis() / 370) % 2 == 0L) 0.08f else 0.005f
            },
            at(Kind.BUS, 3.7f, 38f, 2.5f, 3.1f),
            at(Kind.MOTORCYCLE, -5.2f, 11f + 3f * wave, 0.8f, 1.5f), // no acostamento, fora da faixa do carro que passa
            at(Kind.PERSON, 6.6f, 7f, 0.6f, 1.7f),
        )
    }

    /**
     * Demo do LDW: faixas de mentira num quadro de 480×270; a cada 10 s o carro vai saindo pela
     * direita (ponto de fuga para a esquerda = virado para a direita) e volta.
     */
    private fun demoLanes(t: Float): LaneSides {
        val ph = (t % 10f) / 10f
        val shift = -110f * kotlin.math.sin(Math.PI * ph).toFloat()
        val xv = 240f - 45f * kotlin.math.cos(Math.PI * ph).toFloat()
        fun line(x0: Float): LanePoly {
            val k = (x0 - xv) / (270f - 120f)
            return LanePoly(0f, k, xv - k * 120f, 150f, 250f, 40)
        }
        return LaneSides(480, 270, line(130f + shift), line(350f + shift))
    }

    // ---------------------------------------------------------------- velocidade (GPS do tablet)

    private val lm by lazy { getSystemService(LocationManager::class.java) }
    private var gpsOn = false
    private var lastFixAt = 0L
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // guarda a posição (dia/noite automático e, depois, o mapa) no máximo 1x por minuto
            if (SystemClock.elapsedRealtime() - lastFixAt > 60_000 || prefs.getFloat("lastLat", Float.NaN).isNaN()) {
                prefs.edit().putFloat("lastLat", loc.latitude.toFloat()).putFloat("lastLon", loc.longitude.toFloat()).apply()
            }
            lastFixAt = SystemClock.elapsedRealtime()
            // rua real na Estrada 3D: posição (rumo só andando; parado o GPS inventa direção)
            val bearing = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing else null
            // na reprodução a posição vem da viagem gravada, não do GPS
            if (!fakeMap && !replaying) {
                b.road3d.onLocation(loc.latitude, loc.longitude, bearing)
                feedMap(loc.latitude, loc.longitude)
            }
            // velocidade limpa: parado (ou com erro maior que o valor) vira 0, sem bipe de limite à toa
            val accS = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null
            val accH = if (loc.hasAccuracy()) loc.accuracy else null
            val kmh = if (loc.hasSpeed()) GpsSpeed.kmh(loc.speed, accS, accH) else null
            // grava o ponto da viagem (a Estrada aberta; o TripRecorder guarda 1 por segundo)
            if (!replaying) recorder?.add(System.currentTimeMillis(), loc.latitude, loc.longitude, kmh, bearing)
            // com o OBD2 conectado, a velocidade do carro tem prioridade (mais precisa e sem atraso)
            if (kmh != null && !obdFresh && !fakeMap && !replaying) {
                b.roadView.speedKmh = kmh
                egoKmh = kmh; egoAt = SystemClock.elapsedRealtime()
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { b.roadView.speedKmh = null }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Liga o GPS para mostrar a velocidade na Estrada (pede a permissão na primeira vez). */
    private fun startSpeed() {
        if (gpsOn) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
            return
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, gpsListener, Looper.getMainLooper())
            gpsOn = true
        } catch (e: Exception) {
            android.util.Log.w("DashLive", "GPS indisponível: ${e.message}")
        }
    }

    private fun stopSpeed() {
        if (gpsOn) runCatching { lm.removeUpdates(gpsListener) }
        gpsOn = false
        b.roadView.speedKmh = null
    }

    // ---------------------------------------------------------------- viagens gravadas (replay)

    private val tripsDir by lazy { File(getExternalFilesDir(null), "viagens").apply { mkdirs() } }
    private var recorder: TripRecorder? = null
    private var replaying = false
    private var tripTrack: TripLog.Track? = null
    private var tripStartAt = 0L

    /** Grava a viagem enquanto a Estrada está aberta; sem nenhum ponto, o arquivo é apagado. */
    private fun startRecorder() {
        if (recorder != null) return
        val now = System.currentTimeMillis()
        runCatching { recorder = TripRecorder(File(tripsDir, TripLog.fileName(now)), now) }
            .onFailure { android.util.Log.w("DashLive", "viagem não gravada: ${it.message}") }
    }

    private fun stopRecorder() {
        val r = recorder ?: return
        recorder = null
        r.stop()
        if (r.points == 0) r.file.delete()
    }

    private val speedAlarm = SpeedAlarm()
    private val radarAlarm = RadarAlarm()

    private val tripTick = object : Runnable {
        override fun run() {
            val tr = tripTrack ?: return
            val p = tr.at(SystemClock.elapsedRealtime() - tripStartAt)
            if (p == null) { stopReplay(); toast("Fim da viagem gravada"); return }
            b.road3d.onLocation(p.lat, p.lon, p.bearing?.takeIf { (p.speedKmh ?: 0f) > 1.5f })
            feedMap(p.lat, p.lon)
            p.speedKmh?.let { b.roadView.speedKmh = it; egoKmh = it; egoAt = SystemClock.elapsedRealtime() }
            handler.postDelayed(this, 250)
        }
    }

    /** Abre a Estrada e anda pela viagem gravada, com o mapa e a velocidade daquele dia. */
    private fun startReplay(f: File) {
        val tr = runCatching { TripLog.Track.load(f) }.getOrNull()
        if (tr == null || tr.durationMs <= 0L) { toast("Viagem sem pontos: ${f.name}"); return }
        if (!roadMode) toggleRoad()
        stopSpeed() // o GPS real não entra na reprodução
        replaying = true
        tripTrack = tr
        tripStartAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tripTick)
        handler.post(tripTick)
        toast("Reproduzindo ${f.name.removePrefix("viagem_").removeSuffix(".csv")}")
    }

    private fun stopReplay() {
        replaying = false
        tripTrack = null
        handler.removeCallbacks(tripTick)
    }

    private fun showTrips() {
        if (replaying) {
            AlertDialog.Builder(this).setTitle("Reproduzindo uma viagem")
                .setPositiveButton("Parar") { _, _ -> stopReplay() }
                .setNegativeButton("Fechar", null).show()
            return
        }
        val files = TripLog.list(tripsDir)
        if (files.isEmpty()) { toast("Nenhuma viagem gravada: abra a Estrada com o GPS ligado"); return }
        val names = files.map { f ->
            val date = f.nameWithoutExtension.removePrefix("viagem_")
            val sum = runCatching { TripLog.summary(TripLog.parse(f.readText())) }.getOrNull()
            if (sum == null) date else "$date • ${TripLog.summaryText(sum)}"
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this).setTitle("Reproduzir viagem")
            .setItems(names) { _, i -> startReplay(files[i]) }
            .setNegativeButton("Cancelar", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && (roadMode || adasOn) && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startSpeed()
        }
        if (requestCode == REQ_CAMERA && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startPlayback()
        if (requestCode == REQ_BT) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) showObdDialog()
            else toast("Sem a permissão de Bluetooth o DashLive não consegue falar com o adaptador OBD2.")
        }
    }

    // ---------------------------------------------------------------- carro (OBD2)

    private var obd: ObdManager? = null
    /** Hora da última velocidade do OBD; enquanto recente, ela tem prioridade sobre o GPS. */
    private var obdSpeedAt = 0L
    private val obdFresh: Boolean
        get() = SystemClock.elapsedRealtime() - obdSpeedAt < 3000
    private var obdAddr: String
        get() = prefs.getString("obdAddr", "")!!
        set(v) = prefs.edit().putString("obdAddr", v).apply()
    private var obdName: String
        get() = prefs.getString("obdName", "")!!
        set(v) = prefs.edit().putString("obdName", v).apply()

    private fun hasBtPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startObd() {
        if (obd != null || obdAddr.isBlank()) return
        if (!hasBtPermission()) { showCarStatus("Toque para permitir o Bluetooth", false); return }
        obd = ObdManager(
            this, obdAddr,
            onState = { text, ok ->
                runOnUiThread {
                    if (obd != null) showCarStatus(if (ok) "$obdName • conectado" else text, ok)
                    // carro ligado (OBD conectou): abre a Estrada sozinha, se a opção estiver ligada
                    if (ok && obd != null && !roadMode && prefs.getBoolean("autoRoad", true)) toggleRoad()
                }
            },
            onData = { d -> runOnUiThread { if (obd != null) showCarData(d) } },
        ).also { it.start() }
    }

    private fun stopObd() {
        obd?.stop()
        obd = null
        obdSpeedAt = 0L
        handler.removeCallbacks(blinkTick)
        showLights(left = false, right = false, low = false, high = false)
        b.carLights.visibility = View.GONE
        b.roadView.lightsKnown = false
    }

    private fun showCarStatus(text: String, ok: Boolean) {
        b.carStatus.text = text
        b.carDot.setBackgroundResource(if (ok) R.drawable.bg_ok_dot else R.drawable.bg_warn_dot)
        if (!ok) {
            b.carValues.visibility = View.GONE
            b.carVolts.text = ""
            b.carLights.visibility = View.GONE
            showLights(left = false, right = false, low = false, high = false)
            b.roadView.lightsKnown = false
        }
    }

    private fun showCarData(d: ObdManager.Data) {
        b.carValues.visibility = View.VISIBLE
        b.carSpeed.text = d.speed?.toString() ?: "--"
        b.carRpm.text = d.rpm?.toString() ?: "--"
        b.carTemp.text = d.coolant?.toString() ?: "--"
        b.carFuel.text = d.fuel?.toString() ?: "--"
        b.carVolts.text = d.volts?.let { "%.1f V".format(it) } ?: ""
        if (d.speed != null) {
            obdSpeedAt = SystemClock.elapsedRealtime()
            egoKmh = d.speed.toFloat(); egoAt = obdSpeedAt
            if (roadMode) b.roadView.speedKmh = d.speed.toFloat()
        }
        // Setas e faróis (Ford Ka). A 0x3B3 chega ~2x/s e manda no liga/desliga; a 0x3C3 (a cada ~3 s)
        // só diz se é farol baixo ou alto. Mensagem velha (escuta perdida, ignição desligada) = apagado.
        val now = SystemClock.elapsedRealtime()
        val bodyOk = d.bodyAt > 0 && now - d.bodyAt < 3000
        val beamsOk = d.beamsAt > 0 && now - d.beamsAt < 10_000
        val high = beamsOk && d.highBeam && (!bodyOk || d.lights)
        val low = !high && (if (bodyOk) d.lights else beamsOk && d.lowBeam)
        b.carLights.visibility = if (d.bodyAt > 0 || d.beamsAt > 0) View.VISIBLE else View.GONE
        b.roadView.lightsKnown = bodyOk || beamsOk
        showLights(bodyOk && d.left, bodyOk && d.right, low, high)
    }

    /** Setas do carro (OBD); o LDW lê na thread do detector. */
    @Volatile private var turnL = false
    @Volatile private var turnR = false

    private fun showLights(left: Boolean, right: Boolean, low: Boolean, high: Boolean) {
        val wasTurning = turnL || turnR
        turnL = left
        turnR = right
        if (wasTurning != (left || right)) onTurnSignal(left || right)
        b.carLowBeam.alpha = if (low) 1f else LIGHT_OFF
        b.carHighBeam.alpha = if (high) 1f else LIGHT_OFF
        if (!roadDemo) {
            b.roadView.turnLeft = left
            b.roadView.turnRight = right
            b.roadView.lowBeam = low
            b.roadView.highBeam = high
        }
        blinkTick.run()
    }

    /** Pisca das setas no cartão, no ritmo do carro (o RoadView pisca no mesmo relógio). */
    private val blinkTick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            val on = RoadView.blinkOn()
            b.carTurnLeft.alpha = if (turnL && on) 1f else LIGHT_OFF
            b.carTurnRight.alpha = if (turnR && on) 1f else LIGHT_OFF
            if (turnL || turnR) handler.postDelayed(this, 60)
        }
    }

    /** Toque no cartão "Carro": escolher o adaptador pareado, descobrir sinais ou esquecer. */
    private fun showObdDialog() {
        if (!hasBtPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        val bt = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (bt == null) { toast("Este tablet não tem Bluetooth"); return }
        if (!bt.isEnabled) { toast("Ligue o Bluetooth do tablet"); openBtSettings(); return }
        fun looksObd(n: String?) = n != null && Regex("OBD|ELM|V-?LINK|VGATE|KONNWEI", RegexOption.IGNORE_CASE).containsMatchIn(n)
        val devices = runCatching { bt.bondedDevices.toList() }.getOrDefault(emptyList())
            .sortedByDescending { looksObd(it.name) }
        val names = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (obdAddr.isNotBlank() && obd?.connected != true) {
            // depois de algumas falhas as tentativas ficam espaçadas (até 2 min): isto tenta já
            names += "Tentar conectar agora ($obdName)"
            actions += { stopObd(); startObd() }
        }
        for (d in devices) {
            val n = d.name ?: d.address
            names += if (d.address == obdAddr) "✓  $n" else n
            actions += {
                obdAddr = d.address
                obdName = n
                stopObd()
                startObd()
            }
        }
        names += "Parear um adaptador novo…"
        actions += {
            toast("Pareie o adaptador (costuma aparecer como OBDII; senha 1234 ou 0000) e volte aqui.")
            openBtSettings()
        }
        if (obd?.connected == true) {
            names += "Descobrir sinais (seta, farol)…"
            actions += { showSniffDialog() }
        }
        if (obdAddr.isNotBlank()) {
            names += "Esquecer o adaptador"
            actions += {
                stopObd()
                obdAddr = ""; obdName = ""
                showCarStatus("Toque para escolher o adaptador", false)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Adaptador OBD2 (ELM327) pareado")
            .setItems(names.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun openBtSettings() {
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    /**
     * Seta e farol não fazem parte do OBD2 padrão. Este modo grava 60 s de tudo o que passa na rede
     * do carro enquanto a pessoa liga seta/farol na ordem pedida; depois o arquivo é analisado para
     * achar quais mensagens mudam (Ford: pode estar só na rede MS-CAN, que o ELM327 comum não alcança).
     */
    private fun showSniffDialog() {
        val o = obd ?: return
        val steps = listOf(
            Triple(0, "parado", "Carro ligado e parado.\nNão mexa em nada."),
            Triple(12, "seta_esq", "AGORA: ligue a SETA DA ESQUERDA"),
            Triple(24, "seta_dir", "AGORA: desligue a seta e ligue a SETA DA DIREITA"),
            Triple(36, "farol_baixo", "AGORA: desligue a seta e ligue o FAROL BAIXO"),
            Triple(48, "farol_alto", "AGORA: ligue o FAROL ALTO"),
            Triple(60, "desligado", "AGORA: desligue os faróis.\nNão mexa em mais nada."),
        )
        val total = 72
        val pad = (24 * resources.displayMetrics.density).toInt()
        val tv = android.widget.TextView(this).apply {
            textSize = 22f
            setTextColor(getColor(R.color.text))
            setPadding(pad, pad, pad, pad)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Descobrir sinais do carro")
            .setView(tv)
            .setNegativeButton("Fechar", null)
            .setCancelable(false)
            .show()
        var done = false
        val file = File(getExternalFilesDir(null), "obd_sinais.txt")
        val t0 = SystemClock.elapsedRealtime()
        o.sniffPhase = steps[0].second
        o.recordSignals(file, total) { msg ->
            runOnUiThread {
                done = true
                tv.textSize = 16f
                tv.text = "Pronto!\n$msg\n\nCom o tablet no PC, o Claude lê esse arquivo e procura a seta e o farol."
            }
        }
        // bipe a cada passo novo: com a mão na alavanca não dá para ficar olhando a tela
        val beeper = runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
        }.getOrNull()
        var lastStep = -1
        val tick = object : Runnable {
            override fun run() {
                if (done || !dlg.isShowing) { beeper?.release(); return }
                val s = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
                val i = steps.indexOfLast { it.first <= s }
                val step = steps[i]
                if (i != lastStep && s < total) {
                    if (lastStep >= 0) beeper?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 250)
                    lastStep = i
                }
                o.sniffPhase = step.second
                val next = steps.getOrNull(i + 1)
                tv.text = when {
                    s >= total -> "Salvando…"
                    next != null -> "Passo ${i + 1} de ${steps.size}\n\n${step.third}\n\nPróximo passo em ${next.first - s} s"
                    else -> "Passo ${i + 1} de ${steps.size}\n\n${step.third}\n\nTermina em ${total - s} s"
                }
                handler.postDelayed(this, 250)
            }
        }
        handler.post(tick)
    }

    /** Pega um quadro do vídeo e manda o detector olhar, sem travar a tela. */
    private fun grabAndDetect() {
        // aparelho quente: detecta em menos quadros para não travar (nível 3 = grave, 4 = crítico)
        val heat = thermalLevel()
        if (heat >= 3 && (++thermalTick % (if (heat >= 4) 4 else 2)) != 0) return
        if (!useNative || !detecting.compareAndSet(false, true)) return
        val tv = b.textureView
        if (!tv.isAvailable || tv.width == 0 || tv.height == 0 || videoW <= 0 || videoH <= 0) {
            detecting.set(false); return
        }
        // getBitmap() ignora a matriz de encaixe (setTransform): devolve o quadro inteiro da câmera
        // esticado no tamanho pedido. Pedindo na proporção do vídeo, sai o quadro certo, sem recorte.
        // (Antes recortava o "centro" achando que havia barras pretas e o detector via só a faixa
        // do meio da imagem, esticada na vertical.)
        val bw = 480
        // a câmera entrega o quadro na proporção do sensor; com a câmera do celular girada 90°,
        // a proporção "de pé" é a inversa
        val swapped = phoneCamMode && phoneRot % 180 == 90
        val rawW = if (swapped) videoH else videoW
        val rawH = if (swapped) videoW else videoH
        val bh = (bw.toFloat() * rawH / rawW).toInt().coerceAtLeast(2)
        val raw: Bitmap? = try {
            tv.getBitmap(bw, bh)
        } catch (e: Exception) {
            null
        }
        // câmera do celular: gira o quadro para ficar em pé (o detector vê a cena como ela é)
        val bmp: Bitmap? = if (raw != null && phoneCamMode && phoneRot != 0) rotateBitmap(raw, phoneRot) else raw
        if (bmp == null) { detecting.set(false); return }
        val vw = videoW; val vh = videoH
        val rearFrame = rearNow // lente deste quadro: a de ré não alimenta FCW/LDW
        val diagSource = if (phoneCamMode) "celular" else "a200-" + (if (rearFrame) "traseira" else "frontal")
        // Build de teste: guarda o primeiro quadro que o detector recebe, para conferir o recorte
        val dump = BuildConfig.DEBUG && dumpNextFrame
        dumpNextFrame = false
        detectExec.execute {
            try {
                if (dump) runCatching {
                    File(getExternalFilesDir(null), "road_frame.png").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                val det = roadDetector ?: RoadDetector(this).also { roadDetector = it }
                // LDW: mesmo quadro, detecção de faixa sem modelo (funciona mesmo sem o detector)
                if (!rearFrame) adas?.onFrame(bmp, egoSpeed(), turnL, turnR, SystemClock.uptimeMillis())
                if (!det.available) {
                    if (roadMode) runOnUiThread { b.roadView.message = det.error ?: "Detecção indisponível" }
                    return@execute
                }
                val seen = det.detect(bmp)
                val now = SystemClock.uptimeMillis()
                diagLog?.frame(SystemClock.elapsedRealtime(), diagSource, egoSpeed(), seen, bmp)
                // FCW na mesma thread, mesmo quadro, mesma inferência (o FcwLogic é só conta)
                if (!rearFrame) adas?.onDetections(seen, vw, vh, egoSpeed(), now)
                if (now - detSecStart > 1000) { detFps = detCount; detCount = 0; detSecStart = now }
                detCount++
                if (roadMode) runOnUiThread {
                    b.roadView.message = null
                    b.roadView.fps = detFps
                    b.roadView.rearView = rearFrame
                    b.roadView.update(seen)
                }
            } catch (e: Throwable) {
                runOnUiThread { b.roadView.message = "Falha na detecção: ${e.message}" }
            } finally {
                detecting.set(false)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Nível térmico do aparelho (0 = frio ... 4 = crítico); 0 em versões antigas do Android. */
    private fun thermalLevel(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0
        } else 0

    /** Gira um quadro em graus (0, 90, 180, 270) para ficar em pé. */
    private fun rotateBitmap(src: Bitmap, deg: Int): Bitmap {
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    companion object {
        private val RETRY = Any()
        private val REAR = Any()
        private const val HEADUNIT_PKG = "com.andrerinas.headunitrevived"
        private const val REQ_LOCATION = 42
        private const val REQ_BT = 43
        private const val REQ_CAMERA = 44
        /** Transparência de uma luz do painel apagada. */
        private const val LIGHT_OFF = 0.15f

        /** Todos os caminhos de vídeo que o app oficial da 70mai conhece. */
        private val SOURCES = listOf(
            "Canal 12 (principal)" to "/livestream/12",
            "Canal 13" to "/livestream/13",
            "Fonte av1" to "/liveRTSP/av1",
            "Fonte av2" to "/liveRTSP/av2",
            "Fonte av4" to "/liveRTSP/av4",
            "Fonte av5" to "/liveRTSP/av5",
            "Fonte v1" to "/liveRTSP/v1",
        )
    }
}

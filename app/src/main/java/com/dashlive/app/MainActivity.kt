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

    // Câmera da direita (a outra do par frontal/traseira), sempre pelo player nativo
    private var rearPlayer: NativePlayer? = null
    private var rearSurface: Surface? = null
    private var rearW = 0
    private var rearH = 0

    // Simulação da estrada (detecção de objetos no vídeo da frente)
    private var roadMode = false
    @Volatile private var roadDetector: RoadDetector? = null
    private val detectExec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val detecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var detCount = 0
    private var detFps = 0
    private var detSecStart = 0L
    private var dumpNextFrame = false
    private val detectTick = object : Runnable {
        override fun run() {
            if (!roadMode) return
            // sem posição nova há 4 s (túnel, garagem): não mostra velocidade velha
            if (gpsOn && SystemClock.elapsedRealtime() - lastFixAt > 4000) b.roadView.speedKmh = null
            if (roadDemo) {
                b.roadView.message = null
                b.roadView.update(demoScene(SystemClock.uptimeMillis() / 1000f))
                if (b.roadView.speedKmh == null) b.roadView.speedKmh = 62f
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
    private var dual: Boolean
        get() = prefs.getBoolean("dual", false)
        set(v) = prefs.edit().putBoolean("dual", v).apply()
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

        b.valVersion.text = BuildConfig.VERSION_NAME
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
        roadDemo = BuildConfig.DEBUG && intent.getBooleanExtra("roadDemo", false)
        b.roadView.style = if (prefs.getString("roadStyle", "scene") == "video") RoadView.Style.VIDEO else RoadView.Style.SCENE
        b.roadView.onStyleChange = { s -> prefs.edit().putString("roadStyle", if (s == RoadView.Style.VIDEO) "video" else "scene").apply() }
        b.btnDiag.setOnClickListener { runDiagnostics() }
        b.btnUpdate.setOnClickListener { checkForUpdate(manual = true) }
        b.btnUpdateNow.setOnClickListener { onUpdateButton() }
        refreshInfo()
        handler.post { showLastCrash() }
    }

    override fun onDestroy() {
        super.onDestroy()
        roadDetector?.close()
        roadDetector = null
        detectExec.shutdown()
    }

    override fun onStart() {
        super.onStart()
        if (roadMode && !rearLens) handler.post(detectTick)
        if (roadMode) startSpeed()
        bindToCameraWifi()
        if (pendingRelease == null) checkForUpdate(manual = false)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        stopSpeed()
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
        setState(State.CONNECTING, "Procurando a câmera", "Conecte o tablet ao Wi-Fi 70mai Dash Cam A200-…")
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
                            "Conecte o tablet ao Wi-Fi 70mai Dash Cam A200-…"
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
        if (wifiNetwork != null && wifiNetwork != network) return // a câmera continua conectada
        offCameraWifi = true
        playGen++ // descarta inícios que ainda esperavam a troca de lente
        wifiNetwork = null
        cm.bindProcessToNetwork(null)
        handler.removeCallbacksAndMessages(RETRY)
        releasePlayer()
        setState(
            State.ERROR, "Fora do Wi-Fi da câmera",
            "O tablet está em outra rede Wi-Fi (o Android prefere a que tem internet). " +
                "Toque aqui para escolher o Wi-Fi 70mai Dash Cam A200-…"
        )
        // Toque no aviso abre o painel de Wi-Fi do Android por cima do app
        b.overlay.setOnClickListener { openWifiPanel() }
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
        if (offCameraWifi) return // espera o Wi-Fi da câmera voltar (onAvailable)
        releasePlayer()
        sawVideo = false
        val gen = ++playGen
        if (customUrl.isNotBlank()) { startStreams(); return }
        // Garante a lente escolhida (o app oficial pode ter trocado) antes de abrir o vídeo
        setState(State.CONNECTING, "Conectando", "${lensName()} • $camIp")
        val ip = camIp
        val rear = rearLens
        Thread {
            CameraApi.setLens(ip, rear)
            runOnUiThread { if (gen == playGen) startStreams() }
        }.start()
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
        val items = arrayOf("Câmera frontal", "Câmera traseira", "Endereço de vídeo (avançado)")
        val cur = when {
            customUrl.isNotBlank() -> -1
            rearLens -> 1
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("Câmera")
            .setSingleChoiceItems(items, cur) { d, which ->
                d.dismiss()
                if (which == 2) { pickSource(main = true); return@setSingleChoiceItems }
                rearLens = which == 1
                customUrl = ""
                refreshInfo()
                startPlayback()
                if (roadMode) { roadMode = false; toggleRoad() } // reaplica a Estrada para a lente nova
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun lensName() = if (rearLens) "Traseira" else "Frontal"

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
            onSize = { w, h -> runOnUiThread { videoW = w; videoH = h; b.roadView.setVideoSize(w, h); fitVideo() } },
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
        dual = false
        toast("A câmera envia um vídeo por vez. A segunda câmera foi desligada (dá para religar em Ajustes).")
        startPlayback()
        return true
    }

    private fun stopNative() {
        nativePlayer?.stop()
        nativePlayer = null
    }

    /** Nome amigável da fonte de vídeo. */
    private fun sourceName(path: String) = SOURCES.firstOrNull { it.second == path }?.first ?: path

    private fun markWorked() {
        engineProven = true
        prefs.edit().putBoolean("native", useNative).apply()
    }

    /** Mantém a proporção do vídeo dentro do cartão (barras pretas se precisar). */
    private fun fitVideo() = fit(b.textureView, videoW, videoH)

    private fun fitRear() = fit(b.rearTexture, rearW, rearH)

    private fun fit(v: TextureView, srcW: Int, srcH: Int) {
        if (srcW <= 0 || srcH <= 0 || v.width == 0 || v.height == 0) return
        val vw = v.width.toFloat()
        val vh = v.height.toFloat()
        val scale = minOf(vw / srcW, vh / srcH)
        val m = Matrix()
        m.setScale(srcW * scale / vw, srcH * scale / vh, vw / 2, vh / 2)
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
        b.overlay.visibility = if (live) View.GONE else View.VISIBLE
        b.overlay.text = "$title\n$subtitle"
    }

    private fun refreshInfo() {
        b.mainLabel.text = mainName()
        b.rearLabel.text = sourceName(rearPath)
        b.valIp.text = if (customUrl.isBlank()) camIp else "personalizado"
        b.valChannel.text = mainName()
        b.valProto.text = when {
            useNative -> "Nativo / TCP"
            forceTcp || autoTcp -> "ExoPlayer / TCP"
            else -> "ExoPlayer / UDP"
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        exitFullscreenOnBack.isEnabled = on
        val v = if (on) View.GONE else View.VISIBLE
        b.sidePanel.visibility = v
        b.dock.visibility = v
        b.mainLabel.visibility = if (on) View.GONE else View.VISIBLE
        b.btnExitFs.visibility = if (on) View.VISIBLE else View.GONE
        val pad = if (on) 0 else (12 * resources.displayMetrics.density).toInt()
        b.main.setPadding(pad, pad, pad, pad)
        b.videoCard.setBackgroundColor(getColor(R.color.video_bg))
        if (!on) b.videoCard.setBackgroundResource(R.drawable.bg_video)
    }

    private fun showSettings() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val ipField = EditText(this).apply { hint = "IP da câmera (vazio = automático)"; setText(ip) }
        val urlField = EditText(this).apply {
            hint = "URL RTSP personalizada (opcional)"
            setText(customUrl)
        }
        val tcpBox = CheckBox(this).apply {
            text = "Forçar RTSP por TCP (use se a imagem não abrir)"
            isChecked = forceTcp
        }
        val dualBox = CheckBox(this).apply {
            text = "Mostrar uma segunda câmera ao lado (a câmera pode aceitar só uma por vez)"
            isChecked = dual
        }
        box.addView(ipField); box.addView(urlField); box.addView(tcpBox); box.addView(dualBox)

        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                ip = ipField.text.toString().trim()
                customUrl = urlField.text.toString().trim()
                forceTcp = tcpBox.isChecked
                dual = dualBox.isChecked
                refreshInfo(); startPlayback()
            }
            .setNeutralButton("Padrão") { _, _ ->
                ip = ""; customUrl = ""; forceTcp = false; dual = false; mainPath = SOURCES[0].second; rearPath = SOURCES[1].second
                refreshInfo(); startPlayback()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        b.roadView.visibility = if (roadMode) View.VISIBLE else View.GONE
        b.lblRoad.text = if (roadMode) "Vídeo" else "Estrada"
        handler.removeCallbacks(detectTick)
        b.roadView.clear()
        if (roadMode) {
            dumpNextFrame = true
            b.roadView.message = when {
                rearLens -> "A simulação usa a câmera frontal. Troque em Câmeras."
                useNative -> "Procurando veículos…"
                else -> "A simulação precisa do player nativo"
            }
            if (!rearLens) handler.post(detectTick)
            startSpeed()
        } else {
            stopSpeed()
        }
    }

    /**
     * Só no build de teste (`adb shell am start ... --ez roadDemo true`): objetos de mentira para
     * conferir o desenho da Estrada sem precisar de trânsito na frente da câmera.
     */
    private var roadDemo = false

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
        return listOf(
            at(Kind.CAR, 0.2f, 14f + 4f * wave, 1.8f, 1.5f),
            at(Kind.TRUCK, -3.6f, 26f, 2.5f, 3.4f),
            at(Kind.CAR, 3.4f, 9f - 2f * wave, 1.8f, 1.5f),
            at(Kind.BUS, 3.7f, 38f, 2.5f, 3.1f),
            at(Kind.MOTORCYCLE, -3.3f, 11f + 3f * wave, 0.8f, 1.5f),
            at(Kind.PERSON, 6.6f, 7f, 0.6f, 1.7f),
        )
    }

    // ---------------------------------------------------------------- velocidade (GPS do tablet)

    private val lm by lazy { getSystemService(LocationManager::class.java) }
    private var gpsOn = false
    private var lastFixAt = 0L
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastFixAt = SystemClock.elapsedRealtime()
            if (loc.hasSpeed()) b.roadView.speedKmh = loc.speed * 3.6f
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && roadMode && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startSpeed()
        }
    }

    /** Pega um quadro do vídeo e manda o detector olhar, sem travar a tela. */
    private fun grabAndDetect() {
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
        val bh = (bw.toFloat() * videoH / videoW).toInt().coerceAtLeast(2)
        val bmp: Bitmap? = try {
            tv.getBitmap(bw, bh)
        } catch (e: Exception) {
            null
        }
        if (bmp == null) { detecting.set(false); return }
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
                if (!det.available) {
                    runOnUiThread { b.roadView.message = det.error ?: "Detecção indisponível" }
                    return@execute
                }
                val seen = det.detect(bmp)
                val now = SystemClock.uptimeMillis()
                if (now - detSecStart > 1000) { detFps = detCount; detCount = 0; detSecStart = now }
                detCount++
                runOnUiThread {
                    b.roadView.message = null
                    b.roadView.fps = detFps
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

    companion object {
        private val RETRY = Any()
        private val REAR = Any()
        private const val HEADUNIT_PKG = "com.andrerinas.headunitrevived"
        private const val REQ_LOCATION = 42

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

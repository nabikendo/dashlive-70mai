package com.dashlive.app

import android.content.Context
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

    private var pendingRelease: Updater.Release? = null
    private var downloadedApk: File? = null
    private var downloading = false

    private var ip: String
        get() = prefs.getString("ip", "")!!
        set(v) = prefs.edit().putString("ip", v).apply()
    private var channel: Int
        get() = prefs.getInt("channel", 12)
        set(v) = prefs.edit().putInt("channel", v).apply()
    private var dual: Boolean
        get() = prefs.getBoolean("dual", true)
        set(v) = prefs.edit().putBoolean("dual", v).apply()
    private val rearChannel: Int get() = if (channel == 12) 13 else 12
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
        get() = customUrl.ifBlank { "rtsp://$camIp:554/livestream/$channel" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        useNative = prefs.getBoolean("native", true)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        hideSystemBars()

        b.valVersion.text = BuildConfig.VERSION_NAME
        b.btnChannel.setOnClickListener { swapCameras() }
        b.rearCard.setOnClickListener { swapCameras() }
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
        b.btnDiag.setOnClickListener { runDiagnostics() }
        b.btnUpdate.setOnClickListener { checkForUpdate(manual = true) }
        b.btnUpdateNow.setOnClickListener { onUpdateButton() }
        refreshInfo()
        handler.post { showLastCrash() }
    }

    override fun onStart() {
        super.onStart()
        bindToCameraWifi()
        if (pendingRelease == null) checkForUpdate(manual = false)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
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
                wifiNetwork = network
                cm.bindProcessToNetwork(network)
                detectedIp = gatewayOf(network)
                handler.post { refreshInfo(); startPlayback() }
            }

            override fun onLost(network: Network) {
                if (network == wifiNetwork) {
                    wifiNetwork = null
                    cm.bindProcessToNetwork(null)
                    handler.post {
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
        releasePlayer()
        if (useNative) startNative() else startExo()
        // A segunda câmera entra um instante depois, para a principal abrir primeiro
        handler.postAtTime({ startRear() }, REAR, SystemClock.uptimeMillis() + 1200)
    }

    private fun swapCameras() {
        channel = rearChannel
        refreshInfo()
        startPlayback()
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
            surface, "rtsp://$camIp:554/livestream/$rearChannel",
            onSize = { w, h -> runOnUiThread { rearW = w; rearH = h; fitRear() } },
            onLive = { runOnUiThread { b.rearOverlay.visibility = View.GONE } },
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
            onSize = { w, h -> runOnUiThread { videoW = w; videoH = h; fitVideo() } },
            onLive = { runOnUiThread { markWorked(); setState(State.LIVE, "Ao vivo", "${cameraName(channel)} • $camIp") } },
            onError = { msg -> runOnUiThread { onNativeFailed(msg) } },
        )
        nativePlayer = p
        p.start()
    }

    private fun onNativeFailed(msg: String) {
        stopNative()
        if (!engineProven) useNative = false
        scheduleRetry("Sem sinal da câmera: $msg")
    }

    private fun stopNative() {
        nativePlayer?.stop()
        nativePlayer = null
    }

    /** Canal 12 = frontal, 13 = traseira (como no app oficial). */
    private fun cameraName(ch: Int) = if (ch == 12) "Frontal" else "Traseira"

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
                    Player.STATE_READY -> { markWorked(); setState(State.LIVE, "Ao vivo", "${cameraName(channel)} • $camIp") }
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
        b.mainLabel.text = cameraName(channel)
        b.rearLabel.text = cameraName(rearChannel)
        b.valIp.text = if (customUrl.isBlank()) camIp else "personalizado"
        b.valChannel.text = "${cameraName(channel)} (canal $channel)"
        b.valProto.text = when {
            useNative -> "Nativo / TCP"
            forceTcp || autoTcp -> "ExoPlayer / TCP"
            else -> "ExoPlayer / UDP"
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
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
            text = "Mostrar as duas câmeras (frontal e traseira)"
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
                ip = ""; customUrl = ""; forceTcp = false; dual = true; channel = 12
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private val RETRY = Any()
        private val REAR = Any()
    }
}

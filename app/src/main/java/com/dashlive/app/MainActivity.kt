package com.dashlive.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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

/**
 * Mostra ao vivo o vídeo da 70mai A200 (DR2210, chip HiSilicon).
 * Endereço encontrado no app oficial: rtsp://192.168.0.1:554/livestream/12
 * (12 = canal principal; 13 = segundo canal, se existir).
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var player: ExoPlayer? = null
    private val prefs by lazy { getSharedPreferences("dashlive", Context.MODE_PRIVATE) }
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetwork: Network? = null

    private var ip: String
        get() = prefs.getString("ip", "192.168.0.1")!!
        set(v) = prefs.edit().putString("ip", v).apply()
    private var channel: Int
        get() = prefs.getInt("channel", 12)
        set(v) = prefs.edit().putInt("channel", v).apply()
    private var forceTcp: Boolean
        get() = prefs.getBoolean("tcp", false)
        set(v) = prefs.edit().putBoolean("tcp", v).apply()
    private var customUrl: String
        get() = prefs.getString("custom", "")!!
        set(v) = prefs.edit().putString("custom", v).apply()

    private val streamUrl: String
        get() = customUrl.ifBlank { "rtsp://$ip:554/livestream/$channel" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        hideSystemBars()

        b.btnChannel.setOnClickListener {
            channel = if (channel == 12) 13 else 12
            updateChannelLabel()
            startPlayback()
        }
        b.btnReload.setOnClickListener { startPlayback() }
        b.btnSettings.setOnClickListener { showSettings() }
        // Toque no vídeo mostra/esconde os botões
        b.playerView.setOnClickListener {
            val v = if (b.bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            b.bar.visibility = v
            b.status.visibility = v
        }
        updateChannelLabel()
    }

    override fun onStart() {
        super.onStart()
        bindToCameraWifi()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        wifiCallback = null
        cm.bindProcessToNetwork(null)
    }

    /**
     * O Wi-Fi da câmera não tem internet; sem isto o Android pode mandar o
     * tráfego pelos dados móveis ou por outra rede e o vídeo nunca abre.
     */
    private fun bindToCameraWifi() {
        setStatus("Procurando o Wi-Fi da câmera…")
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                cm.bindProcessToNetwork(network)
                handler.post { startPlayback() }
            }

            override fun onLost(network: Network) {
                if (network == wifiNetwork) {
                    wifiNetwork = null
                    cm.bindProcessToNetwork(null)
                    handler.post {
                        releasePlayer()
                        setStatus("Wi-Fi da câmera desconectado.\nConecte o tablet à rede 70mai_A200_…")
                    }
                }
            }
        }
        wifiCallback = cb
        cm.requestNetwork(request, cb)
        // Se em 4 s nada aconteceu, tenta assim mesmo (alguns aparelhos não chamam onAvailable)
        handler.postDelayed({ if (player == null) startPlayback() }, 4000)
    }

    private fun startPlayback() {
        handler.removeCallbacksAndMessages(RETRY)
        releasePlayer()
        setStatus("Conectando…\n$streamUrl" + if (forceTcp) "  (TCP)" else "")

        // Buffers curtos = menos atraso no ao vivo
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 250, 250)
            .build()

        val p = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(forceTcp)
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(streamUrl))

        p.setMediaSource(source)
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> setStatus("Carregando…")
                    Player.STATE_READY -> setStatus("Ao vivo • canal $channel")
                    Player.STATE_ENDED -> scheduleRetry("Transmissão encerrada")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleRetry("Erro: ${error.errorCodeName}")
            }
        })
        p.prepare()
        b.playerView.player = p
        player = p
    }

    private fun scheduleRetry(msg: String) {
        setStatus("$msg\nTentando de novo em 3 s…")
        handler.postAtTime({ startPlayback() }, RETRY, android.os.SystemClock.uptimeMillis() + 3000)
    }

    private fun releasePlayer() {
        b.playerView.player = null
        player?.release()
        player = null
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val ipField = EditText(this).apply { hint = "IP da câmera"; setText(ip) }
        val urlField = EditText(this).apply {
            hint = "URL RTSP personalizada (opcional)"
            setText(customUrl)
        }
        val tcpBox = CheckBox(this).apply {
            text = "Forçar RTSP por TCP (use se a imagem não abrir)"
            isChecked = forceTcp
        }
        box.addView(ipField); box.addView(urlField); box.addView(tcpBox)

        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                ip = ipField.text.toString().trim().ifBlank { "192.168.0.1" }
                customUrl = urlField.text.toString().trim()
                forceTcp = tcpBox.isChecked
                startPlayback()
            }
            .setNeutralButton("Padrão") { _, _ ->
                ip = "192.168.0.1"; customUrl = ""; forceTcp = false; channel = 12
                updateChannelLabel(); startPlayback()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateChannelLabel() {
        b.btnChannel.text = "Canal $channel"
    }

    private fun setStatus(text: String) {
        b.status.text = text
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, b.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private val RETRY = Any()
    }
}

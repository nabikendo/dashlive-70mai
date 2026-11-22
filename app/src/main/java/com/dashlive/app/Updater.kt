package com.dashlive.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização dentro do app: consulta a última Release do GitHub, baixa o APK
 * e abre o instalador do Android.
 *
 * O Wi-Fi da câmera não tem internet, então as chamadas usam uma rede que tenha
 * internet de verdade (Wi-Fi comum ou dados móveis), nunca a rede da câmera.
 */
object Updater {

    data class Release(
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    sealed class Check {
        data class Available(val release: Release) : Check()
        object UpToDate : Check()
        object NoInternet : Check()
        data class Failed(val reason: String) : Check()
    }

    private const val UA = "DashLive-Updater"

    /** Rede com internet validada, ignorando o Wi-Fi da câmera. */
    @Suppress("DEPRECATION")
    fun internetNetwork(cm: ConnectivityManager): Network? =
        cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    fun check(cm: ConnectivityManager, currentVersion: String): Check {
        val net = internetNetwork(cm) ?: return Check.NoInternet
        return try {
            val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            val conn = net.openConnection(url) as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode == 404) return Check.UpToDate // ainda não há release
            if (conn.responseCode != 200) return Check.Failed("GitHub respondeu ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            if (!isNewer(tag, currentVersion)) return Check.UpToDate

            val assets = json.getJSONArray("assets")
            var apk: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
            }
            if (apk == null) return Check.Failed("A versão $tag não tem arquivo APK")
            Check.Available(
                Release(
                    tag = tag,
                    notes = json.optString("body", "").trim(),
                    apkUrl = apk.getString("browser_download_url"),
                    sizeBytes = apk.optLong("size", 0),
                )
            )
        } catch (e: Exception) {
            Check.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Baixa o APK. [onProgress] recebe 0..100 (ou -1 se o tamanho for desconhecido). */
    fun download(
        context: Context,
        cm: ConnectivityManager,
        release: Release,
        onProgress: (Int) -> Unit,
    ): File {
        val net = internetNetwork(cm) ?: error("Sem internet")
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // limpa baixados antigos
        val out = File(dir, "DashLive-${release.tag}.apk")

        val conn = net.openConnection(URL(release.apkUrl)) as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode != 200) error("Download falhou (${conn.responseCode})")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
        var done = 0L
        var lastPct = -2
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }
            }
        }
        if (total > 0 && done != total) error("Download incompleto")
        return out
    }

    /** Abre o instalador. Retorna false se faltar a permissão "instalar apps desconhecidos". */
    fun install(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return true
    }

    /** Compara versões do tipo "v1.2.3" com "1.2.0". */
    fun isNewer(tag: String, current: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

package com.dashlive.app

import android.app.Activity
import android.net.ConnectivityManager
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tela "Mapas offline": lista os mapas guardados e baixa a cidade (pela posição ou por link do
 * OpenStreetMap). [position] = última posição conhecida (ou null); [onChanged] avisa a Estrada para
 * recarregar a rua.
 */
class OfflineMapsDialog(
    private val a: Activity,
    private val cm: ConnectivityManager,
    private val position: () -> Pair<Double, Double>?,
    private val onChanged: () -> Unit,
) {
    private val dp = a.resources.displayMetrics.density

    fun show() {
        val packs = OsmPacks.list(a)
        val fmt = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
        val items = packs.map { p ->
            val mb = "%.1f MB".format(p.bytes / 1_048_576.0)
            val st = if (p.complete) "" else " • incompleto (${p.chunksDone}/${p.chunks})"
            "${p.name} — ${"%,d".format(p.ways).replace(',', '.')} ruas • $mb • ${fmt.format(Date(p.savedAt))}$st"
        }
        val b = AlertDialog.Builder(a)
            .setTitle("Mapas offline")
            .setPositiveButton("Baixar a cidade onde estou") { _, _ -> downloadHere() }
            .setNeutralButton("Por link do mapa") { _, _ -> askLink() }
            .setNegativeButton("Fechar", null)
        if (items.isEmpty()) {
            b.setMessage(
                "Nenhum mapa guardado.\n\nBaixe a sua cidade em casa, com internet: no carro, o tablet fica no " +
                    "Wi-Fi da câmera (sem internet) e a Estrada usa o mapa guardado. A região é enviada ao " +
                    "OpenStreetMap para baixar."
            )
        } else {
            b.setItems(items.toTypedArray()) { _, i -> packMenu(packs[i]) }
        }
        b.show()
    }

    private fun packMenu(p: OsmPack.Info) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (!p.complete) opts += "Continuar o download" to {
            download { OsmPacks.Region(p.id, p.name, (p.s + p.n) / 2, (p.w + p.e) / 2, p.s, p.w, p.n, p.e) }
        }
        opts += "Atualizar (baixar de novo)" to { redownload(p) }
        opts += "Apagar" to {
            AlertDialog.Builder(a).setMessage("Apagar o mapa de ${p.name}?")
                .setPositiveButton("Apagar") { _, _ -> OsmPacks.delete(a, p.id); onChanged(); show() }
                .setNegativeButton("Cancelar", null).show()
        }
        AlertDialog.Builder(a).setTitle(p.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .setNegativeButton("Voltar") { _, _ -> show() }
            .show()
    }

    private fun redownload(p: OsmPack.Info) {
        val rel = OsmPacks.relationId(p.id)
        OsmPacks.delete(a, p.id)
        if (rel != null) download { OsmPacks.lookupRelation(cm, rel) }
        else download { OsmPacks.Region(p.id, p.name, (p.s + p.n) / 2, (p.w + p.e) / 2, p.s, p.w, p.n, p.e) }
    }

    private fun downloadHere() {
        val pos = position()
        if (pos == null) {
            AlertDialog.Builder(a).setMessage(
                "Ainda não sei onde você está. Abra a Estrada com o GPS ligado por alguns segundos, " +
                    "ou use \"Por link do mapa\"."
            ).setPositiveButton("OK", null).show()
            return
        }
        download { OsmPacks.cityAt(cm, pos.first, pos.second) }
    }

    private fun askLink() {
        val field = EditText(a).apply {
            hint = "Link do openstreetmap.org/relation/… ou o número"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(a).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(a).setTitle("Baixar por link")
            .setMessage("No openstreetmap.org, busque a cidade e copie o link da página dela (…/relation/número).")
            .setView(box)
            .setPositiveButton("Baixar") { _, _ ->
                val id = OsmPacks.relationId(field.text.toString())
                if (id == null) {
                    AlertDialog.Builder(a).setMessage("Não achei o número da região nesse link.")
                        .setPositiveButton("OK") { _, _ -> askLink() }.show()
                } else download { OsmPacks.lookupRelation(cm, id) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun download(region: () -> OsmPacks.Region) {
        val cancel = AtomicBoolean(false)
        val text = TextView(a).apply { text = "Procurando a região…"; textSize = 16f }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
            addView(text); addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })
        }
        val dlg = AlertDialog.Builder(a).setTitle("Baixando mapa").setView(box).setCancelable(false)
            .setNegativeButton("Parar") { _, _ -> cancel.set(true) }
            .show()
        Thread {
            val result = runCatching {
                val r = region()
                OsmPacks.download(a, cm, r, cancel) { done, total, msg ->
                    a.runOnUiThread {
                        text.text = msg
                        bar.isIndeterminate = false
                        bar.max = total; bar.progress = done
                    }
                }
            }
            a.runOnUiThread {
                if (dlg.isShowing) dlg.dismiss()
                onChanged()
                result.onSuccess { info ->
                    val mb = "%.1f MB".format(info.bytes / 1_048_576.0)
                    AlertDialog.Builder(a).setTitle("Mapa guardado")
                        .setMessage(
                            if (info.complete) "${info.name}: ${info.ways} ruas, $mb. A Estrada usa este mapa sem internet."
                            else "${info.name}: ${info.chunksDone} de ${info.chunks} pedaços ($mb). Toque no mapa para continuar depois."
                        )
                        .setPositiveButton("OK") { _, _ -> show() }.show()
                }.onFailure { e ->
                    AlertDialog.Builder(a).setTitle("Não deu para baixar")
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("OK") { _, _ -> show() }.show()
                }
            }
        }.start()
    }
}

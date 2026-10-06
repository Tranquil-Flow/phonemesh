package org.phonemesh.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackKind
import org.phonemesh.core.SetupPlan

/** Basic settings (3a): language, packs with the Tiny/Base choice, share the app, licences. */
class SettingsActivity : ScreenActivity() {
    private lateinit var languageValue: TextView
    private lateinit var packList: LinearLayout
    private lateinit var engines: RadioGroup
    private var licencesDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), pad())
        }
        column.addView(Ui.text(this, getString(R.string.settings), palette.text, 24f, bold = true))
        column.addView(noteLine())
        val languageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(this@SettingsActivity, 64)
            isClickable = true
            tag = "settings_language"
            contentDescription = getString(R.string.settings_language)
            setOnClickListener { startActivity(Intent(this@SettingsActivity, LanguageActivity::class.java)) }
        }
        languageRow.addView(Ui.icon(this, R.drawable.ic_globe, palette.text, 32))
        languageRow.addView(Ui.text(this, getString(R.string.settings_language), palette.text, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
        languageValue = Ui.text(this, "", palette.muted).apply { tag = "language_value" }
        languageRow.addView(languageValue)
        column.addView(languageRow)
        column.addView(section(R.string.settings_packs))
        packList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "packs" }
        column.addView(packList)
        column.addView(section(R.string.settings_model))
        engines = RadioGroup(this).apply { tag = "engines" }
        column.addView(engines)
        column.addView(Ui.button(this, palette, getString(R.string.share_app), R.drawable.ic_send).apply {
            tag = "share"
            setOnClickListener { shareApp() }
        }, spaced())
        column.addView(Ui.button(this, palette, getString(R.string.licences), null).apply {
            tag = "licences"
            setOnClickListener { showLicences() }
        }, spaced())
        show(ScrollView(this).apply { addView(column) })
    }

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = pad() }

    private fun section(title: Int) = Ui.text(this, getString(title), palette.text, 20f, bold = true).apply {
        setPadding(0, pad() * 3 / 2, 0, pad() / 2)
    }

    override fun render() {
        languageValue.text = core.currentLanguage().endonym
        renderPacks()
        renderEngines()
    }

    private fun renderPacks() {
        val downloads = core.downloads()
        val pending = runCatching { downloads.pending() }.getOrDefault(emptyMap())
        val rows = PackRows.build(GeneratedPackCatalog.entries, core.store.list(),
            pending.keys.associateWith { runCatching { downloads.progress(it) }.getOrNull() })
        packList.removeAllViews()
        for (row in rows) {
            val name = core.text(row.name)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(this@SettingsActivity, 64)
                tag = "pack:${row.id}"
            }
            line.addView(Ui.content(Ui.text(this, name, palette.text, bold = true)),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(actionView(row, name))
            packList.addView(line)
        }
    }

    private fun actionView(row: PackRow, name: String): View = when (val action = row.action) {
        PackRow.Action.Download -> Ui.button(this, palette,
            getString(R.string.pack_download_button, SetupPlan.megabytes(listOf(checkNotNull(row.entry)))),
            R.drawable.ic_download, getString(R.string.pack_download_cd, name)).apply {
            tag = "download:${row.id}"
            setOnClickListener { core.startDownload(checkNotNull(row.entry)); render() }
        }
        is PackRow.Action.Progress -> LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(this@SettingsActivity, getString(R.string.progress_percent, action.percent), palette.text, bold = true)
                .apply { tag = "progress:${row.id}" })
            addView(Ui.iconButton(this@SettingsActivity, palette, R.drawable.ic_close, getString(R.string.pack_cancel_cd, name)).apply {
                tag = "cancel:${row.id}"
                setOnClickListener { core.cancelDownload(checkNotNull(row.entry)); render() }
            })
        }
        PackRow.Action.Remove -> Ui.button(this, palette, getString(R.string.pack_remove), R.drawable.ic_close,
            getString(R.string.pack_remove_cd, name)).apply {
            tag = "remove:${row.id}"
            setOnClickListener { core.removePack(row.id, row.name) }
        }
        PackRow.Action.None -> Ui.text(this, getString(R.string.pack_installed_label), palette.muted).apply { tag = "installed:${row.id}" }
    }

    /** The engine in use: the chosen one, else Tiny, else any (as PackStore.readiness picks it). */
    private fun renderEngines() {
        val installed = core.store.list().filter { it.kind == PackKind.ENGINE }
        val current = installed.firstOrNull { it.id == core.settings.engine }
            ?: installed.firstOrNull { it.id == SetupPlan.DEFAULT_ENGINE } ?: installed.firstOrNull()
        engines.setOnCheckedChangeListener(null)
        engines.removeAllViews()
        installed.forEach { engine ->
            val label = getString(if (engine.recognizerModel == "whisper-base") R.string.model_base else R.string.model_tiny)
            engines.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = "engine:${engine.id}"
                text = label
                contentDescription = label
                textSize = Ui.BODY_SP
                setTextColor(palette.text)
                minHeight = Ui.dp(this@SettingsActivity, 56)
                isChecked = engine.id == current?.id
            })
        }
        engines.setOnCheckedChangeListener { group, checkedId ->
            val tag = group.findViewById<View>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            core.settings.engine = tag.removePrefix("engine:")
        }
        engines.visibility = if (installed.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shareApp() = core.share { uris ->
        if (!isDestroyed) startActivity(Intent.createChooser(PackSharing.shareIntent(this, uris), null))
    }

    private fun showLicences() {
        val body = Ui.text(this, Notices.TEXT, palette.text, Ui.BODY_SP).apply {
            setPadding(pad() * 2, pad(), pad() * 2, pad())
            setTextIsSelectable(true)
        }
        licencesDialog = AlertDialog.Builder(this).setTitle(R.string.licences)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    override fun onDestroy() {
        licencesDialog?.dismiss()
        super.onDestroy()
    }

    // Test hook
    internal val licencesDialogForTest: AlertDialog? get() = licencesDialog?.takeIf { it.isShowing }
}

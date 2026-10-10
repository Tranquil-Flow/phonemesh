package org.phonemesh.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackKind
import org.phonemesh.core.SetupPlan

/** Settings: language, appearance, the Fast/Accurate speech cards, voice packs, share the app, licences, For helpers. */
class SettingsActivity : ScreenActivity() {
    private lateinit var languageValue: TextView
    private lateinit var packList: LinearLayout
    private lateinit var themes: LinearLayout
    private lateinit var engineList: LinearLayout
    private lateinit var helpers: View
    private var licencesDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.settings)))
        column.addView(noteLine())
        column.addView(languageRow())
        column.addView(section(R.string.settings_appearance))
        themes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(themes)
        column.addView(section(R.string.settings_speech))
        engineList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "engines" }
        column.addView(engineList)
        column.addView(section(R.string.settings_packs))
        packList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "packs" }
        column.addView(packList)
        column.addView(Ui.button(this, palette, getString(R.string.share_app), R.drawable.ic_send).apply {
            tag = "share"
            setOnClickListener { shareApp() }
        }, spaced())
        column.addView(Ui.button(this, palette, getString(R.string.licences), null).apply {
            tag = "licences"
            setOnClickListener { showLicences() }
        }, spaced())
        // The connected radio's region list, for someone who knows the radio rules; nothing is set without their tick.
        helpers = Ui.button(this, palette, getString(R.string.region_for_helpers), R.drawable.ic_radio_on).apply {
            tag = "settings_helpers"
            setOnClickListener { startActivity(RegionActivity.helpers(this@SettingsActivity)) }
        }
        column.addView(helpers, spaced())
        show(ScrollView(this).apply { addView(column) })
    }

    private fun languageRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(this@SettingsActivity, 64)
            isClickable = true
            tag = "settings_language"
            contentDescription = getString(R.string.settings_language)
            setOnClickListener { startActivity(Intent(this@SettingsActivity, LanguageActivity::class.java)) }
        }
        row.addView(Ui.icon(this, R.drawable.ic_globe, palette.text, 32))
        row.addView(Ui.text(this, getString(R.string.settings_language), palette.text, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
        languageValue = Ui.text(this, "", palette.muted).apply { tag = "language_value" }
        row.addView(languageValue)
        return row
    }

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = pad() }

    private fun section(title: Int) = Ui.text(this, getString(title), palette.text, 20f, bold = true).apply {
        setPadding(0, pad() * 3 / 2, 0, pad() / 2)
    }

    override fun render() {
        languageValue.text = core.currentLanguage().endonym
        helpers.visibility = if (core.connected) View.VISIBLE else View.GONE
        renderThemes()
        val downloads = core.downloads()
        val pending = runCatching { downloads.pending() }.getOrDefault(emptyMap())
        val rows = PackRows.build(GeneratedPackCatalog.entries, core.store.list(),
            pending.keys.associateWith { runCatching { downloads.progress(it) }.getOrNull() })
        renderEngines(rows)
        renderPacks(rows.filter { it.kind != PackKind.ENGINE || it.id !in EngineCards.ids })
    }

    private fun renderPacks(rows: List<PackRow>) {
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

    private fun renderThemes() {
        themes.removeAllViews()
        val choices = listOf(
            Triple(ThemeMode.AUTO, R.drawable.ic_auto, R.string.theme_auto),
            Triple(ThemeMode.LIGHT, R.drawable.ic_sun, R.string.theme_light),
            Triple(ThemeMode.DARK, R.drawable.ic_moon, R.string.theme_dark),
        )
        choices.forEachIndexed { i, (mode, icon, label) ->
            val selected = core.settings.theme == mode
            val ink = if (selected) palette.onAccent else palette.text
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                tag = "theme:${mode.key}"
                contentDescription = getString(label)
                isSelected = selected
                minimumHeight = Ui.dp(this@SettingsActivity, 88)
                background = Ui.ripple(Ui.outline(this@SettingsActivity, if (selected) palette.accent else palette.surface, palette.outline,
                    strokeDp = if (selected) 3 else 2), Ui.pressedOf(palette.text))
                setOnClickListener {
                    if (core.settings.theme != mode) {
                        core.settings.theme = mode
                        recreate()
                    }
                }
                addView(Ui.icon(this@SettingsActivity, icon, ink, 32))
                addView(Ui.text(this@SettingsActivity, getString(label), ink, bold = true).apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }
            themes.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = pad() / 2
            })
        }
    }

    /** The engine in use: the chosen one, else Tiny, else any (as PackStore.readiness picks it). */
    private fun renderEngines(rows: List<PackRow>) {
        val installed = core.store.list().filter { it.kind == PackKind.ENGINE }
        val current = installed.firstOrNull { it.id == core.settings.engine }
            ?: installed.firstOrNull { it.id == SetupPlan.DEFAULT_ENGINE } ?: installed.firstOrNull()
        engineList.removeAllViews()
        EngineCards.build(rows, current?.id).forEach { engineList.addView(engineCard(it)) }
    }

    private fun engineCard(card: EngineCard): View {
        val title = getString(card.title)
        val inUse = card.state == EngineCard.State.InUse
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "engine:${card.id}"
            setPadding(pad(), pad(), pad(), pad())
            background = Ui.outline(this@SettingsActivity, palette.surface, if (inUse) palette.good else palette.outline,
                strokeDp = if (inUse) 3 else 2)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad() / 2 }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, title, palette.text, 22f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (inUse) head.addView(Ui.text(this, getString(R.string.engine_in_use), palette.good, bold = true).apply {
            tag = "in_use:${card.id}"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(palette.good)
            compoundDrawablePadding = Ui.dp(this@SettingsActivity, 4)
        })
        box.addView(head)
        box.addView(level(R.string.engine_speed, card.speed, "speed:${card.id}"))
        box.addView(level(R.string.engine_accuracy, card.accuracy, "accuracy:${card.id}"))
        box.addView(Ui.text(this, getString(card.description), palette.text))
        box.addView(Ui.text(this, getString(R.string.engine_size, card.megabytes), palette.muted).apply { tag = "size:${card.id}" })
        engineActions(card, title)?.let { box.addView(it) }
        return box
    }

    /** A word and three dots, grouped for speech as Speed: 3 of 3. */
    private fun level(label: Int, value: Int, tag: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        this.tag = tag
        contentDescription = getString(R.string.engine_level, getString(label), value)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(0, pad() / 4, 0, pad() / 4)
        addView(Ui.text(this@SettingsActivity, getString(label), palette.muted).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(this@SettingsActivity, 120), LinearLayout.LayoutParams.WRAP_CONTENT))
        repeat(3) { i ->
            addView(View(this@SettingsActivity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = if (i < value) Ui.circle(palette.text) else Ui.circle(palette.background).apply {
                    setStroke(Ui.dp(this@SettingsActivity, 2), palette.outline)
                }
            }, LinearLayout.LayoutParams(Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 14)).apply {
                marginEnd = Ui.dp(this@SettingsActivity, 6)
            })
        }
    }

    private fun engineActions(card: EngineCard, title: String): View? {
        val entry = GeneratedPackCatalog.entries.first { it.id == card.id }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad() / 2, 0, 0) }
        val half = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        when (val state = card.state) {
            EngineCard.State.InUse -> return null
            is EngineCard.State.Installed -> {
                row.addView(Ui.button(this, palette, getString(R.string.engine_use), R.drawable.ic_check, filled = true).apply {
                    tag = "use:${card.id}"
                    setOnClickListener { core.settings.engine = card.id; render() }
                }, half())
                if (state.removable) row.addView(Ui.button(this, palette, getString(R.string.pack_remove), R.drawable.ic_close,
                    getString(R.string.pack_remove_cd, title)).apply {
                    tag = "remove:${card.id}"
                    setOnClickListener { core.removePack(card.id, UiText(R.string.plain, listOf(title))) }
                }, half().apply { marginStart = pad() / 2 })
            }
            EngineCard.State.Download -> row.addView(Ui.button(this, palette, getString(R.string.pack_download_button, card.megabytes),
                R.drawable.ic_download, getString(R.string.pack_download_cd, title)).apply {
                tag = "download:${card.id}"
                setOnClickListener { core.startDownload(entry); render() }
            }, half())
            is EngineCard.State.Progress -> {
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(Ui.text(this, getString(R.string.progress_percent, state.percent), palette.text, bold = true)
                    .apply { tag = "progress:${card.id}" }, half())
                row.addView(Ui.iconButton(this, palette, R.drawable.ic_close, getString(R.string.pack_cancel_cd, title)).apply {
                    tag = "cancel:${card.id}"
                    setOnClickListener { core.cancelDownload(entry); render() }
                })
            }
        }
        return row
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

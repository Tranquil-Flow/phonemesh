package org.phonemesh.app

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.phonemesh.core.update.UpdateController
import org.phonemesh.core.update.UpdateController.Notice
import org.phonemesh.core.update.UpdateController.State
import org.phonemesh.core.update.UpdateController.Why

/**
 * Settings, Local hub, Updates (hub updates spec 5.8; decisions 45 and 62). It lists the packs this phone has that the hub
 * offers newer, says "An update is ready.", and copies one only when the person taps its Download button: nothing starts by
 * itself. Progress, Pause and Resume, and every error sit beside the pack they belong to. Copies run over the hub's own Wi-Fi
 * only, and pause when the screen is left.
 */
class UpdatesActivity : ScreenActivity() {
    private lateinit var notice: TextView
    private lateinit var list: LinearLayout
    private lateinit var check: View
    private var updates: HubUpdates? = null
    private var active = false
    private var retriedForWifi = 0
    private val skipped = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.updates_title)))
        column.addView(noteLine())
        column.addView(Ui.text(this, getString(R.string.updates_source), palette.muted).apply { tag = "updates_source" })
        notice = Ui.text(this, "", palette.text, 20f, bold = true).apply { tag = "updates_notice" }
        column.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "updates_list" }
        column.addView(list, LinearLayout.LayoutParams(-1, -2))
        check = Ui.button(this, palette, getString(R.string.updates_check), R.drawable.ic_play).apply {
            tag = "updates_check"
            setOnClickListener { skipped.clear(); retriedForWifi = 0; updates?.controller?.takeIf { !it.view().busy }?.refresh() }
        }
        column.addView(check, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad() })
        column.addView(Ui.text(this, getString(R.string.updates_no_mobile_data), palette.muted).apply { tag = "updates_data" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad() })
        show(ScrollView(this).apply { addView(column) })
    }

    override fun onScreenStart() {
        active = true
        retriedForWifi = 0
        // Opening the saved state reads files and checks signatures: not on the main thread.
        Thread({
            val opened = runCatching { core.hubUpdates() }.getOrNull()
            runOnUiThread { if (active && !isFinishing) attach(opened) }
        }, "updates-attach").apply { isDaemon = true }.start()
    }

    override fun onScreenStop() {
        active = false
        // Nothing keeps copying while the screen is away; what was copied stays for Resume.
        updates?.controller?.let { it.onChange = null; it.pauseAll() }
        updates = null
    }

    private fun attach(opened: HubUpdates?) {
        updates = opened
        val controller = opened?.controller
        if (controller != null) {
            controller.onChange = { runOnUiThread { if (active) render() } }
            controller.refresh()
        }
        render()
    }

    override fun render() {
        val opened = updates
        val controller = opened?.controller
        if (opened == null) { show(R.string.updates_notice_checking, palette.text); list.removeAllViews(); check.visibility = View.GONE; return }
        if (controller == null) {
            show(opened.problem ?: R.string.updates_off, palette.bad)
            list.removeAllViews(); check.visibility = View.GONE
            return
        }
        check.visibility = View.VISIBLE
        val view = controller.view()
        // The hub's Wi-Fi may come up after the screen did: look again, a few times at most.
        if (view.notice == Notice.NO_WIFI && retriedForWifi < MAX_WIFI_RETRIES && core.hub?.wire() != null) {
            retriedForWifi++
            controller.refresh()
        }
        val rows = view.rows.filter { it.key !in skipped }
        val words = when {
            // Each offer says "An update is ready." itself, beside its own Download button.
            view.notice == Notice.NONE -> null
            else -> noticeWords(view.notice)
        }
        if (words == null) { notice.visibility = View.GONE } else show(words.first, words.second)
        list.removeAllViews()
        rows.forEach { list.addView(rowView(it), LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun show(words: Int, color: Int) { notice.visibility = View.VISIBLE; notice.setText(words); notice.setTextColor(color) }

    private fun noticeWords(n: Notice): Pair<Int, Int> = when (n) {
        Notice.NO_WIFI -> R.string.updates_notice_no_wifi to palette.text
        Notice.CHECKING -> R.string.updates_notice_checking to palette.text
        Notice.NO_UPDATES -> R.string.updates_notice_none to palette.text
        Notice.UP_TO_DATE -> R.string.updates_notice_current to palette.text
        Notice.UNREACHABLE -> R.string.updates_notice_unreachable to palette.bad
        Notice.LIST_NOT_TRUSTED -> R.string.updates_notice_untrusted to palette.bad
        Notice.NEEDS_NEWER_APP -> R.string.updates_notice_newer_app to palette.bad
        Notice.SAVE_FAILED -> R.string.updates_notice_save to palette.bad
        Notice.NONE -> R.string.updates_notice_current to palette.text
    }

    private fun whyWords(why: Why): Int = when (why) {
        Why.HUB_GONE -> R.string.updates_stopped
        Why.NO_SPACE -> R.string.updates_fail_space
        Why.NOT_TRUSTED -> R.string.updates_fail_untrusted
        Why.BUSY -> R.string.updates_fail_busy
        Why.REFUSED -> R.string.updates_fail_refused
        Why.OPEN_FAILED -> R.string.updates_fail_open
        Why.NOT_SAVED -> R.string.updates_fail_saved
        Why.NOT_ON_HUB -> R.string.updates_fail_not_on_hub
        Why.NOT_OFFERED -> R.string.updates_fail_offered
    }

    private fun megabytes(bytes: Long) = ((bytes + 999_999) / 1_000_000).toInt().coerceAtLeast(1)

    private fun rowView(row: UpdateController.Row): View {
        val controller = checkNotNull(updates?.controller)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "update:${row.id}"
        }
        box.addView(Ui.content(Ui.text(this, row.label, palette.text, 20f, bold = true)))
        box.addView(Ui.text(this, getString(R.string.updates_row_detail, row.installed, row.version, megabytes(row.bytes)), palette.muted)
            .apply { tag = "update_detail:${row.id}" })
        fun say(words: Int, color: Int = palette.text) = box.addView(Ui.text(this, getString(words), color, bold = true).apply { tag = "update_state:${row.id}" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad() / 2 })
        fun bar(done: Long, total: Long) {
            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
            box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = percent; tag = "update_bar:${row.id}"
                contentDescription = getString(R.string.progress_percent, percent)
                progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            }, LinearLayout.LayoutParams(-1, Ui.dp(this, 12)).apply { topMargin = pad() / 2 })
            box.addView(Ui.text(this, getString(R.string.progress_percent, percent), palette.text, bold = true).apply { tag = "update_percent:${row.id}" })
        }
        fun button(label: Int, description: Int, tagName: String, filled: Boolean, icon: Int?, action: () -> Unit) = box.addView(
            Ui.button(this, palette, getString(label), icon, getString(description, row.label), filled = filled).apply {
                tag = "$tagName:${row.id}"; setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad() / 2 })
        val ask = { controller.download(row.key); Unit }
        when (val s = row.state) {
            State.Ready -> {
                say(R.string.updates_ready)
                box.addView(Ui.button(this, palette, getString(R.string.updates_download, megabytes(row.bytes)), R.drawable.ic_download,
                    getString(R.string.updates_download_cd, row.label), filled = true).apply {
                    tag = "update_download:${row.id}"; setOnClickListener { ask() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad() / 2 })
                button(R.string.updates_not_now, R.string.updates_not_now_cd, "update_skip", false, null) { skipped.add(row.key); render() }
            }
            State.Waiting -> { say(R.string.updates_waiting); button(R.string.updates_pause, R.string.updates_pause_cd, "update_pause", false, R.drawable.ic_pause) { controller.pause(row.key) } }
            is State.Copying -> {
                say(R.string.updates_copying); bar(s.done, s.total)
                button(R.string.updates_pause, R.string.updates_pause_cd, "update_pause", false, R.drawable.ic_pause) { controller.pause(row.key) }
            }
            is State.Paused -> { say(R.string.updates_paused); bar(s.done, s.total); button(R.string.updates_resume, R.string.updates_resume_cd, "update_resume", true, R.drawable.ic_play) { ask() } }
            is State.Stopped -> {
                say(whyWords(s.why), palette.bad); bar(s.done, s.total)
                button(R.string.updates_resume, R.string.updates_resume_cd, "update_resume", true, R.drawable.ic_play) { ask() }
            }
            State.Installing -> {
                say(R.string.updates_installing)
                box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true; tag = "update_bar:${row.id}"; contentDescription = getString(R.string.updates_installing)
                }, LinearLayout.LayoutParams(-1, Ui.dp(this, 12)).apply { topMargin = pad() / 2 })
            }
            State.Done -> box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.icon(this@UpdatesActivity, R.drawable.ic_check, palette.good, 28))
                addView(Ui.text(this@UpdatesActivity, getString(R.string.updates_done), palette.good, bold = true).apply { tag = "update_state:${row.id}" },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = pad() / 2 })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad() / 2 })
            is State.Failed -> {
                say(whyWords(s.why), palette.bad)
                if (s.why != Why.NOT_OFFERED) box.addView(Ui.button(this, palette, getString(R.string.updates_download, megabytes(row.bytes)), R.drawable.ic_download,
                    getString(R.string.updates_download_cd, row.label), filled = true).apply {
                    tag = "update_download:${row.id}"; setOnClickListener { ask() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad() / 2 })
            }
        }
        box.background = GradientDrawable().apply { setColor(palette.surface); cornerRadius = Ui.dp(this@UpdatesActivity, 12).toFloat() }
        box.setPadding(pad(), pad(), pad(), pad())
        return LinearLayout(this).apply { setPadding(0, pad() / 2, 0, pad() / 2); addView(box, LinearLayout.LayoutParams(-1, -2)) }
    }

    private companion object { const val MAX_WIFI_RETRIES = 3 }
}

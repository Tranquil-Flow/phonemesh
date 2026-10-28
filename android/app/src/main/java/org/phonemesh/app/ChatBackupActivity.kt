package org.phonemesh.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import org.phonemesh.core.backup.ChatExport
import org.phonemesh.core.backup.RecoveryKey

/**
 * Save contacts and chats (backup spec 5.2, 5.6; v0.1.2 B3): choose the chats and how far back, see exact counts and the
 * size before anything is saved, show the card, save the file, read it back, and only then offer to check it with the
 * card: "checked" is said by the recovery screen once the typed card opens the file (B4-2), never by this one. The work
 * and everything it holds live in a [ChatBackupSession], so the screen can be recreated without losing the card or the
 * job (B4-6). The key is shown on this screen only and never stored; the snapshot (a private scratch file) is deleted
 * when the visit ends. Group chats are saved as read-only history (decision 75): their words, never their keys.
 */
class ChatBackupActivity : ScreenActivity() {
    private lateinit var body: LinearLayout
    private lateinit var s: ChatBackupSession
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = ChatBackupSession.attach(core, fresh = savedInstanceState == null)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.backup_title)))
        column.addView(noteLine())
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "chatbackup_body" }
        column.addView(body)
        show(ScrollView(this).apply { addView(column) })
        s.onChange = { build() }
        s.onProgress = { progressBar?.progress = it }
        build()
    }

    override fun onDestroy() {
        s.onChange = null
        s.onProgress = null
        // A turned phone or a new font size recreates the screen and keeps the visit; leaving it ends the visit.
        if (isFinishing) s.release()
        super.onDestroy()
    }

    /** App changes (radio, mail) do not touch this screen: rebuilding would lose the choices. */
    override fun render() = Unit

    private fun line(id: Int, vararg args: Any, bold: Boolean = false, tag: String? = null) =
        body.addView(Ui.text(this, getString(id, *args), palette.text, bold = bold).apply { setPadding(0, pad() / 2, 0, pad() / 2); if (tag != null) this.tag = tag })

    private fun action(tag: String, id: Int, icon: Int?, filled: Boolean = false, onClick: () -> Unit) =
        body.addView(Ui.button(this, palette, getString(id), icon, filled = filled).apply {
            this.tag = tag
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad() / 2 })

    private fun build() {
        body.removeAllViews()
        progressBar = null
        when (s.step) {
            ChatBackupSession.Step.SCOPE -> scope()
            ChatBackupSession.Step.WORKING -> working()
            ChatBackupSession.Step.PREVIEW -> previewScreen()
            ChatBackupSession.Step.CARD -> card()
            ChatBackupSession.Step.DONE -> doneScreen()
            ChatBackupSession.Step.FAILED -> failedScreen()
        }
    }

    // ---- 1. What to save

    private fun scope() {
        if (s.sizesFor != s.last90) {
            // The per-chat counts depend on the range: asked once on the worker, then the choices are drawn.
            s.loadSizes(if (s.last90) System.currentTimeMillis() - 90L * DAY else null, s.last90)
            build()
            return
        }
        s.failure?.let { line(it, bold = true, tag = "chatbackup_scope_note") }
        line(R.string.backup_pick, bold = true)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Ui.button(this@ChatBackupActivity, palette, getString(R.string.backup_range_90), null, filled = s.last90).apply {
                tag = "scope_90"
                setOnClickListener { s.last90 = true; build() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad() / 4 })
            addView(Ui.button(this@ChatBackupActivity, palette, getString(R.string.backup_range_all), null, filled = !s.last90).apply {
                tag = "scope_all"
                setOnClickListener { s.last90 = false; build() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() / 4 })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad() / 2 })
        val chats = core.history.chats().filter { it.kind != ChatKind.GROUP || (s.sizes[it.id] ?: 0) > 0 || core.history.lastMessage(it.id) != null }
        for (chat in chats) {
            val count = s.sizes[chat.id] ?: 0
            val kind = when (chat.kind) {
                ChatKind.PRIVATE_GROUP -> getString(R.string.backup_kind_group) + " · "
                ChatKind.GROUP -> getString(R.string.backup_kind_shared) + " · "
                ChatKind.DIRECT -> getString(R.string.backup_kind_old) + " · "
                ChatKind.PERSON -> ""
            }
            val label = core.chatTitle(chat) + " · " + kind + getString(R.string.backup_chat_count, count)
            body.addView(Ui.content(toggle("scope_chat:${chat.id}", label, chat.id in s.chosen) { on -> if (on) s.chosen.add(chat.id) else s.chosen.remove(chat.id) }))
        }
        body.addView(toggle("scope_pictures", getString(R.string.backup_pictures), s.pictures) { s.pictures = it })
        body.addView(toggle("scope_unsent", getString(R.string.backup_unsent), s.unsent) { s.unsent = it })
        line(R.string.backup_no_keys)
        action("scope_count", R.string.backup_count, R.drawable.ic_check, filled = true) {
            if (s.chosen.isEmpty()) { core.note(UiText(R.string.backup_nothing)); return@action }
            s.count(ChatScope(HashSet(s.chosen), if (s.last90) System.currentTimeMillis() - 90L * DAY else null, s.unsent, s.pictures), getString(R.string.archives_missing_picture))
            build()
        }
    }

    private fun toggle(tag: String, label: String, on: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
        this.tag = tag
        text = label
        contentDescription = label
        textSize = Ui.BODY_SP
        setTextColor(palette.text)
        minHeight = Ui.dp(this@ChatBackupActivity, 64)
        isChecked = on
        setOnCheckedChangeListener { _, value -> change(value) }
    }

    // ---- 2. Exact counts and size

    private fun previewScreen() {
        val p = s.preview
        val snap = s.snapshot
        if (p == null || snap == null) { s.step = ChatBackupSession.Step.SCOPE; build(); return }
        when (p) {
            is ChatExport.Preview.Refused -> line(R.string.backup_too_big, bold = true)
            is ChatExport.Preview.Ok -> {
                line(R.string.backup_summary, p.counts.chats, p.counts.messages, p.counts.pictures, bold = true)
                body.addView(Ui.content(Ui.text(this, getString(R.string.backup_size, Formatter.formatShortFileSize(this, p.sealedBytes)), palette.text)).apply { tag = "preview_size" })
                if (p.counts.missingPictures > 0) line(R.string.backup_missing_pictures, p.counts.missingPictures)
            }
        }
        val left = snap.left
        if (left.unsent > 0) line(R.string.backup_left_unsent, left.unsent)
        if (left.unconfirmed > 0) line(R.string.backup_left_unconfirmed, left.unconfirmed)
        if (left.notes > 0) line(R.string.backup_left_notes, left.notes)
        if (left.chatsWithoutContact > 0) line(R.string.backup_left_chats, left.chatsWithoutContact)
        if (left.reactions > 0) line(R.string.backup_left_reactions, left.reactions)
        if (left.altered > 0) line(R.string.backup_left_altered, left.altered)
        if (left.unsupported > 0) line(R.string.backup_left_other, left.unsupported)
        line(R.string.backup_no_resend)
        if (p is ChatExport.Preview.Ok) {
            action("chatbackup_card", R.string.backup_make_card, R.drawable.ic_lock, filled = true) { s.makeCard(); build() }
        }
        action("chatbackup_change", R.string.backup_change, null) { s.change(); build() }
    }

    // ---- 3. The card, then the file

    private fun card() {
        val shown = s.key ?: run { s.step = ChatBackupSession.Step.SCOPE; build(); return }
        line(R.string.recovery_keep_private, bold = true)
        val size = Ui.dp(this, 240)
        body.addView(ImageView(this).apply {
            tag = "chatbackup_qr"
            contentDescription = getString(R.string.recovery_keep_private)
            setImageBitmap(AddSomeoneActivity.qr(shown.text(), size))
        }, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = pad() / 2 })
        val french = core.currentLanguage().tag.startsWith("fr")
        val words = shown.words(org.phonemesh.core.backup.RecoveryKey.WordList.load(if (french) "french" else "english")).withIndex().joinToString("   ") { (i, w) -> "${i + 1}. $w" }
        body.addView(Ui.content(Ui.text(this, words, palette.text, bold = true)).apply { tag = "chatbackup_words" })
        line(R.string.recovery_elsewhere)
        // The file can be saved once the card is on paper (B4-2): the person says so first.
        lateinit var save: View
        body.addView(toggle("chatbackup_written", getString(R.string.backup_written), s.written) { on -> s.written = on; save.isEnabled = on })
        action("chatbackup_save", R.string.recovery_save, R.drawable.ic_check, filled = true) { create() }
        save = body.findViewWithTag("chatbackup_save")
        save.isEnabled = s.written
    }

    private fun create() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = RecoveryActivity.MIME
            putExtra(Intent.EXTRA_TITLE, "phonemesh-chats.pmbk")
        }, REQUEST_CREATE)
    }

    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_CREATE || resultCode != Activity.RESULT_OK || uri == null) return
        if (!s.canSave()) {
            // The app was closed while the picker was open: the card on paper no longer opens anything. Say so, and remove the empty file.
            Thread { deleter(contentResolver, uri) }.start()
            s.fail(R.string.backup_fail_lost)
            build()
            return
        }
        s.save(uri, contentResolver)
        build()
    }

    private fun doneScreen() {
        val ok = s.done ?: run { s.step = ChatBackupSession.Step.SCOPE; build(); return }
        line(R.string.backup_saved, ok.counts.chats, ok.counts.messages, bold = true)
        line(R.string.backup_saved_here)
        line(R.string.backup_no_keys)
        s.savedUri?.let { uri ->
            action("chatbackup_check", R.string.backup_check_now, R.drawable.ic_play, filled = true) { startActivity(RecoveryActivity.checkIntent(this, uri)) }
        }
        action("chatbackup_done", R.string.backup_done, R.drawable.ic_check) { finish() }
    }

    private fun failedScreen() {
        line(s.failure ?: R.string.backup_fail_destination, bold = true, tag = "chatbackup_failure")
        if (s.leftover) line(R.string.backup_fail_leftover, tag = "chatbackup_leftover")
        if (s.canSave()) action("chatbackup_retry", R.string.recovery_save, R.drawable.ic_check, filled = true) { s.step = ChatBackupSession.Step.CARD; build() }
        action("chatbackup_change", R.string.backup_change, null) { s.change(); build() }
    }

    // ---- Work with progress

    /** The progress bar and a Cancel button while the session works; the session fills the bar. */
    private fun working() {
        line(s.workText, bold = true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; tag = "chatbackup_progress"; isIndeterminate = false; progress = s.percent }
        progressBar = bar
        body.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)))
        action("chatbackup_cancel", R.string.cancel, null) { s.cancel() }
    }

    companion object {
        private const val REQUEST_CREATE = 51
        private const val DAY = 86_400_000L

        /** Removes a document the picker made; false when the place does not allow it. A test seam. */
        internal var deleter: (android.content.ContentResolver, Uri) -> Boolean = { resolver, uri ->
            try { DocumentsContract.deleteDocument(resolver, uri) } catch (e: Exception) { false }
        }

        fun open(activity: Activity) = activity.startActivity(Intent(activity, ChatBackupActivity::class.java))
    }
}

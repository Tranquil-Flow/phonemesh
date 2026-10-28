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
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Save contacts and chats (backup spec 5.2, 5.6; v0.1.2 B3): choose the chats and how far back, see exact counts and the
 * size before anything is saved, show the card, save the file, and say "checked" only after the saved file was read
 * back and opened with the key. Counting, sealing, copying and checking run on one worker with a progress bar and a
 * Cancel button. The key is shown on this screen only and never stored; the snapshot (a private scratch file) is deleted
 * when the screen goes. Group chats are saved as read-only history (decision 75): their words, never their keys.
 */
class ChatBackupActivity : ScreenActivity() {
    private enum class Step { SCOPE, WORKING, PREVIEW, CARD, DONE, FAILED }

    private lateinit var body: LinearLayout
    private var step = Step.SCOPE
    private var last90 = true
    private var pictures = true
    private var unsent = false
    private val chosen = HashSet<Long>()
    private var started = false
    private var snapshot: ChatSnapshot? = null
    private var preview: ChatExport.Preview? = null
    private var key: RecoveryKey? = null
    private var failure: Int? = null
    private var done: ChatBackup.Saved.Ok? = null
    private var sizes: Map<Long, Int> = emptyMap()
    private var sizesFor: Boolean? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "chatbackup").apply { isDaemon = true } }
    @Volatile private var cancelled = false
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.backup_title)))
        column.addView(noteLine())
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "chatbackup_body" }
        column.addView(body)
        show(ScrollView(this).apply { addView(column) })
        build()
    }

    override fun onDestroy() {
        cancelled = true
        val scratch = snapshot
        snapshot = null
        key = null
        worker.execute { runCatching { scratch?.close() } }
        worker.shutdown()
        super.onDestroy()
    }

    /** App changes (radio, mail) do not touch this screen: rebuilding would lose the choices. */
    override fun render() = Unit

    private fun line(id: Int, vararg args: Any, bold: Boolean = false) =
        body.addView(Ui.text(this, getString(id, *args), palette.text, bold = bold).apply { setPadding(0, pad() / 2, 0, pad() / 2) })

    private fun action(tag: String, id: Int, icon: Int?, filled: Boolean = false, onClick: () -> Unit) =
        body.addView(Ui.button(this, palette, getString(id), icon, filled = filled).apply {
            this.tag = tag
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad() / 2 })

    private fun build() {
        body.removeAllViews()
        progressBar = null
        when (step) {
            Step.SCOPE -> scope()
            Step.WORKING -> Unit
            Step.PREVIEW -> previewScreen()
            Step.CARD -> card()
            Step.DONE -> doneScreen()
            Step.FAILED -> failedScreen()
        }
    }

    // ---- 1. What to save

    private fun scope() {
        if (sizesFor != last90) {
            // The per-chat counts depend on the range: asked once on the worker, then the choices are drawn.
            val since = if (last90) System.currentTimeMillis() - 90L * DAY else null
            val flag = last90
            work(R.string.recovery_working, { ChatSnapshot.sizes(core.history, since) }) { found ->
                sizes = found
                sizesFor = flag
                if (!started) { started = true; core.history.chats().forEach { if (it.kind == ChatKind.PERSON || it.kind == ChatKind.PRIVATE_GROUP) chosen.add(it.id) } }
                step = Step.SCOPE
                build()
            }
            return
        }
        line(R.string.backup_pick, bold = true)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Ui.button(this@ChatBackupActivity, palette, getString(R.string.backup_range_90), null, filled = last90).apply {
                tag = "scope_90"
                setOnClickListener { last90 = true; build() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad() / 4 })
            addView(Ui.button(this@ChatBackupActivity, palette, getString(R.string.backup_range_all), null, filled = !last90).apply {
                tag = "scope_all"
                setOnClickListener { last90 = false; build() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() / 4 })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad() / 2 })
        val chats = core.history.chats().filter { it.kind != ChatKind.GROUP || (sizes[it.id] ?: 0) > 0 || core.history.lastMessage(it.id) != null }
        for (chat in chats) {
            val count = sizes[chat.id] ?: 0
            val kind = when (chat.kind) {
                ChatKind.PRIVATE_GROUP -> getString(R.string.backup_kind_group) + " · "
                ChatKind.GROUP -> getString(R.string.backup_kind_shared) + " · "
                ChatKind.DIRECT -> getString(R.string.backup_kind_old) + " · "
                ChatKind.PERSON -> ""
            }
            val label = core.chatTitle(chat) + " · " + kind + getString(R.string.backup_chat_count, count)
            body.addView(Ui.content(toggle("scope_chat:${chat.id}", label, chat.id in chosen) { on -> if (on) chosen.add(chat.id) else chosen.remove(chat.id) }))
        }
        body.addView(toggle("scope_pictures", getString(R.string.backup_pictures), pictures) { pictures = it })
        body.addView(toggle("scope_unsent", getString(R.string.backup_unsent), unsent) { unsent = it })
        line(R.string.backup_no_keys)
        action("scope_count", R.string.backup_count, R.drawable.ic_check, filled = true) {
            if (chosen.isEmpty()) { core.note(UiText(R.string.backup_nothing)); return@action }
            count()
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

    private fun count() {
        val scope = ChatScope(HashSet(chosen), if (last90) System.currentTimeMillis() - 90L * DAY else null, unsent, pictures)
        val now = System.currentTimeMillis()
        val history = core.history
        val dir = core.picturesDir
        val workDir = core.backupDir
        working(R.string.backup_counting)
        cancelled = false
        worker.execute {
            val result = try {
                val made = ChatSnapshot.build(history, dir, workDir, scope, now, core::exportLabel) { cancelled }
                if (made == null) Result.failure(IllegalStateException("no name")) else {
                    progress(30)
                    try {
                        SqlIndex(java.io.File(workDir, "index-${java.util.UUID.randomUUID()}.db")).use { index ->
                            Result.success(made to ChatExport.preview(made, now, 0, index, { cancelled }) { done, total -> progress(30 + (70 * done / maxOf(1L, total)).toInt()) })
                        }
                    } catch (e: Throwable) { made.close(); throw e }
                }
            } catch (e: CancellationException) { Result.failure(e) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing || isDestroyed) { result.getOrNull()?.first?.close(); return@runOnUiThread }
                result.fold({ (made, p) -> snapshot = made; preview = p; step = Step.PREVIEW; build() }) { e ->
                    step = Step.SCOPE
                    build()
                    core.note(UiText(if (e is CancellationException) R.string.backup_fail_cancelled else R.string.backup_fail_nothing))
                }
            }
        }
    }

    private fun previewScreen() {
        val p = preview
        val snap = snapshot
        if (p == null || snap == null) { step = Step.SCOPE; build(); return }
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
        if (left.notes > 0) line(R.string.backup_left_notes, left.notes)
        if (left.chatsWithoutContact > 0) line(R.string.backup_left_chats, left.chatsWithoutContact)
        if (left.reactions > 0) line(R.string.backup_left_reactions, left.reactions)
        if (left.unsupported > 0) line(R.string.backup_left_other, left.unsupported)
        line(R.string.backup_no_resend)
        if (p is ChatExport.Preview.Ok) {
            action("chatbackup_card", R.string.backup_make_card, R.drawable.ic_lock, filled = true) {
                key = RecoveryKey.generate()
                step = Step.CARD
                build()
            }
        }
        action("chatbackup_change", R.string.backup_change, null) { discardSnapshot(); step = Step.SCOPE; build() }
    }

    private fun discardSnapshot() {
        val scratch = snapshot
        snapshot = null
        preview = null
        worker.execute { runCatching { scratch?.close() } }
    }

    // ---- 3. The card, then the file

    private fun card() {
        val shown = key ?: run { step = Step.SCOPE; build(); return }
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
        action("chatbackup_save", R.string.recovery_save, R.drawable.ic_check, filled = true) { create() }
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
        save(uri)
    }

    private fun save(uri: Uri) {
        val snap = snapshot ?: return
        val p = preview as? ChatExport.Preview.Ok ?: return
        val shown = key ?: return
        val resolver = contentResolver
        val now = System.currentTimeMillis()
        val generation = core.settings.nextRecoveryGeneration()
        val workDir = core.backupDir
        working(R.string.backup_saving)
        cancelled = false
        worker.execute {
            val saved = try {
                ChatBackup(workDir).save(snap, p, shown, now, generation, { resolver.openOutputStream(uri, "wt") }, { resolver.openInputStream(uri) }, { cancelled }) { percent -> progress(percent) }
            } catch (e: Throwable) { ChatBackup.Saved.Failed(ChatBackup.Why.DESTINATION, partial = true) }
            if (saved is ChatBackup.Saved.Failed && saved.partial && saved.why != ChatBackup.Why.NOT_CHECKED) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (saved) {
                    is ChatBackup.Saved.Ok -> {
                        // The card is on paper from here; the copy was read back and opened with it just now.
                        core.settings.recoveryChecked = now
                        done = saved
                        key = null
                        discardSnapshot()
                        step = Step.DONE
                    }
                    is ChatBackup.Saved.Failed -> {
                        failure = when (saved.why) {
                            ChatBackup.Why.CHANGED -> R.string.backup_fail_changed
                            ChatBackup.Why.TOO_BIG -> R.string.backup_too_big
                            ChatBackup.Why.NO_SPACE -> R.string.backup_fail_space
                            ChatBackup.Why.DESTINATION -> R.string.backup_fail_destination
                            ChatBackup.Why.NOT_CHECKED -> R.string.backup_fail_unchecked
                            ChatBackup.Why.CANCELLED -> R.string.backup_fail_cancelled
                        }
                        step = Step.FAILED
                    }
                }
                build()
            }
        }
    }

    private fun doneScreen() {
        val ok = done ?: run { step = Step.SCOPE; build(); return }
        line(R.string.backup_saved, ok.counts.chats, ok.counts.messages, bold = true)
        line(R.string.backup_saved_here)
        line(R.string.backup_no_keys)
        action("chatbackup_done", R.string.backup_done, R.drawable.ic_check, filled = true) { finish() }
    }

    private fun failedScreen() {
        line(failure ?: R.string.backup_fail_destination, bold = true)
        if (key != null && snapshot != null) action("chatbackup_retry", R.string.recovery_save, R.drawable.ic_check, filled = true) { step = Step.CARD; build() }
        action("chatbackup_change", R.string.backup_change, null) { discardSnapshot(); key = null; step = Step.SCOPE; build() }
    }

    // ---- Work with progress

    /** Replaces the screen with a progress bar and a Cancel button while [text] is the job. */
    private fun working(text: Int) {
        step = Step.WORKING
        body.removeAllViews()
        line(text, bold = true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; tag = "chatbackup_progress"; isIndeterminate = false }
        progressBar = bar
        body.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)))
        action("chatbackup_cancel", R.string.cancel, null) { cancelled = true }
    }

    private fun progress(percent: Int) { runOnUiThread { progressBar?.progress = percent } }

    /** A short job on the worker with an indeterminate bar; [done] runs on the screen's thread. */
    private fun <T> work(text: Int, job: () -> T, done: (T) -> Unit) {
        working(text)
        progressBar?.isIndeterminate = true
        cancelled = false
        worker.execute {
            val result = runCatching(job)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(done) { core.note(UiText(R.string.backup_fail_nothing)); step = Step.SCOPE; build() }
            }
        }
    }

    companion object {
        private const val REQUEST_CREATE = 51
        private const val DAY = 86_400_000L

        fun open(activity: Activity) = activity.startActivity(Intent(activity, ChatBackupActivity::class.java))
    }
}

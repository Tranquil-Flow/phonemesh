package org.phonemesh.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import org.phonemesh.core.backup.BackupFile
import org.phonemesh.core.backup.BackupStream
import org.phonemesh.core.backup.RecoveryKey
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Recovery kit (account recovery spec 5.3-5.4, RC1-RC6 A; backup purpose 0). Make a kit: a card with a QR code and
 * twelve words, and a file saved somewhere else; then try the saved file with the card, which marks it checked.
 * Restore: open a saved file with its card and keep the people, to add again on this phone. The key exists only while
 * its card is shown and is never stored (RC6: keeping it is off). Reading, sealing, opening and keeping run on one worker
 * thread with a progress bar, never on the screen's thread (review A-01).
 */
class RecoveryActivity : ScreenActivity() {
    private enum class Step { START, CARD, SAVED, CHECK, RESTORE, FOUND, RESTORED }

    private lateinit var body: LinearLayout
    private var step = Step.START
    private var key: RecoveryKey? = null
    private var file: ByteArray? = null
    private var found: Recovery.Opened.Ok? = null
    /** Set when the picked file is a full chat copy: it is read from here, one chunk at a time, never held whole. */
    private var chatUri: Uri? = null
    private var restored: ArchiveImport.Result.Saved? = null
    @Volatile private var cancelRequested = false
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "recovery").apply { isDaemon = true } }
    private var busyViews: List<View> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.recovery_title)))
        column.addView(noteLine())
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "recovery_body" }
        column.addView(body)
        show(ScrollView(this).apply { addView(column) })
        build()
    }

    override fun onDestroy() {
        cancelRequested = true
        worker.shutdownNow()
        key = null
        file = null
        chatUri = null
        super.onDestroy()
    }

    /**
     * Runs [job] on the worker while the screen shows a progress bar and takes no taps; then [done] on the screen's
     * thread, or the sentence [failed] when the job threw. Nothing comes back once the screen is closed.
     */
    private fun <T> work(failed: Int, job: () -> T, done: (T) -> Unit) {
        if (busyViews.isNotEmpty()) return
        busy(true)
        worker.execute {
            val result = runCatching(job)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                result.fold(done) { core.note(UiText(failed)) }
            }
        }
    }

    private fun busy(on: Boolean, cancellable: Boolean = false) {
        if (on) {
            val bar = if (cancellable) ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = false; tag = "recovery_busy" }
                else ProgressBar(this).apply { isIndeterminate = true; tag = "recovery_busy" }
            val text = Ui.text(this, getString(R.string.recovery_working), palette.text)
            progressBar = bar
            val views = mutableListOf<View>(text, bar)
            if (cancellable) views += Ui.button(this, palette, getString(R.string.cancel), null).apply {
                tag = "recovery_cancel"
                setOnClickListener { cancelRequested = true }
            }
            // In reverse, so they sit in order at the top.
            views.asReversed().forEach { body.addView(it, 0) }
            busyViews = views
            enable(body, false)
            // Cancel stays pressable while everything else waits.
            if (cancellable) views.last().isEnabled = true
        } else {
            busyViews.forEach(body::removeView)
            busyViews = emptyList()
            progressBar = null
            enable(body, true)
        }
    }

    private var progressBar: ProgressBar? = null

    /**
     * [work] for a job that takes long (a full chat copy): a progress bar, a Cancel button, and the job told how to
     * ask whether to stop and where it has got to. A cancelled job is [CancellationException]: its [cancelled] sentence.
     */
    private fun <T> longWork(failed: Int, job: (() -> Boolean, (Long, Long) -> Unit) -> T, done: (T) -> Unit) {
        if (busyViews.isNotEmpty()) return
        cancelRequested = false
        busy(true, cancellable = true)
        worker.execute {
            val result = runCatching { job({ cancelRequested }) { at, total -> runOnUiThread { progressBar?.progress = (100 * at / maxOf(1L, total)).toInt() } } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                result.fold(done) { e -> core.note(UiText(if (e is CancellationException) R.string.recovery_cancelled else failed)) }
            }
        }
    }

    private fun enable(view: View, on: Boolean) {
        view.isEnabled = on
        if (view is ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i), on)
    }

    private fun line(id: Int, vararg args: Any, bold: Boolean = false) =
        body.addView(Ui.text(this, getString(id, *args), palette.text, bold = bold).apply { setPadding(0, pad() / 2, 0, pad() / 2) })

    private fun action(tag: String, id: Int, icon: Int?, filled: Boolean = false, text: String? = null, onClick: () -> Unit) =
        body.addView(Ui.button(this, palette, text ?: getString(id), icon, filled = filled).apply {
            this.tag = tag
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad() / 2 })

    /** App changes (radio, mail) do not touch this screen: rebuilding would erase words being typed. */
    override fun render() = Unit

    private fun build() {
        body.removeAllViews()
        when (step) {
            Step.START -> start()
            Step.CARD -> card()
            Step.SAVED -> {
                line(R.string.recovery_try, bold = true)
                action("recovery_try", R.string.recovery_try_button, R.drawable.ic_play, filled = true) { key = null; pick(REQUEST_CHECK) }
            }
            Step.CHECK, Step.RESTORE -> words()
            Step.FOUND -> foundPeople()
            Step.RESTORED -> restoredChats()
        }
    }

    private fun start() {
        line(R.string.recovery_why, bold = true)
        line(R.string.recovery_pictures)
        val checked = core.settings.recoveryChecked
        if (checked > 0) line(R.string.recovery_checked, Ui.date(this, checked, "dMMMMyyyy"))
        line(R.string.recovery_make, bold = true)
        action("recovery_make", R.string.recovery_kind_contacts, R.drawable.ic_person, filled = true) {
            if (core.account() == null) { NameActivity.open(this); return@action }
            key = RecoveryKey.generate()
            step = Step.CARD
            build()
        }
        action("recovery_make_chats", R.string.recovery_kind_chats, R.drawable.ic_person, filled = true) {
            if (core.account() == null) { NameActivity.open(this); return@action }
            ChatBackupActivity.open(this)
        }
        action("recovery_restore", R.string.recovery_restore, R.drawable.ic_person) { pick(REQUEST_RESTORE) }
        action("recovery_check", R.string.recovery_check_button, R.drawable.ic_check) { key = null; pick(REQUEST_CHECK) }
        val archives = ArchiveRows(core.history).archives().size
        if (archives > 0) action("recovery_archives", R.string.recovery_saved_chats, R.drawable.ic_person, text = getString(R.string.recovery_saved_chats, archives)) { ArchivesActivity.open(this) }
        val saved = core.history.recoveredRows()
        if (saved.isNotEmpty()) {
            line(R.string.recovery_saved_people, bold = true)
            for (person in saved.take(SHOWN)) {
                body.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    tag = "recovered:${person.name}"
                    addView(Ui.content(Ui.text(this@RecoveryActivity, person.name, palette.text, bold = true)),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Ui.button(this@RecoveryActivity, palette, getString(R.string.recovery_add_again), R.drawable.ic_person,
                        description = getString(R.string.recovery_add_again) + " " + person.name).apply {
                        setOnClickListener { AddSomeoneActivity.open(this@RecoveryActivity) }
                    })
                })
            }
            if (saved.size > SHOWN) line(R.string.recovery_more, saved.size - SHOWN)
        }
    }

    private fun card() {
        val shown = key ?: run { step = Step.START; build(); return }
        line(R.string.recovery_keep_private, bold = true)
        val size = Ui.dp(this, 240)
        body.addView(ImageView(this).apply {
            tag = "recovery_qr"
            contentDescription = getString(R.string.recovery_keep_private)
            setImageBitmap(AddSomeoneActivity.qr(shown.text(), size))
        }, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = pad() / 2 })
        val words = shown.words(wordList()).withIndex().joinToString("   ") { (i, w) -> "${i + 1}. $w" }
        body.addView(Ui.content(Ui.text(this, words, palette.text, bold = true)).apply { tag = "recovery_words" })
        line(R.string.recovery_elsewhere)
        action("recovery_save", R.string.recovery_save, R.drawable.ic_check, filled = true) { create() }
    }

    private fun words() {
        line(if (step == Step.CHECK) R.string.recovery_try else R.string.recovery_restore, bold = true)
        val entry = EditText(this).apply {
            tag = "recovery_entry"
            hint = getString(R.string.recovery_type)
            contentDescription = getString(R.string.recovery_type)
            textSize = Ui.BODY_SP
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            // Private: no suggestions, no learning, never saved with the screen.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        body.addView(entry)
        action("recovery_open", R.string.recovery_open, R.drawable.ic_check, filled = true) { open(entry.text.toString()) }
    }

    private fun foundPeople() {
        val ok = found ?: run { step = Step.START; build(); return }
        line(R.string.recovery_found, ok.copy.contacts.size, bold = true)
        for (c in ok.copy.contacts.take(SHOWN)) body.addView(Ui.content(Ui.text(this, c.savedName, palette.text)))
        if (ok.copy.contacts.size > SHOWN) line(R.string.recovery_more, ok.copy.contacts.size - SHOWN)
        line(R.string.recovery_new_identity)
        line(R.string.recovery_compare)
        action("recovery_keep", R.string.recovery_keep, R.drawable.ic_check, filled = true) {
            val history = core.history
            val now = System.currentTimeMillis()
            work(R.string.recovery_keep_failed, { Recovery.restore(history, ok, now) }) {
                found = null
                file = null
                step = Step.START
                build()
                core.note(UiText(R.string.recovery_kept))
            }
        }
    }

    /** The key from typed words (either list) or a pasted `pmrk1:` card text; null when nothing matches. */
    private fun keyFrom(typed: String): RecoveryKey? {
        val text = typed.trim()
        if (text.startsWith(RecoveryKey.PREFIX)) return RecoveryKey.fromText(text)
        return listOf(wordList(), otherList()).firstNotNullOfOrNull { RecoveryKey.fromWords(text, it) }
    }

    private fun open(typed: String) {
        val entered = keyFrom(typed) ?: run { core.note(UiText(R.string.recovery_bad_words)); return }
        chatUri?.let { chatCopy(it, entered); return }
        val bytes = file ?: return
        work(R.string.recovery_read_failed, { Recovery.open(bytes, entered) }) { o -> opened(o) }
    }

    /** A full chat copy: tried with its card (a check) or kept apart as read-only chats (a restore), chunk by chunk. */
    private fun chatCopy(uri: Uri, entered: RecoveryKey) {
        val resolver = contentResolver
        val history = core.history
        val archives = core.archivesDir
        val scratch = core.backupDir
        val checking = step == Step.CHECK
        val now = System.currentTimeMillis()
        longWork(R.string.recovery_read_failed, { cancelled, progress ->
            if (checking) ArchiveReader.read(checkNotNull(resolver.openInputStream(uri)), entered, scratch, cancelled, progress)
            else ArchiveImport(history, archives, scratch).import({ resolver.openInputStream(uri) }, entered, now, cancelled, progress)
        }) { result ->
            when (result) {
                is ArchiveReader.Read.Ok -> {
                    core.settings.recoveryChecked = now
                    chatUri = null
                    step = Step.START
                    build()
                    core.note(UiText(R.string.recovery_check_chats, listOf(result.manifest.chats, result.manifest.messages)))
                }
                is ArchiveReader.Read.Failed -> core.note(UiText(chatProblem(result.problem)))
                is ArchiveImport.Result.Saved -> { chatUri = null; restored = result; step = Step.RESTORED; build() }
                is ArchiveImport.Result.Already -> core.note(UiText(R.string.recovery_already))
                is ArchiveImport.Result.Refused -> core.note(UiText(if (result.why == ArchiveImport.Refused.TOO_MANY_COPIES) R.string.recovery_too_many else R.string.recovery_same_number))
                is ArchiveImport.Result.Failed -> core.note(UiText(chatProblem(result.problem)))
                ArchiveImport.Result.NoSpace -> core.note(UiText(R.string.recovery_no_space))
                ArchiveImport.Result.Unreadable -> core.note(UiText(R.string.recovery_unreadable))
                else -> core.note(UiText(R.string.recovery_read_failed))
            }
        }
    }

    private fun chatProblem(problem: ArchiveReader.Problem) = when (problem) {
        ArchiveReader.Problem.NOT_A_COPY, ArchiveReader.Problem.NOT_CHAT_COPY -> R.string.recovery_not_copy
        ArchiveReader.Problem.KEY_OR_FILE_DOES_NOT_MATCH -> R.string.recovery_no_match
        ArchiveReader.Problem.TOO_NEW, ArchiveReader.Problem.TOO_BIG -> R.string.recovery_too_new
    }

    /** After a restore of chats: kept apart, read only, marked as from this saved copy; people are to add again. */
    private fun restoredChats() {
        val ok = restored ?: run { step = Step.START; build(); return }
        line(R.string.recovery_restored_chats, ok.chats, ok.messages, bold = true)
        line(R.string.recovery_from_copy)
        line(R.string.recovery_read_only)
        line(R.string.recovery_new_identity)
        line(R.string.recovery_compare)
        action("recovery_see_chats", R.string.recovery_see_chats, R.drawable.ic_person, filled = true) { ArchivesActivity.open(this) }
        action("recovery_restored_done", R.string.backup_done, R.drawable.ic_check) { restored = null; step = Step.START; build() }
    }

    private fun opened(o: Recovery.Opened) {
        when (o) {
            is Recovery.Opened.Failed -> core.note(UiText(problemText(o.problem)))
            is Recovery.Opened.Ok -> if (step == Step.CHECK) {
                core.settings.recoveryChecked = System.currentTimeMillis()
                file = null
                step = Step.START
                build()
                core.note(UiText(R.string.recovery_check_ok, listOf(o.copy.contacts.size)))
            } else {
                found = o
                step = Step.FOUND
                build()
            }
        }
    }

    private fun problemText(problem: Recovery.Problem) = when (problem) {
        Recovery.Problem.NOT_A_COPY -> R.string.recovery_not_copy
        Recovery.Problem.KEY_OR_FILE_DOES_NOT_MATCH -> R.string.recovery_no_match
        Recovery.Problem.NOT_CONTACTS, Recovery.Problem.TOO_NEW, Recovery.Problem.TOO_BIG -> R.string.recovery_too_new
    }

    private fun wordList() = RecoveryKey.WordList.load(if (core.currentLanguage().tag.startsWith("fr")) "french" else "english")
    private fun otherList() = RecoveryKey.WordList.load(if (core.currentLanguage().tag.startsWith("fr")) "english" else "french")

    private fun create() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME
            putExtra(Intent.EXTRA_TITLE, "phonemesh-contacts.pmbk")
        }, REQUEST_CREATE)
    }

    private fun pick(request: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, request)
    }

    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_CREATE -> save(uri)
            REQUEST_CHECK, REQUEST_RESTORE -> work(R.string.recovery_read_failed, { head(uri) }) { info ->
                val next = if (requestCode == REQUEST_CHECK) Step.CHECK else Step.RESTORE
                when {
                    info == null -> core.note(UiText(R.string.recovery_not_copy))
                    info.purpose == BackupFile.Purpose.HISTORY -> { chatUri = uri; file = null; step = next; build() }
                    else -> work(R.string.recovery_read_failed, { read(uri) }) { bytes -> chatUri = null; file = bytes; step = next; build() }
                }
            }
        }
    }

    private fun save(uri: Uri) {
        val shown = key ?: return
        val history = core.history
        val resolver = contentResolver
        val now = System.currentTimeMillis()
        val generation = core.settings.nextRecoveryGeneration()
        work(R.string.recovery_file_failed, {
            val bytes = checkNotNull(Recovery.export(history, shown, now, generation)) { "no name yet" }
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        }) {
            // The card is now only on paper: the check below asks for it again (RC4 A).
            key = null
            step = Step.SAVED
            build()
        }
    }

    /** On the worker: the public start of the file, to tell a contacts copy from a chat copy before any key work; null when it is neither. */
    private fun head(uri: Uri): BackupStream.Head? {
        val bytes = ByteArray(HEAD_BYTES)
        val got = checkNotNull(contentResolver.openInputStream(uri)).use { input ->
            var at = 0
            while (at < bytes.size) { val n = input.read(bytes, at, bytes.size - at); if (n < 0) break; at += n }
            at
        }
        return BackupStream.head(bytes.copyOf(got))
    }

    /** On the worker: the whole file, at most [MAX_FILE] bytes; throws when it cannot be read. */
    private fun read(uri: Uri): ByteArray =
        checkNotNull(contentResolver.openInputStream(uri)).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                check(out.size() <= MAX_FILE) { "too big" }
            }
            out.toByteArray()
        }

    companion object {
        const val MIME = "application/octet-stream"
        private const val REQUEST_CREATE = 41
        private const val REQUEST_CHECK = 42
        private const val REQUEST_RESTORE = 43
        private const val MAX_FILE = 16 * 1024 * 1024 + 4096
        private const val HEAD_BYTES = 28
        /** Names listed on screen at most; the rest are counted (review A-01). */
        private const val SHOWN = 50

        fun open(activity: Activity) = activity.startActivity(Intent(activity, RecoveryActivity::class.java))
    }
}

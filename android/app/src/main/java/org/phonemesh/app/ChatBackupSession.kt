package org.phonemesh.app

import android.content.ContentResolver
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.phonemesh.core.backup.ChatExport
import org.phonemesh.core.backup.RecoveryKey
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Everything one "save contacts and chats" visit holds (B4-6): the choices, the snapshot, the preview, the key, the
 * running job and its result. It lives in the process, not in the screen, so turning the phone, changing the font size
 * or the dark mode recreates the screen without losing a card that was written down or a save that is half done: the
 * new screen attaches ([onChange], [onProgress]) and draws the same step. A fresh visit starts a fresh session; leaving
 * the screen for good ([release]) cancels the job, wipes the key and deletes the snapshot. Work runs on its own thread
 * and results are applied on the main thread, whether or not a screen is attached.
 */
class ChatBackupSession(private val core: AppCore) {
    enum class Step { SCOPE, WORKING, PREVIEW, CARD, DONE, FAILED }

    var step = Step.SCOPE
    var last90 = true
    var pictures = true
    var unsent = false
    val chosen = HashSet<Long>()
    var started = false
    var snapshot: ChatSnapshot? = null
    var preview: ChatExport.Preview? = null
    var key: RecoveryKey? = null
    /** The person said they wrote the card down: only then can the file be saved (B4-2). */
    var written = false
    /** What went wrong, as a string id, shown beside the action that failed (never silent, B4-6). */
    var failure: Int? = null
    /** The document made by the picker could not be removed after a failure (B4-7). */
    var leftover = false
    var done: ChatBackup.Saved.Ok? = null
    var savedUri: Uri? = null
    var sizes: Map<Long, Int> = emptyMap()
    var sizesFor: Boolean? = null
    var workText = 0
    @Volatile var percent = 0
    @Volatile var cancelled = false
    @Volatile private var released = false

    /** Set by the attached screen: redraw the current step / show this percent. Cleared when the screen goes. */
    var onChange: (() -> Unit)? = null
    var onProgress: ((Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "chatbackup").apply { isDaemon = true } }
    private val gate = ProgressGate { p -> main.post { percent = p; onProgress?.invoke(p) } }

    /** Applies [f] on the main thread and redraws; when the visit was left meanwhile, only [orElse] runs. */
    private fun ui(orElse: () -> Unit = {}, f: () -> Unit) { main.post { if (!released) { f(); onChange?.invoke() } else orElse() } }

    private fun progress(p: Int) = gate.update(p)

    fun working(text: Int) {
        step = Step.WORKING
        workText = text
        percent = 0
        cancelled = false
    }

    // ---- 1. Sizes per chat for the range

    fun loadSizes(sinceMs: Long?, flag: Boolean) {
        working(R.string.recovery_working)
        val history = core.history
        worker.execute {
            val found = runCatching { ChatSnapshot.sizes(history, sinceMs) }
            ui {
                found.fold({
                    sizes = it
                    sizesFor = flag
                    if (!started) { started = true; core.history.chats().forEach { c -> if (c.kind == ChatKind.PERSON || c.kind == ChatKind.PRIVATE_GROUP) chosen.add(c.id) } }
                    failure = null
                    step = Step.SCOPE
                }) { failure = R.string.backup_fail_nothing; step = Step.SCOPE; sizesFor = flag }
            }
        }
    }

    // ---- 2. Exact counts and size

    fun count(scope: ChatScope, missingPicture: String) {
        val now = System.currentTimeMillis()
        val history = core.history
        val dir = core.picturesDir
        val workDir = core.backupDir
        failure = null
        working(R.string.backup_counting)
        worker.execute {
            val result = try {
                val made = ChatSnapshot.build(history, dir, workDir, scope, now, core::exportLabel, missingPicture) { cancelled }
                if (made == null) Result.failure(IllegalStateException("no name")) else {
                    progress(30)
                    try {
                        SqlIndex(File(workDir, "index-${UUID.randomUUID()}.db")).use { index ->
                            Result.success(made to ChatExport.preview(made, now, 0, index, { cancelled }) { done, total -> gate.share(30, 100, done, total) })
                        }
                    } catch (e: Throwable) { made.close(); throw e }
                }
            } catch (e: CancellationException) { Result.failure(e) } catch (e: Exception) { Result.failure(e) }
            ui(orElse = { result.getOrNull()?.first?.close() }) {
                result.fold({ (made, p) -> snapshot = made; preview = p; step = Step.PREVIEW }) { e ->
                    failure = when {
                        e is CancellationException -> R.string.backup_fail_cancelled
                        e is ChatSnapshot.NoRoom || e is SQLiteFullException -> R.string.backup_fail_space
                        e is SQLiteException || e is IOException -> R.string.backup_fail_storage
                        else -> R.string.backup_fail_nothing
                    }
                    step = Step.SCOPE
                }
            }
        }
    }

    fun makeCard() {
        key?.wipe()
        key = RecoveryKey.generate()
        written = false
        step = Step.CARD
    }

    // ---- 3. The file

    /** Whether a save can start: the snapshot, the preview and the key are all still here. */
    fun canSave() = snapshot != null && preview is ChatExport.Preview.Ok && key?.isWiped == false

    fun save(uri: Uri, resolver: ContentResolver) {
        val snap = snapshot
        val p = preview as? ChatExport.Preview.Ok
        val shown = key
        if (snap == null || p == null || shown == null) { fail(R.string.backup_fail_lost); return }
        val now = System.currentTimeMillis()
        val generation = core.settings.nextRecoveryGeneration()
        val workDir = core.backupDir
        failure = null
        leftover = false
        working(R.string.backup_saving)
        worker.execute {
            val saved = try {
                ChatBackup(workDir).save(snap, p, shown, now, generation, { resolver.openOutputStream(uri, "wt") }, { resolver.openInputStream(uri) }, { cancelled }) { percent -> progress(percent) }
            } catch (e: Throwable) { ChatBackup.Saved.Failed(ChatBackup.Why.FAILED) }
            // A document the picker made is ours; one that is no good goes (B4-7). If it cannot, the screen says so.
            val removed = saved !is ChatBackup.Saved.Failed || !saved.discard || ChatBackupActivity.deleter(resolver, uri)
            ui {
                when (saved) {
                    is ChatBackup.Saved.Ok -> {
                        // Read back and opened with the key just now; the card is only called checked when it opens the file (B4-2).
                        done = saved
                        savedUri = uri
                        key?.wipe()
                        key = null
                        dropSnapshot()
                        step = Step.DONE
                    }
                    is ChatBackup.Saved.Failed -> {
                        leftover = !removed
                        failure = when (saved.why) {
                            ChatBackup.Why.CHANGED -> R.string.backup_fail_changed
                            ChatBackup.Why.TOO_BIG -> R.string.backup_too_big
                            ChatBackup.Why.NO_SPACE -> R.string.backup_fail_space
                            ChatBackup.Why.STORAGE -> R.string.backup_fail_storage
                            ChatBackup.Why.DESTINATION -> R.string.backup_fail_destination
                            ChatBackup.Why.NOT_CHECKED -> R.string.backup_fail_unchecked
                            ChatBackup.Why.MISMATCH -> R.string.backup_fail_mismatch
                            ChatBackup.Why.CANCELLED -> R.string.backup_fail_cancelled
                            ChatBackup.Why.FAILED -> R.string.backup_fail_unknown
                        }
                        step = Step.FAILED
                    }
                }
            }
        }
    }

    /** Something needed for the save is gone (the app was closed while the file picker was open): say so, start again. */
    fun fail(text: Int) {
        failure = text
        step = Step.FAILED
        key?.wipe()
        key = null
    }

    fun cancel() { cancelled = true }

    fun dropSnapshot() {
        val scratch = snapshot
        snapshot = null
        preview = null
        background { runCatching { scratch?.close() } }
    }

    /** On the worker; when the worker has been shut down (the visit ended), right here. */
    private fun background(f: () -> Unit) {
        try { worker.execute(f) } catch (e: java.util.concurrent.RejectedExecutionException) { f() }
    }

    /** The person chose again: scratch and key go, the choices stay. */
    fun change() {
        dropSnapshot()
        key?.wipe()
        key = null
        failure = null
        step = Step.SCOPE
    }

    /** The screen was left for good: stop, wipe, delete. */
    fun release() {
        if (released) return
        released = true
        cancelled = true
        onChange = null
        onProgress = null
        key?.wipe()
        key = null
        val scratch = snapshot
        snapshot = null
        preview = null
        background { runCatching { scratch?.close() } }
        worker.shutdown()
        if (current === this) current = null
    }

    companion object {
        @Volatile var current: ChatBackupSession? = null

        /** A fresh visit starts clean (an old one that was left behind is released); a recreated screen gets its session back. */
        fun attach(core: AppCore, fresh: Boolean): ChatBackupSession {
            val old = current
            if (!fresh && old != null) return old
            old?.release()
            return ChatBackupSession(core).also { current = it }
        }
    }
}

package org.phonemesh.app

import org.phonemesh.core.backup.BackupFile
import org.phonemesh.core.backup.BackupStream
import org.phonemesh.core.backup.ChatExport
import org.phonemesh.core.backup.HistoryBackup
import org.phonemesh.core.backup.RecoveryKey
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Reading a full chat copy (backup spec 5.7 steps 1 to 3): the file opened with its key, every chunk authenticated, every
 * record checked against the format and its cross-references (kept on disk in a scratch [SqlIndex]), the END record,
 * the counts and the end of the file. Records go to [read]'s `sink` as they pass, so a restore stages them and a check
 * simply drops them; nothing is visible, and nothing changes, until the caller commits after [Read.Ok].
 */
object ArchiveReader {
    /** [NO_SPACE] and [STORAGE] are this phone's own trouble while reading (B4-1), never a verdict on the file or the key. */
    enum class Problem { NOT_A_COPY, TOO_NEW, TOO_BIG, KEY_OR_FILE_DOES_NOT_MATCH, NOT_CHAT_COPY, NO_SPACE, STORAGE }

    sealed class Read {
        /** [fileDigest] is the SHA-256 of every byte of the file, [fileBytes] its length: the identity of this exact copy. */
        class Ok(val manifest: HistoryBackup.Manifest, val archiveId: ByteArray, val fileDigest: ByteArray, val fileBytes: Long) : Read()
        class Failed(val problem: Problem) : Read()
    }

    /** What a screen needs before any key work: whether [head] (the file's first bytes) is a chat copy and its archive id. */
    fun head(bytes: ByteArray): BackupStream.Head? = BackupStream.head(bytes)

    /** The plain stream with the first problem it met remembered (HistoryBackup.read answers only null). */
    private class Watch(private val plain: BackupStream.Plain, private val progress: (Long, Long) -> Unit) : InputStream() {
        var problem: BackupFile.Problem? = null
        private var done = 0L
        override fun read(): Int = guard { plain.read() }.also { if (it >= 0) tick(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = guard { plain.read(b, off, len) }.also { if (it > 0) tick(it) }
        private fun tick(n: Int) { done += n; progress(done, plain.size) }
        private inline fun guard(f: () -> Int): Int = try { f() } catch (e: BackupStream.Broken) { problem = e.problem; throw e }
    }

    /** A failure of the phone's own storage inside a sink (a picture file that could not be written): not a verdict on the file. */
    class StorageFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    /**
     * Reads the copy in [input] with [key] and closes [input] when done, whatever happens (B4-8). [sink] gets each
     * record once it is checked; [progress] gets bytes read and the stream's size; [cancelled] is asked between
     * records ([CancellationException]). [scratch] is a directory for the cross-reference index. Never writes outside it.
     * A database or file failure of this phone, in the index or in [sink], is [Problem.NO_SPACE] or [Problem.STORAGE].
     */
    fun read(
        input: InputStream, key: RecoveryKey, scratch: File, cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> }, sink: (HistoryBackup.Record) -> Unit = {},
    ): Read = try {
        readOpen(input, key, scratch, cancelled, progress, sink)
    } catch (e: SQLiteException) {
        Read.Failed(storageProblem(e, scratch))
    } catch (e: StorageFailure) {
        Read.Failed(storageProblem(e, scratch))
    } finally {
        runCatching { input.close() }
    }

    private fun storageProblem(e: Throwable, scratch: File) =
        if (e is SQLiteFullException || scratch.usableSpace < LOW_SPACE) Problem.NO_SPACE else Problem.STORAGE

    private const val LOW_SPACE = 4L * 1024 * 1024

    private fun readOpen(
        input: InputStream, key: RecoveryKey, scratch: File, cancelled: () -> Boolean,
        progress: (Long, Long) -> Unit, sink: (HistoryBackup.Record) -> Unit,
    ): Read {
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = object : DigestInputStream(input, digest) {
            var bytes = 0L
            override fun read(): Int = super.read().also { if (it >= 0) bytes++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytes += it }
        }
        val opened = BackupStream.openStream(key, counted)
        if (opened is BackupStream.Streamed.Failed) return Read.Failed(problem(opened.problem))
        val plain = (opened as BackupStream.Streamed.Ok).stream
        if (plain.purpose != BackupFile.Purpose.HISTORY) { plain.close(); return Read.Failed(Problem.NOT_CHAT_COPY) }
        scratch.mkdirs()
        val watch = Watch(plain, progress)
        var cancel = false
        try {
            SqlIndex(File(scratch, "index-${UUID.randomUUID()}.db")).use { index ->
                val manifest = try {
                    HistoryBackup.read(watch, index) { record ->
                        if (cancelled()) { cancel = true; throw CancellationException("cancelled") }
                        sink(record)
                    }
                } catch (e: HistoryBackup.Unsupported) {
                    return Read.Failed(Problem.TOO_NEW)
                }
                if (cancel) throw CancellationException("cancelled")
                if (manifest == null) return Read.Failed(watch.problem?.let { problem(it) } ?: Problem.KEY_OR_FILE_DOES_NOT_MATCH)
                return Read.Ok(manifest, plain.archiveId, digest.digest(), counted.bytes)
            }
        } finally {
            plain.close()
        }
    }

    private fun problem(p: BackupFile.Problem) = when (p) {
        BackupFile.Problem.NOT_A_BACKUP -> Problem.NOT_A_COPY
        BackupFile.Problem.UNSUPPORTED -> Problem.TOO_NEW
        BackupFile.Problem.TOO_BIG -> Problem.TOO_BIG
        BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH -> Problem.KEY_OR_FILE_DOES_NOT_MATCH
    }
}

/**
 * Saving a full chat copy (backup spec 5.6), the part after the snapshot and the preview: seal into a private
 * temporary file, check that file, copy it to the place the person picked, then reopen what was written there and check
 * it again before saying so. A failure says which step failed; a copy that was written but not read back is never called
 * checked, and the temporary file never outlives the call.
 */
class ChatBackup(private val workDir: File) {
    /**
     * [NO_SPACE] and [STORAGE] are this phone's storage (never "your chats changed", B4-1); [MISMATCH] means what was
     * read back from the destination positively differs from what was written; [NOT_CHECKED] means it could not be
     * read back at all; [FAILED] is a bug in the app, not in the place chosen.
     */
    enum class Why { CHANGED, TOO_BIG, NO_SPACE, STORAGE, DESTINATION, NOT_CHECKED, MISMATCH, CANCELLED, FAILED }

    sealed class Saved {
        /** [bytes] and [archiveId] are of the file as it was read back from where it was saved. */
        class Ok(val counts: ChatExport.Counts, val bytes: Long, val archiveId: ByteArray) : Saved()
        /**
         * [partial]: something may have been written at the destination, which is not a good copy. [discard]: the
         * document the person's picker made is ours and known to be no good (empty, cut, or different from what was
         * written), so the caller removes it (B4-7). Only [Why.NOT_CHECKED] keeps it: the file may be fine, and the
         * screen says it was not read back.
         */
        class Failed(val why: Why, val partial: Boolean = false, val discard: Boolean = why != Why.NOT_CHECKED) : Saved()
    }

    /**
     * Seals [snapshot] under [key] for [preview] and puts it where [destination] opens, then checks it through [readBack].
     * [progress] gets a percentage 0..100 over the whole job: sealing, checking, copying, checking again.
     */
    fun save(
        snapshot: ChatSnapshot, preview: ChatExport.Preview.Ok, key: RecoveryKey, now: Long, generation: Long,
        destination: () -> OutputStream?, readBack: () -> InputStream?, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {},
    ): Saved = try {
        saveInner(snapshot, preview, key, now, generation, destination, readBack, cancelled, progress)
    } catch (e: CancellationException) {
        Saved.Failed(Why.CANCELLED)
    } catch (e: SQLiteFullException) {
        Saved.Failed(Why.NO_SPACE)
    } catch (e: SQLiteException) {
        Saved.Failed(if (workDir.usableSpace < LOW_SPACE) Why.NO_SPACE else Why.STORAGE)
    } catch (e: IOException) {
        Saved.Failed(if (workDir.usableSpace < LOW_SPACE) Why.NO_SPACE else Why.STORAGE)
    } catch (e: RuntimeException) {
        Saved.Failed(Why.FAILED)
    }

    private fun saveInner(
        snapshot: ChatSnapshot, preview: ChatExport.Preview.Ok, key: RecoveryKey, now: Long, generation: Long,
        destination: () -> OutputStream?, readBack: () -> InputStream?, cancelled: () -> Boolean, progress: (Int) -> Unit,
    ): Saved {
        workDir.mkdirs()
        if (workDir.usableSpace < preview.sealedBytes * 2 + SPARE) return Saved.Failed(Why.NO_SPACE)
        val temporary = File(workDir, "out-${UUID.randomUUID()}.pmbk")
        var written = false
        try {
            try {
                SqlIndex(File(workDir, "index-${UUID.randomUUID()}.db")).use { index ->
                    temporary.outputStream().buffered().use { out ->
                        ChatExport.seal(snapshot, key, now, generation, preview.plainBytes, out, index, cancelled) { done, total -> progress(share(0, 40, done, total)) }
                    }
                }
            } catch (e: ChatExport.Changed) {
                return Saved.Failed(Why.CHANGED)
            }
            val own = temporary.inputStream().buffered().use { ArchiveReader.read(it, key, workDir, cancelled, progress = { done, total -> progress(share(40, 60, done, total)) }) }
            if (own is ArchiveReader.Read.Failed && own.problem == ArchiveReader.Problem.NO_SPACE) return Saved.Failed(Why.NO_SPACE)
            if (own is ArchiveReader.Read.Failed && own.problem == ArchiveReader.Problem.STORAGE) return Saved.Failed(Why.STORAGE)
            if (own !is ArchiveReader.Read.Ok || own.fileBytes != preview.sealedBytes) return Saved.Failed(Why.CHANGED)
            try {
                val out = destination() ?: return Saved.Failed(Why.DESTINATION)
                written = true
                out.use { sink ->
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (cancelled()) return Saved.Failed(Why.CANCELLED, partial = true)
                            sink.write(buffer, 0, n)
                            copied += n
                            progress(share(60, 85, copied, preview.sealedBytes))
                        }
                        sink.flush()
                    }
                }
            } catch (e: IOException) {
                return Saved.Failed(Why.DESTINATION, partial = written)
            } catch (e: RuntimeException) {
                if (e is CancellationException) throw e
                return Saved.Failed(Why.DESTINATION, partial = written)
            }
            // Read back from where it was written. A destination that cannot be read is "not checked" (the file may be
            // fine and is kept); one that is read to its end and differs is a bad file and is removed (B4-7).
            val back = try {
                val input = readBack() ?: return Saved.Failed(Why.NOT_CHECKED, partial = true)
                ArchiveReader.read(input.buffered(), key, workDir, cancelled, progress = { done, total -> progress(share(85, 100, done, total)) })
            } catch (e: CancellationException) {
                return Saved.Failed(Why.CANCELLED, partial = true)
            } catch (e: IOException) {
                return Saved.Failed(Why.NOT_CHECKED, partial = true)
            } catch (e: SQLiteException) {
                return Saved.Failed(Why.NOT_CHECKED, partial = true)
            } catch (e: RuntimeException) {
                return Saved.Failed(Why.NOT_CHECKED, partial = true)
            }
            if (back is ArchiveReader.Read.Failed && back.problem in setOf(ArchiveReader.Problem.NO_SPACE, ArchiveReader.Problem.STORAGE)) {
                return Saved.Failed(Why.NOT_CHECKED, partial = true)
            }
            if (back !is ArchiveReader.Read.Ok || !back.fileDigest.contentEquals(own.fileDigest) || back.fileBytes != own.fileBytes ||
                !back.archiveId.contentEquals(own.archiveId)) return Saved.Failed(Why.MISMATCH, partial = true)
            progress(100)
            return Saved.Ok(preview.counts, back.fileBytes, back.archiveId)
        } finally {
            temporary.delete()
        }
    }

    private fun share(from: Int, to: Int, done: Long, total: Long): Int = if (total <= 0) from else from + ((to - from) * minOf(1.0, done.toDouble() / total)).toInt()

    companion object {
        private const val SPARE = 4L * 1024 * 1024
        private const val LOW_SPACE = 4L * 1024 * 1024

        /** Removes sealed or scratch files a crash left in [dir]; call at start. */
        fun sweep(dir: File, before: Long = Long.MAX_VALUE) {
            dir.listFiles()?.filter { (it.name.startsWith("out-") || it.name.startsWith("index-")) && it.lastModified() <= before }?.forEach { it.delete() }
        }
    }
}

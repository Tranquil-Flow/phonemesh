package org.phonemesh.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteStatement
import org.phonemesh.core.backup.BackupStream
import org.phonemesh.core.backup.ContactsBackup
import org.phonemesh.core.backup.HistoryBackup
import org.phonemesh.core.backup.RecoveryKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.phonemesh.core.backup.BackupFile

/**
 * Putting a full chat copy on this phone, read only (backup spec 5.7). Nothing here makes a live chat, contact, session,
 * outbox row, receipt, notification or group: the records go to the `archive_*` tables and nowhere else, and the only
 * live table touched is `recovered_contacts`, the saved people to add again (the same list a contacts-only copy fills).
 *
 * The steps, each journaled in `backup_imports` so a crash at any of them is cleaned up by [recover] at the next start:
 *  1. BEGUN: the journal row, then a private staging folder.
 *  2. STAGED: every chunk authenticated and every record checked ([ArchiveReader]) into a staging database; pictures
 *     into files named by this app. A copy that fails any check is dropped here and changes nothing.
 *  3. VALIDATED: the staged counts equal the manifest and every complete picture has its file.
 *  4. FILES_MOVED: the picture folder moves to its final place, `archives/<id>`.
 *  5. COMMITTED: one SQLite transaction copies the staged rows, adds the people to add again and drops the journal row.
 *  6. CLEANED: the staging folder goes.
 * Before COMMITTED nothing is visible, and a folder with no committed copy is a cleanup candidate; after it the copy is
 * whole. The same archive id and file digest again is [Result.Already]; the same id with other bytes is refused.
 */
class ArchiveImport(
    private val history: History, private val archivesDir: File, private val workDir: File,
    /** Test seam: the free bytes of a folder's partition. */
    private val usableSpace: (File) -> Long = { it.usableSpace },
    /** Test seam: asked before each record is staged; may throw as a full disk would. */
    private val stageHook: (HistoryBackup.Record) -> Unit = {},
) {
    enum class Step { BEGUN, STAGED, VALIDATED, FILES_MOVED, COMMITTED, CLEANED }

    /** Test seam: the process "died" right after [at]: nothing is cleaned up, as after a real crash. */
    class Crash(val at: Step) : Error("crash after $at")

    enum class Refused { SAME_ID_OTHER_BYTES, TOO_MANY_COPIES }

    sealed class Result {
        class Saved(val archiveId: ByteArray, val chats: Int, val messages: Int, val people: Int) : Result()
        class Already(val archiveId: ByteArray) : Result()
        class Refused(val why: ArchiveImport.Refused) : Result()
        class Failed(val problem: ArchiveReader.Problem) : Result()
        /** This phone is out of room (B4-1): the file and the card are not at fault. */
        object NoSpace : Result()
        /** This phone's storage failed in some other way (B4-1): nothing was kept. */
        object StorageError : Result()
        /** The file could not be opened or read to its end: the place it is in is not answering. */
        object Unreadable : Result()
    }

    private val rows = ArchiveRows(history)

    /**
     * On a worker thread. [open] opens the file once: it is copied into private storage while its SHA-256 is taken, and
     * everything after (the archive id, the duplicate check, the read, the commit) works on that one copy, so the id
     * that is checked is the id that is committed (B4-9). Only one import or removal runs at a time.
     */
    fun import(
        open: () -> InputStream?, key: RecoveryKey, now: Long, cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> }, crashAt: Step? = null,
    ): Result = synchronized(ArchiveRows.LOCK) { importLocked(open, key, now, cancelled, progress, crashAt) }

    private fun importLocked(
        open: () -> InputStream?, key: RecoveryKey, now: Long, cancelled: () -> Boolean, progress: (Long, Long) -> Unit, crashAt: Step?,
    ): Result {
        workDir.mkdirs()
        val copy = File(workDir, "$COPY_PREFIX${UUID.randomUUID()}.pmbk")
        try {
            val taken = when (val r = take(open, copy, cancelled)) {
                is Taken.Stop -> return r.result
                is Taken.Done -> r
            }
            val id = taken.archiveId
            val known = rows.archive(id)
            if (known != null) return if (MessageDigest.isEqual(taken.digest, known.digest)) Result.Already(id) else Result.Refused(Refused.SAME_ID_OTHER_BYTES)
            if (rows.archives().size >= ArchiveRows.MAX_ARCHIVES) return Result.Refused(Refused.TOO_MANY_COPIES)
            // Staging holds about the plaintext again, and the committed rows as much once more: sized from the file (B4-1).
            if (usableSpace(workDir) < 2 * taken.length + SPARE) return Result.NoSpace
            return stageAndCommit(copy, id, key, now, cancelled, progress, crashAt)
        } finally {
            copy.delete()
        }
    }

    private sealed class Taken {
        class Stop(val result: Result) : Taken()
        class Done(val archiveId: ByteArray, val digest: ByteArray, val length: Long) : Taken()
    }

    /** Copies what [open] gives into [into], checking only that it starts like a chat copy; the first open and the only one. */
    private fun take(open: () -> InputStream?, into: File, cancelled: () -> Boolean): Taken {
        val input = try { open() } catch (e: IOException) { return Taken.Stop(Result.Unreadable) } ?: return Taken.Stop(Result.Unreadable)
        input.use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val head = ByteArray(HEAD_BYTES)
            val got = try { source.readNBytesCompat(head) } catch (e: IOException) { return Taken.Stop(Result.Unreadable) }
            val info = BackupStream.head(head.copyOf(got)) ?: return Taken.Stop(Result.Failed(ArchiveReader.Problem.NOT_A_COPY))
            if (info.purpose != BackupFile.Purpose.HISTORY) return Taken.Stop(Result.Failed(ArchiveReader.Problem.NOT_CHAT_COPY))
            var length = 0L
            try {
                into.outputStream().buffered(64 * 1024).use { out ->
                    out.write(head); digest.update(head); length += head.size
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw CancellationException("cancelled")
                        val n = try { source.read(buffer) } catch (e: IOException) { return Taken.Stop(Result.Unreadable) }
                        if (n < 0) break
                        out.write(buffer, 0, n); digest.update(buffer, 0, n); length += n
                        if (length > MAX_FILE) return Taken.Stop(Result.Failed(ArchiveReader.Problem.TOO_BIG))
                    }
                }
            } catch (e: IOException) {
                // Writing to this phone failed (the reads are guarded above): its room or its storage, not the file.
                return Taken.Stop(if (usableSpace(workDir) < SPARE) Result.NoSpace else Result.StorageError)
            }
            return Taken.Done(info.archiveId, digest.digest(), length)
        }
    }

    private fun stageAndCommit(
        copy: File, id: ByteArray, key: RecoveryKey, now: Long, cancelled: () -> Boolean, progress: (Long, Long) -> Unit, crashAt: Step?,
    ): Result {
        val hex = ArchiveRows.hex(id)
        val staging = File(workDir, "staging-$hex")
        staging.deleteRecursively()
        rows.journalBegin(id, now)
        staging.mkdirs()
        File(staging, "pictures").mkdirs()
        try {
            hook(Step.BEGUN, crashAt)
            val stage = Staging(File(staging, "stage.db"), File(staging, "pictures"))
            val read = try {
                stage.use { s ->
                    val result = ArchiveReader.read(copy.inputStream(), key, workDir, cancelled, progress) { stageHook(it); s.add(it) }
                    if (result is ArchiveReader.Read.Ok) s.finish()
                    result
                }
            } catch (e: IOException) {
                abort(id, staging)
                return if (usableSpace(workDir) < SPARE) Result.NoSpace else Result.StorageError
            } catch (e: SQLiteException) {
                abort(id, staging)
                return if (e is SQLiteFullException || usableSpace(workDir) < SPARE) Result.NoSpace else Result.StorageError
            }
            if (read !is ArchiveReader.Read.Ok) {
                abort(id, staging)
                return when (val p = (read as ArchiveReader.Read.Failed).problem) {
                    ArchiveReader.Problem.NO_SPACE -> Result.NoSpace
                    ArchiveReader.Problem.STORAGE -> Result.StorageError
                    else -> Result.Failed(p)
                }
            }
            // The id of the bytes that were read is the id everything is filed under (B4-9).
            if (!read.archiveId.contentEquals(id)) { abort(id, staging); return Result.Unreadable }
            hook(Step.STAGED, crashAt)
            val m = read.manifest
            val staged = Staging.counts(File(staging, "stage.db"))
            if (staged != listOf(m.contacts, m.chats, m.messages, m.reactions, m.pictures) || !Staging.picturesPresent(File(staging, "stage.db"), File(staging, "pictures"))) {
                abort(id, staging)
                return Result.Failed(ArchiveReader.Problem.KEY_OR_FILE_DOES_NOT_MATCH)
            }
            // The private copy of the file is not needed any more: its room is free for the committed rows.
            copy.delete()
            rows.journalStep(id, "VALIDATED")
            hook(Step.VALIDATED, crashAt)
            val finalFolder = File(archivesDir, hex)
            archivesDir.mkdirs()
            finalFolder.deleteRecursively()
            check(File(staging, "pictures").renameTo(finalFolder)) { "picture folder not moved" }
            rows.journalStep(id, "FILES_MOVED")
            hook(Step.FILES_MOVED, crashAt)
            val people = try {
                commit(read, File(staging, "stage.db"), now, cancelled)
            } catch (e: SQLiteException) {
                if (!isStorage(e)) throw e
                abort(id, staging)
                return if (e is SQLiteFullException || usableSpace(workDir) < SPARE) Result.NoSpace else Result.StorageError
            }
            hook(Step.COMMITTED, crashAt)
            staging.deleteRecursively()
            hook(Step.CLEANED, crashAt)
            return Result.Saved(id, m.chats, m.messages, people)
        } catch (e: Crash) {
            throw e
        } catch (e: CancellationException) {
            abort(id, staging)
            throw e
        } catch (e: Throwable) {
            abort(id, staging)
            throw e
        }
    }

    private fun hook(step: Step, crashAt: Step?) { if (crashAt == step) throw Crash(step) }

    /** A failed import changes nothing: staging, partly committed rows, a moved picture folder with no committed copy, and the journal row go. */
    private fun abort(id: ByteArray, staging: File) {
        runCatching { staging.deleteRecursively() }
        runCatching {
            if (rows.archive(id) == null) {
                rows.removeRows(id)
                File(archivesDir, ArchiveRows.hex(id)).deleteRecursively()
            }
        }
        runCatching { rows.journalDrop(id) }
    }

    private fun isStorage(e: SQLiteException) = e is SQLiteFullException || e is SQLiteDiskIOException || usableSpace(workDir) < SPARE

    /**
     * The staged rows go into the archive tables in small steps of [BATCH] rows, each its own short transaction, so the
     * receive path and the screen are never held for long (B4-3). They are invisible until the last step, one small
     * transaction: the archive's own row, the people to add again and the journal row gone. A crash or failure before
     * it leaves rows nothing lists, which [abort] and [recover] remove.
     */
    private fun commit(read: ArchiveReader.Read.Ok, stage: File, now: Long, cancelled: () -> Boolean): Int {
        val id = ArchiveRows.hex(read.archiveId)
        val m = read.manifest
        val db = history.writableDatabase
        db.execSQL("ATTACH DATABASE ? AS stage", arrayOf(stage.path))
        try {
            steps(db, "stage.stage_contacts", "INSERT INTO archive_contacts SELECT ?, local_id, identity, card, saved_name, colour, historical_met, voice, added FROM stage.stage_contacts", id, cancelled)
            steps(db, "stage.stage_chats", "INSERT INTO archive_chats SELECT ?, local_id, kind, contact_id, title, pinned FROM stage.stage_chats", id, cancelled)
            steps(db, "stage.stage_messages", "INSERT INTO archive_messages SELECT ?, local_id, chat_id, outgoing, author_contact_id, author_label, text, language, time, status, reply_id, reply_missing, planned_at, picture_id FROM stage.stage_messages", id, cancelled)
            steps(db, "stage.stage_reactions", "INSERT INTO archive_reactions SELECT ?, local_id, target_id, author_contact_id, author_label, emoji, time, was_unsent FROM stage.stage_reactions", id, cancelled)
            steps(db, "stage.stage_pictures", "INSERT INTO archive_pictures SELECT ?, local_id, message_id, complete, codec, width, height, sketch, file, digest FROM stage.stage_pictures", id, cancelled)
            var people = 0
            db.beginTransaction()
            try {
                db.insertOrThrow("backup_archives", null, ContentValues().apply {
                    put("archive_id", id); put("file_digest", read.fileDigest); put("source_identity", m.sourceIdentity); put("generation", m.generation)
                    put("created", m.createdAt); put("own_name", m.ownName); put("scope_start", m.scopeStart); put("scope_end", m.scopeEnd)
                    put("scope_flags", m.scopeFlags); put("contacts", m.contacts); put("chats", m.chats); put("messages", m.messages)
                    put("reactions", m.reactions); put("pictures", m.pictures); put("missing_pictures", m.missingPictures); put("imported", now)
                    put("bytes", read.fileBytes)
                })
                // The people to add again: the same list and rules as a contacts-only copy; never a live contact.
                people = Recovery.restore(history, savedPeople(db, id), read.archiveId, now)
                rows.journalDrop(read.archiveId)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return people
        } finally {
            runCatching { db.execSQL("DETACH DATABASE stage") }
        }
    }

    /** [insert] (with `?` for the archive id, then the id range) over [table] in keyset batches, one transaction each. */
    private fun steps(db: SQLiteDatabase, table: String, insert: String, id: String, cancelled: () -> Boolean) {
        var last = 0L
        while (true) {
            if (cancelled()) throw CancellationException("cancelled")
            val upper = db.rawQuery("SELECT MAX(local_id) FROM (SELECT local_id FROM $table WHERE local_id > ? ORDER BY local_id LIMIT $BATCH)", arrayOf(last.toString()))
                .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null } ?: return
            batches++
            db.beginTransaction()
            try {
                db.execSQL("$insert WHERE local_id > ? AND local_id <= ?", arrayOf<Any>(id, last, upper))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            last = upper
        }
    }

    private fun savedPeople(db: SQLiteDatabase, archive: String): List<ContactsBackup.Contact> =
        db.rawQuery("SELECT local_id, identity, card, saved_name, colour, historical_met, voice, added FROM archive_contacts WHERE archive_id = ? ORDER BY local_id", arrayOf(archive)).use { c ->
            buildList { while (c.moveToNext()) add(ContactsBackup.Contact(c.getLong(0), c.getBlob(1), c.getBlob(2), c.getString(3), c.getInt(4), c.getInt(5) != 0, if (c.isNull(6)) null else c.getString(6), c.getLong(7))) }
        }

    private fun InputStream.readNBytesCompat(into: ByteArray): Int {
        var at = 0
        while (at < into.size) { val n = read(into, at, into.size - at); if (n < 0) break; at += n }
        return at
    }

    /** The staging database and picture files of one import: records in, checked, until the commit moves them. */
    private class Staging(file: File, private val pictures: File) : AutoCloseable {
        private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
            rawQuery("PRAGMA synchronous=OFF", null).use { it.moveToFirst() }
            execSQL("CREATE TABLE stage_contacts (local_id INTEGER PRIMARY KEY, identity BLOB NOT NULL, card BLOB NOT NULL, saved_name TEXT NOT NULL, colour INTEGER NOT NULL, historical_met INTEGER NOT NULL, voice TEXT, added INTEGER NOT NULL)")
            execSQL("CREATE TABLE stage_chats (local_id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, contact_id INTEGER NOT NULL, title TEXT NOT NULL, pinned INTEGER NOT NULL)")
            execSQL("CREATE TABLE stage_messages (local_id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, outgoing INTEGER NOT NULL, author_contact_id INTEGER NOT NULL, author_label TEXT, text TEXT NOT NULL, language TEXT, time INTEGER NOT NULL, status INTEGER NOT NULL, reply_id INTEGER NOT NULL, reply_missing INTEGER NOT NULL, planned_at INTEGER NOT NULL, picture_id INTEGER NOT NULL)")
            execSQL("CREATE TABLE stage_reactions (local_id INTEGER PRIMARY KEY, target_id INTEGER NOT NULL, author_contact_id INTEGER NOT NULL, author_label TEXT, emoji TEXT NOT NULL, time INTEGER NOT NULL, was_unsent INTEGER NOT NULL)")
            execSQL("CREATE TABLE stage_pictures (local_id INTEGER PRIMARY KEY, message_id INTEGER NOT NULL, complete INTEGER NOT NULL, codec INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, sketch BLOB NOT NULL, file TEXT, digest BLOB NOT NULL)")
            beginTransaction()
        }
        private val contact = db.compileStatement("INSERT INTO stage_contacts VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        private val chat = db.compileStatement("INSERT INTO stage_chats VALUES (?, ?, ?, ?, ?)")
        private val message = db.compileStatement("INSERT INTO stage_messages VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        private val reaction = db.compileStatement("INSERT INTO stage_reactions VALUES (?, ?, ?, ?, ?, ?, ?)")
        private val picture = db.compileStatement("INSERT INTO stage_pictures VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")

        fun add(r: HistoryBackup.Record) {
            when (r) {
                is HistoryBackup.Contact -> contact.run {
                    val c = r.contact
                    bind(1, c.id); bind(2, c.identity); bind(3, c.card); bind(4, c.savedName); bind(5, c.colour.toLong()); bind(6, if (c.historicalMet) 1L else 0L)
                    bind(7, c.voice); bind(8, c.addedAt); execute()
                }
                is HistoryBackup.Chat -> chat.run { bind(1, r.id); bind(2, r.kind.toLong()); bind(3, r.contactId); bind(4, r.title); bind(5, if (r.pinned) 1L else 0L); execute() }
                is HistoryBackup.Message -> message.run {
                    bind(1, r.id); bind(2, r.chatId); bind(3, if (r.outgoing) 1L else 0L); bind(4, r.authorContactId); bind(5, r.authorLabel); bind(6, r.text); bind(7, r.language)
                    bind(8, r.time); bind(9, r.status.toLong()); bind(10, r.replyId); bind(11, if (r.replyMissing) 1L else 0L); bind(12, r.plannedAt); bind(13, r.pictureId); execute()
                }
                is HistoryBackup.Reaction -> reaction.run {
                    bind(1, r.id); bind(2, r.targetId); bind(3, r.authorContactId); bind(4, r.authorLabel); bind(5, r.emoji); bind(6, r.time); bind(7, if (r.wasUnsent) 1L else 0L); execute()
                }
                is HistoryBackup.Picture -> picture.run {
                    // The file's name is made here, never read from the copy (spec 5.5).
                    val name = if (r.complete && r.data.isNotEmpty()) "${r.id}.webp" else null
                    if (name != null) try { File(pictures, name).writeBytes(r.data) } catch (e: IOException) { throw ArchiveReader.StorageFailure("a picture file could not be written", e) }
                    bind(1, r.id); bind(2, r.messageId); bind(3, if (r.complete) 1L else 0L); bind(4, r.codec.toLong()); bind(5, r.width.toLong()); bind(6, r.height.toLong())
                    bind(7, r.sketch); bind(8, name); bind(9, java.security.MessageDigest.getInstance("SHA-256").digest(r.data)); execute()
                }
            }
        }

        fun finish() { db.setTransactionSuccessful(); db.endTransaction() }

        override fun close() {
            runCatching { contact.close(); chat.close(); message.close(); reaction.close(); picture.close() }
            // A scratch file with its journal off: end the transaction as a commit, never a rollback (undefined without a journal).
            runCatching { if (db.inTransaction()) { db.setTransactionSuccessful(); db.endTransaction() } }
            runCatching { db.close() }
        }

        private fun SQLiteStatement.bind(i: Int, v: Long) = bindLong(i, v)
        private fun SQLiteStatement.bind(i: Int, v: String?) { if (v == null) bindNull(i) else bindString(i, v) }
        private fun SQLiteStatement.bind(i: Int, v: ByteArray) = bindBlob(i, v)

        companion object {
            fun counts(file: File): List<Int> = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                listOf("contacts", "chats", "messages", "reactions", "pictures").map { t -> db.rawQuery("SELECT COUNT(*) FROM stage_$t", null).use { it.moveToFirst(); it.getInt(0) } }
            }

            fun picturesPresent(file: File, pictures: File): Boolean = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT file FROM stage_pictures WHERE file IS NOT NULL", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.all { File(pictures, it).isFile } }
            }
        }
    }

    companion object {
        private const val HEAD_BYTES = 28
        private const val SPARE = 16L * 1024 * 1024
        private const val BATCH = 2_000
        /** The biggest file a chat copy can be: header, the plaintext cap and the tag and length of every chunk. */
        private const val MAX_FILE = BackupFile.HEADER + BackupFile.MAX_PLAIN + 20L * BackupFile.MAX_CHUNKS
        const val COPY_PREFIX = "copy-"

        /** Test seam: how many short transactions the restores so far used to put staged rows into the history. */
        @Volatile internal var batches = 0

        /**
         * At every start (before any import): finishes or undoes what a crash left. A journal row without a committed
         * copy loses its partly committed rows, staging folder and picture folder; a removal that was under way is
         * finished; a picture folder or staging folder nothing owns is removed; scratch files from saving go too. Never
         * touches a committed copy. Each sweep stands alone, so one failure does not skip the rest (B4-13). Scratch files
         * newer than [before] are left (the caller started work after the process did).
         */
        fun recover(history: History, archivesDir: File, workDir: File, before: Long = Long.MAX_VALUE) = synchronized(ArchiveRows.LOCK) {
            val rows = ArchiveRows(history)
            runCatching {
                for (j in rows.journal()) {
                    val hex = ArchiveRows.hex(j.archiveId)
                    if (j.step == ArchiveRows.REMOVING) { rows.finishRemoving(j.archiveId, archivesDir); continue }
                    if (rows.archive(j.archiveId) == null) {
                        rows.removeRows(j.archiveId)
                        File(archivesDir, hex).deleteRecursively()
                    }
                    File(workDir, "staging-$hex").deleteRecursively()
                    rows.journalDrop(j.archiveId)
                }
            }
            runCatching { workDir.listFiles()?.filter { it.name.startsWith("staging-") }?.forEach { it.deleteRecursively() } }
            runCatching {
                val kept = rows.archives().map { ArchiveRows.hex(it.archiveId) }.toSet()
                archivesDir.listFiles()?.filter { it.name !in kept }?.forEach { it.deleteRecursively() }
            }
            runCatching { workDir.listFiles()?.filter { it.name.startsWith(COPY_PREFIX) && it.lastModified() <= before }?.forEach { it.delete() } }
            runCatching { ChatSnapshot.sweep(workDir, before) }
            runCatching { ChatBackup.sweep(workDir, before) }
            Unit
        }
    }
}

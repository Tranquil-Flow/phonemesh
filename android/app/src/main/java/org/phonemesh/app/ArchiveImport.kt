package org.phonemesh.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
import java.util.concurrent.CancellationException

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
class ArchiveImport(private val history: History, private val archivesDir: File, private val workDir: File) {
    enum class Step { BEGUN, STAGED, VALIDATED, FILES_MOVED, COMMITTED, CLEANED }

    /** Test seam: the process "died" right after [at]: nothing is cleaned up, as after a real crash. */
    class Crash(val at: Step) : Error("crash after $at")

    enum class Refused { SAME_ID_OTHER_BYTES, TOO_MANY_COPIES }

    sealed class Result {
        class Saved(val archiveId: ByteArray, val chats: Int, val messages: Int, val people: Int) : Result()
        class Already(val archiveId: ByteArray) : Result()
        class Refused(val why: ArchiveImport.Refused) : Result()
        class Failed(val problem: ArchiveReader.Problem) : Result()
        object NoSpace : Result()
        /** The file could not be opened or read to its end: the place it is in is not answering. */
        object Unreadable : Result()
    }

    private val rows = ArchiveRows(history)

    /** On a worker thread. [open] opens the file anew each time it is asked (the id check reads it, then the import). */
    fun import(
        open: () -> InputStream?, key: RecoveryKey, now: Long, cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> }, crashAt: Step? = null,
    ): Result {
        val head = ByteArray(HEAD_BYTES)
        val got = try { open()?.use { it.readNBytesCompat(head) } } catch (e: IOException) { return Result.Unreadable } ?: return Result.Unreadable
        val info = BackupStream.head(head.copyOf(got)) ?: return Result.Failed(ArchiveReader.Problem.NOT_A_COPY)
        if (info.purpose != org.phonemesh.core.backup.BackupFile.Purpose.HISTORY) return Result.Failed(ArchiveReader.Problem.NOT_CHAT_COPY)
        val id = info.archiveId
        val known = rows.archive(id)
        if (known != null) {
            val digest = try { open()?.use { digestOf(it, cancelled) } } catch (e: IOException) { return Result.Unreadable } ?: return Result.Unreadable
            return if (MessageDigest.isEqual(digest, known.digest)) Result.Already(id) else Result.Refused(Refused.SAME_ID_OTHER_BYTES)
        }
        if (rows.archives().size >= ArchiveRows.MAX_ARCHIVES) return Result.Refused(Refused.TOO_MANY_COPIES)
        workDir.mkdirs()
        if (workDir.usableSpace < MIN_SPACE) return Result.NoSpace
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
                    val result = ArchiveReader.read(open() ?: throw IOException("gone"), key, workDir, cancelled, progress) { s.add(it) }
                    if (result is ArchiveReader.Read.Ok) s.finish()
                    result
                }
            } catch (e: IOException) {
                abort(id, staging)
                return if (workDir.usableSpace < MIN_SPACE) Result.NoSpace else Result.Unreadable
            } catch (e: SQLiteException) {
                abort(id, staging)
                return Result.NoSpace
            }
            if (read !is ArchiveReader.Read.Ok) { abort(id, staging); return Result.Failed((read as ArchiveReader.Read.Failed).problem) }
            hook(Step.STAGED, crashAt)
            val m = read.manifest
            val staged = Staging.counts(File(staging, "stage.db"))
            if (staged != listOf(m.contacts, m.chats, m.messages, m.reactions, m.pictures) || !Staging.picturesPresent(File(staging, "stage.db"), File(staging, "pictures"))) {
                abort(id, staging)
                return Result.Failed(ArchiveReader.Problem.KEY_OR_FILE_DOES_NOT_MATCH)
            }
            rows.journalStep(id, "VALIDATED")
            hook(Step.VALIDATED, crashAt)
            val finalFolder = File(archivesDir, hex)
            archivesDir.mkdirs()
            finalFolder.deleteRecursively()
            check(File(staging, "pictures").renameTo(finalFolder)) { "picture folder not moved" }
            rows.journalStep(id, "FILES_MOVED")
            hook(Step.FILES_MOVED, crashAt)
            val people = commit(read, File(staging, "stage.db"), now)
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

    /** A failed import changes nothing: staging, a moved picture folder with no committed copy, and the journal row go. */
    private fun abort(id: ByteArray, staging: File) {
        runCatching { staging.deleteRecursively() }
        runCatching { if (rows.archive(id) == null) File(archivesDir, ArchiveRows.hex(id)).deleteRecursively() }
        runCatching { rows.journalDrop(id) }
    }

    /** One transaction: the staged rows, the archive's own row and the people to add again, and the journal row gone. */
    private fun commit(read: ArchiveReader.Read.Ok, stage: File, now: Long): Int {
        val id = ArchiveRows.hex(read.archiveId)
        val m = read.manifest
        val db = history.writableDatabase
        db.execSQL("ATTACH DATABASE ? AS stage", arrayOf(stage.path))
        try {
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
                val args = arrayOf<Any>(id)
                db.execSQL("INSERT INTO archive_contacts SELECT ?, local_id, identity, card, saved_name, colour, historical_met, voice, added FROM stage.stage_contacts", args)
                db.execSQL("INSERT INTO archive_chats SELECT ?, local_id, kind, contact_id, title, pinned FROM stage.stage_chats", args)
                db.execSQL("INSERT INTO archive_messages SELECT ?, local_id, chat_id, outgoing, author_contact_id, author_label, text, language, time, status, reply_id, reply_missing, planned_at, picture_id FROM stage.stage_messages", args)
                db.execSQL("INSERT INTO archive_reactions SELECT ?, local_id, target_id, author_contact_id, author_label, emoji, time, was_unsent FROM stage.stage_reactions", args)
                db.execSQL("INSERT INTO archive_pictures SELECT ?, local_id, message_id, complete, codec, width, height, sketch, file, digest FROM stage.stage_pictures", args)
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

    private fun savedPeople(db: SQLiteDatabase, archive: String): List<ContactsBackup.Contact> =
        db.rawQuery("SELECT local_id, identity, card, saved_name, colour, historical_met, voice, added FROM archive_contacts WHERE archive_id = ? ORDER BY local_id", arrayOf(archive)).use { c ->
            buildList { while (c.moveToNext()) add(ContactsBackup.Contact(c.getLong(0), c.getBlob(1), c.getBlob(2), c.getString(3), c.getInt(4), c.getInt(5) != 0, if (c.isNull(6)) null else c.getString(6), c.getLong(7))) }
        }

    private fun digestOf(input: InputStream, cancelled: () -> Boolean): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        DigestInputStream(input, digest).use { s -> while (s.read(buffer) >= 0) if (cancelled()) throw CancellationException("cancelled") }
        return digest.digest()
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
                    if (name != null) File(pictures, name).writeBytes(r.data)
                    bind(1, r.id); bind(2, r.messageId); bind(3, if (r.complete) 1L else 0L); bind(4, r.codec.toLong()); bind(5, r.width.toLong()); bind(6, r.height.toLong())
                    bind(7, r.sketch); bind(8, name); bind(9, java.security.MessageDigest.getInstance("SHA-256").digest(r.data)); execute()
                }
            }
        }

        fun finish() { db.setTransactionSuccessful(); db.endTransaction() }

        override fun close() {
            runCatching { contact.close(); chat.close(); message.close(); reaction.close(); picture.close() }
            runCatching { if (db.inTransaction()) db.endTransaction() }
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
        private const val MIN_SPACE = 16L * 1024 * 1024

        /**
         * At every start (before any import): finishes or undoes what a crash left. A journal row without a committed
         * copy loses its staging folder and picture folder; a picture folder or staging folder nothing owns is removed;
         * scratch files from saving go too. Never touches a committed copy.
         */
        fun recover(history: History, archivesDir: File, workDir: File) {
            val rows = ArchiveRows(history)
            val journal = rows.journal()
            for (j in journal) {
                val hex = ArchiveRows.hex(j.archiveId)
                if (rows.archive(j.archiveId) == null) File(archivesDir, hex).deleteRecursively()
                File(workDir, "staging-$hex").deleteRecursively()
                rows.journalDrop(j.archiveId)
            }
            workDir.listFiles()?.filter { it.name.startsWith("staging-") }?.forEach { it.deleteRecursively() }
            val kept = rows.archives().map { ArchiveRows.hex(it.archiveId) }.toSet()
            archivesDir.listFiles()?.filter { it.name !in kept }?.forEach { it.deleteRecursively() }
            ChatSnapshot.sweep(workDir)
            ChatBackup.sweep(workDir)
        }
    }
}

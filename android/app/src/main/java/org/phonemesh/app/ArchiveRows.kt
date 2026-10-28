package org.phonemesh.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Saved chat copies read back (backup spec 5.7, History version 22): archive tables keyed by `(archive_id, local_id)`,
 * kept apart from every live table. Nothing here is a live chat, contact, session, outbox row or group: no sender,
 * receiver, notification, speech or hub code reads these tables, and a saved copy never becomes one (the boundary test
 * greps the sources for these names). A copy shows only once its row is in `backup_archives`; `backup_imports` is the
 * journal of one still being made (see [ArchiveImport]).
 */
class ArchiveRows(private val history: History) {
    private val db: SQLiteDatabase get() = history.writableDatabase

    /** One saved copy. [created] is when it was made (inside the file, unverified), [imported] when it came to this phone. */
    class ArchiveRow(
        val archiveId: ByteArray, val digest: ByteArray, val sourceIdentity: ByteArray, val generation: Long, val created: Long, val ownName: String,
        val scopeStart: Long, val scopeEnd: Long, val scopeFlags: Int, val contacts: Int, val chats: Int, val messages: Int, val reactions: Int,
        val pictures: Int, val missingPictures: Int, val imported: Long, val bytes: Long,
    ) { val hex: String get() = hex(archiveId) }

    class ChatRow(val archiveId: ByteArray, val id: Long, val kind: Int, val contactId: Long, val title: String, val pinned: Boolean, val messages: Int, val lastTime: Long)

    class MessageRow(
        val id: Long, val chatId: Long, val outgoing: Boolean, val authorContactId: Long, val authorLabel: String?, val text: String,
        val language: String?, val time: Long, val status: Int, val replyId: Long, val replyMissing: Boolean, val plannedAt: Long, val pictureId: Long,
    )

    class ReactionRow(val id: Long, val targetId: Long, val authorContactId: Long, val authorLabel: String?, val emoji: String, val time: Long, val wasUnsent: Boolean)

    class PictureRow(val id: Long, val messageId: Long, val complete: Boolean, val codec: Int, val width: Int, val height: Int, val sketch: ByteArray, val file: String?)

    class ContactRow(val id: Long, val identity: ByteArray, val name: String, val colour: Int, val historicalMet: Boolean)

    fun archives(): List<ArchiveRow> = db.query("backup_archives", null, null, null, null, null, "imported DESC, rowid DESC").use { c -> buildList { while (c.moveToNext()) add(c.archive()) } }

    fun archive(id: ByteArray): ArchiveRow? = db.query("backup_archives", null, "archive_id = ?", arrayOf(hex(id)), null, null, null).use { if (it.moveToFirst()) it.archive() else null }

    fun chats(id: ByteArray): List<ChatRow> = db.rawQuery(
        "SELECT c.local_id, c.kind, c.contact_id, c.title, c.pinned, COUNT(m.local_id), COALESCE(MAX(m.time), 0) FROM archive_chats c " +
            "LEFT JOIN archive_messages m ON m.archive_id = c.archive_id AND m.chat_id = c.local_id WHERE c.archive_id = ? " +
            "GROUP BY c.local_id ORDER BY c.pinned DESC, MAX(m.time) DESC, c.local_id",
        arrayOf(hex(id)),
    ).use { c -> buildList { while (c.moveToNext()) add(ChatRow(id, c.getLong(0), c.getInt(1), c.getLong(2), c.getString(3), c.getInt(4) != 0, c.getInt(5), c.getLong(6))) } }

    /** The newest [limit] messages of a chat of a copy, oldest first; [before] (a message id) pages back. */
    fun messages(id: ByteArray, chatId: Long, limit: Int = 200, before: Long = Long.MAX_VALUE): List<MessageRow> = db.query(
        "archive_messages", null, "archive_id = ? AND chat_id = ? AND local_id < ?", arrayOf(hex(id), chatId.toString(), before.toString()), null, null, "local_id DESC", limit.toString(),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(MessageRow(
                c.getLong(c.getColumnIndexOrThrow("local_id")), c.getLong(c.getColumnIndexOrThrow("chat_id")), c.getInt(c.getColumnIndexOrThrow("outgoing")) != 0,
                c.getLong(c.getColumnIndexOrThrow("author_contact_id")), c.optString("author_label"), c.getString(c.getColumnIndexOrThrow("text")), c.optString("language"),
                c.getLong(c.getColumnIndexOrThrow("time")), c.getInt(c.getColumnIndexOrThrow("status")), c.getLong(c.getColumnIndexOrThrow("reply_id")),
                c.getInt(c.getColumnIndexOrThrow("reply_missing")) != 0, c.getLong(c.getColumnIndexOrThrow("planned_at")), c.getLong(c.getColumnIndexOrThrow("picture_id")),
            ))
        }
    }.asReversed()

    fun reactions(id: ByteArray, chatId: Long): List<ReactionRow> = db.rawQuery(
        "SELECT r.local_id, r.target_id, r.author_contact_id, r.author_label, r.emoji, r.time, r.was_unsent FROM archive_reactions r " +
            "JOIN archive_messages m ON m.archive_id = r.archive_id AND m.local_id = r.target_id WHERE r.archive_id = ? AND m.chat_id = ? ORDER BY r.local_id",
        arrayOf(hex(id), chatId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(ReactionRow(c.getLong(0), c.getLong(1), c.getLong(2), c.optString(3), c.getString(4), c.getLong(5), c.getInt(6) != 0)) } }

    fun picture(id: ByteArray, pictureId: Long): PictureRow? = db.query("archive_pictures", null, "archive_id = ? AND local_id = ?", arrayOf(hex(id), pictureId.toString()), null, null, null).use { c ->
        if (!c.moveToFirst()) null else PictureRow(
            c.getLong(c.getColumnIndexOrThrow("local_id")), c.getLong(c.getColumnIndexOrThrow("message_id")), c.getInt(c.getColumnIndexOrThrow("complete")) != 0,
            c.getInt(c.getColumnIndexOrThrow("codec")), c.getInt(c.getColumnIndexOrThrow("width")), c.getInt(c.getColumnIndexOrThrow("height")),
            c.getBlob(c.getColumnIndexOrThrow("sketch")), c.optString("file"),
        )
    }

    fun contacts(id: ByteArray): List<ContactRow> = db.query("archive_contacts", null, "archive_id = ?", arrayOf(hex(id)), null, null, "local_id").use { c ->
        buildList { while (c.moveToNext()) add(ContactRow(c.getLong(c.getColumnIndexOrThrow("local_id")), c.getBlob(c.getColumnIndexOrThrow("identity")), c.getString(c.getColumnIndexOrThrow("saved_name")), c.getInt(c.getColumnIndexOrThrow("colour")), c.getInt(c.getColumnIndexOrThrow("historical_met")) != 0)) }
    }

    /** Takes a saved copy off this phone: its rows, then its picture folder. The file it came from is not touched. */
    fun delete(id: ByteArray, archivesDir: File) {
        val h = hex(id)
        db.beginTransaction()
        try {
            for (t in listOf("archive_reactions", "archive_pictures", "archive_messages", "archive_chats", "archive_contacts", "backup_archives")) db.delete(t, "archive_id = ?", arrayOf(h))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        File(archivesDir, h).deleteRecursively()
    }

    // ---- The import journal (spec 5.7 step 6).

    class JournalRow(val archiveId: ByteArray, val step: String, val started: Long)

    fun journal(): List<JournalRow> = db.query("backup_imports", null, null, null, null, null, "started").use { c -> buildList { while (c.moveToNext()) add(JournalRow(unhex(c.getString(0)), c.getString(1), c.getLong(2))) } }

    fun journalBegin(id: ByteArray, now: Long) {
        db.insertWithOnConflict("backup_imports", null, android.content.ContentValues().apply { put("archive_id", hex(id)); put("step", "BEGUN"); put("started", now) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun journalStep(id: ByteArray, step: String) {
        db.update("backup_imports", android.content.ContentValues().apply { put("step", step) }, "archive_id = ?", arrayOf(hex(id)))
    }

    fun journalDrop(id: ByteArray) { db.delete("backup_imports", "archive_id = ?", arrayOf(hex(id))) }

    private fun Cursor.optString(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.optString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.archive() = ArchiveRow(
        unhex(getString(getColumnIndexOrThrow("archive_id"))), getBlob(getColumnIndexOrThrow("file_digest")), getBlob(getColumnIndexOrThrow("source_identity")),
        getLong(getColumnIndexOrThrow("generation")), getLong(getColumnIndexOrThrow("created")), getString(getColumnIndexOrThrow("own_name")),
        getLong(getColumnIndexOrThrow("scope_start")), getLong(getColumnIndexOrThrow("scope_end")), getInt(getColumnIndexOrThrow("scope_flags")),
        getInt(getColumnIndexOrThrow("contacts")), getInt(getColumnIndexOrThrow("chats")), getInt(getColumnIndexOrThrow("messages")),
        getInt(getColumnIndexOrThrow("reactions")), getInt(getColumnIndexOrThrow("pictures")), getInt(getColumnIndexOrThrow("missing_pictures")),
        getLong(getColumnIndexOrThrow("imported")), getLong(getColumnIndexOrThrow("bytes")),
    )

    companion object {
        /** Saved copies kept on one phone at most: each can be half a gigabyte of messages (spec 5.8, bounded growth). */
        const val MAX_ARCHIVES = 8

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
        fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** History version 22. `IF NOT EXISTS` so a table already made stays as it is. The archive id is the 16 bytes in lower-case hex. */
        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS backup_archives (archive_id TEXT PRIMARY KEY CHECK(length(archive_id)=32), file_digest BLOB NOT NULL CHECK(length(file_digest)=32), " +
                    "source_identity BLOB NOT NULL, generation INTEGER NOT NULL, created INTEGER NOT NULL, own_name TEXT NOT NULL, scope_start INTEGER NOT NULL, " +
                    "scope_end INTEGER NOT NULL, scope_flags INTEGER NOT NULL, contacts INTEGER NOT NULL, chats INTEGER NOT NULL, messages INTEGER NOT NULL, " +
                    "reactions INTEGER NOT NULL, pictures INTEGER NOT NULL, missing_pictures INTEGER NOT NULL, imported INTEGER NOT NULL, bytes INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS archive_contacts (archive_id TEXT NOT NULL, local_id INTEGER NOT NULL, identity BLOB NOT NULL, card BLOB NOT NULL, " +
                    "saved_name TEXT NOT NULL, colour INTEGER NOT NULL, historical_met INTEGER NOT NULL, voice TEXT, added INTEGER NOT NULL, PRIMARY KEY(archive_id, local_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS archive_chats (archive_id TEXT NOT NULL, local_id INTEGER NOT NULL, kind INTEGER NOT NULL, contact_id INTEGER NOT NULL, " +
                    "title TEXT NOT NULL, pinned INTEGER NOT NULL, PRIMARY KEY(archive_id, local_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS archive_messages (archive_id TEXT NOT NULL, local_id INTEGER NOT NULL, chat_id INTEGER NOT NULL, outgoing INTEGER NOT NULL, " +
                    "author_contact_id INTEGER NOT NULL, author_label TEXT, text TEXT NOT NULL, language TEXT, time INTEGER NOT NULL, status INTEGER NOT NULL, " +
                    "reply_id INTEGER NOT NULL, reply_missing INTEGER NOT NULL, planned_at INTEGER NOT NULL, picture_id INTEGER NOT NULL, PRIMARY KEY(archive_id, local_id))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS archive_messages_by_chat ON archive_messages (archive_id, chat_id, local_id)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS archive_reactions (archive_id TEXT NOT NULL, local_id INTEGER NOT NULL, target_id INTEGER NOT NULL, author_contact_id INTEGER NOT NULL, " +
                    "author_label TEXT, emoji TEXT NOT NULL, time INTEGER NOT NULL, was_unsent INTEGER NOT NULL, PRIMARY KEY(archive_id, local_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS archive_pictures (archive_id TEXT NOT NULL, local_id INTEGER NOT NULL, message_id INTEGER NOT NULL, complete INTEGER NOT NULL, " +
                    "codec INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, sketch BLOB NOT NULL, file TEXT, digest BLOB NOT NULL, PRIMARY KEY(archive_id, local_id))",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS backup_imports (archive_id TEXT PRIMARY KEY CHECK(length(archive_id)=32), step TEXT NOT NULL, started INTEGER NOT NULL)")
        }

        /** Every table of this feature, for tests and the boundary check. */
        val TABLES = listOf("backup_archives", "archive_contacts", "archive_chats", "archive_messages", "archive_reactions", "archive_pictures", "backup_imports")
    }
}

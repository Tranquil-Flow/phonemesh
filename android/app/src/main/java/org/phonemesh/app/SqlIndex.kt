package org.phonemesh.app

import android.database.sqlite.SQLiteDatabase
import org.phonemesh.core.backup.HistoryBackup
import java.io.File

/**
 * The cross-references a full chat copy's rules need ([HistoryBackup.Index]), kept in a scratch SQLite file instead of
 * memory, so half a million messages cost a few kilobytes of heap (spec 5.5: "indexes on disk"). One scratch file per
 * walk or restore; [close] deletes it. One transaction for the whole walk: the file is disposable, never read by
 * anything else, and its journal is off.
 */
class SqlIndex(private val file: File) : HistoryBackup.Index, AutoCloseable {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
        rawQuery("PRAGMA synchronous=OFF", null).use { it.moveToFirst() }
        execSQL("CREATE TABLE contacts (id INTEGER PRIMARY KEY, identity BLOB NOT NULL UNIQUE)")
        execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY, contact INTEGER NOT NULL)")
        execSQL("CREATE UNIQUE INDEX chats_by_contact ON chats (contact) WHERE contact <> 0")
        execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, chat INTEGER NOT NULL, picture INTEGER NOT NULL)")
        execSQL("CREATE UNIQUE INDEX messages_by_picture ON messages (picture) WHERE picture <> 0")
        execSQL("CREATE TABLE reactions (target INTEGER NOT NULL, author INTEGER NOT NULL, label TEXT NOT NULL, emoji TEXT NOT NULL, PRIMARY KEY (target, author, label, emoji)) WITHOUT ROWID")
        beginTransaction()
    }
    private val insertContact = db.compileStatement("INSERT OR IGNORE INTO contacts (id, identity) VALUES (?, ?)")
    private val insertChat = db.compileStatement("INSERT OR IGNORE INTO chats (id, contact) VALUES (?, ?)")
    private val insertMessage = db.compileStatement("INSERT OR IGNORE INTO messages (id, chat, picture) VALUES (?, ?, ?)")
    private val insertReaction = db.compileStatement("INSERT OR IGNORE INTO reactions (target, author, label, emoji) VALUES (?, ?, ?, ?)")

    override fun addContact(id: Long, identity: ByteArray): Boolean = insertContact.run {
        bindLong(1, id); bindBlob(2, identity); executeInsert() != -1L
    }

    override fun hasContact(id: Long): Boolean = exists("SELECT 1 FROM contacts WHERE id = ?", id)

    override fun addChat(id: Long, contactId: Long): Boolean = insertChat.run { bindLong(1, id); bindLong(2, contactId); executeInsert() != -1L }

    override fun hasChat(id: Long): Boolean = exists("SELECT 1 FROM chats WHERE id = ?", id)

    override fun addMessage(id: Long, chatId: Long, pictureId: Long): Boolean = insertMessage.run {
        bindLong(1, id); bindLong(2, chatId); bindLong(3, pictureId); executeInsert() != -1L
    }

    /** A missing label is stored as "-" and a label L as "+L", so no label and an empty label stay apart. */
    override fun addReaction(target: Long, authorContactId: Long, authorLabel: String?, emoji: String): Boolean = insertReaction.run {
        bindLong(1, target); bindLong(2, authorContactId); bindString(3, if (authorLabel == null) "-" else "+$authorLabel"); bindString(4, emoji)
        executeInsert() != -1L
    }

    override fun messageChat(id: Long): Long? = db.rawQuery("SELECT chat FROM messages WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }

    override fun messagePicture(id: Long): Long? = db.rawQuery("SELECT picture FROM messages WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun exists(sql: String, id: Long) = db.rawQuery(sql, arrayOf(id.toString())).use { it.moveToFirst() }

    override fun close() {
        runCatching { insertContact.close(); insertChat.close(); insertMessage.close(); insertReaction.close() }
        // The file is disposable and its journal is off: end the transaction as a commit, never a rollback (undefined without a journal).
        runCatching { if (db.inTransaction()) { db.setTransactionSuccessful(); db.endTransaction() } }
        runCatching { db.close() }
        file.delete()
        File(file.path + "-journal").delete()
    }
}

/** Text of a record field made to fit the format's field rules: no control characters, at most [max] bytes (UTF-8). */
object ArchiveText {
    private fun isControl(point: Int) = point < 0x20 || point == 0x7F || point in 0x80..0x9F

    /** [s] without control characters (a line break stays when [lines]), cut at a character to fit [max] bytes. Same input, same output. */
    fun clean(s: String, max: Int, lines: Boolean = false): String {
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val point = s.codePointAt(i)
            i += Character.charCount(point)
            val shown = if (isControl(point) && !(lines && point == '\n'.code)) ' '.code else point
            val size = String(Character.toChars(shown)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > max) break
            out.appendCodePoint(shown)
            bytes += size
        }
        return out.toString()
    }

    /** Whether [clean] changes [s] for a field of [max] bytes: cut short, or a control character turned into a space. */
    fun altered(s: String, max: Int, lines: Boolean = false) = clean(s, max, lines) != s

    fun cleanOrNull(s: String?, max: Int): String? = s?.let { clean(it, max) }

    /**
     * A chat title or sender label the copy's rules accept (B4-10): NFC, and none of the control, format (bidi,
     * zero-width), separator, private-use or unassigned characters; those are dropped, then the text is cut to [max]
     * bytes. A name that was only such characters becomes empty.
     */
    fun plain(s: String, max: Int): String {
        val out = StringBuilder()
        var i = 0
        val normal = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
        while (i < normal.length) {
            val point = normal.codePointAt(i)
            i += Character.charCount(point)
            val type = Character.getType(point)
            val hidden = isControl(point) || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            if (!hidden) out.appendCodePoint(point)
        }
        return clean(out.toString().trim(), max)
    }
}

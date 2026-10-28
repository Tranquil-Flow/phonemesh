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
        beginTransaction()
    }
    private val insertContact = db.compileStatement("INSERT OR IGNORE INTO contacts (id, identity) VALUES (?, ?)")
    private val insertChat = db.compileStatement("INSERT OR IGNORE INTO chats (id, contact) VALUES (?, ?)")
    private val insertMessage = db.compileStatement("INSERT OR IGNORE INTO messages (id, chat, picture) VALUES (?, ?, ?)")

    override fun addContact(id: Long, identity: ByteArray): Boolean = insertContact.run {
        bindLong(1, id); bindBlob(2, identity); executeInsert() != -1L
    }

    override fun hasContact(id: Long): Boolean = exists("SELECT 1 FROM contacts WHERE id = ?", id)

    override fun addChat(id: Long, contactId: Long): Boolean = insertChat.run { bindLong(1, id); bindLong(2, contactId); executeInsert() != -1L }

    override fun hasChat(id: Long): Boolean = exists("SELECT 1 FROM chats WHERE id = ?", id)

    override fun addMessage(id: Long, chatId: Long, pictureId: Long): Boolean = insertMessage.run {
        bindLong(1, id); bindLong(2, chatId); bindLong(3, pictureId); executeInsert() != -1L
    }

    override fun messageChat(id: Long): Long? = db.rawQuery("SELECT chat FROM messages WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }

    override fun messagePicture(id: Long): Long? = db.rawQuery("SELECT picture FROM messages WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun exists(sql: String, id: Long) = db.rawQuery(sql, arrayOf(id.toString())).use { it.moveToFirst() }

    override fun close() {
        runCatching { insertContact.close(); insertChat.close(); insertMessage.close() }
        runCatching { if (db.inTransaction()) db.endTransaction() }
        runCatching { db.close() }
        file.delete()
        File(file.path + "-journal").delete()
    }
}

/** Text of a record field made to fit the format's field rules: no control characters, at most [max] bytes (UTF-8). */
object ArchiveText {
    /** [s] without control characters (a line break stays when [lines]), cut at a character to fit [max] bytes. Same input, same output. */
    fun clean(s: String, max: Int, lines: Boolean = false): String {
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val point = s.codePointAt(i)
            i += Character.charCount(point)
            val shown = if ((point < 0x20 && !(lines && point == '\n'.code)) || point == 0x7F) ' '.code else point
            val size = String(Character.toChars(shown)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > max) break
            out.appendCodePoint(shown)
            bytes += size
        }
        return out.toString()
    }

    fun cleanOrNull(s: String?, max: Int): String? = s?.let { clean(it, max) }
}

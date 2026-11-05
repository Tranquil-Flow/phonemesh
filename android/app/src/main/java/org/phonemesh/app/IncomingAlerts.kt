package org.phonemesh.app

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase

/**
 * Fresh incoming person and private-group text (v0.1.3 N1): the event an alert may later be raised for.
 *
 * The producer that stores a fresh text or reply [record]s it inside the same transaction, so a rolled-back receive, a
 * refused duplicate or a replayed packet leaves no event, and an event that cannot be kept undoes its message (the
 * packet is then offered again). [take] hands committed events out at most once, oldest first, and never inside a
 * transaction, where they could still roll back. Receipts, reactions, profiles, pictures, group notes and control
 * traffic, messages shared on joining, restored copies and the user's own messages are never recorded.
 *
 * Rows hold ids and a time only: never words, names or pictures. A crash after [take] loses only the alert; the
 * message itself stays, unread. A deleted message's event is dropped. Pending events are bounded to the newest
 * [MAX_PENDING].
 */
class IncomingAlerts(private val history: History) {
    /** A committed fresh text: which message, in which chat, of which kind (person or private group). */
    data class Fresh(val messageId: Long, val chatId: Long, val kind: ChatKind)

    /** Inside the transaction that stored [message]; refused anywhere else, or for anything but a fresh incoming text. */
    fun record(message: Message) {
        val db = history.writableDatabase
        check(db.inTransaction()) { "an alert is recorded in the transaction that stores its message" }
        require(message.direction == Direction.IN && message.kind == MessageKind.TEXT && message.sharedBy == null) {
            "only a fresh incoming text is an alert"
        }
        db.insertWithOnConflict(TABLE, null, ContentValues().apply {
            put("message_id", message.id); put("chat_id", message.chatId); put("committed", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL("DELETE FROM $TABLE WHERE message_id NOT IN (SELECT message_id FROM $TABLE ORDER BY message_id DESC LIMIT $MAX_PENDING)")
    }

    /** Committed events, each once, oldest first; nothing while this thread is inside a transaction. */
    fun take(): List<Fresh> {
        val db = history.writableDatabase
        if (db.inTransaction()) return emptyList()
        // Most changes find nothing pending: no write transaction for them.
        if (DatabaseUtils.queryNumEntries(db, TABLE) == 0L) return emptyList()
        return history.transaction {
            val fresh = db.rawQuery(
                "SELECT a.message_id, a.chat_id, c.kind FROM $TABLE a " +
                    "JOIN messages m ON m.id = a.message_id AND m.chat_id = a.chat_id JOIN chats c ON c.id = m.chat_id " +
                    "WHERE m.direction = 'IN' AND m.kind = 'TEXT' AND m.reaction = 0 AND m.shared_by IS NULL " +
                    "AND c.kind IN ('PERSON', 'PRIVATE_GROUP') ORDER BY a.message_id",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(Fresh(c.getLong(0), c.getLong(1), ChatKind.valueOf(c.getString(2)))) } }
            db.delete(TABLE, null, null)
            fresh
        }
    }

    companion object {
        const val MAX_PENDING = 200
        private const val TABLE = "incoming_alerts"

        /** Schema 24: empty on upgrade, so messages already on the phone never become alerts. */
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE (message_id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, committed INTEGER NOT NULL)")
        }
    }
}

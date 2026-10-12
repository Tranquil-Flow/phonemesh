package org.phonemesh.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.phonemesh.core.DeliveryRules
import org.phonemesh.core.DeliveryStatus

enum class ChatKind { GROUP, DIRECT }

enum class Direction { IN, OUT }

/** [address] is "channel:0" for the group and the radio id ("!1234abcd") for a private chat; [name] is "" while unknown. */
data class Chat(val id: Long, val kind: ChatKind, val address: String, val name: String, val lastActivity: Long, val unread: Int)

/** [senderId] is null for the user's own messages; [language] null when the sender gave none; [status] null when received. */
data class Message(
    val id: Long, val chatId: Long, val direction: Direction, val senderId: String?, val text: String,
    val language: String?, val time: Long, val packetId: Long?, val status: DeliveryStatus?,
)

/**
 * Text-only history on the phone: never audio, never synced (allowBackup=false keeps it off backups).
 * Kept until the user deletes it (deletion UI in 3b). Main thread only: every call is a small indexed query.
 */
class History(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, address TEXT NOT NULL, " +
                "name TEXT NOT NULL DEFAULT '', last_activity INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0, " +
                "UNIQUE (kind, address))",
        )
        db.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL REFERENCES chats(id), " +
                "direction TEXT NOT NULL, sender_id TEXT, text TEXT NOT NULL, language TEXT, time INTEGER NOT NULL, " +
                "packet_id INTEGER, delivery TEXT)",
        )
        db.execSQL("CREATE INDEX messages_by_chat ON messages (chat_id, id)")
        db.execSQL("CREATE INDEX messages_by_packet ON messages (packet_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun groupChat(channel: Int = 0): Chat = chatFor(ChatKind.GROUP, "channel:$channel", "")

    fun directChat(peerId: String, name: String?): Chat {
        val chat = chatFor(ChatKind.DIRECT, peerId, name.orEmpty())
        if (!name.isNullOrBlank() && chat.name != name) {
            rename(peerId, name)
            return checkNotNull(chat(chat.id))
        }
        return chat
    }

    private fun chatFor(kind: ChatKind, address: String, name: String): Chat {
        find(kind, address)?.let { return it }
        writableDatabase.insertWithOnConflict(
            "chats", null,
            ContentValues().apply { put("kind", kind.name); put("address", address); put("name", name) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return checkNotNull(find(kind, address))
    }

    private fun find(kind: ChatKind, address: String): Chat? =
        readableDatabase.query("chats", null, "kind = ? AND address = ?", arrayOf(kind.name, address), null, null, null)
            .use { if (it.moveToFirst()) it.chat() else null }

    fun chat(id: Long): Chat? =
        readableDatabase.query("chats", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.chat() else null }

    /** The group first, then the newest activity first. */
    fun chats(): List<Chat> =
        readableDatabase.query("chats", null, null, null, null, null, "kind = 'GROUP' DESC, last_activity DESC, id DESC")
            .use { c -> buildList { while (c.moveToNext()) add(c.chat()) } }

    fun rename(peerId: String, name: String): Boolean = renameAll(mapOf(peerId to name)) > 0

    /** Gives existing private chats their radio names in one transaction; returns how many changed. */
    fun renameAll(names: Map<String, String>): Int {
        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            names.forEach { (id, name) ->
                if (name.isNotBlank()) {
                    changed += db.update(
                        "chats", ContentValues().apply { put("name", name) },
                        "kind = 'DIRECT' AND address = ? AND name <> ?", arrayOf(id, name),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    fun addIncoming(chatId: Long, senderId: String, text: String, language: String?, time: Long, countUnread: Boolean): Message =
        insert(chatId, Direction.IN, senderId, text, language, time, null, countUnread)

    fun addOutgoing(chatId: Long, text: String, language: String, time: Long): Message =
        insert(chatId, Direction.OUT, null, text, language, time, DeliveryStatus.WAITING, false)

    private fun insert(
        chatId: Long, direction: Direction, senderId: String?, text: String, language: String?, time: Long,
        status: DeliveryStatus?, countUnread: Boolean,
    ): Message {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("messages", null, ContentValues().apply {
                put("chat_id", chatId); put("direction", direction.name); put("sender_id", senderId); put("text", text)
                put("language", language); put("time", time); put("delivery", status?.name)
            })
            db.execSQL(
                "UPDATE chats SET last_activity = MAX(last_activity, ?), unread = unread + ? WHERE id = ?",
                arrayOf<Any>(time, if (countUnread) 1 else 0, chatId),
            )
            db.setTransactionSuccessful()
            return checkNotNull(message(id))
        } finally {
            db.endTransaction()
        }
    }

    /** Removes one message; its chat stays. False when it was already gone. */
    fun deleteMessage(messageId: Long): Boolean =
        writableDatabase.delete("messages", "id = ?", arrayOf(messageId.toString())) > 0

    fun attachPacket(messageId: Long, packetId: Long) = update(messageId) { put("packet_id", packetId) }

    fun setStatus(messageId: Long, status: DeliveryStatus) = update(messageId) { put("delivery", status.name) }

    private fun update(id: Long, values: ContentValues.() -> Unit) {
        writableDatabase.update("messages", ContentValues().apply(values), "id = ?", arrayOf(id.toString()))
    }

    fun message(id: Long): Message? = one("id = ?", arrayOf(id.toString()))

    /** The newest sent message carrying [packetId]; a resend moves the message to its new packet. */
    fun byPacket(packetId: Long): Message? = one("packet_id = ? AND direction = 'OUT'", arrayOf(packetId.toString()))

    fun lastMessage(chatId: Long): Message? = one("chat_id = ?", arrayOf(chatId.toString()))

    private fun one(where: String, args: Array<String>): Message? =
        readableDatabase.query("messages", null, where, args, null, null, "id DESC", "1")
            .use { if (it.moveToFirst()) it.message() else null }

    /** The newest [limit] messages of a chat, oldest first. */
    fun messages(chatId: Long, limit: Int = 500): List<Message> =
        readableDatabase.query("messages", null, "chat_id = ?", arrayOf(chatId.toString()), null, null, "id DESC", limit.toString())
            .use { c -> buildList { while (c.moveToNext()) add(c.message()) } }.asReversed()

    fun clearUnread(chatId: Long) {
        writableDatabase.execSQL("UPDATE chats SET unread = 0 WHERE id = ?", arrayOf<Any>(chatId))
    }

    fun sendersHeard(chatId: Long): Set<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT sender_id FROM messages WHERE chat_id = ? AND direction = 'IN' AND sender_id IS NOT NULL",
        arrayOf(chatId.toString()),
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /** Once per process start: clocks left from before cannot finish any more (DeliveryRules.onReopen). */
    fun expireWaiting(): Int = writableDatabase.update(
        "messages", ContentValues().apply { put("delivery", DeliveryRules.onReopen(DeliveryStatus.WAITING).name) },
        "direction = 'OUT' AND delivery = ?", arrayOf(DeliveryStatus.WAITING.name),
    )

    private fun Cursor.chat() = Chat(
        id = getLong(getColumnIndexOrThrow("id")),
        kind = ChatKind.valueOf(getString(getColumnIndexOrThrow("kind"))),
        address = getString(getColumnIndexOrThrow("address")),
        name = getString(getColumnIndexOrThrow("name")),
        lastActivity = getLong(getColumnIndexOrThrow("last_activity")),
        unread = getInt(getColumnIndexOrThrow("unread")),
    )

    private fun Cursor.message(): Message {
        val packet = getColumnIndexOrThrow("packet_id")
        val delivery = getColumnIndexOrThrow("delivery")
        val sender = getColumnIndexOrThrow("sender_id")
        val language = getColumnIndexOrThrow("language")
        return Message(
            id = getLong(getColumnIndexOrThrow("id")),
            chatId = getLong(getColumnIndexOrThrow("chat_id")),
            direction = Direction.valueOf(getString(getColumnIndexOrThrow("direction"))),
            senderId = if (isNull(sender)) null else getString(sender),
            text = getString(getColumnIndexOrThrow("text")),
            language = if (isNull(language)) null else getString(language),
            time = getLong(getColumnIndexOrThrow("time")),
            packetId = if (isNull(packet)) null else getLong(packet),
            status = if (isNull(delivery)) null else DeliveryStatus.valueOf(getString(delivery)),
        )
    }

    private companion object {
        const val VERSION = 1
    }
}

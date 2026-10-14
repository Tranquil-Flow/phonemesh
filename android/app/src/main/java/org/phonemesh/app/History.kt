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

/** TEXT is a spoken/written message; PICTURE is a picture sent over the radio (its row in `pictures`). */
enum class MessageKind { TEXT, PICTURE }

/**
 * A picture message's details. [file] is set once every byte is on this phone (always for a sent picture);
 * [have] of [total] radio packets arrived (received) or were acknowledged (sent); [lost] when it can no longer arrive.
 */
data class PictureRow(
    val messageId: Long, val pictureId: Int, val codec: Int, val width: Int, val height: Int, val sketch: ByteArray,
    val file: String?, val have: Int, val total: Int, val lost: Boolean,
)

/** [address] is "channel:0" for the group and the radio id ("!1234abcd") for a private chat; [name] is "" while unknown. */
data class Chat(
    val id: Long, val kind: ChatKind, val address: String, val name: String, val lastActivity: Long, val unread: Int,
    val pinned: Boolean = false,
)

/** [senderId] is null for the user's own messages; [language] null when the sender gave none; [status] null when received. */
data class Message(
    val id: Long, val chatId: Long, val direction: Direction, val senderId: String?, val text: String,
    val language: String?, val time: Long, val packetId: Long?, val status: DeliveryStatus?,
    /** The radio id of the message this one answers; null when it answers none. */
    val replyTo: Long? = null,
    /** When a planned message was meant to go; null for one sent at once. */
    val plannedAt: Long? = null,
    val kind: MessageKind = MessageKind.TEXT,
)

/** A message waiting on this phone until [due]; it becomes a sent message when it goes out. */
data class Planned(val id: Long, val chatId: Long, val text: String, val language: String, val due: Long, val replyTo: Long?)

/**
 * A reaction ([emoji]) to the message whose radio id is [target]; [senderId] is null for the user's own. [waiting] is the
 * user's own reaction kept on this phone until a radio can take it.
 */
data class Reaction(val target: Long, val senderId: String?, val emoji: String, val waiting: Boolean = false)

/** The user's own reaction that no radio has taken yet. */
data class QueuedReaction(val id: Long, val chatId: Long, val emoji: String, val target: Long)

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
        onUpgrade(db, 1, VERSION)
    }

    /** Each step adds to what the one before made; a phone that had version 1 keeps its chats. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_to INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN reaction INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN planned_at INTEGER")
            db.execSQL(
                "CREATE TABLE planned (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL REFERENCES chats(id), " +
                    "text TEXT NOT NULL, language TEXT NOT NULL, due INTEGER NOT NULL, reply_to INTEGER)",
            )
        }
        // Pictures (branch feat/image-messages): a kind per message and one `pictures` row per picture message.
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'TEXT'")
            db.execSQL(
                "CREATE TABLE pictures (message_id INTEGER PRIMARY KEY REFERENCES messages(id), picture_id INTEGER NOT NULL, " +
                    "codec INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, sketch BLOB NOT NULL, file TEXT, " +
                    "have INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL, lost INTEGER NOT NULL DEFAULT 0)",
            )
        }
    }

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

    /** The group first, then pinned chats, then the newest activity first. */
    fun chats(): List<Chat> =
        readableDatabase.query("chats", null, null, null, null, null, "kind = 'GROUP' DESC, pinned DESC, last_activity DESC, id DESC")
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

    /** [packetId] is the radio id it arrived with, kept so replies and reactions can point at it. */
    fun addIncoming(
        chatId: Long, senderId: String, text: String, language: String?, time: Long, countUnread: Boolean,
        packetId: Long? = null, replyTo: Long? = null,
    ): Message = insert(chatId, Direction.IN, senderId, text, language, time, null, countUnread, packetId, replyTo, null)

    fun addOutgoing(chatId: Long, text: String, language: String, time: Long, replyTo: Long? = null, plannedAt: Long? = null): Message =
        insert(chatId, Direction.OUT, null, text, language, time, DeliveryStatus.WAITING, false, null, replyTo, plannedAt)

    fun addPlanned(chatId: Long, text: String, language: String, due: Long, replyTo: Long?): Planned {
        val id = writableDatabase.insertOrThrow("planned", null, ContentValues().apply {
            put("chat_id", chatId); put("text", text); put("language", language); put("due", due); put("reply_to", replyTo)
        })
        return Planned(id, chatId, text, language, due, replyTo)
    }

    fun planned(chatId: Long): List<Planned> = plannedWhere("chat_id = ?", arrayOf(chatId.toString()))

    /** Every planned message whose time has come, oldest first. */
    fun duePlanned(now: Long): List<Planned> = plannedWhere("due <= ?", arrayOf(now.toString()))

    fun nextDue(): Long? = readableDatabase.rawQuery("SELECT MIN(due) FROM planned", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun deletePlanned(id: Long): Boolean = writableDatabase.delete("planned", "id = ?", arrayOf(id.toString())) > 0

    private fun plannedWhere(where: String, args: Array<String>): List<Planned> =
        readableDatabase.query("planned", null, where, args, null, null, "due, id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    val reply = c.getColumnIndexOrThrow("reply_to")
                    add(Planned(
                        c.getLong(c.getColumnIndexOrThrow("id")), c.getLong(c.getColumnIndexOrThrow("chat_id")),
                        c.getString(c.getColumnIndexOrThrow("text")), c.getString(c.getColumnIndexOrThrow("language")),
                        c.getLong(c.getColumnIndexOrThrow("due")), if (c.isNull(reply)) null else c.getLong(reply),
                    ))
                }
            }
        }

    /** One reaction per person per message: a new one replaces theirs. Reactions are not messages: no bubble, no unread. */
    fun addReaction(chatId: Long, senderId: String?, emoji: String, target: Long, time: Long, waiting: Boolean = false) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (senderId == null) {
                db.delete("messages", "chat_id = ? AND reaction = 1 AND reply_to = ? AND sender_id IS NULL", arrayOf(chatId.toString(), target.toString()))
            } else {
                db.delete("messages", "chat_id = ? AND reaction = 1 AND reply_to = ? AND sender_id = ?", arrayOf(chatId.toString(), target.toString(), senderId))
            }
            db.insertOrThrow("messages", null, ContentValues().apply {
                put("chat_id", chatId); put("direction", (if (senderId == null) Direction.OUT else Direction.IN).name)
                put("sender_id", senderId); put("text", emoji); put("time", time); put("reply_to", target); put("reaction", 1)
                // Kept until a radio is ready (queuedReactions).
                if (waiting) put("delivery", DeliveryStatus.QUEUED.name)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun reactions(chatId: Long): List<Reaction> = readableDatabase.query(
        "messages", arrayOf("reply_to", "sender_id", "text", "delivery", "packet_id"), "chat_id = ? AND reaction = 1", arrayOf(chatId.toString()),
        null, null, "id",
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val waiting = !c.isNull(3) && c.getString(3) == DeliveryStatus.QUEUED.name
                add(Reaction(c.getLong(0), if (c.isNull(1)) null else c.getString(1), c.getString(2), waiting))
            }
        }
    }

    /** The user's own reactions no radio has taken yet, oldest first. */
    fun queuedReactions(): List<QueuedReaction> = readableDatabase.query(
        "messages", arrayOf("id", "chat_id", "text", "reply_to"),
        "reaction = 1 AND direction = 'OUT' AND delivery = ?", arrayOf(DeliveryStatus.QUEUED.name),
        null, null, "id",
    ).use { c -> buildList { while (c.moveToNext()) add(QueuedReaction(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))) } }

    /** Messages and pictures waiting for a radio (not reactions), oldest first. */
    fun queued(): List<Message> =
        readableDatabase.query("messages", null, "direction = 'OUT' AND reaction = 0 AND delivery = ?", arrayOf(DeliveryStatus.QUEUED.name),
            null, null, "id").use { c -> buildList { while (c.moveToNext()) add(c.message()) } }

    /** A queued reaction the radio took: it becomes like any sent reaction (not tracked for delivery). */
    fun reactionSent(id: Long, packetId: Long) = update(id) { putNull("delivery"); put("packet_id", packetId) }

    /** The message in this chat that went out or arrived with radio id [packetId]: what a reply quotes. */
    fun byRadioId(chatId: Long, packetId: Long): Message? =
        one("chat_id = ? AND packet_id = ? AND reaction = 0", arrayOf(chatId.toString(), packetId.toString()))

    /** A picture this phone sends; its bytes are already in [file]. */
    fun addOutgoingPicture(chatId: Long, picture: PictureRow, time: Long): Message {
        val message = insert(chatId, Direction.OUT, null, "", null, time, DeliveryStatus.WAITING, false, null, null, null, MessageKind.PICTURE)
        putPicture(picture.copy(messageId = message.id))
        return message
    }

    /** A picture that started arriving: so far only its sketch. */
    fun addIncomingPicture(chatId: Long, senderId: String, picture: PictureRow, time: Long, countUnread: Boolean): Message {
        val message = insert(chatId, Direction.IN, senderId, "", null, time, null, countUnread, null, null, null, MessageKind.PICTURE)
        putPicture(picture.copy(messageId = message.id))
        return message
    }

    private fun putPicture(p: PictureRow) {
        writableDatabase.insertOrThrow("pictures", null, ContentValues().apply {
            put("message_id", p.messageId); put("picture_id", p.pictureId); put("codec", p.codec); put("width", p.width)
            put("height", p.height); put("sketch", p.sketch); put("file", p.file); put("have", p.have); put("total", p.total)
            put("lost", if (p.lost) 1 else 0)
        })
    }

    fun picture(messageId: Long): PictureRow? =
        readableDatabase.query("pictures", null, "message_id = ?", arrayOf(messageId.toString()), null, null, null)
            .use { if (it.moveToFirst()) it.picture() else null }

    /** The files of a chat's pictures, before the chat is deleted. */
    fun pictureFiles(chatId: Long): List<String> = readableDatabase.rawQuery(
        "SELECT p.file FROM pictures p JOIN messages m ON m.id = p.message_id WHERE m.chat_id = ? AND p.file IS NOT NULL",
        arrayOf(chatId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun setPictureProgress(messageId: Long, have: Int) =
        writableDatabase.update("pictures", ContentValues().apply { put("have", have) }, "message_id = ?", arrayOf(messageId.toString()))

    fun setPictureFile(messageId: Long, file: String, have: Int) = writableDatabase.update(
        "pictures", ContentValues().apply { put("file", file); put("have", have) }, "message_id = ?", arrayOf(messageId.toString()),
    )

    fun setPictureLost(messageId: Long) =
        writableDatabase.update("pictures", ContentValues().apply { put("lost", 1) }, "message_id = ?", arrayOf(messageId.toString()))

    /** Once per process start: a picture still arriving lost its pieces with the old process. */
    fun loseUnfinishedPictures(): Int =
        writableDatabase.update("pictures", ContentValues().apply { put("lost", 1) }, "file IS NULL AND lost = 0", null)

    private fun insert(
        chatId: Long, direction: Direction, senderId: String?, text: String, language: String?, time: Long,
        status: DeliveryStatus?, countUnread: Boolean, packetId: Long?, replyTo: Long?, plannedAt: Long?,
        kind: MessageKind = MessageKind.TEXT,
    ): Message {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("messages", null, ContentValues().apply {
                put("chat_id", chatId); put("direction", direction.name); put("sender_id", senderId); put("text", text)
                put("language", language); put("time", time); put("delivery", status?.name)
                put("packet_id", packetId); put("reply_to", replyTo); put("planned_at", plannedAt); put("kind", kind.name)
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

    fun setPinned(chatId: Long, pinned: Boolean) {
        writableDatabase.update("chats", ContentValues().apply { put("pinned", if (pinned) 1 else 0) }, "id = ?", arrayOf(chatId.toString()))
    }

    /**
     * Deletes a chat's messages. A private chat goes too (a new message from that radio starts it again);
     * the group stays, empty, at the top. False when there was nothing to delete.
     */
    fun deleteChat(chatId: Long): Boolean {
        val chat = chat(chatId) ?: return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("pictures", "message_id IN (SELECT id FROM messages WHERE chat_id = ?)", arrayOf(chatId.toString()))
            val messages = db.delete("messages", "chat_id = ?", arrayOf(chatId.toString())) +
                db.delete("planned", "chat_id = ?", arrayOf(chatId.toString()))
            val removed = if (chat.kind == ChatKind.GROUP) {
                db.execSQL("UPDATE chats SET unread = 0 WHERE id = ?", arrayOf<Any>(chatId))
                0
            } else {
                db.delete("chats", "id = ?", arrayOf(chatId.toString()))
            }
            db.setTransactionSuccessful()
            return messages + removed > 0
        } finally {
            db.endTransaction()
        }
    }

    /** Removes one message (and its picture row; the caller deletes the file); its chat stays. False when it was already gone. */
    fun deleteMessage(messageId: Long): Boolean {
        writableDatabase.delete("pictures", "message_id = ?", arrayOf(messageId.toString()))
        return writableDatabase.delete("messages", "id = ?", arrayOf(messageId.toString())) > 0
    }

    fun attachPacket(messageId: Long, packetId: Long) = update(messageId) { put("packet_id", packetId) }

    fun setStatus(messageId: Long, status: DeliveryStatus) = update(messageId) { put("delivery", status.name) }

    private fun update(id: Long, values: ContentValues.() -> Unit) {
        writableDatabase.update("messages", ContentValues().apply(values), "id = ?", arrayOf(id.toString()))
    }

    fun message(id: Long): Message? = one("id = ?", arrayOf(id.toString()))

    /** The newest sent message carrying [packetId]; a resend moves the message to its new packet. */
    fun byPacket(packetId: Long): Message? = one("packet_id = ? AND direction = 'OUT'", arrayOf(packetId.toString()))

    fun lastMessage(chatId: Long): Message? = one("chat_id = ? AND reaction = 0", arrayOf(chatId.toString()))

    private fun one(where: String, args: Array<String>): Message? =
        readableDatabase.query("messages", null, where, args, null, null, "id DESC", "1")
            .use { if (it.moveToFirst()) it.message() else null }

    /** The newest [limit] messages of a chat, oldest first. */
    fun messages(chatId: Long, limit: Int = 500): List<Message> =
        readableDatabase.query("messages", null, "chat_id = ? AND reaction = 0", arrayOf(chatId.toString()), null, null, "id DESC", limit.toString())
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
        pinned = getInt(getColumnIndexOrThrow("pinned")) != 0,
    )

    private fun Cursor.picture() = PictureRow(
        messageId = getLong(getColumnIndexOrThrow("message_id")),
        pictureId = getInt(getColumnIndexOrThrow("picture_id")),
        codec = getInt(getColumnIndexOrThrow("codec")),
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        sketch = getBlob(getColumnIndexOrThrow("sketch")),
        file = getColumnIndexOrThrow("file").let { if (isNull(it)) null else getString(it) },
        have = getInt(getColumnIndexOrThrow("have")),
        total = getInt(getColumnIndexOrThrow("total")),
        lost = getInt(getColumnIndexOrThrow("lost")) != 0,
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
            replyTo = getColumnIndexOrThrow("reply_to").let { if (isNull(it)) null else getLong(it) },
            plannedAt = getColumnIndexOrThrow("planned_at").let { if (isNull(it)) null else getLong(it) },
            kind = MessageKind.valueOf(getString(getColumnIndexOrThrow("kind"))),
        )
    }

    private companion object {
        const val VERSION = 5
    }
}

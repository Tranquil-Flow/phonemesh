package org.phonemesh.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.phonemesh.core.PictureJoin
import org.phonemesh.core.PictureWire
import org.phonemesh.core.wire.Body

/**
 * Pictures between people over wire v2 (accounts spec, "Message format"). A head or part is kept here in the same
 * transaction that advances the session and records the acknowledgement ([AppMailStore.keep]), so nothing is
 * acknowledged that a restart could lose. Pieces wait in `picture_pieces`; once every part is there and the picture
 * checks out, [ready] hands it over to be written as a file and [finished] drops the pieces, in one transaction with
 * the file's row. Nothing here deletes the pieces of a picture that is whole but has no file yet.
 *
 * A bad piece from an authenticated contact (a part past the head's count, a picture that fails its CRC) is consumed
 * and dropped, never refused: refusing would leave the session unadvanced and block that contact's later messages for
 * good. Only storage failures throw, so the packet is not acknowledged and the sender's resend can try again.
 * Called on the thread that owns History, inside its transaction.
 */
internal class PersonPictures(private val history: History) {
    /** A picture whose pieces are all kept and check out, waiting for its file. */
    class Ready(val messageId: Long, val accountId: String, val pictureId: Int, val parts: Int, val bytes: ByteArray)

    fun accept(chat: Chat, accountId: String, body: Body, ref: Long, at: Long) {
        when (body) {
            is Body.PictureHead -> (PictureWire.decodeV2(body.bytes) as? PictureWire.Head)?.let { head(chat, accountId, it, body.bytes, ref, at) }
            is Body.PicturePart -> (PictureWire.decodeV2(body.bytes) as? PictureWire.Part)?.let { part(chat, accountId, it, at) }
            else -> error("not a picture body")
        }
    }

    private fun head(chat: Chat, accountId: String, head: PictureWire.Head, bytes: ByteArray, ref: Long, at: Long) {
        val existing = newest(chat.id, head.id)
        if (existing != null) {
            val (id, shown) = existing
            // A copy of a picture already here, or the same head again while it is still arriving.
            if (shown.file != null || (!shown.lost && pieces(accountId, head.id)[HEAD]?.contentEquals(bytes) == true)) {
                // A resealed head has a fresh reference; future replies/reactions must name that generation.
                history.attachPacket(id, ref)
                return
            }
            if (shown.lost && sameShape(shown, head)) {
                // A resend of a picture that gave up: it reuses its bubble.
                history.writableDatabase.execSQL("UPDATE pictures SET lost = 0, have = 0 WHERE message_id = ?", arrayOf<Any>(id))
                history.attachPacket(id, ref)
                begin(id, accountId, head, bytes, at)
                return
            }
            if (!shown.lost) lose(id, accountId, head.id)
        }
        val row = PictureRow(0, head.id, head.codec.wire, head.width, head.height, head.sketch, null, 0, head.parts, false)
        val message = history.addIncomingPicture(chat.id, accountId, row, at, countUnread = true)
        history.attachPacket(message.id, ref)
        begin(message.id, accountId, head, bytes, at)
    }

    private fun begin(messageId: Long, accountId: String, head: PictureWire.Head, bytes: ByteArray, at: Long) {
        keepPiece(accountId, head.id, HEAD, bytes, at)
        // Parts that arrived first and lie past what the head says are dropped.
        history.writableDatabase.delete(
            "picture_pieces", "account_id = ? AND picture_id = ? AND idx <> ? AND idx >= ?",
            arrayOf(accountId, head.id.toString(), HEAD.toString(), head.parts.toString()),
        )
        progress(messageId, accountId, head.id)
    }

    private fun part(chat: Chat, accountId: String, part: PictureWire.Part, at: Long) {
        val existing = newest(chat.id, part.id)
        if (existing == null) { orphan(accountId, part, at); return }
        val (id, shown) = existing
        if (shown.file != null || shown.lost || part.index >= shown.total) return
        keepPiece(accountId, part.id, part.index, part.data, at)
        progress(id, accountId, part.id)
    }

    /** Bound headless pictures without evicting bytes already acknowledged to the sender. */
    private fun orphan(accountId: String, part: PictureWire.Part, at: Long) {
        val db = history.writableDatabase
        val ids = db.rawQuery(
            "SELECT picture_id FROM picture_pieces WHERE account_id = ? GROUP BY picture_id HAVING SUM(idx = 255) = 0 ORDER BY MAX(at), picture_id",
            arrayOf(accountId),
        ).use { c -> buildList<Int> { while (c.moveToNext()) add(c.getInt(0)) } }
        if (part.id !in ids && ids.size >= MAX_ORPHANS) {
            // Roll back receive state/ack too. The exact packet can retry after a head frees a slot.
            throw org.phonemesh.core.mail.MailStore.Refused()
        }
        keepPiece(accountId, part.id, part.index, part.data, at)
    }

    /** Counts the parts kept; a picture whose parts are all there but do not check out is lost, its pieces dropped. */
    private fun progress(messageId: Long, accountId: String, pictureId: Int) {
        val all = pieces(accountId, pictureId)
        val head = (all[HEAD]?.let { PictureWire.decodeV2(it) } as? PictureWire.Head) ?: return
        history.setPictureProgress(messageId, (0 until head.parts).count { it in all })
        if ((0 until head.parts).all { it in all } && PictureJoin.join(head, all) == null) lose(messageId, accountId, pictureId)
    }

    private fun lose(messageId: Long, accountId: String, pictureId: Int) {
        history.setPictureLost(messageId)
        dropPieces(accountId, pictureId)
    }

    private fun dropPieces(accountId: String, pictureId: Int) {
        history.writableDatabase.delete("picture_pieces", "account_id = ? AND picture_id = ?", arrayOf(accountId, pictureId.toString()))
    }

    private fun keepPiece(accountId: String, pictureId: Int, idx: Int, data: ByteArray, at: Long) {
        history.writableDatabase.insertWithOnConflict(
            "picture_pieces", null,
            ContentValues().apply {
                put("account_id", accountId); put("picture_id", pictureId); put("idx", idx); put("data", data); put("at", at)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun sameShape(shown: PictureRow, head: PictureWire.Head): Boolean =
        shown.codec == head.codec.wire && shown.width == head.width && shown.height == head.height &&
            shown.total == head.parts && shown.sketch.contentEquals(head.sketch)

    /** The newest picture received in [chatId] with this picture id: its message id and row. */
    private fun newest(chatId: Long, pictureId: Int): Pair<Long, PictureRow>? {
        val id = history.readableDatabase.rawQuery(
            "SELECT p.message_id FROM pictures p JOIN messages m ON m.id = p.message_id " +
                "WHERE m.chat_id = ? AND m.direction = 'IN' AND p.picture_id = ? ORDER BY p.message_id DESC LIMIT 1",
            arrayOf(chatId.toString(), pictureId.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return history.picture(id)?.let { id to it }
    }

    private fun pieces(accountId: String, pictureId: Int): Map<Int, ByteArray> = history.readableDatabase.rawQuery(
        "SELECT idx, data FROM picture_pieces WHERE account_id = ? AND picture_id = ?", arrayOf(accountId, pictureId.toString()),
    ).use { c -> buildMap<Int, ByteArray> { while (c.moveToNext()) put(c.getInt(0), c.getBlob(1)) } }

    /** Pictures whose every piece is kept and checks out, oldest first, for the app to write their files. */
    fun ready(): List<Ready> {
        val rows = history.readableDatabase.rawQuery(
            "SELECT p.message_id, c.address, p.picture_id FROM pictures p JOIN messages m ON m.id = p.message_id " +
                "JOIN chats c ON c.id = m.chat_id WHERE c.kind = 'PERSON' AND m.direction = 'IN' AND p.file IS NULL " +
                "AND p.lost = 0 AND p.have = p.total ORDER BY p.message_id",
            null,
        ).use { c -> buildList<Triple<Long, String, Int>> { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getInt(2))) } }
        return rows.mapNotNull { (messageId, account, pictureId) ->
            val all = pieces(account, pictureId)
            val head = (all[HEAD]?.let { PictureWire.decodeV2(it) } as? PictureWire.Head) ?: return@mapNotNull null
            PictureJoin.join(head, all)?.let { Ready(messageId, account, pictureId, head.parts, it) }
        }
    }

    /** The file is written: the picture is whole, and its pieces are no longer needed. */
    fun finished(ready: Ready, file: String) {
        history.transaction {
            history.setPictureFile(ready.messageId, file, ready.parts)
            dropPieces(ready.accountId, ready.pictureId)
        }
    }

    /**
     * Gives up pictures and loose parts that heard nothing since [before]: the sender stops resending after
     * [org.phonemesh.core.mail.MailStore.REPLAY_LIFETIME]. A picture with every part kept is never given up here: its
     * pieces stay until [finished].
     */
    fun expire(before: Long): Int = history.transaction {
        val stale = history.readableDatabase.rawQuery(
            "SELECT account_id, picture_id FROM picture_pieces GROUP BY account_id, picture_id HAVING MAX(at) < CAST(? AS INTEGER)",
            arrayOf(before.toString()),
        ).use { c -> buildList<Pair<String, Int>> { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
        var gone = 0
        for ((account, pictureId) in stale) {
            val pending = history.readableDatabase.rawQuery(
                "SELECT p.message_id, p.have < p.total FROM pictures p JOIN messages m ON m.id = p.message_id " +
                    "JOIN chats c ON c.id = m.chat_id WHERE c.kind = 'PERSON' AND c.address = ? AND m.direction = 'IN' " +
                    "AND p.picture_id = ? AND p.file IS NULL AND p.lost = 0 ORDER BY p.message_id DESC LIMIT 1",
                arrayOf(account, pictureId.toString()),
            ).use { if (it.moveToFirst()) it.getLong(0) to (it.getInt(1) != 0) else null }
            if (pending != null && !pending.second) continue
            if (pending != null) history.setPictureLost(pending.first)
            dropPieces(account, pictureId)
            gone++
        }
        gone
    }

    /** Earliest incomplete-group expiry; complete pictures instead wait for a bounded file-write retry. */
    fun nextExpiry(): Long? = history.readableDatabase.rawQuery(
        "SELECT MIN(last_at) FROM (SELECT account_id, picture_id, MAX(at) AS last_at FROM picture_pieces " +
            "GROUP BY account_id, picture_id) g WHERE NOT EXISTS (" +
            "SELECT 1 FROM pictures p JOIN messages m ON m.id=p.message_id JOIN chats c ON c.id=m.chat_id " +
            "WHERE c.kind='PERSON' AND c.address=g.account_id AND m.direction='IN' AND p.picture_id=g.picture_id " +
            "AND p.file IS NULL AND p.lost=0 AND p.have=p.total)", null,
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) + org.phonemesh.core.mail.MailStore.REPLAY_LIFETIME + 1 else null }

    class IdLimit : RuntimeException()

    /**
     * Reserve an outgoing wire id per account, not per local bubble. The peer may retain a picture after this
     * phone deletes its chat. A fixed bitmap retains only used ids; no contents, times or filenames survive.
     * Nested in sendPicture's transaction, so a failed file/outbox commit also rolls back the reservation.
     */
    fun freshId(chatId: Long, pick: () -> Int): Int = history.transaction {
        val chat = history.chat(chatId) ?: throw org.phonemesh.core.mail.MailStore.Refused()
        require(chat.kind == ChatKind.PERSON || chat.kind == ChatKind.PRIVATE_GROUP)
        // A private group's ids live under the first 8 bytes of its id (the column holds 16 hex characters): only its
        // own members name pictures by them, so this phone just must not reuse one there.
        val key = chat.address.take(16)
        val db = history.writableDatabase
        val saved = db.rawQuery("SELECT used FROM picture_ids WHERE account_id = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }
        val used = saved ?: ByteArray(8192)
        if (used.size != 8192) throw org.phonemesh.core.mail.MailStore.Refused()
        fun taken(id: Int): Boolean = used[id ushr 3].toInt() and (1 shl (id and 7)) != 0
        fun reserve(id: Int) { used[id ushr 3] = (used[id ushr 3].toInt() or (1 shl (id and 7))).toByte() }
        var chosen = -1
        for (attempt in 0 until 64) {
            val id = pick()
            require(id in 0..65535)
            if (!taken(id)) { chosen = id; break }
        }
        if (chosen < 0) chosen = (0..65535).firstOrNull { !taken(it) } ?: throw IdLimit()
        reserve(chosen)
        val values = ContentValues().apply { put("account_id", key); put("used", used) }
        if (saved == null) db.insertOrThrow("picture_ids", null, values)
        else if (db.update("picture_ids", values, "account_id = ?", arrayOf(key)) != 1)
            throw org.phonemesh.core.mail.MailStore.Refused()
        chosen
    }

    companion object {
        /** The slot that keeps a picture's head packet; parts use their own index. */
        const val HEAD = 255
        const val MAX_ORPHANS = 4

        /** Schema 12, as an added block of the parent's `oldVersion < 12` migration. */
        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE picture_pieces (account_id TEXT NOT NULL, picture_id INTEGER NOT NULL CHECK(picture_id BETWEEN 0 AND 65535), " +
                    "idx INTEGER NOT NULL CHECK(idx BETWEEN 0 AND 16 OR idx = 255), " +
                    "data BLOB NOT NULL CHECK(length(data) BETWEEN 1 AND 198), at INTEGER NOT NULL, " +
                    "PRIMARY KEY(account_id, picture_id, idx))",
            )
        }
    }
}

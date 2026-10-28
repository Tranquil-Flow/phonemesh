package org.phonemesh.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.phonemesh.core.PictureJoin
import org.phonemesh.core.PictureWire
import org.phonemesh.core.wire.Body

/**
 * Pictures in private groups (decision 69, draft GK4): the head, with its colour sketch, comes by radio and makes the
 * bubble at once; the parts come only through a hub, on the sender's hub chain, and are kept in
 * `group_picture_pieces` until the picture is whole and checks out. Phones that never reach a hub keep the sketch.
 * Like [PersonPictures], a bad piece is dropped, never refused. Called inside [AppGroupStore]'s transaction.
 */
internal class GroupPictures(private val history: History, private val rows: GroupRows) {
    class Ready(val messageId: Long, val groupId: String, val accountId: String, val pictureId: Int, val parts: Int, val bytes: ByteArray)

    fun accept(groupId: String, chatId: Long, accountId: String, body: Body, ref: Long?, at: Long) {
        when (body) {
            is Body.PictureHead -> (PictureWire.decodeGroup(body.bytes) as? PictureWire.Head)?.let { head(groupId, chatId, accountId, it, body.bytes, ref, at) }
            is Body.PicturePart -> (PictureWire.decodeGroup(body.bytes) as? PictureWire.Part)?.let { part(groupId, chatId, accountId, it, at) }
            else -> Unit
        }
    }

    private fun head(groupId: String, chatId: Long, accountId: String, head: PictureWire.Head, bytes: ByteArray, ref: Long?, at: Long) {
        if (newest(chatId, accountId, head.id) != null) return
        val row = PictureRow(0, head.id, head.codec.wire, head.width, head.height, head.sketch, null, 0, head.parts, false)
        val message = history.addIncomingPicture(chatId, accountId, row, at, countUnread = true)
        ref?.let { history.attachPacket(message.id, it) }
        keepPiece(groupId, accountId, head.id, HEAD, bytes, at)
        // Parts kept before the head that lie past its count are dropped.
        history.writableDatabase.delete("group_picture_pieces", "group_id = ? AND account_id = ? AND picture_id = ? AND idx <> ? AND idx >= ?",
            arrayOf(groupId, accountId, head.id.toString(), HEAD.toString(), head.parts.toString()))
        progress(message.id, groupId, accountId, head.id)
    }

    private fun part(groupId: String, chatId: Long, accountId: String, part: PictureWire.Part, at: Long) {
        val existing = newest(chatId, accountId, part.id)
        if (existing != null) {
            val (id, shown) = existing
            if (shown.file != null || shown.lost || part.index >= shown.total) return
            keepPiece(groupId, accountId, part.id, part.index, part.data, at)
            progress(id, groupId, accountId, part.id)
            return
        }
        // A part before its head (the hub had it first): kept, a few pictures at most per sender.
        val waiting = history.readableDatabase.rawQuery("SELECT COUNT(DISTINCT picture_id) FROM group_picture_pieces WHERE group_id = ? AND account_id = ?",
            arrayOf(groupId, accountId)).use { it.moveToFirst(); it.getInt(0) }
        if (waiting < MAX_WAITING) keepPiece(groupId, accountId, part.id, part.index, part.data, at)
    }

    private fun progress(messageId: Long, groupId: String, accountId: String, pictureId: Int) {
        val all = pieces(groupId, accountId, pictureId)
        val head = (all[HEAD]?.let { PictureWire.decodeGroup(it) } as? PictureWire.Head) ?: return
        history.setPictureProgress(messageId, (0 until head.parts).count { it in all })
        if (head.parts > 0 && (0 until head.parts).all { it in all } && PictureJoin.join(head, all, PictureWire.PART_BYTES_GROUP) == null) {
            history.setPictureLost(messageId)
            drop(groupId, accountId, pictureId)
        }
    }

    private fun keepPiece(groupId: String, accountId: String, pictureId: Int, idx: Int, data: ByteArray, at: Long) {
        history.writableDatabase.insertWithOnConflict("group_picture_pieces", null, ContentValues().apply {
            put("group_id", groupId); put("account_id", accountId); put("picture_id", pictureId); put("idx", idx); put("data", data); put("at", at)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun drop(groupId: String, accountId: String, pictureId: Int) {
        history.writableDatabase.delete("group_picture_pieces", "group_id = ? AND account_id = ? AND picture_id = ?", arrayOf(groupId, accountId, pictureId.toString()))
    }

    private fun newest(chatId: Long, accountId: String, pictureId: Int): Pair<Long, PictureRow>? {
        val id = history.readableDatabase.rawQuery(
            "SELECT p.message_id FROM pictures p JOIN messages m ON m.id = p.message_id WHERE m.chat_id = ? AND m.direction = 'IN' " +
                "AND m.sender_id = ? AND p.picture_id = ? ORDER BY p.message_id DESC LIMIT 1",
            arrayOf(chatId.toString(), accountId, pictureId.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        return history.picture(id)?.let { id to it }
    }

    private fun pieces(groupId: String, accountId: String, pictureId: Int): Map<Int, ByteArray> = history.readableDatabase.rawQuery(
        "SELECT idx, data FROM group_picture_pieces WHERE group_id = ? AND account_id = ? AND picture_id = ?", arrayOf(groupId, accountId, pictureId.toString()),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.getBlob(1)) } }

    /**
     * Pieces of pictures that did not complete by [before] go (review G9-10): a picture with its head shows as lost,
     * parts that waited for a head that never came are simply dropped.
     */
    fun expire(before: Long) {
        val stale = history.readableDatabase.rawQuery(
            "SELECT group_id, account_id, picture_id FROM group_picture_pieces GROUP BY group_id, account_id, picture_id HAVING MIN(at) < CAST(? AS INTEGER)",
            arrayOf(before.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getInt(2))) } }
        for ((g, a, p) in stale) {
            rows.group(g)?.let { row -> newest(row.chatId, a, p) }?.let { (message, shown) -> if (shown.file == null && !shown.lost) history.setPictureLost(message) }
            drop(g, a, p)
        }
    }

    /** Group pictures whose every piece is kept and checks out, oldest first, for the app to write their files. */
    fun ready(): List<Ready> {
        val found = history.readableDatabase.rawQuery(
            "SELECT p.message_id, c.address, m.sender_id, p.picture_id FROM pictures p JOIN messages m ON m.id = p.message_id " +
                "JOIN chats c ON c.id = m.chat_id WHERE c.kind = 'PRIVATE_GROUP' AND m.direction = 'IN' AND p.file IS NULL " +
                "AND p.lost = 0 AND p.total > 0 AND p.have = p.total ORDER BY p.message_id", null,
        ).use { c -> buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3))) } }
        return found.mapNotNull { (m, g, a, p) ->
            val all = pieces(g as String, a as String, p as Int)
            val head = (all[HEAD]?.let { PictureWire.decodeGroup(it) } as? PictureWire.Head) ?: return@mapNotNull null
            PictureJoin.join(head, all, PictureWire.PART_BYTES_GROUP)?.let { Ready(m as Long, g, a, p, head.parts, it) }
        }
    }

    fun finished(ready: Ready, file: String) {
        history.transaction {
            history.setPictureFile(ready.messageId, file, ready.parts)
            drop(ready.groupId, ready.accountId, ready.pictureId)
        }
    }

    companion object {
        const val HEAD = 255
        const val MAX_WAITING = 4
    }
}

package org.phonemesh.app

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Private groups on the phone (v0.1.2, decisions 47, 66, 69, 72, 74; History version 21). Each group's state is kept
 * sealed by the [Vault] in `private_groups` with its signing key; the rest is what the screens read: members with the
 * names they sent, invitations, the group outbox (packets kept before they leave, review C8), private bodies waiting
 * for the Postman, who has each message ([deliveredCount]) and photo pieces. Main thread only, like [History].
 */
class GroupRows(private val history: History) {
    private val db: SQLiteDatabase get() = history.writableDatabase

    class GroupRow(
        val groupId: String, val chatId: Long, val sealed: ByteArray, val signer: ByteArray, val signerSealed: ByteArray,
        val invited: ByteArray, val receiptDue: Long?, val profileDue: Long?, val adminDue: Long?, val askedEpoch: Long,
        val draft: ByteArray?, val state: String, val epoch: Long, val admin: Boolean, val name: String, val colour: Int,
        val avatar: ByteArray, val recent: Boolean, val waitingOn: String?, val left: Boolean,
    )

    class MemberRow(val groupId: String, val accountId: String, val admin: Boolean, val profile: ByteArray?, val position: Int)

    class InvitationRow(val groupId: String, val fromAccount: String, val fromIdentity: ByteArray, val invite: ByteArray, val at: Long)

    class OutRow(
        val id: Long, val groupId: String, val packet: ByteArray, val hubOnly: Boolean, val messageId: Long?, val epoch: Long,
        val seq: Long, val audience: Int, val created: Long, val airings: Int, val state: String,
    )

    class PrivateRow(val id: Long, val accountId: String, val kind: Int, val sealed: ByteArray)

    fun groups(): List<GroupRow> = db.query("private_groups", null, null, null, null, null, "group_id").use { c -> buildList { while (c.moveToNext()) add(c.group()) } }

    fun group(groupId: String): GroupRow? = db.query("private_groups", null, "group_id = ?", arrayOf(groupId), null, null, null).use { if (it.moveToFirst()) it.group() else null }

    fun groupByChat(chatId: Long): GroupRow? = db.query("private_groups", null, "chat_id = ?", arrayOf(chatId.toString()), null, null, null).use { if (it.moveToFirst()) it.group() else null }

    fun putGroup(row: GroupRow) {
        db.insertWithOnConflict("private_groups", null, ContentValues().apply {
            put("group_id", row.groupId); put("chat_id", row.chatId); put("sealed", row.sealed); put("signer", row.signer)
            put("signer_sealed", row.signerSealed); put("invited", row.invited); put("receipt_due", row.receiptDue)
            put("profile_due", row.profileDue); put("admin_due", row.adminDue); put("asked_epoch", row.askedEpoch); put("draft", row.draft)
            put("state", row.state); put("epoch", row.epoch); put("admin", if (row.admin) 1 else 0); put("name", row.name)
            put("colour", row.colour); put("avatar", row.avatar); put("recent", if (row.recent) 1 else 0); put("waiting_on", row.waitingOn)
            put("left_group", if (row.left) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Drops a group's state, members, waiting packets and photo pieces; its chat and messages stay. */
    fun deleteGroup(groupId: String) {
        for (t in listOf("private_groups", "group_members", "group_photo_pieces", "group_photos", "group_picture_pieces")) db.delete(t, "group_id = ?", arrayOf(groupId))
        db.delete("group_outbox", "group_id = ? AND state = 'WAITING'", arrayOf(groupId))
    }

    fun members(groupId: String): List<MemberRow> = db.query("group_members", null, "group_id = ?", arrayOf(groupId), null, null, "position").use { c ->
        buildList { while (c.moveToNext()) add(MemberRow(c.getString(0), c.getString(1), c.getInt(2) != 0, if (c.isNull(3)) null else c.getBlob(3), c.getInt(4))) }
    }

    /** The list's people in order; names and pictures they sent are kept for people who stay. */
    fun setMembers(groupId: String, members: List<Pair<String, Boolean>>) {
        val profiles = members(groupId).associate { it.accountId to it.profile }
        db.delete("group_members", "group_id = ?", arrayOf(groupId))
        members.forEachIndexed { i, (account, admin) ->
            db.insertOrThrow("group_members", null, ContentValues().apply {
                put("group_id", groupId); put("account_id", account); put("admin", if (admin) 1 else 0); put("profile", profiles[account]); put("position", i)
            })
        }
    }

    /** Keeps [profile] (encoded) for [accountId] in this group when it is newer than the one kept; also for people who left. */
    fun setProfile(groupId: String, accountId: String, profile: ByteArray, revision: Long) {
        val known = db.query("group_profiles", arrayOf("revision"), "group_id = ? AND account_id = ?", arrayOf(groupId, accountId), null, null, null)
            .use { if (it.moveToFirst()) it.getLong(0) else null }
        if (known != null && known >= revision) return
        db.insertWithOnConflict("group_profiles", null, ContentValues().apply {
            put("group_id", groupId); put("account_id", accountId); put("profile", profile); put("revision", revision)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.update("group_members", ContentValues().apply { put("profile", profile) }, "group_id = ? AND account_id = ?", arrayOf(groupId, accountId))
    }

    /** The newest profile [accountId] sent in any group, if one is kept. */
    fun anyProfile(accountId: String): ByteArray? =
        db.query("group_profiles", arrayOf("profile"), "account_id = ?", arrayOf(accountId), null, null, "revision DESC", "1")
            .use { if (it.moveToFirst()) it.getBlob(0) else null }

    /** The newest profile [accountId] sent in [groupId] (kept after they leave, for old messages' names). */
    fun profile(groupId: String, accountId: String): ByteArray? =
        db.query("group_profiles", arrayOf("profile"), "group_id = ? AND account_id = ?", arrayOf(groupId, accountId), null, null, null)
            .use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun invitations(): List<InvitationRow> = db.query("group_invitations", null, null, null, null, null, "at").use { c ->
        buildList { while (c.moveToNext()) add(InvitationRow(c.getString(0), c.getString(1), c.getBlob(2), c.getBlob(3), c.getLong(4))) }
    }

    fun putInvitation(row: InvitationRow) {
        db.insertWithOnConflict("group_invitations", null, ContentValues().apply {
            put("group_id", row.groupId); put("from_account", row.fromAccount); put("from_identity", row.fromIdentity)
            put("invite", row.invite); put("at", row.at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun dropInvitation(groupId: String) { db.delete("group_invitations", "group_id = ?", arrayOf(groupId)) }

    fun addPrivate(accountId: String, kind: Int, sealed: ByteArray, at: Long): Long = db.insertOrThrow("group_private_out", null, ContentValues().apply {
        put("account_id", accountId); put("kind", kind); put("sealed", sealed); put("at", at)
    })

    fun privates(): List<PrivateRow> = db.query("group_private_out", null, null, null, null, null, "id").use { c ->
        buildList { while (c.moveToNext()) add(PrivateRow(c.getLong(0), c.getString(1), c.getInt(2), c.getBlob(3))) }
    }

    fun dropPrivate(id: Long) { db.delete("group_private_out", "id = ?", arrayOf(id.toString())) }

    /** Private bodies queued before [before] go; returns (contact, kind) of each, so a key that never went can be reported. */
    fun expirePrivate(before: Long): List<Pair<String, Int>> {
        val gone = db.query("group_private_out", arrayOf("account_id", "kind"), "at < ?", arrayOf(before.toString()), null, null, "id")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
        if (gone.isNotEmpty()) db.delete("group_private_out", "at < ?", arrayOf(before.toString()))
        return gone
    }

    /** Hub-only packets still waiting for a hub since before [before] can no longer be read by anyone: they stop waiting. */
    fun expireHubOnly(before: Long) {
        db.execSQL("UPDATE group_outbox SET state = '${AppGroupStore.STATE_EXPIRED}' WHERE state = 'WAITING' AND hub_only = 1 AND created < ?", arrayOf<Any>(before))
    }

    /** Photo pieces of a member or group photo that never became whole, kept since before [before], go. */
    fun expirePhotoPieces(before: Long) { db.delete("group_photo_pieces", "at < ?", arrayOf(before.toString())) }

    fun addOut(groupId: String, packet: ByteArray, hubOnly: Boolean, messageId: Long?, epoch: Long, seq: Long, audience: Int, at: Long): Long =
        db.insertOrThrow("group_outbox", null, ContentValues().apply {
            put("group_id", groupId); put("packet", packet); put("hub_only", if (hubOnly) 1 else 0); put("message_id", messageId)
            put("epoch", epoch); put("seq", seq); put("audience", audience); put("created", at)
        })

    fun out(id: Long): OutRow? = db.query("group_outbox", null, "id = ?", arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) it.out() else null }

    fun outWaiting(): List<OutRow> = db.query("group_outbox", null, "state = 'WAITING'", null, null, null, "id").use { c -> buildList { while (c.moveToNext()) add(c.out()) } }

    fun outFor(messageId: Long): List<OutRow> = db.query("group_outbox", null, "message_id = ?", arrayOf(messageId.toString()), null, null, "id").use { c -> buildList { while (c.moveToNext()) add(c.out()) } }

    fun outAt(groupId: String, epoch: Long): List<OutRow> = db.query("group_outbox", null, "group_id = ? AND epoch = ? AND seq >= 0 AND hub_only = 0",
        arrayOf(groupId, epoch.toString()), null, null, "seq").use { c -> buildList { while (c.moveToNext()) add(c.out()) } }

    fun setOutState(id: Long, state: String, airings: Int? = null) {
        db.update("group_outbox", ContentValues().apply { put("state", state); airings?.let { put("airings", it) } }, "id = ?", arrayOf(id.toString()))
    }

    /** Rows done long ago go; a message's rows stay while it is shown (they say "of M" and what to send again). */
    fun pruneOut(before: Long) {
        db.delete("group_outbox", "state <> 'WAITING' AND created < ? AND (message_id IS NULL OR message_id NOT IN (SELECT id FROM messages))", arrayOf(before.toString()))
    }

    /** [accountId] has message [messageId]: counted once. */
    fun delivered(messageId: Long, accountId: String) {
        db.insertWithOnConflict("group_delivered", null, ContentValues().apply { put("message_id", messageId); put("account_id", accountId) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deliveredCount(messageId: Long): Int = db.rawQuery("SELECT COUNT(*) FROM group_delivered WHERE message_id = ?", arrayOf(messageId.toString())).use { it.moveToFirst(); it.getInt(0) }

    fun deliveredTo(messageId: Long): List<String> = db.query("group_delivered", arrayOf("account_id"), "message_id = ?", arrayOf(messageId.toString()), null, null, null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** How many others were on the list when [messageId] was sent; null when it is not a group message of this phone. */
    fun audience(messageId: Long): Int? = db.rawQuery("SELECT MAX(audience) FROM group_outbox WHERE message_id = ?", arrayOf(messageId.toString()))
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null }

    fun keepPhotoPiece(groupId: String, accountId: String, ofGroup: Boolean, piece: ByteArray, at: Long) {
        db.insertOrThrow("group_photo_pieces", null, ContentValues().apply {
            put("group_id", groupId); put("account_id", accountId); put("of_group", if (ofGroup) 1 else 0); put("piece", piece); put("at", at)
        })
        // Bounded: a photo is at most 9 pieces; keep the newest few photos' worth per sender.
        db.execSQL("DELETE FROM group_photo_pieces WHERE group_id = ? AND account_id = ? AND of_group = ? AND rowid NOT IN " +
            "(SELECT rowid FROM group_photo_pieces WHERE group_id = ? AND account_id = ? AND of_group = ? ORDER BY rowid DESC LIMIT $MAX_PHOTO_PIECES)",
            arrayOf<Any>(groupId, accountId, if (ofGroup) 1 else 0, groupId, accountId, if (ofGroup) 1 else 0))
    }

    fun photoPieces(groupId: String, accountId: String, ofGroup: Boolean): List<ByteArray> = db.query("group_photo_pieces", arrayOf("piece"),
        "group_id = ? AND account_id = ? AND of_group = ?", arrayOf(groupId, accountId, if (ofGroup) "1" else "0"), null, null, "rowid")
        .use { c -> buildList { while (c.moveToNext()) add(c.getBlob(0)) } }

    class Photo(val webp: ByteArray, val width: Int, val height: Int, val sketch: ByteArray)

    /** A checked photo of [accountId] in [groupId], or of the group itself ([accountId] empty). */
    fun photo(groupId: String, accountId: String): Photo? = db.query("group_photos", arrayOf("webp", "width", "height", "sketch"),
        "group_id = ? AND account_id = ?", arrayOf(groupId, accountId), null, null, null)
        .use { if (it.moveToFirst()) Photo(it.getBlob(0), it.getInt(1), it.getInt(2), it.getBlob(3)) else null }

    fun setPhoto(groupId: String, accountId: String, photo: Photo) {
        db.insertWithOnConflict("group_photos", null, ContentValues().apply {
            put("group_id", groupId); put("account_id", accountId); put("webp", photo.webp); put("width", photo.width)
            put("height", photo.height); put("sketch", photo.sketch)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.group() = GroupRow(
        getString(getColumnIndexOrThrow("group_id")), getLong(getColumnIndexOrThrow("chat_id")), getBlob(getColumnIndexOrThrow("sealed")),
        getBlob(getColumnIndexOrThrow("signer")), getBlob(getColumnIndexOrThrow("signer_sealed")), getBlob(getColumnIndexOrThrow("invited")),
        long("receipt_due"), long("profile_due"), long("admin_due"), getLong(getColumnIndexOrThrow("asked_epoch")),
        getColumnIndexOrThrow("draft").let { if (isNull(it)) null else getBlob(it) }, getString(getColumnIndexOrThrow("state")),
        getLong(getColumnIndexOrThrow("epoch")), getInt(getColumnIndexOrThrow("admin")) != 0, getString(getColumnIndexOrThrow("name")),
        getInt(getColumnIndexOrThrow("colour")), getBlob(getColumnIndexOrThrow("avatar")), getInt(getColumnIndexOrThrow("recent")) != 0,
        getColumnIndexOrThrow("waiting_on").let { if (isNull(it)) null else getString(it) }, getInt(getColumnIndexOrThrow("left_group")) != 0,
    )

    private fun Cursor.long(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.out() = OutRow(
        getLong(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("group_id")), getBlob(getColumnIndexOrThrow("packet")),
        getInt(getColumnIndexOrThrow("hub_only")) != 0, long("message_id"), getLong(getColumnIndexOrThrow("epoch")),
        getLong(getColumnIndexOrThrow("seq")), getInt(getColumnIndexOrThrow("audience")), getLong(getColumnIndexOrThrow("created")),
        getInt(getColumnIndexOrThrow("airings")), getString(getColumnIndexOrThrow("state")),
    )

    companion object {
        const val MAX_PHOTO_PIECES = 27

        /** History version 21. `IF NOT EXISTS` so a table already made stays as it is. */
        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS private_groups (group_id TEXT PRIMARY KEY CHECK(length(group_id)=32), " +
                    "chat_id INTEGER NOT NULL REFERENCES chats(id), sealed BLOB NOT NULL, signer BLOB NOT NULL CHECK(length(signer)=33), " +
                    "signer_sealed BLOB NOT NULL, invited BLOB NOT NULL, receipt_due INTEGER, profile_due INTEGER, admin_due INTEGER, " +
                    "asked_epoch INTEGER NOT NULL DEFAULT 0, draft BLOB, state TEXT NOT NULL, epoch INTEGER NOT NULL DEFAULT 0, " +
                    "admin INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, colour INTEGER NOT NULL, avatar BLOB NOT NULL, " +
                    "recent INTEGER NOT NULL DEFAULT 0, waiting_on TEXT, left_group INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_members (group_id TEXT NOT NULL, account_id TEXT NOT NULL CHECK(length(account_id)=16), " +
                    "admin INTEGER NOT NULL, profile BLOB, position INTEGER NOT NULL, PRIMARY KEY(group_id, account_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_profiles (group_id TEXT NOT NULL, account_id TEXT NOT NULL CHECK(length(account_id)=16), " +
                    "profile BLOB NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(group_id, account_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_invitations (group_id TEXT PRIMARY KEY CHECK(length(group_id)=32), " +
                    "from_account TEXT NOT NULL CHECK(length(from_account)=16), from_identity BLOB NOT NULL CHECK(length(from_identity)=33), " +
                    "invite BLOB NOT NULL, at INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_private_out (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "account_id TEXT NOT NULL CHECK(length(account_id)=16), kind INTEGER NOT NULL, sealed BLOB NOT NULL, at INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL, " +
                    "packet BLOB NOT NULL CHECK(length(packet)<=228), hub_only INTEGER NOT NULL, message_id INTEGER, " +
                    "epoch INTEGER NOT NULL, seq INTEGER NOT NULL, audience INTEGER NOT NULL, created INTEGER NOT NULL, " +
                    "airings INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'WAITING')",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS group_outbox_by_message ON group_outbox (message_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS group_outbox_by_state ON group_outbox (state, id)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_delivered (message_id INTEGER NOT NULL, account_id TEXT NOT NULL CHECK(length(account_id)=16), " +
                    "PRIMARY KEY(message_id, account_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_photo_pieces (group_id TEXT NOT NULL, account_id TEXT NOT NULL, of_group INTEGER NOT NULL, " +
                    "piece BLOB NOT NULL CHECK(length(piece)<=137), at INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_photos (group_id TEXT NOT NULL, account_id TEXT NOT NULL, webp BLOB NOT NULL, " +
                    "width INTEGER NOT NULL, height INTEGER NOT NULL, sketch BLOB NOT NULL, PRIMARY KEY(group_id, account_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS group_picture_pieces (group_id TEXT NOT NULL, account_id TEXT NOT NULL CHECK(length(account_id)=16), " +
                    "picture_id INTEGER NOT NULL CHECK(picture_id BETWEEN 0 AND 65535), idx INTEGER NOT NULL CHECK(idx BETWEEN 0 AND 24 OR idx = 255), " +
                    "data BLOB NOT NULL CHECK(length(data) BETWEEN 1 AND 133), at INTEGER NOT NULL, PRIMARY KEY(group_id, account_id, picture_id, idx))",
            )
        }
    }
}

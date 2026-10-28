package org.phonemesh.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.phonemesh.core.DeliveryRules
import org.phonemesh.core.DeliveryStatus

/**
 * GROUP: the shared channel. DIRECT: an old chat with one radio (read only once accounts arrive). PERSON: a contact's
 * account. PRIVATE_GROUP: a private group ([GroupRows]; address = the group id in hex).
 */
enum class ChatKind { GROUP, DIRECT, PERSON, PRIVATE_GROUP }

enum class Direction { IN, OUT }

/**
 * TEXT is a spoken/written message; PICTURE is a picture sent over the radio (its row in `pictures`); NOTE is a line the
 * app writes into a private group's chat ("Kofi joined."): its text is a [GroupNotes] code, shown in the current language.
 */
enum class MessageKind { TEXT, PICTURE, NOTE }

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
    /** In a private group: the admin who shared this recent message with new members (decision 74), else null. */
    val sharedBy: String? = null,
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
        // Accounts (docs/specs/2026-10-14-accounts-design.md, plan step 9). Secrets are sealed by AccountStore's Vault;
        // skipped message keys live inside each sealed session state, and recipient codes are derived from it.
        if (oldVersion < 6) {
            db.execSQL(
                "CREATE TABLE account (id INTEGER PRIMARY KEY CHECK (id = 1), store TEXT NOT NULL, identity BLOB NOT NULL, " +
                    "sealed BLOB, name TEXT NOT NULL DEFAULT '', colour INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)",
            )
            db.execSQL("CREATE TABLE prekeys (public BLOB PRIMARY KEY, sealed BLOB NOT NULL, created INTEGER NOT NULL, retired INTEGER)")
            db.execSQL(
                "CREATE TABLE contacts (account_id TEXT PRIMARY KEY, card BLOB NOT NULL, name TEXT NOT NULL, colour INTEGER NOT NULL, " +
                    "met INTEGER NOT NULL DEFAULT 0, voice TEXT, added INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE sessions (account_id TEXT PRIMARY KEY REFERENCES contacts(account_id), serial INTEGER NOT NULL, " +
                    "state BLOB NOT NULL, updated INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL, message_id INTEGER, " +
                    "port INTEGER NOT NULL, packet BLOB NOT NULL, chain_tag BLOB, counter INTEGER, created INTEGER NOT NULL, " +
                    "tries INTEGER NOT NULL DEFAULT 0, next_try INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'WAITING')",
            )
            db.execSQL("CREATE INDEX outbox_by_contact ON outbox (account_id, id)")
            db.execSQL("CREATE TABLE hellos_seen (id BLOB PRIMARY KEY, at INTEGER NOT NULL)")
        }
        // Messaging engine (plan step 13): each outgoing packet's recipient code (hub acks, references), the newest
        // ack from each contact (flow control), and messages held past the window.
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN code BLOB")
            db.execSQL("ALTER TABLE contacts ADD COLUMN last_ack BLOB")
            db.execSQL(
                "CREATE TABLE held (id INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL, message_id INTEGER, " +
                    "body BLOB NOT NULL, at INTEGER NOT NULL)",
            )
        }
        if (oldVersion < 8) {
            db.execSQL("CREATE TABLE received_mail (digest TEXT PRIMARY KEY CHECK(length(digest)=64), " +
                "account_id TEXT NOT NULL, response_ack BLOB, receipt_due INTEGER, expires INTEGER NOT NULL, " +
                "CHECK(response_ack IS NULL OR length(response_ack)=10), CHECK(receipt_due IS NULL OR response_ack IS NOT NULL))")
            db.execSQL("CREATE INDEX received_mail_due ON received_mail(receipt_due)")
            db.execSQL("CREATE INDEX received_mail_expiry ON received_mail(expires)")
            db.execSQL("ALTER TABLE outbox ADD COLUMN is_receipt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE outbox ADD COLUMN carried_ack BLOB")
            // Acceptance of old receipt rows was never persisted. Offer those exact bytes once, conservatively.
            db.execSQL("UPDATE outbox SET is_receipt=1, state='WAITING' WHERE state='RECEIPT'")
        }
        if (oldVersion < 9) db.execSQL("CREATE TABLE qr_sessions (account_id TEXT PRIMARY KEY, start_digest BLOB NOT NULL CHECK(length(start_digest)=32))")
        if (oldVersion < 10) {
            db.execSQL("CREATE TABLE incoming_held (account_id TEXT NOT NULL, digest TEXT NOT NULL CHECK(length(digest)=64), " +
                "packet BLOB NOT NULL CHECK(length(packet)<=228), expires INTEGER NOT NULL, PRIMARY KEY(account_id,digest))")
            db.execSQL("CREATE INDEX incoming_held_expiry ON incoming_held(expires)")
        }
        if (oldVersion < 11) {
            db.execSQL("CREATE TABLE incoming_deadlines (account_id TEXT NOT NULL, code TEXT NOT NULL CHECK(length(code)=16), " +
                "expires INTEGER NOT NULL, PRIMARY KEY(account_id,code))")
            // Preserve the earliest v10 deadline; wire v2 has one marker byte followed by the eight-byte code.
            db.execSQL("INSERT INTO incoming_deadlines SELECT account_id,lower(hex(substr(packet,2,8))),min(expires) " +
                "FROM incoming_held GROUP BY account_id,substr(packet,2,8)")
            db.execSQL("UPDATE incoming_held SET expires=(SELECT d.expires FROM incoming_deadlines d " +
                "WHERE d.account_id=incoming_held.account_id AND d.code=lower(hex(substr(incoming_held.packet,2,8))))")
        }
        if (oldVersion < 12) {
            db.execSQL("CREATE TABLE pending_requests (account_id TEXT PRIMARY KEY CHECK(length(account_id)=16), " +
                "hello_id BLOB NOT NULL UNIQUE CHECK(length(hello_id)=16), sealed BLOB NOT NULL CHECK(length(sealed)<=8192), at INTEGER NOT NULL)")
            PersonPictures.createTables(db)
        }
        if (oldVersion < 13) {
            db.execSQL("CREATE TABLE picture_ids (account_id TEXT PRIMARY KEY CHECK(length(account_id)=16), " +
                "used BLOB NOT NULL CHECK(length(used)=8192))")
            // Preserve every retained outgoing v12 picture before local deletion can erase its wire id.
            val allocated = mutableMapOf<String, ByteArray>()
            db.rawQuery("SELECT c.address,p.picture_id FROM pictures p JOIN messages m ON m.id=p.message_id " +
                "JOIN chats c ON c.id=m.chat_id WHERE c.kind='PERSON' AND m.direction='OUT'", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getInt(1)
                    require(id in 0..65535)
                    val bits = allocated.getOrPut(c.getString(0)) { ByteArray(8192) }
                    bits[id ushr 3] = (bits[id ushr 3].toInt() or (1 shl (id and 7))).toByte()
                }
            }
            for ((account, bits) in allocated) db.insertOrThrow("picture_ids", null,
                ContentValues().apply { put("account_id", account); put("used", bits) })
        }
        if (oldVersion < 14) db.execSQL("CREATE TABLE hub_cursor (pin TEXT PRIMARY KEY CHECK(length(pin)=43), seq INTEGER NOT NULL CHECK(seq>=0))")
        // Only a pinned /send response binds custody to a particular hub. Legacy HUB observations remain unbound.
        if (oldVersion < 15) db.execSQL("CREATE TABLE hub_custody (outbox_id INTEGER NOT NULL REFERENCES outbox(id), " +
            "pin TEXT NOT NULL CHECK(length(pin)=43), PRIMARY KEY(outbox_id,pin))")
        // Profiles (spec decisions 36, 48-50): the encoded profile beside the name, and colours as RGB from the wheel
        // instead of an index into the first eight. A contact's profile stays empty until their next card, hello or
        // profile update, so any real one replaces it.
        // A table that already has the column holds version 16 data (tests set the version back): leave it alone.
        if (oldVersion < 16) for (table in listOf("account", "contacts")) {
            if (hasColumn(db, table, "profile")) continue
            db.execSQL("ALTER TABLE $table ADD COLUMN profile BLOB")
            db.execSQL("UPDATE $table SET colour = $OLD_COLOURS")
        }
        // Profile updates (PF5): the newest revision of this phone's profile each contact was sent, and when.
        if (oldVersion < 17) for (column in listOf("profile_sent", "profile_sent_at")) {
            if (!hasColumn(db, "contacts", column)) db.execSQL("ALTER TABLE contacts ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
        }
        // Saved people from a restored copy (account recovery RC5 A): kept apart from contacts, to add again.
        if (oldVersion < 18) db.execSQL(
            "CREATE TABLE IF NOT EXISTS recovered_contacts (identity BLOB PRIMARY KEY, card BLOB NOT NULL, name TEXT NOT NULL, " +
                "colour INTEGER NOT NULL, met_before INTEGER NOT NULL, voice TEXT, added INTEGER NOT NULL, archive BLOB NOT NULL, " +
                "restored INTEGER NOT NULL)",
        )
        // Photo avatars (decision 50): pieces as they arrive, each contact's checked photo, this phone's own photo, and
        // which photo each contact was sent.
        if (oldVersion < 19) {
            db.execSQL("CREATE TABLE IF NOT EXISTS avatar_pieces (account_id TEXT NOT NULL, piece BLOB NOT NULL, at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS avatar_photos (account_id TEXT PRIMARY KEY, webp BLOB NOT NULL)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS own_photo (id INTEGER PRIMARY KEY CHECK (id = 1), webp BLOB NOT NULL, width INTEGER NOT NULL, " +
                    "height INTEGER NOT NULL, sketch BLOB NOT NULL, changed INTEGER NOT NULL)",
            )
            if (!hasColumn(db, "contacts", "photo_sent")) db.execSQL("ALTER TABLE contacts ADD COLUMN photo_sent BLOB")
        }
        // Decision 64: outbox rows that go only through a hub, never by this phone's radio (photo avatar pieces).
        if (oldVersion < 20) db.execSQL("CREATE TABLE IF NOT EXISTS hub_only (outbox_id INTEGER PRIMARY KEY)")
        // Private groups (v0.1.2): their tables, and who shared a recent message with new members.
        if (oldVersion < 21) {
            GroupRows.createTables(db)
            if (!hasColumn(db, "messages", "shared_by")) db.execSQL("ALTER TABLE messages ADD COLUMN shared_by TEXT")
        }
        // Saved chat copies read back (v0.1.2): read-only archive tables, kept apart from every live table.
        if (oldVersion < 22) ArchiveRows.createTables(db)
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == column }
        }

    // ---- Accounts: raw rows; AccountStore seals and opens what they hold.

    /** [colour] is RGB; [profile] the encoded [org.phonemesh.core.account.Profile], null before one was saved. */
    class AccountRow(
        val store: String, val identity: ByteArray, val sealed: ByteArray?, val name: String, val colour: Int, val created: Long,
        val profile: ByteArray? = null,
    )
    class PrekeyRow(val public: ByteArray, val sealed: ByteArray, val created: Long, val retired: Long?)
    /** [colour] is RGB; [profile] the newest profile they sent (card, hello or update), null when not known. */
    class ContactRow(
        val accountId: String, val card: ByteArray, val name: String, val colour: Int, val met: Boolean, val voice: String?,
        val added: Long, val profile: ByteArray? = null,
    )
    class OutboxRow(
        val id: Long, val accountId: String, val messageId: Long?, val port: Int, val packet: ByteArray,
        val chainTag: ByteArray?, val counter: Long?, val created: Long, val tries: Int, val nextTry: Long, val state: String,
        val code: ByteArray? = null,
        val isReceipt: Boolean = false, val carriedAck: ByteArray? = null,
    )

    fun accountRow(): AccountRow? = readableDatabase.query("account", null, "id = 1", null, null, null, null).use { c ->
        if (!c.moveToFirst()) null else AccountRow(
            c.getString(c.getColumnIndexOrThrow("store")), c.getBlob(c.getColumnIndexOrThrow("identity")),
            c.getColumnIndexOrThrow("sealed").let { if (c.isNull(it)) null else c.getBlob(it) },
            c.getString(c.getColumnIndexOrThrow("name")), c.getInt(c.getColumnIndexOrThrow("colour")), c.getLong(c.getColumnIndexOrThrow("created")),
            c.getColumnIndexOrThrow("profile").let { if (c.isNull(it)) null else c.getBlob(it) },
        )
    }

    /** The account is made once; a second call keeps the first (false). */
    fun createAccount(store: String, identity: ByteArray, sealed: ByteArray?, created: Long): Boolean =
        writableDatabase.insertWithOnConflict("account", null, ContentValues().apply {
            put("id", 1); put("store", store); put("identity", identity); put("sealed", sealed); put("created", created)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun setAccountProfile(profile: org.phonemesh.core.account.Profile) {
        writableDatabase.update("account", ContentValues().apply {
            put("name", profile.name); put("colour", profile.colour); put("profile", profile.encode())
        }, "id = 1", null)
    }

    /** Prekeys not yet dropped, newest first. */
    fun prekeyRows(): List<PrekeyRow> = readableDatabase.query("prekeys", null, null, null, null, null, "created DESC").use { c ->
        buildList {
            while (c.moveToNext()) add(PrekeyRow(
                c.getBlob(0), c.getBlob(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3),
            ))
        }
    }

    /** Adds [public] as the current prekey: the one before is retired at [now]; any retired before [dropBefore] go. */
    fun rotatePrekey(public: ByteArray, sealed: ByteArray, now: Long, dropBefore: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("prekeys", ContentValues().apply { put("retired", now) }, "retired IS NULL", null)
            db.insertOrThrow("prekeys", null, ContentValues().apply { put("public", public); put("sealed", sealed); put("created", now) })
            db.delete("prekeys", "retired IS NOT NULL AND retired < ?", arrayOf(dropBefore.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun dropPrekeysRetiredBefore(time: Long): Int = writableDatabase.delete("prekeys", "retired IS NOT NULL AND retired < ?", arrayOf(time.toString()))

    fun putContact(row: ContactRow) {
        val values = ContentValues().apply {
            put("account_id", row.accountId); put("card", row.card); put("name", row.name); put("colour", row.colour)
            put("met", if (row.met) 1 else 0); put("voice", row.voice); put("added", row.added); put("profile", row.profile)
        }
        // Replacing the row would erase transport-owned columns such as last_ack.
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.update("contacts", values, "account_id = ?", arrayOf(row.accountId)) == 0) {
                db.insertOrThrow("contacts", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun contactRow(accountId: String): ContactRow? =
        readableDatabase.query("contacts", null, "account_id = ?", arrayOf(accountId), null, null, null).use { c -> if (c.moveToFirst()) c.contact() else null }

    // ---- Photo avatars

    class OwnPhoto(val webp: ByteArray, val width: Int, val height: Int, val sketch: ByteArray, val changed: Long)

    fun ownPhoto(): OwnPhoto? = readableDatabase.query("own_photo", null, "id = 1", null, null, null, null).use { c ->
        if (!c.moveToFirst()) null else OwnPhoto(
            c.getBlob(c.getColumnIndexOrThrow("webp")), c.getInt(c.getColumnIndexOrThrow("width")),
            c.getInt(c.getColumnIndexOrThrow("height")), c.getBlob(c.getColumnIndexOrThrow("sketch")),
            c.getLong(c.getColumnIndexOrThrow("changed")),
        )
    }

    fun setOwnPhoto(photo: OwnPhoto) {
        writableDatabase.insertWithOnConflict("own_photo", null, ContentValues().apply {
            put("id", 1); put("webp", photo.webp); put("width", photo.width); put("height", photo.height)
            put("sketch", photo.sketch); put("changed", photo.changed)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The checked photo [accountId] sent last, or null. */
    fun avatarPhoto(accountId: String): ByteArray? =
        readableDatabase.query("avatar_photos", arrayOf("webp"), "account_id = ?", arrayOf(accountId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    /**
     * Keeps one photo piece from [accountId] and, once [join] makes a photo of the pieces held, keeps that photo and
     * drops the pieces. At most [MAX_AVATAR_PIECES] pieces wait per contact; the oldest go first.
     */
    fun keepAvatarPiece(accountId: String, piece: ByteArray, at: Long, join: (List<ByteArray>) -> ByteArray?) {
        val db = writableDatabase
        db.insert("avatar_pieces", null, ContentValues().apply { put("account_id", accountId); put("piece", piece); put("at", at) })
        db.execSQL(
            "DELETE FROM avatar_pieces WHERE account_id = ? AND rowid NOT IN " +
                "(SELECT rowid FROM avatar_pieces WHERE account_id = ? ORDER BY rowid DESC LIMIT $MAX_AVATAR_PIECES)",
            arrayOf<Any>(accountId, accountId),
        )
        val pieces = db.query("avatar_pieces", arrayOf("piece"), "account_id = ?", arrayOf(accountId), null, null, "rowid")
            .use { c -> buildList { while (c.moveToNext()) add(c.getBlob(0)) } }
        // The newest head's photo: try the pieces from the newest head on (an older, unfinished photo is dropped).
        val photo = join(pieces) ?: return
        db.insertWithOnConflict("avatar_photos", null, ContentValues().apply { put("account_id", accountId); put("webp", photo) },
            SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("avatar_pieces", "account_id = ?", arrayOf(accountId))
    }

    /** The id of the photo [accountId] was sent, or null. */
    fun photoSent(accountId: String): ByteArray? =
        readableDatabase.query("contacts", arrayOf("photo_sent"), "account_id = ?", arrayOf(accountId), null, null, null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null }

    /** Outbox row [outboxId] goes only through a hub (decision 64); rows that left the outbox are forgotten. */
    fun markHubOnly(outboxId: Long) {
        writableDatabase.execSQL("DELETE FROM hub_only WHERE outbox_id NOT IN (SELECT id FROM outbox)")
        writableDatabase.execSQL("INSERT OR IGNORE INTO hub_only (outbox_id) VALUES (?)", arrayOf(outboxId))
    }

    fun isHubOnly(outboxId: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM hub_only WHERE outbox_id = ?", arrayOf(outboxId.toString())).use { it.moveToFirst() }

    fun setPhotoSent(accountId: String, id: ByteArray) {
        writableDatabase.update("contacts", ContentValues().apply { put("photo_sent", id) }, "account_id = ?", arrayOf(accountId))
    }

    /** A person from a restored copy: [metBefore] is history from the old phone, never trust on this one. */
    class RecoveredRow(
        val identity: ByteArray, val card: ByteArray, val name: String, val colour: Int, val metBefore: Boolean, val voice: String?,
        val added: Long,
    )

    fun recoveredRows(): List<RecoveredRow> =
        readableDatabase.query("recovered_contacts", null, null, null, null, null, "name").use { c ->
            buildList {
                while (c.moveToNext()) add(RecoveredRow(
                    c.getBlob(c.getColumnIndexOrThrow("identity")), c.getBlob(c.getColumnIndexOrThrow("card")),
                    c.getString(c.getColumnIndexOrThrow("name")), c.getInt(c.getColumnIndexOrThrow("colour")),
                    c.getInt(c.getColumnIndexOrThrow("met_before")) == 1,
                    c.getString(c.getColumnIndexOrThrow("voice")), c.getLong(c.getColumnIndexOrThrow("added")),
                ))
            }
        }

    /** Adds the people not saved yet (by full identity) in one transaction; returns how many were new. */
    fun addRecovered(rows: List<RecoveredRow>, archive: ByteArray, at: Long): Int {
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (r in rows) {
                val values = ContentValues().apply {
                    put("identity", r.identity); put("card", r.card); put("name", r.name); put("colour", r.colour)
                    put("met_before", if (r.metBefore) 1 else 0); put("voice", r.voice); put("added", r.added)
                    put("archive", archive); put("restored", at)
                }
                if (db.insertWithOnConflict("recovered_contacts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun removeRecovered(identity: ByteArray) {
        writableDatabase.compileStatement("DELETE FROM recovered_contacts WHERE identity = ?").use {
            it.bindBlob(1, identity)
            it.executeUpdateDelete()
        }
    }

    fun contactRows(): List<ContactRow> =
        readableDatabase.query("contacts", null, null, null, null, null, "added").use { c -> buildList { while (c.moveToNext()) add(c.contact()) } }

    /** The revision of this phone's profile last sent to [accountId] (0: none known) and when (ms, 0: never). */
    fun profileSent(accountId: String): Pair<Long, Long> =
        readableDatabase.query("contacts", arrayOf("profile_sent", "profile_sent_at"), "account_id = ?", arrayOf(accountId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L }

    fun setProfileSent(accountId: String, revision: Long, at: Long) {
        writableDatabase.update("contacts", ContentValues().apply { put("profile_sent", revision); put("profile_sent_at", at) },
            "account_id = ?", arrayOf(accountId))
    }

    /** Local listening preference only; never replace the contact or its transport state. */
    fun setContactVoice(accountId: String, voice: String?): Boolean =
        writableDatabase.update("contacts", ContentValues().apply { put("voice", voice) },
            "account_id = ?", arrayOf(accountId)) == 1

    private fun Cursor.contact() = ContactRow(
        getString(getColumnIndexOrThrow("account_id")), getBlob(getColumnIndexOrThrow("card")), getString(getColumnIndexOrThrow("name")),
        getInt(getColumnIndexOrThrow("colour")), getInt(getColumnIndexOrThrow("met")) != 0,
        getColumnIndexOrThrow("voice").let { if (isNull(it)) null else getString(it) }, getLong(getColumnIndexOrThrow("added")),
        getColumnIndexOrThrow("profile").let { if (isNull(it)) null else getBlob(it) },
    )

    /** The sealed session and its serial, or null. */
    fun sessionRow(accountId: String): Pair<Long, ByteArray>? =
        readableDatabase.query("sessions", arrayOf("serial", "state"), "account_id = ?", arrayOf(accountId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) to c.getBlob(1) else null }

    /**
     * Stores a session state and, in the same transaction, the packet sealed with it (review F3): either both are kept
     * or neither. Refuses a state whose [serial] is not above the stored one (review F6): false, nothing written.
     */
    fun storeSession(accountId: String, serial: Long, sealed: ByteArray, now: Long, outgoing: OutboxRow? = null, consumeHeldId: Long? = null): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = db.query("sessions", arrayOf("serial"), "account_id = ?", arrayOf(accountId), null, null, null)
                .use { if (it.moveToFirst()) it.getLong(0) else null }
            if (stored != null && serial <= stored) return false
            if (consumeHeldId != null) {
                require(outgoing != null) { "held consumption needs a replacement" }
                val held = held(accountId).firstOrNull { it.id == consumeHeldId }
                check(held != null && held.messageId == outgoing.messageId) { "held ownership changed" }
            }
            db.replaceOrThrow("sessions", null, ContentValues().apply {
                put("account_id", accountId); put("serial", serial); put("state", sealed); put("updated", now)
            })
            if (outgoing != null) db.insertOrThrow("outbox", null, ContentValues().apply {
                put("account_id", accountId); put("message_id", outgoing.messageId); put("port", outgoing.port)
                put("packet", outgoing.packet); put("chain_tag", outgoing.chainTag); put("counter", outgoing.counter)
                put("created", outgoing.created); put("next_try", outgoing.nextTry); put("state", outgoing.state)
                put("code", outgoing.code)
                put("is_receipt", if (outgoing.isReceipt) 1 else 0); put("carried_ack", outgoing.carriedAck)
            }).also { lastOutboxId = it }
            if (consumeHeldId != null) check(db.delete("held", "id = ? AND account_id = ?",
                arrayOf(consumeHeldId.toString(), accountId)) == 1) { "held row was not consumed" }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** An explicit fresh QR supersedes retry work atomically; repeating one showing cannot rewind it. */
    fun installQrSession(accountId: String, digest: ByteArray, sealed: ByteArray, now: Long): Boolean = transaction {
        val db = writableDatabase
        val previous = db.query("qr_sessions", arrayOf("start_digest"), "account_id = ?", arrayOf(accountId), null, null, null)
            .use { if (it.moveToFirst()) it.getBlob(0) else null }
        if (previous != null && previous.contentEquals(digest)) return@transaction true
        retireSessionWork(accountId)
        check(storeSession(accountId, 0, sealed, now))
        db.replaceOrThrow("qr_sessions", null, ContentValues().apply { put("account_id", accountId); put("start_digest", digest) })
        true
    }

    /** A crossed link loses to the smaller opener only; QR provenance always wins. Caller owns the transaction. */
    fun hasQrStart(accountId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM qr_sessions WHERE account_id=?", arrayOf(accountId),
    ).use { it.moveToFirst() }

    fun retireSessionWork(accountId: String) {
        val db = writableDatabase
        val live = outbox(accountId).filter { it.state != "SUPERSEDED" }
        val unfinished = (live.filter { it.state != "DELIVERED" }.mapNotNull { it.messageId } +
            held(accountId).mapNotNull { it.messageId }).toSet()
        for (row in live) {
            // A partly delivered picture must retire as one generation, including its delivered pieces.
            if (row.state == "DELIVERED" && row.messageId !in unfinished) continue
            setOutboxState(row.id, "SUPERSEDED")
        }
        unfinished.forEach { setStatus(it, DeliveryStatus.FAILED) }
        db.delete("held", "account_id = ?", arrayOf(accountId))
        db.delete("received_mail", "account_id = ?", arrayOf(accountId))
        db.delete("incoming_held", "account_id = ?", arrayOf(accountId))
        db.delete("incoming_deadlines", "account_id = ?", arrayOf(accountId))
        db.execSQL("UPDATE contacts SET last_ack=NULL WHERE account_id=?", arrayOf(accountId))
        db.delete("sessions", "account_id = ?", arrayOf(accountId))
    }

    /** Existing nested History helpers join this outer transaction; no notification escapes before commit. */
    fun <T> transaction(action: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = action()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun outbox(accountId: String): List<OutboxRow> =
        readableDatabase.query("outbox", null, "account_id = ?", arrayOf(accountId), null, null, "id").use { c ->
            buildList {
                while (c.moveToNext()) add(OutboxRow(
                    c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("account_id")),
                    c.getColumnIndexOrThrow("message_id").let { if (c.isNull(it)) null else c.getLong(it) },
                    c.getInt(c.getColumnIndexOrThrow("port")), c.getBlob(c.getColumnIndexOrThrow("packet")),
                    c.getColumnIndexOrThrow("chain_tag").let { if (c.isNull(it)) null else c.getBlob(it) },
                    c.getColumnIndexOrThrow("counter").let { if (c.isNull(it)) null else c.getLong(it) },
                    c.getLong(c.getColumnIndexOrThrow("created")), c.getInt(c.getColumnIndexOrThrow("tries")),
                    c.getLong(c.getColumnIndexOrThrow("next_try")), c.getString(c.getColumnIndexOrThrow("state")),
                    c.getColumnIndexOrThrow("code").let { if (c.isNull(it)) null else c.getBlob(it) },
                    c.getInt(c.getColumnIndexOrThrow("is_receipt")) != 0,
                    c.getColumnIndexOrThrow("carried_ack").let { if (c.isNull(it)) null else c.getBlob(it) },
                ))
            }
        }

    /** The id of the last outbox row [storeSession] wrote. */
    @Volatile var lastOutboxId: Long = -1
        private set

    /** Every outbox row in [states], all contacts, oldest first. */
    fun outboxIn(states: Set<String>): List<OutboxRow> =
        readableDatabase.query("outbox", arrayOf("DISTINCT account_id"), null, null, null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            .flatMap { outbox(it) }.filter { it.state in states }.sortedBy { it.id }

    fun outboxRow(id: Long): OutboxRow? = readableDatabase.query("outbox", arrayOf("account_id"), "id = ?", arrayOf(id.toString()), null, null, null)
        .use { c -> if (c.moveToFirst()) c.getString(0) else null }?.let { account -> outbox(account).firstOrNull { it.id == id } }

    fun updateOutbox(id: Long, tries: Int, nextTry: Long, state: String) {
        writableDatabase.update("outbox", ContentValues().apply { put("tries", tries); put("next_try", nextTry); put("state", state) },
            "id = ?", arrayOf(id.toString()))
    }

    fun setOutboxState(id: Long, state: String) {
        writableDatabase.update("outbox", ContentValues().apply { put("state", state) }, "id = ?", arrayOf(id.toString()))
    }

    fun lastAck(accountId: String): ByteArray? =
        readableDatabase.query("contacts", arrayOf("last_ack"), "account_id = ?", arrayOf(accountId), null, null, null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null }

    fun saveAck(accountId: String, ack: ByteArray) {
        writableDatabase.update("contacts", ContentValues().apply { put("last_ack", ack) }, "account_id = ?", arrayOf(accountId))
    }

    class HeldRow(val id: Long, val accountId: String, val messageId: Long?, val body: ByteArray)

    fun hold(accountId: String, messageId: Long?, body: ByteArray, now: Long) {
        writableDatabase.insertOrThrow("held", null, ContentValues().apply {
            put("account_id", accountId); put("message_id", messageId); put("body", body); put("at", now)
        })
    }

    fun held(accountId: String): List<HeldRow> =
        readableDatabase.query("held", null, "account_id = ?", arrayOf(accountId), null, null, "id").use { c ->
            buildList {
                while (c.moveToNext()) add(HeldRow(
                    c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("account_id")),
                    c.getColumnIndexOrThrow("message_id").let { if (c.isNull(it)) null else c.getLong(it) },
                    c.getBlob(c.getColumnIndexOrThrow("body")),
                ))
            }
        }

    fun dropHeld(id: Long) { writableDatabase.delete("held", "id = ?", arrayOf(id.toString())) }

    /** True the first time a hello [id] is seen; false for a replay (review F2). */
    fun firstHello(id: ByteArray, now: Long): Boolean =
        writableDatabase.insertWithOnConflict("hellos_seen", null, ContentValues().apply { put("id", id); put("at", now) },
            SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun personChat(accountId: String, name: String): Chat = chatFor(ChatKind.PERSON, accountId, name)

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
        packetId: Long? = null, replyTo: Long? = null, sharedBy: String? = null,
    ): Message = insert(chatId, Direction.IN, senderId, text, language, time, null, countUnread, packetId, replyTo, null, sharedBy = sharedBy)

    /** A line the app writes into a private group's chat ([GroupNotes] code); never unread, never sent. */
    fun addNote(chatId: Long, code: String, time: Long): Message =
        insert(chatId, Direction.IN, null, code, null, time, null, false, null, null, null, MessageKind.NOTE)

    fun privateGroupChat(groupHex: String, name: String): Chat = chatFor(ChatKind.PRIVATE_GROUP, groupHex, name)

    fun renameChat(chatId: Long, name: String) {
        writableDatabase.update("chats", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(chatId.toString()))
    }

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

    /** Every picture message of a chat, before the chat is deleted. */
    fun pictureMessageIds(chatId: Long): List<Long> = readableDatabase.rawQuery(
        "SELECT p.message_id FROM pictures p JOIN messages m ON m.id = p.message_id WHERE m.chat_id = ?",
        arrayOf(chatId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    fun setPictureProgress(messageId: Long, have: Int) =
        writableDatabase.update("pictures", ContentValues().apply { put("have", have) }, "message_id = ?", arrayOf(messageId.toString()))

    fun setPictureFile(messageId: Long, file: String, have: Int) = writableDatabase.update(
        "pictures", ContentValues().apply { put("file", file); put("have", have) }, "message_id = ?", arrayOf(messageId.toString()),
    )

    fun setPictureLost(messageId: Long) =
        writableDatabase.update("pictures", ContentValues().apply { put("lost", 1) }, "message_id = ?", arrayOf(messageId.toString()))

    /** Once per process start: a picture still arriving lost its pieces with the old process. */
    fun loseUnfinishedPictures(): Int = writableDatabase.update(
        "pictures", ContentValues().apply { put("lost", 1) },
        // Pictures between people keep their pieces on disk (picture_pieces) and carry on after a restart.
        "file IS NULL AND lost = 0 AND message_id NOT IN (SELECT m.id FROM messages m JOIN chats c ON c.id = m.chat_id WHERE c.kind = 'PERSON')", null,
    )

    private fun insert(
        chatId: Long, direction: Direction, senderId: String?, text: String, language: String?, time: Long,
        status: DeliveryStatus?, countUnread: Boolean, packetId: Long?, replyTo: Long?, plannedAt: Long?,
        kind: MessageKind = MessageKind.TEXT, sharedBy: String? = null,
    ): Message {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("messages", null, ContentValues().apply {
                put("chat_id", chatId); put("direction", direction.name); put("sender_id", senderId); put("text", text)
                put("language", language); put("time", time); put("delivery", status?.name)
                put("packet_id", packetId); put("reply_to", replyTo); put("planned_at", plannedAt); put("kind", kind.name)
                if (sharedBy != null) put("shared_by", sharedBy)
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
            if (chat.kind == ChatKind.PERSON) db.delete("picture_pieces", "account_id = ?", arrayOf(chat.address))
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
        writableDatabase.execSQL(
            "DELETE FROM picture_pieces WHERE EXISTS (SELECT 1 FROM pictures p JOIN messages m ON m.id = p.message_id " +
                "JOIN chats c ON c.id = m.chat_id WHERE p.message_id = ? AND m.direction = 'IN' AND c.kind = 'PERSON' " +
                "AND c.address = picture_pieces.account_id AND p.picture_id = picture_pieces.picture_id)",
            arrayOf<Any>(messageId),
        )
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

    /** Legacy clocks cannot finish after restart. Durable account outbox/held ownership can, so retain those clocks. */
    fun expireWaiting(): Int = writableDatabase.update(
        "messages", ContentValues().apply { put("delivery", DeliveryRules.onReopen(DeliveryStatus.WAITING).name) },
        "direction = 'OUT' AND delivery = ? " +
            "AND id NOT IN (SELECT message_id FROM outbox WHERE message_id IS NOT NULL AND state IN ('WAITING','SENT','HUB')) " +
            "AND id NOT IN (SELECT message_id FROM held WHERE message_id IS NOT NULL)", arrayOf(DeliveryStatus.WAITING.name),
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
            sharedBy = getColumnIndex("shared_by").let { if (it < 0 || isNull(it)) null else getString(it) },
        )
    }

    private companion object {
        const val VERSION = 22
        const val MAX_AVATAR_PIECES = 24

        /** Version 15's eight colour indices as RGB (red, orange, yellow, green, blue, purple, pink, brown), else grey. */
        private const val OLD_COLOURS = "CASE colour WHEN 0 THEN ${0xE53935} WHEN 1 THEN ${0xFB8C00} WHEN 2 THEN ${0xFDD835} " +
            "WHEN 3 THEN ${0x43A047} WHEN 4 THEN ${0x1E88E5} WHEN 5 THEN ${0x8E24AA} WHEN 6 THEN ${0xEC407A} " +
            "WHEN 7 THEN ${0x6D4C41} ELSE ${0x9E9E9E} END"
    }
}

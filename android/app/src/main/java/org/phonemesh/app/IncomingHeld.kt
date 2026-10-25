package org.phonemesh.app

import android.content.ContentValues

import org.phonemesh.core.account.AccountId
import org.phonemesh.core.mail.MailStore
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.wire.WireV2

/** Unauthenticated ciphertext waiting for a ratchet key; never a message or receipt yet. */
internal class IncomingHeld(private val history: History) {
    fun hold(contact: AccountId, digest: String, packet: ByteArray, now: Long, codes: Set<String>) {
        require(packet.size <= WireV2.MAX_PACKET && digest.length == 64)
        val code = WireV2.parse(packet)?.header?.code?.joinToString("") { "%02x".format(it) } ?: return
        if (code !in codes) return
        history.transaction {
            expire(now)
            val db = history.writableDatabase
            val account = contact.toString()
            retainCodes(contact, codes)
            val savedDeadline = db.rawQuery("SELECT expires FROM incoming_deadlines WHERE account_id=? AND code=?", arrayOf(account, code))
                .use { if (it.moveToFirst()) it.getLong(0) else null }
            if (savedDeadline != null && savedDeadline <= now) return@transaction
            val exists = db.rawQuery("SELECT 1 FROM incoming_held WHERE account_id=? AND digest=?", arrayOf(account, digest))
                .use { it.moveToFirst() }
            if (exists) return@transaction // duplicates never renew their original deadline
            val count = db.rawQuery("SELECT count(*) FROM incoming_held WHERE account_id=?", arrayOf(account))
                .use { it.moveToFirst(); it.getLong(0) }
            if (count >= MailStore.MAX_INCOMING_HELD) return@transaction
            // Rejected packets must not burn a retention window. The first admitted packet owns its deadline.
            val deadline = savedDeadline ?: (now + MailStore.INCOMING_LIFETIME)
            if (savedDeadline == null) db.insertOrThrow("incoming_deadlines", null, ContentValues().apply {
                put("account_id", account); put("code", code); put("expires", deadline)
            })
            db.insertOrThrow("incoming_held", null, ContentValues().apply {
                put("account_id", account); put("digest", digest); put("packet", packet)
                put("expires", deadline)
            })
        }
    }

    /** Called within the caller's session/hold transaction. No code can return after it leaves the ratchet window. */
    fun retainCodes(contact: AccountId, codes: Set<String>) {
        require(codes.size <= Ratchet.MAX_SKIPPED + 2 * Ratchet.WINDOW)
        val account = contact.toString()
        if (codes.isEmpty()) {
            history.writableDatabase.delete("incoming_deadlines", "account_id=?", arrayOf(account))
        } else {
            val placeholders = codes.joinToString(",") { "?" }
            history.writableDatabase.delete("incoming_deadlines", "account_id=? AND code NOT IN ($placeholders)",
                (listOf(account) + codes).toTypedArray())
        }
    }

    fun pending(now: Long): List<MailStore.HeldIncoming> = history.transaction {
        expire(now)
        history.readableDatabase.query("incoming_held", arrayOf("account_id", "digest", "packet", "expires"),
            null, null, null, null, "expires, digest").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(MailStore.HeldIncoming(AppMailStore.idOf(cursor.getString(0)),
                    cursor.getString(1), cursor.getBlob(2), cursor.getLong(3)))
            }
        }
    }

    fun nextExpiry(): Long? = history.readableDatabase.rawQuery("SELECT min(expires) FROM incoming_held", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /** Called inside the same transaction as content, session and authenticated replay persistence. */
    fun consume(contact: AccountId, digest: String) {
        history.writableDatabase.delete("incoming_held", "account_id=? AND digest=?", arrayOf(contact.toString(), digest))
    }

    private fun expire(now: Long) {
        history.writableDatabase.delete("incoming_held", "expires<=?", arrayOf(now.toString()))
    }
}

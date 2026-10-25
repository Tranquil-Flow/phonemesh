package org.phonemesh.app

import android.content.ContentValues
import android.database.Cursor
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.mail.MailStore
import org.phonemesh.core.wire.Ack

/** Durable, bounded recognition of authenticated packets. No plaintext or message keys are stored here. */
internal class MailLedger(private val history: History) {
    fun pending(): List<MailStore.Replay> = history.readableDatabase.query(
        "received_mail", null, "receipt_due IS NOT NULL", null, null, null, "receipt_due, digest",
    ).use { c -> buildList { while (c.moveToNext()) add(c.row()) } }

    fun replay(digest: String, now: Long, due: Long): Boolean = history.transaction {
        val saved = history.readableDatabase.query("received_mail", null, "digest = ?", arrayOf(digest), null, null, null)
            .use { if (it.moveToFirst()) it.row() else null } ?: return@transaction false
        if (saved.expires < now && saved.due == null) return@transaction false
        val ack = saved.responseAck
        if (ack != null && saved.due == null) {
            val alreadyQueued = history.outbox(saved.contact.toString()).any { row ->
                row.state == "WAITING" && row.carriedAck?.let(Ack::decode)?.let { MailStore.covers(it, ack) } == true
            }
            if (!alreadyQueued) history.writableDatabase.update("received_mail", ContentValues().apply { put("receipt_due", due) },
                "digest = ? AND receipt_due IS NULL", arrayOf(digest))
        }
        true
    }

    /** Called only inside the same outer transaction as the ratchet/content/ack commit. */
    fun incoming(contact: AccountId, item: MailStore.Incoming) {
        val db = history.writableDatabase
        db.delete("received_mail", "expires < ? AND receipt_due IS NULL", arrayOf(item.at.toString()))
        val count = db.rawQuery("SELECT count(*) FROM received_mail", null).use { it.moveToFirst(); it.getLong(0) }
        if (count >= MailStore.MAX_REPLAYS) throw MailStore.Refused()
        // New data extends a burst's quiet period; duplicate data never postpones it.
        if (item.due != null) db.update("received_mail", ContentValues().apply { put("receipt_due", item.due) },
            "account_id = ? AND receipt_due IS NOT NULL", arrayOf(contact.toString()))
        db.insertOrThrow("received_mail", null, ContentValues().apply {
            put("digest", item.digest); put("account_id", contact.toString()); put("response_ack", item.responseAck?.encode())
            put("receipt_due", item.due); put("expires", item.at + MailStore.REPLAY_LIFETIME)
        })
    }

    /** The covering ack is already in a committed-to-be outbox packet; no obligation is merely forgotten. */
    fun carried(contact: AccountId, ack: Ack) {
        for (row in pending()) {
            val saved = row.responseAck ?: continue
            if (row.contact != contact || !MailStore.covers(ack, saved)) continue
            history.writableDatabase.update("received_mail", ContentValues().apply { putNull("receipt_due") },
                "digest = ?", arrayOf(row.digest))
        }
    }

    private fun Cursor.row(): MailStore.Replay {
        val ack = getColumnIndexOrThrow("response_ack")
        val due = getColumnIndexOrThrow("receipt_due")
        return MailStore.Replay(getString(getColumnIndexOrThrow("digest")),
            AppMailStore.idOf(getString(getColumnIndexOrThrow("account_id"))),
            if (isNull(ack)) null else checkNotNull(Ack.decode(getBlob(ack))),
            if (isNull(due)) null else getLong(due), getLong(getColumnIndexOrThrow("expires")))
    }
}

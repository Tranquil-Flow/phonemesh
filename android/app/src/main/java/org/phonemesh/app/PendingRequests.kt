package org.phonemesh.app

import android.content.ContentValues
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Body
import java.nio.ByteBuffer

/** Unanswered hellos retain their initial state sealed by the account vault, even after prekey rotation. */
class PendingRequests(private val history: History, private val vault: Vault) {
    fun add(hello: Start.HelloReceived, at: Long): Boolean = history.transaction {
        val account = AccountId.of(hello.identity)
        val db = history.writableDatabase
        if (history.contactRow(account.toString()) != null ||
            db.rawQuery("SELECT 1 FROM pending_requests WHERE account_id=?", arrayOf(account.toString())).use { it.moveToFirst() } ||
            db.rawQuery("SELECT count(*) FROM pending_requests", null).use { it.moveToFirst(); it.getInt(0) >= MAX_PENDING }) return@transaction false
        if (!history.firstHello(hello.id, at)) return@transaction false
        val name = Body.encode(Body.Hello(hello.name, hello.colour))
        val state = hello.state.encode()
        val plain = ByteBuffer.allocate(1 + 33 + 16 + 2 + name.size + state.size)
            .put(1.toByte()).put(hello.identity).put(hello.id).putShort(name.size.toShort()).put(name).put(state).array()
        val sealed = try { vault.seal(plain, label(account, hello.id)) } finally { plain.fill(0); state.fill(0) }
        db.insertOrThrow("pending_requests", null, ContentValues().apply {
            put("account_id", account.toString()); put("hello_id", hello.id); put("sealed", sealed); put("at", at)
        })
        true
    }

    /** Presence is separate from readability: a transient vault failure must not authorize a competing start. */
    fun contains(account: AccountId): Boolean = history.readableDatabase.rawQuery(
        "SELECT 1 FROM pending_requests WHERE account_id=?", arrayOf(account.toString()),
    ).use { it.moveToFirst() }

    fun all(): List<Start.HelloReceived> = history.readableDatabase.query(
        "pending_requests", arrayOf("account_id", "hello_id", "sealed"), null, null, null, null, "at,account_id",
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val account = c.getString(0)
                val id = c.getBlob(1)
                val sealed = c.getBlob(2)
                if (sealed.size > MAX_SEALED) continue
                val plain = vault.open(sealed, "request:$account:${SessionState.hexOf(id)}") ?: continue
                try {
                    decode(plain)?.takeIf { AccountId.of(it.identity).toString() == account && it.id.contentEquals(id) }?.let { add(it) }
                } finally { plain.fill(0) }
            }
        }
    }

    /** Compare the durable request id, not a stale UI object's state or mutable display fields. */
    fun current(account: AccountId, id: ByteArray): Start.HelloReceived? =
        all().firstOrNull { AccountId.of(it.identity) == account && it.id.contentEquals(id) }

    fun remove(account: AccountId, id: ByteArray): Boolean = history.writableDatabase.compileStatement(
        "DELETE FROM pending_requests WHERE account_id=? AND hello_id=?",
    ).use { it.bindString(1, account.toString()); it.bindBlob(2, id); it.executeUpdateDelete() == 1 }

    fun retire(account: AccountId) {
        history.writableDatabase.delete("pending_requests", "account_id=?", arrayOf(account.toString()))
    }

    private fun decode(plain: ByteArray): Start.HelloReceived? = runCatching {
        val b = ByteBuffer.wrap(plain)
        require(b.get().toInt() == 1)
        val identity = ByteArray(33).also { b.get(it) }
        val id = ByteArray(16).also { b.get(it) }
        val size = b.short.toInt() and 0xffff
        require(size in 1..128 && size < b.remaining())
        val body = Body.decode(ByteArray(size).also { b.get(it) }) as? Body.Hello ?: return null
        val state = SessionState.decode(ByteArray(b.remaining()).also { b.get(it) }) ?: return null
        require(state.serial == 1L && !state.iAmL && state.mayStep && state.heard && state.nr == 1L && state.ns == 0L)
        Start.HelloReceived(state, identity, body.name, body.colour, id)
    }.getOrNull()

    companion object {
        const val MAX_PENDING = 128
        const val MAX_SEALED = 8192
        private fun label(account: AccountId, id: ByteArray) = "request:$account:${SessionState.hexOf(id)}"
    }
}

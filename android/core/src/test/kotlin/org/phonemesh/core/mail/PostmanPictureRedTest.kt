package org.phonemesh.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureWire
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.session.Phone
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body

/**
 * Behavioral RED, existing APIs only (compiles on the unchanged snapshot): a picture head and parts that already fit a
 * wire v2 body must be consumed and acknowledged like a text, and a refused store must never look like receipt. Today
 * Postman.receive returns null for them without keeping anything. The 396-byte picture is two parts of 198, valid under
 * both the 200-byte and the 198-byte count.
 */
class PostmanPictureRedTest {
    private class Store : MailStore {
        val sessions = LinkedHashMap<AccountId, SessionState>()
        val rows = LinkedHashMap<Long, Postman.Outgoing>()
        val kept = ArrayList<MailStore.Incoming>()
        private val acks = HashMap<AccountId, Ack>()
        private var nextId = 1L
        var refuseCommit = false
        private val replays = LinkedHashMap<String, MailStore.Replay>()
        override fun pendingReceipts() = replays.values.filter { it.due != null }
        override fun replay(digest: String, now: Long, due: Long): Boolean {
            val saved = replays[digest] ?: return false
            if (saved.expires < now && saved.due == null) return false
            val ack = saved.responseAck
            if (ack != null && saved.due == null && rows.values.none {
                it.contact == saved.contact && it.status == Postman.Status.WAITING && it.carriedAck?.let { a -> MailStore.covers(a, ack) } == true
            }) replays[digest] = saved.copy(due = due)
            return true
        }
        private val incomingHeld = LinkedHashMap<Pair<AccountId, String>, MailStore.HeldIncoming>()
        override fun holdIncoming(contact: AccountId, digest: String, packet: ByteArray, now: Long) {
            val key = contact to digest
            if (key !in incomingHeld && incomingHeld.values.count { it.contact == contact } < MailStore.MAX_INCOMING_HELD)
                incomingHeld[key] = MailStore.HeldIncoming(contact, digest, packet.copyOf(), now + MailStore.INCOMING_LIFETIME)
        }
        override fun pendingIncoming(now: Long): List<MailStore.HeldIncoming> {
            incomingHeld.entries.removeAll { it.value.expires <= now }
            return incomingHeld.values.toList()
        }
        override fun incomingExpiry(): Long? = incomingHeld.values.minOfOrNull { it.expires }
        override fun contacts() = sessions.keys.toList()
        override fun session(contact: AccountId) = sessions[contact]
        override fun keep(contact: AccountId, state: SessionState, outgoing: Postman.Outgoing?,
            consumeHeldId: Long?, incoming: MailStore.Incoming?, expected: SessionState): MailStore.Committed? {
            if (refuseCommit) return null
            incoming?.let { item ->
                kept += item
                if (item.due != null) pendingReceipts().filter { it.contact == contact }.forEach { replays[it.digest] = it.copy(due = item.due) }
                replays[item.digest] = MailStore.Replay(item.digest, contact, item.responseAck, item.due, item.at + MailStore.REPLAY_LIFETIME)
                incomingHeld.remove(contact to item.digest)
            }
            outgoing?.carriedAck?.let { ack ->
                pendingReceipts().filter { it.contact == contact && it.responseAck?.let { saved -> MailStore.covers(ack, saved) } == true }
                    .forEach { replays[it.digest] = it.copy(due = null) }
            }
            sessions[contact] = state
            val id = outgoing?.let { val id = nextId++; rows[id] = it.copy(id = id); id }
            incoming?.peerAck?.let { ack -> acks[contact] = ack; markDelivered(contact, ack) }
            return MailStore.Committed(id)
        }
        override fun unconfirmed() = rows.values.filter { it.status == Postman.Status.WAITING || it.status == Postman.Status.SENT }
        override fun update(row: Postman.Outgoing) { rows[row.id] = row }
        override fun markSent(id: Long) { rows[id]?.let { rows[id] = it.copy(status = Postman.Status.SENT) } }
        override fun markHub(codes: Set<String>) {}
        override fun markDelivered(contact: AccountId, ack: Ack) {
            rows.values.toList().forEach { if (it.contact == contact && Bytes.equal(it.chainTag, ack.tag) && ack.covers(it.counter)) rows[it.id] = it.copy(status = Postman.Status.DELIVERED) }
        }
        override fun lastAck(contact: AccountId) = acks[contact]
        override fun saveAck(contact: AccountId, ack: Ack) { acks[contact] = ack }
        override fun hold(contact: AccountId, messageId: Long?, body: ByteArray, now: Long) {}
        override fun held(contact: AccountId) = emptyList<Postman.Held>()
        override fun dropHeld(id: Long) {}
    }

    private class Side(phone: Phone, other: Phone) {
        val store = Store()
        val them = AccountId.of(other.keys.identity)
        val postman = Postman(phone.keys, store, quietDelay = { 20_000L })
        init { store.sessions[them] = phone.state }
    }

    private fun pair(): Pair<Side, Side> {
        val (a, b) = Phone.qrPair()
        return Side(a, b) to Side(b, a)
    }

    /** Two parts of 198 bytes: the same head is valid whether parts are counted by 200 or by 198. */
    private fun bodies(): List<Body> {
        val bytes = ByteArray(396) { (it * 3 + 1).toByte() }
        val head = PictureWire.Head(4, PictureCodec.WEBP, 8, 8, 396, 2, PictureWire.crc(bytes), byteArrayOf(1, 2, 3))
        return listOf(Body.PictureHead(PictureWire.encode(head))) +
            (0..1).map { Body.PicturePart(PictureWire.encode(PictureWire.Part(4, it, bytes.copyOfRange(it * 198, (it + 1) * 198)))) }
    }

    @Test fun aPictureHeadAndPartsAreConsumedAndAcknowledgedLikeAnyMessage() {
        val (a, b) = pair()
        val sent = bodies().mapIndexed { i, body -> checkNotNull(a.postman.send(a.them, body, 1, 0L + i)) }
        sent.forEach { assertNotNull("a picture body must be consumed", b.postman.receive(it.packet, 1_000)) }
        assertEquals(3, b.store.kept.size)
        val receipt = b.postman.due(30_000)
        assertEquals("one receipt covers the picture", 1, receipt.size)
        a.postman.receive(receipt.single().packet, 31_000)
        assertTrue(a.store.rows.values.all { it.status == Postman.Status.DELIVERED })
    }

    @Test fun aRefusedStoreNeverLooksLikeReceiptOfAPicturePacket() {
        val (a, b) = pair()
        val head = checkNotNull(a.postman.send(a.them, bodies()[0], 1, 0))
        val before = b.store.sessions.getValue(b.them)
        b.store.refuseCommit = true
        try { b.postman.receive(head.packet, 1_000); fail("a refused commit must throw, not return as if dropped") } catch (_: MailStore.Refused) {}
        assertSame("the session did not move", before, b.store.sessions.getValue(b.them))
        b.store.refuseCommit = false
        assertNotNull(b.postman.receive(head.packet, 2_000))
    }
}

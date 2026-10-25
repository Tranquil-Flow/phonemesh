package org.phonemesh.app

import org.phonemesh.ble.MeshRadio
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.mail.MailStore
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body

/**
 * A second phone for tests, with real keys: [meet] makes the installed app (TestCore) and a new person scan each other's
 * real codes, so the contact, the person chat and both ratchet sessions are the production ones, never injected rows.
 * Bea's side is a JVM Postman over memory. Call after TestCore.install(); not from inside TestCore.onMain.
 */
object PersonFixture {
    class Bea(val name: String, val colour: Int) {
        val keys = SoftKeys.generate()
        val pair = KeyPairBytes.generate()
        val qr = QrCode(ContactCard.create(keys, name, colour), pair.public)
        val store = Memory()
        val postman = Postman(keys, store, quietDelay = { 20_000L })
        lateinit var awa: AccountId
        /** The id the app files this person under (contacts, chats, outbox rows). */
        val account: String get() = qr.card.accountId.toString()
    }

    class Memory : MailStore {
        val sessions = LinkedHashMap<AccountId, SessionState>()
        val rows = LinkedHashMap<Long, Postman.Outgoing>()
        private var next = 1L
        private val replays = LinkedHashMap<String, MailStore.Replay>()
        override fun pendingReceipts() = replays.values.filter { it.due != null }
        override fun replay(digest: String, now: Long, due: Long): Boolean {
            val row = replays[digest] ?: return false
            if (row.expires < now && row.due == null) return false
            if (row.responseAck != null && row.due == null && rows.values.none {
                it.contact == row.contact && it.status == Postman.Status.WAITING && it.carriedAck?.let { ack -> MailStore.covers(ack, row.responseAck!!) } == true
            }) replays[digest] = row.copy(due = due)
            return true
        }
        private val incomingHeld = LinkedHashMap<Pair<AccountId, String>, MailStore.HeldIncoming>()
        override fun holdIncoming(contact: AccountId, digest: String, packet: ByteArray, now: Long) {
            pendingIncoming(now)
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
            sessions[contact] = state
            incoming?.let { item ->
                if (item.due != null) pendingReceipts().filter { it.contact == contact }.forEach { replays[it.digest] = it.copy(due = item.due) }
                replays[item.digest] = MailStore.Replay(item.digest, contact, item.responseAck, item.due, item.at + MailStore.REPLAY_LIFETIME)
                incomingHeld.remove(contact to item.digest)
            }
            outgoing?.carriedAck?.let { ack ->
                pendingReceipts().filter { it.contact == contact && it.responseAck?.let { saved -> MailStore.covers(ack, saved) } == true }
                    .forEach { replays[it.digest] = it.copy(due = null) }
            }
            incoming?.peerAck?.let { markDelivered(contact, it) }
            return MailStore.Committed(outgoing?.let { val id = next++; rows[id] = it.copy(id = id); id })
        }
        override fun unconfirmed() = rows.values.filter { it.status == Postman.Status.WAITING || it.status == Postman.Status.SENT }
        override fun update(row: Postman.Outgoing) { rows[row.id] = row }
        override fun markSent(id: Long) { rows[id]?.let { rows[id] = it.copy(status = Postman.Status.SENT) } }
        override fun markHub(codes: Set<String>) {}
        override fun markDelivered(contact: AccountId, ack: Ack) {
            rows.values.toList().forEach { if (Bytes.equal(it.chainTag, ack.tag) && ack.covers(it.counter)) rows[it.id] = it.copy(status = Postman.Status.DELIVERED) }
        }
        override fun lastAck(contact: AccountId): Ack? = null
        override fun saveAck(contact: AccountId, ack: Ack) {}
        override fun hold(contact: AccountId, messageId: Long?, body: ByteArray, now: Long) {}
        override fun held(contact: AccountId) = emptyList<Postman.Held>()
        override fun dropHeld(id: Long) {}
    }

    /**
     * Awa shows her code, scans [name]'s: a met contact, a person chat (its id returned) and matching sessions. [accept]
     * makes the fake radio report each broadcast as taken by itself; false lets a test play the radio.
     */
    fun meet(name: String = "Bea", colour: Int = 5, accept: Boolean = true): Pair<Bea, Long> {
        TestCore.radio.acceptBroadcasts = accept
        val core = TestCore.core
        val bea = Bea(name, colour)
        val awaText = TestCore.onMain { core.accountsFlow.showCode() }
        val awaQr = (AddCode.parse(awaText) as AddCode.Qr).code
        val added = TestCore.onMain { core.accountsFlow.scanned(AddCode.of(bea.qr)) } as Accounts.Added.Contact
        bea.awa = awaQr.card.accountId
        bea.store.sessions[bea.awa] = Start.qr(bea.keys, bea.qr, bea.pair, awaQr)!!
        return bea to added.chatId
    }

    /** On the main thread already (inside TestCore.onMain). */
    fun deliverNow(packet: ByteArray) = TestCore.core.onBroadcastData("!0000bea0", MeshRadio.ACCOUNT_PORT, packet, 0, 1L, "")

    fun deliver(packet: ByteArray) = TestCore.onMain { deliverNow(packet) }

    /** Bea seals [body] for the app (no message id, no bubble of hers). */
    fun send(bea: Bea, body: Body, now: Long = 1_000): ByteArray = checkNotNull(bea.postman.send(bea.awa, body, null, now)).packet

    /** Bea receives [packet]; once her quiet period ends her receipt reaches the app, as a real phone's would. */
    fun acknowledge(bea: Bea, packet: ByteArray) {
        bea.postman.receive(packet, 1_000)
        deliver(bea.postman.due(60_000).first().packet)
    }
}

package org.phonemesh.core.mail

import org.phonemesh.core.account.AccountId
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.OwnKeys
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.HubAck

/**
 * The person-to-person messaging engine (accounts plan step 13), pure Kotlin so two phones and a lossy radio can be
 * tested on the JVM. It seals and opens wire v2 packets, keeps every outgoing packet with the state that sealed it
 * ([MailStore.keep], review F3), resends after 2, 10 and 30 minutes until the other phone's receipt covers it,
 * sends a receipt after [QUIET_MS] of quiet unless a reply goes first, and holds new messages past the flow-control
 * window ([Ratchet.WINDOW], review F5) until an ack frees room. Times are milliseconds. Not thread-safe: one caller.
 */
class Postman(
    private val own: OwnKeys,
    private val store: MailStore,
    private val newPair: () -> KeyPairBytes = KeyPairBytes::generate,
) {
    /** A message the app should show. [ref] is what replies and reactions to it name ([Ratchet.reference]). */
    class Received(val contact: AccountId, val body: Body, val ref: ByteArray)

    /** Packets for the radio, oldest first. */
    class Out(val contact: AccountId, val packet: ByteArray, val outboxId: Long?)

    private val receiptDue = HashMap<AccountId, Long>()
    private val lastAck = HashMap<AccountId, Ack>()
    // Codes already used by packets that arrived: a resent copy means our receipt was lost, so send it again.
    private val seen = LinkedHashMap<String, AccountId>()

    /**
     * Seals [body] for [contact] and keeps it in the outbox with its state. Returns the packet to send now, or null
     * when the flow-control window is full (the body waits as HELD and goes out once an ack frees room).
     */
    fun send(contact: AccountId, body: Body, messageId: Long?, now: Long): Out? {
        val state = store.session(contact) ?: error("no session with $contact")
        if (!Ratchet.maySealNew(state, lastAck[contact] ?: store.lastAck(contact))) {
            store.hold(contact, messageId, Body.encode(body), now)
            return null
        }
        return seal(contact, state, body, messageId, now)
    }

    private fun seal(contact: AccountId, state: SessionState, body: Body, messageId: Long?, now: Long): Out {
        val ack = if (body is Body.Receipt) null else Ratchet.ack(state)
        val sealed = Ratchet.seal(state, Body.encode(body, ack), newPair)
        val row = Outgoing(
            0, contact, messageId, sealed.packet, sealed.chainTag, sealed.counter, sealed.code.copyOf(),
            created = now, tries = 0, nextTry = now + RETRY_MS[0], status = if (body is Body.Receipt) Status.RECEIPT else Status.WAITING,
        )
        val id = store.keep(contact, sealed.state, row)
        // A message to them carries the ack, so it is the receipt.
        if (ack != null) receiptDue.remove(contact)
        return Out(contact, sealed.packet, id.takeIf { body !is Body.Receipt })
    }

    /** A packet from port 301. Returns what to show, or null for packets that are not ours (most of them). */
    fun receive(packet: ByteArray, now: Long): Received? {
        val code = packet.copyOfRange(1, minOf(packet.size, 9))
        val codeHex = SessionState.hexOf(code)
        seen[codeHex]?.let { contact ->
            // A copy of something we already have: the sender did not get our receipt.
            receiptDue.putIfAbsent(contact, now + QUIET_MS)
            return null
        }
        for (contact in store.contacts()) {
            val state = store.session(contact) ?: continue
            if (codeHex !in Ratchet.codes(state)) continue
            val opened = Ratchet.open(state, packet, own, now / 1000) as? Ratchet.Opened.Delivered ?: continue
            val decoded = Body.decodeWithAck(opened.body) ?: return null.also { store.keep(contact, opened.state, null) }
            store.keep(contact, opened.state, null)
            remember(codeHex, contact)
            decoded.ack?.let { ackFrom(contact, it, now) }
            if (decoded.body !is Body.Receipt) receiptDue[contact] = now + QUIET_MS
            if (decoded.body is Body.Receipt) return null
            return Received(contact, decoded.body, Ratchet.reference(opened.code))
        }
        return null
    }

    /** A hub's port-302 acknowledgement: packets it now holds are "reached hub". */
    fun hubAck(packet: ByteArray) {
        val codes = HubAck.decode(packet) ?: return
        store.markHub(codes.map { SessionState.hexOf(it) }.toSet())
    }

    /** What must go out at [now]: receipts after quiet, held messages the window now allows, then resends. */
    fun due(now: Long): List<Out> {
        val out = ArrayList<Out>()
        for ((contact, at) in receiptDue.entries.toList()) {
            if (at > now) continue
            receiptDue.remove(contact)
            val state = store.session(contact) ?: continue
            val ack = Ratchet.ack(state) ?: continue
            out += seal(contact, state, Body.Receipt(ack), null, now)
        }
        for (contact in store.contacts()) {
            for (held in store.held(contact)) {
                val state = store.session(contact) ?: break
                if (!Ratchet.maySealNew(state, lastAck[contact] ?: store.lastAck(contact))) break
                val body = Body.decode(held.body) ?: run { store.dropHeld(held.id); null } ?: continue
                store.dropHeld(held.id)
                out += seal(contact, state, body, held.messageId, now)
            }
        }
        for (row in store.unconfirmed()) {
            if (row.nextTry > now || row.status == Status.RECEIPT) continue
            val tries = row.tries + 1
            val next = if (tries < RETRY_MS.size) now + RETRY_MS[tries] else now + RETRY_MS.last()
            val failed = now - row.created > GIVE_UP_MS
            store.update(row.copy(tries = tries, nextTry = next, status = if (failed) Status.FAILED else row.status))
            if (!failed) out += Out(row.contact, row.packet, row.id)
        }
        return out
    }

    /** The radio took the packet of outbox row [id] (WAITING becomes SENT). */
    fun transmitted(id: Long) = store.markSent(id)

    private fun ackFrom(contact: AccountId, ack: Ack, now: Long) {
        val previous = lastAck[contact] ?: store.lastAck(contact)
        if (previous == null || !Bytes.equal(previous.tag, ack.tag) || ack.highest >= previous.highest) {
            lastAck[contact] = ack
            store.saveAck(contact, ack)
        }
        store.markDelivered(contact, ack)
    }

    private fun remember(codeHex: String, contact: AccountId) {
        seen[codeHex] = contact
        while (seen.size > SEEN_MAX) seen.remove(seen.keys.first())
    }

    /** When the next [due] call has work, for scheduling a wake-up; null when nothing waits. */
    fun nextDue(): Long? = (receiptDue.values + store.unconfirmed().filter { it.status != Status.RECEIPT }.map { it.nextTry }).minOrNull()

    enum class Status { WAITING, SENT, HUB, DELIVERED, FAILED, RECEIPT }

    data class Outgoing(
        val id: Long, val contact: AccountId, val messageId: Long?, val packet: ByteArray, val chainTag: ByteArray,
        val counter: Long, val code: ByteArray, val created: Long, val tries: Int, val nextTry: Long, val status: Status,
    )

    class Held(val id: Long, val messageId: Long?, val body: ByteArray)

    companion object {
        /** Resends after 2, 10 and 30 minutes, then every 30 minutes until delivered or [GIVE_UP_MS]. */
        val RETRY_MS = longArrayOf(2 * 60_000L, 10 * 60_000L, 30 * 60_000L)
        const val QUIET_MS = 20_000L
        const val GIVE_UP_MS = 3 * 86_400_000L
        private const val SEEN_MAX = 2_000
    }
}

/** What [Postman] keeps. The app backs it with the sealed history; tests with maps. */
interface MailStore {
    fun contacts(): List<AccountId>
    fun session(contact: AccountId): SessionState?
    /** Stores [state] (its serial must rise) and [outgoing] together; returns the new outbox id, or null. */
    fun keep(contact: AccountId, state: SessionState, outgoing: Postman.Outgoing?): Long?
    fun unconfirmed(): List<Postman.Outgoing>
    fun update(row: Postman.Outgoing)
    fun markSent(id: Long)
    fun markHub(codes: Set<String>)
    /** Every outbox row for [contact] whose chain tag and counter [ack] covers becomes DELIVERED. */
    fun markDelivered(contact: AccountId, ack: Ack)
    fun lastAck(contact: AccountId): Ack?
    fun saveAck(contact: AccountId, ack: Ack)
    fun hold(contact: AccountId, messageId: Long?, body: ByteArray, now: Long)
    fun held(contact: AccountId): List<Postman.Held>
    fun dropHeld(id: Long)
}

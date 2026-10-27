package org.phonemesh.core.mail

import org.phonemesh.core.PictureWire
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.OwnKeys
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.HubAck
import org.phonemesh.core.wire.WireV2

/**
 * The person-to-person messaging engine (accounts plan step 13), pure Kotlin so two phones and a lossy radio can be
 * tested on the JVM. It seals and opens wire v2 packets, keeps every outgoing packet with the state that sealed it
 * ([MailStore.keep], review F3), resends after 2, 10 and 30 minutes until the other phone's receipt covers it,
 * sends a receipt after 15–60 seconds of quiet unless a reply goes first, and holds new messages past the flow-control
 * window ([Ratchet.WINDOW], review F5) until an ack frees room. Times are milliseconds. Not thread-safe: one caller.
 */
class Postman(
    private val own: OwnKeys,
    private val store: MailStore,
    private val newPair: () -> KeyPairBytes = KeyPairBytes::generate,
    private val quietDelay: () -> Long = { 15_000L + receiptRandom.nextInt(45_001) },
) {
    /** A message the app should show. [ref] is what replies and reactions to it name ([Ratchet.reference]). */
    class Received(val contact: AccountId, val body: Body, val ref: ByteArray)

    /** Packets for the radio, oldest first. */
    class Out(val contact: AccountId, val packet: ByteArray, val outboxId: Long, val isReceipt: Boolean = false)

    /**
     * Seals [body] for [contact] and keeps it in the outbox with its state. Returns the packet to send now, or null
     * when the flow-control window is full (the body waits as HELD and goes out once an ack frees room).
     */
    fun send(contact: AccountId, body: Body, messageId: Long?, now: Long): Out? {
        val state = store.session(contact) ?: throw MailStore.Refused()
        if (!Ratchet.maySealNew(state, store.lastAck(contact))) {
            store.hold(contact, messageId, Body.encode(body), now)
            return null
        }
        return seal(contact, state, body, messageId, now)
    }

    /**
     * A picture ([PictureWire.splitV2] order: head, then parts) as separate bodies, each sealed and kept alone, so each
     * part has its own key, its own outbox row and its own resends. One entry per packet: null where the flow-control
     * window is full and the part waits as HELD (order is kept; [due] seals it once an ack frees room).
     */
    fun sendPicture(contact: AccountId, messageId: Long?, packets: List<PictureWire.Packet>, now: Long): List<Out?> =
        packets.map { packet ->
            val bytes = PictureWire.encodeV2(packet)
            send(contact, if (packet is PictureWire.Head) Body.PictureHead(bytes) else Body.PicturePart(bytes), messageId, now)
        }

    /**
     * The packet sizes [bodies] would have if sent to [contact] now, in order (airtime meter): the key announcement
     * and a riding receipt as [seal] would add them. Nothing is sealed, stepped or stored. Null without a session.
     */
    fun sizes(contact: AccountId, bodies: List<Body>): List<Int>? {
        val state = store.session(contact) ?: return null
        val ack = Ratchet.ack(state)
        val encoded = bodies.map { body ->
            val plain = Body.encode(body).size
            if (body !is Body.Receipt && ack != null && plain + Ack.SIZE <= WireV2.maxBody(false)) plain + Ack.SIZE else plain
        }
        return encoded.zip(Ratchet.keyed(state, encoded)) { size, keyed ->
            (if (keyed) WireV2.KEYED_HEADER else WireV2.PLAIN_HEADER) + size + org.phonemesh.core.crypto.Aead.TAG
        }
    }

    private fun seal(contact: AccountId, state: SessionState, body: Body, messageId: Long?, now: Long, heldId: Long? = null): Out {
        // An acknowledgement is optional: a full-sized text/reply must not overflow merely because we heard mail.
        // Keep its quiet-period obligation when it cannot ride on this packet.
        val ack = if (body is Body.Receipt) null else Ratchet.ack(state)?.takeIf {
            Body.encode(body).size + Ack.SIZE <= WireV2.maxBody(false)
        }
        val sealed = Ratchet.seal(state, Body.encode(body, ack), newPair)
        val row = Outgoing(
            0, contact, messageId, sealed.packet, sealed.chainTag, sealed.counter, sealed.code.copyOf(),
            created = now, tries = 0, nextTry = now + RETRY_MS[0], status = Status.WAITING,
            isReceipt = body is Body.Receipt, carriedAck = if (body is Body.Receipt) body.ack else ack,
            namesMessage = body !is Body.PicturePart,
        )
        val committed = store.keep(contact, sealed.state, row, consumeHeldId = heldId, expected = state)
            ?: throw MailStore.Refused()
        val id = checkNotNull(committed.outboxId) { "committed packet has no outbox row" }
        return Out(contact, sealed.packet, id, row.isReceipt)
    }

    /** A packet from port 301. Returns what to show, or null for packets that are not ours (most of them). */
    fun receive(packet: ByteArray, now: Long): Received? {
        store.pendingIncoming(now) // expire unauthenticated bytes even when this packet is not ours
        val received = receiveOne(packet, now, allowHold = true)
        val recovered = recoverIncoming(now)
        return received ?: recovered.firstOrNull()
    }

    /** Retry bounded durable ciphertext after a key arrives, or after an interrupted receive/restart. */
    fun recoverIncoming(now: Long): List<Received> = store.pendingIncoming(now).mapNotNull {
        receiveOne(it.packet, now, allowHold = false)
    }

    fun nextIncomingExpiry(): Long? = store.incomingExpiry()

    /** Every recipient code this phone would open now (16 hex digits), for asking a hub for its mail in a request body. */
    fun expectedCodes(): Set<String> = store.contacts().flatMapTo(LinkedHashSet<String>()) { contact ->
        store.session(contact)?.let { Ratchet.codes(it) }.orEmpty()
    }

    private fun receiveOne(packet: ByteArray, now: Long, allowHold: Boolean): Received? {
        val code = WireV2.parse(packet)?.header?.code ?: return null
        val codeHex = SessionState.hexOf(code)
        val digest = SessionState.hexOf(Bytes.sha256(packet))
        val delay = quietDelay().also { require(it in MIN_QUIET_MS..MAX_QUIET_MS) }
        if (store.replay(digest, now, now + delay)) return null
        for (contact in store.contacts()) {
            val state = store.session(contact) ?: continue
            if (codeHex !in Ratchet.codes(state)) continue
            val result = Ratchet.open(state, packet, own, now / 1000)
            if (result is Ratchet.Opened.Held) {
                if (allowHold) store.holdIncoming(contact, digest, packet, now)
                return null
            }
            val opened = result as? Ratchet.Opened.Delivered ?: continue
            val decoded = Body.decodeWithAck(opened.body) ?: return null
            // Do not consume keys or acknowledge bodies whose durable app handler is not implemented yet. Picture
            // heads and parts are consumed only by MailStore.keep, which must store them before anything is acknowledged.
            if (decoded.body !is Body.Text && decoded.body !is Body.Reply && decoded.body !is Body.Reaction &&
                decoded.body !is Body.Receipt && decoded.body !is Body.PictureHead && decoded.body !is Body.PicturePart &&
                decoded.body !is Body.ProfileUpdate) return null
            val ref = Ratchet.reference(opened.code)
            val response = if (decoded.body is Body.Receipt) null else {
                // A late old-chain packet needs its own covering ack, not today's unrelated receiving chain.
                if (opened.counter < 0 || opened.chainTag.size != Ack.TAG) throw MailStore.Refused()
                Ack(opened.chainTag, opened.counter, -1)
            }
            store.keep(contact, opened.state, null,
                incoming = MailStore.Incoming(decoded.body, ref, now, decoded.ack, digest, response,
                    if (response == null) null else now + delay), expected = state) ?: throw MailStore.Refused()
            if (decoded.body is Body.Receipt) return null
            return Received(contact, decoded.body, ref)
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
        for (work in store.pendingReceipts()) {
            if (work.due == null || work.due > now) continue
            // Earlier iterations may have batched this row into another durable receipt.
            if (store.pendingReceipts().none { it.digest == work.digest && it.due != null && it.due <= now }) continue
            val state = store.session(work.contact) ?: continue
            val saved = checkNotNull(work.responseAck)
            val ack = Ratchet.ack(state)?.takeIf { MailStore.covers(it, saved) } ?: saved
            out += seal(work.contact, state, Body.Receipt(ack), null, now)
        }
        for (contact in store.contacts()) {
            for (held in store.held(contact)) {
                val state = store.session(contact) ?: break
                if (!Ratchet.maySealNew(state, store.lastAck(contact))) break
                val body = Body.decode(held.body) ?: throw MailStore.Refused()
                out += seal(contact, state, body, held.messageId, now, held.id)
            }
        }
        for (row in store.unconfirmed()) {
            if (row.nextTry > now || row.isReceipt) continue
            val tries = row.tries + 1
            val next = if (tries < RETRY_MS.size) now + RETRY_MS[tries] else now + RETRY_MS.last()
            val failed = now - row.created > GIVE_UP_MS
            store.update(row.copy(tries = tries, nextTry = next, status = if (failed) Status.FAILED else row.status))
            if (!failed) out += Out(row.contact, row.packet, row.id)
        }
        return out
    }

    /** Packets no radio has taken yet (WAITING), oldest first: send them as soon as a radio is ready. */
    fun unsent(): List<Out> = store.unconfirmed().filter { it.status == Status.WAITING }.map { Out(it.contact, it.packet, it.id, it.isReceipt) }

    /** The radio took the packet of outbox row [id] (WAITING becomes SENT). */
    fun transmitted(id: Long) = store.markSent(id)



    /** When the next [due] call has work, for scheduling a wake-up; null when nothing waits. */
    fun nextDue(): Long? {
        // An ack may deliver every outstanding row, leaving no retry timer. Wake eligible held work explicitly;
        // a still-full window must not create a busy loop.
        for (contact in store.contacts()) {
            if (store.held(contact).isEmpty()) continue
            val state = store.session(contact) ?: continue
            if (Ratchet.maySealNew(state, store.lastAck(contact))) return 0L
        }
        return (store.pendingReceipts().mapNotNull { it.due } + store.unconfirmed().filter { !it.isReceipt }.map { it.nextTry } + listOfNotNull(nextIncomingExpiry())).minOrNull()
    }

    enum class Status { WAITING, SENT, HUB, DELIVERED, FAILED, RECEIPT, SUPERSEDED }

    data class Outgoing(
        val id: Long, val contact: AccountId, val messageId: Long?, val packet: ByteArray, val chainTag: ByteArray,
        val counter: Long, val code: ByteArray, val created: Long, val tries: Int, val nextTry: Long, val status: Status,
        val isReceipt: Boolean = false, val carriedAck: Ack? = null,
        /** False for a picture part: only the head (and every other body) is what replies and reactions name. */
        val namesMessage: Boolean = true,
    )

    class Held(val id: Long, val messageId: Long?, val body: ByteArray)

    companion object {
        private val receiptRandom = java.security.SecureRandom()
        /** Resends after 2, 10 and 30 minutes, then every 30 minutes until delivered or [GIVE_UP_MS]. */
        val RETRY_MS = longArrayOf(2 * 60_000L, 10 * 60_000L, 30 * 60_000L)
        const val MIN_QUIET_MS = 15_000L
        const val MAX_QUIET_MS = 60_000L
        const val GIVE_UP_MS = 3 * 86_400_000L
    }
}

/** What [Postman] keeps. The app backs it with the sealed history; tests with maps. */
interface MailStore {
    fun contacts(): List<AccountId>
    fun session(contact: AccountId): SessionState?
    /** Null means refused, never successful receive. All mutations commit together before returning. */
    class Committed(val outboxId: Long?)
    class Refused : IllegalStateException("mail persistence refused")
    class Incoming(val body: Body, val ref: ByteArray, val at: Long, val peerAck: Ack?,
        val digest: String, val responseAck: Ack?, val due: Long?)
    data class Replay(val digest: String, val contact: AccountId, val responseAck: Ack?, val due: Long?, val expires: Long)
    /** Recognises only an exact previously authenticated packet; receipt duplicates never schedule receipts. */
    fun replay(digest: String, now: Long, due: Long): Boolean
    fun pendingReceipts(): List<Replay>
    class HeldIncoming(val contact: AccountId, val digest: String, val packet: ByteArray, val expires: Long)
    /** Unauthenticated bytes only; no keys, content or acknowledgements may be advanced here. */
    fun holdIncoming(contact: AccountId, digest: String, packet: ByteArray, now: Long)
    /** Removes expired rows first. An authenticated keep consumes its matching row atomically. */
    fun pendingIncoming(now: Long): List<HeldIncoming>
    fun incomingExpiry(): Long?
    /** Atomically saves state, incoming content/ack, or an outgoing packet and its consumed held row. */
    fun keep(contact: AccountId, state: SessionState, outgoing: Postman.Outgoing?,
        consumeHeldId: Long? = null, incoming: Incoming? = null, expected: SessionState): Committed?
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

    companion object {
        const val MAX_REPLAYS = 2_000
        const val MAX_INCOMING_HELD = 10
        const val INCOMING_LIFETIME = 86_400_000L
        const val REPLAY_LIFETIME = Postman.GIVE_UP_MS + Postman.MAX_QUIET_MS
        fun covers(ack: Ack, saved: Ack): Boolean = Bytes.equal(ack.tag, saved.tag) && ack.covers(saved.highest)
    }
}

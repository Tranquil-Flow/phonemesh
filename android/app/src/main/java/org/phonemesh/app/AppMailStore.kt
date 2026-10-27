package org.phonemesh.app

import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.mail.MailStore
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.WireV2

/**
 * [Postman]'s storage on the phone (plan step 13): sessions sealed by [AccountStore], packets in the outbox table in
 * the same transaction, and each outbox change mirrored onto the message the person sees.
 */
class AppMailStore(private val history: History, private val store: AccountStore) : MailStore {
    private val ledger = MailLedger(history)
    private val incomingHeld = IncomingHeld(history)
    private val incomingPictures = PersonPictures(history)
    override fun holdIncoming(contact: AccountId, digest: String, packet: ByteArray, now: Long) {
        history.transaction {
            val state = store.session(contact) ?: return@transaction
            incomingHeld.hold(contact, digest, packet, now, Ratchet.codes(state))
        }
    }
    override fun pendingIncoming(now: Long): List<MailStore.HeldIncoming> = incomingHeld.pending(now)
    override fun incomingExpiry(): Long? = incomingHeld.nextExpiry()
    override fun replay(digest: String, now: Long, due: Long): Boolean = ledger.replay(digest, now, due)
    override fun pendingReceipts(): List<MailStore.Replay> = ledger.pending()
    override fun contacts(): List<AccountId> = history.contactRows().map { idOf(it.accountId) }

    override fun session(contact: AccountId): SessionState? = store.session(contact)

    override fun keep(contact: AccountId, state: SessionState, outgoing: Postman.Outgoing?,
        consumeHeldId: Long?, incoming: MailStore.Incoming?, expected: SessionState): MailStore.Committed? = history.transaction {
        val row = outgoing?.let {
            History.OutboxRow(0, contact.toString(), it.messageId, WireV2.PORT, it.packet, it.chainTag, it.counter, it.created,
                it.tries, it.nextTry, it.status.name, it.code, it.isReceipt, it.carriedAck?.encode())
        }
        if (!store.storeSession(contact, state, row, consumeHeldId, expected)) return@transaction null
        incomingHeld.retainCodes(contact, Ratchet.codes(state))
        val outboxId = if (row != null) history.lastOutboxId else null
        // A picture's parts do not name the bubble: its head does, as on the receiving phone.
        outgoing?.takeIf { it.namesMessage && it.messageId != null }
            ?.let { history.attachPacket(checkNotNull(it.messageId), refNumber(Ratchet.reference(it.code))) }
        // A held part sealed later is a new packet still to be accepted: the bubble is waiting again.
        if (consumeHeldId != null) outgoing?.messageId?.let { reconcile(contact.toString(), it) }
        incoming?.let { project(contact, state, it); ledger.incoming(contact, it); incomingHeld.consume(contact, it.digest) }
        outgoing?.carriedAck?.let { ledger.carried(contact, it) }
        MailStore.Committed(outboxId)
    }

    /**
     * A contact's changed profile (PF5): kept when newer than the one known, so an old or repeated update changes
     * nothing. It is acknowledged either way, and shows no bubble.
     */
    private fun keepProfile(person: History.ContactRow, profile: org.phonemesh.core.account.Profile) {
        val known = person.profile?.let { org.phonemesh.core.account.Profile.decode(it) }
        if (known != null && known.revision >= profile.revision) return
        history.putContact(History.ContactRow(person.accountId, person.card, profile.name, profile.colour, person.met, person.voice,
            person.added, profile.encode()))
    }

    private fun project(contact: AccountId, state: SessionState, incoming: MailStore.Incoming) {
        val person = checkNotNull(history.contactRow(contact.toString())) { "missing contact" }
        // A packet authenticated by the current session proves the peer received this start; hub acks cannot.
        history.outbox(contact.toString()).filter {
            it.state in OPEN && org.phonemesh.core.session.Start.isHello(it.packet)
        }.forEach { history.setOutboxState(it.id, Postman.Status.DELIVERED.name) }
        if (incoming.body !is Body.Receipt) {
            val chat = history.personChat(person.accountId, person.name)
            val ref = refNumber(incoming.ref)
            when (val body = incoming.body) {
                is Body.Text -> history.addIncoming(chat.id, person.accountId, body.text, body.language, incoming.at, true, packetId = ref)
                is Body.Reply -> history.addIncoming(chat.id, person.accountId, body.text, body.language, incoming.at, true,
                    packetId = ref, replyTo = refNumber(body.ref))
                is Body.Reaction -> history.addReaction(chat.id, person.accountId, body.emoji, refNumber(body.ref), incoming.at)
                is Body.PictureHead, is Body.PicturePart -> incomingPictures.accept(chat, person.accountId, body, ref, incoming.at)
                is Body.ProfileUpdate -> keepProfile(person, body.profile)
                is Body.AvatarPiece -> history.keepAvatarPiece(person.accountId, body.bytes, incoming.at) { pieces ->
                    // Any run of pieces from one head that makes a whole, checked photo (decision 50).
                    pieces.indices.reversed().asSequence().mapNotNull { from ->
                        org.phonemesh.core.account.AvatarPhotos.join(pieces.subList(from, pieces.size))?.webp
                    }.firstOrNull()
                }
                else -> error("unsupported incoming body")
            }
        }
        incoming.peerAck?.let { ack ->
            val previous = lastAck(contact)
            if (Bytes.equal(ack.tag, Ratchet.sendingTag(state)) &&
                (previous == null || !Bytes.equal(previous.tag, ack.tag) || ack.highest >= previous.highest)) saveAck(contact, ack)
            markDelivered(contact, ack)
        }
    }

    override fun unconfirmed(): List<Postman.Outgoing> = history.outboxIn(OPEN)
        .filter { !it.isReceipt || it.state == Postman.Status.WAITING.name }.map { outgoing(it) }

    override fun update(row: Postman.Outgoing) {
        history.updateOutbox(row.id, row.tries, row.nextTry, row.status.name)
        if (row.status == Postman.Status.FAILED) row.messageId?.let { history.setStatus(it, DeliveryStatus.FAILED) }
    }

    override fun markSent(id: Long) {
        history.transaction {
            val row = history.outboxRow(id) ?: return@transaction
            if (row.state != Postman.Status.WAITING.name) return@transaction
            history.setOutboxState(id, Postman.Status.SENT.name)
            row.messageId?.let { reconcile(row.accountId, it) }
        }
    }

    /** A pinned hub accepted this durable row, including a hello without a recipient code. */
    fun markHubReached(id: Long, pin: String? = null) {
        history.transaction {
            val row = history.outboxRow(id) ?: return@transaction
            if (row.state !in OPEN) return@transaction
            pin?.let { HubCustody(history).accepted(id, it) }
            if (row.state == Postman.Status.HUB.name) return@transaction
            history.setOutboxState(id, Postman.Status.HUB.name)
            row.messageId?.let { reconcile(row.accountId, it) }
        }
    }

    override fun markHub(codes: Set<String>) {
        history.transaction {
            for (row in history.outboxIn(setOf(Postman.Status.WAITING.name, Postman.Status.SENT.name))) {
                val code = row.code ?: continue
                if (SessionState.hexOf(code) !in codes) continue
                history.setOutboxState(row.id, Postman.Status.HUB.name)
                row.messageId?.let { reconcile(row.accountId, it) }
            }
        }
    }

    override fun markDelivered(contact: AccountId, ack: Ack) {
        for (row in history.outbox(contact.toString())) {
            if (row.state !in OPEN) continue
            val tag = row.chainTag ?: continue
            val counter = row.counter ?: continue
            if (!Bytes.equal(tag, ack.tag) || !ack.covers(counter)) continue
            history.setOutboxState(row.id, Postman.Status.DELIVERED.name)
            row.messageId?.let { reconcile(contact.toString(), it) }
        }
    }

    /**
     * One status for a bubble made of several packets (a picture), the least advanced of its obligations. WAITING while
     * any packet is unaccepted by the radio or still held past the flow-control window; SENT once every packet is
     * accepted; the hub status only when every packet reached the hub or was delivered; DELIVERED only when the head and
     * every part are delivered. A text has one packet, so this is the same as before. Call after any outbox row of the
     * message changes state, inside that change's transaction. Failed bubbles stay failed until a retry resets them.
     */
    private fun reconcile(accountHex: String, messageId: Long) {
        val message = history.message(messageId) ?: return
        if (message.status == DeliveryStatus.DELIVERED || message.status == DeliveryStatus.FAILED) return
        val rows = history.outbox(accountHex).filter { it.messageId == messageId && it.state != Postman.Status.SUPERSEDED.name }
        if (rows.isEmpty() || rows.any { it.state == Postman.Status.FAILED.name }) return
        val states = rows.map { it.state }
        val delivered = states.count { it == Postman.Status.DELIVERED.name }
        if (history.picture(messageId) != null) history.setPictureProgress(messageId, delivered)
        val held = history.held(accountHex).any { it.messageId == messageId }
        val next = when {
            held || Postman.Status.WAITING.name in states -> DeliveryStatus.WAITING
            delivered == states.size -> DeliveryStatus.DELIVERED
            states.all { it == Postman.Status.DELIVERED.name || it == Postman.Status.HUB.name } -> hubStatus
            else -> DeliveryStatus.SENT
        }
        if (next != message.status) history.setStatus(messageId, next)
    }

    /** A hub stores ciphertext; only the recipient can prove delivery to their phone. */
    private val hubStatus: DeliveryStatus get() = DeliveryStatus.HUB

    override fun lastAck(contact: AccountId): Ack? = history.lastAck(contact.toString())?.let { Ack.decode(it) }

    override fun saveAck(contact: AccountId, ack: Ack) = history.saveAck(contact.toString(), ack.encode())

    override fun hold(contact: AccountId, messageId: Long?, body: ByteArray, now: Long) = history.hold(contact.toString(), messageId, body, now)

    override fun held(contact: AccountId): List<Postman.Held> = history.held(contact.toString()).map { Postman.Held(it.id, it.messageId, it.body) }

    override fun dropHeld(id: Long) = history.dropHeld(id)

    private fun outgoing(row: History.OutboxRow) = Postman.Outgoing(
        row.id, idOf(row.accountId), row.messageId, row.packet, row.chainTag ?: ByteArray(0), row.counter ?: 0, row.code ?: ByteArray(0),
        row.created, row.tries, row.nextTry, Postman.Status.valueOf(row.state),
        row.isReceipt, row.carriedAck?.let(Ack::decode),
    )

    companion object {
        private val OPEN = setOf(Postman.Status.WAITING.name, Postman.Status.SENT.name, Postman.Status.HUB.name)

        fun idOf(hex: String): AccountId = AccountId.fromBytes(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

        /** A reference (4 bytes) as the number messages keep in packet_id, so replies and reactions can find it. */
        fun refNumber(ref: ByteArray): Long = ref.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) } or REF_MARK

        fun refBytes(number: Long): ByteArray = ByteArray(4) { ((number ushr (8 * (3 - it))) and 0xff).toByte() }

        /** Keeps references apart from radio packet ids (which fit in 32 bits). */
        private const val REF_MARK = 1L shl 40
    }
}

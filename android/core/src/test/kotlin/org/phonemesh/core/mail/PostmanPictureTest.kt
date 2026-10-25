package org.phonemesh.core.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureJoin
import org.phonemesh.core.PictureWire
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.session.Phone
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.WireV2
import kotlin.random.Random

/** Uses the new Postman.sendPicture and PictureWire.splitV2: a compile failure here is setup, not behavioural RED. */
class PostmanPictureTest {
    private class MapStore : MailStore {
        val sessions = LinkedHashMap<AccountId, SessionState>()
        val rows = LinkedHashMap<Long, Postman.Outgoing>()
        val acks = HashMap<AccountId, Ack>()
        val holds = LinkedHashMap<Long, Pair<AccountId, Postman.Held>>()
        val kept = ArrayList<MailStore.Incoming>()
        private var nextId = 1L
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
            val old = sessions[contact]
            check(old == null || state.serial > old.serial) { "state must move forward" }
            incoming?.let { item ->
                kept += item
                replays.entries.removeAll { it.value.expires < item.at && it.value.due == null }
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
            consumeHeldId?.let { holds.remove(it) }
            incoming?.peerAck?.let { ack ->
                val previous = acks[contact]
                if (previous == null || !Bytes.equal(previous.tag, ack.tag) || ack.highest >= previous.highest) saveAck(contact, ack)
                markDelivered(contact, ack)
            }
            return MailStore.Committed(id)
        }
        override fun unconfirmed() = rows.values.filter { it.status in setOf(Postman.Status.WAITING, Postman.Status.SENT, Postman.Status.HUB) && (!it.isReceipt || it.status == Postman.Status.WAITING) }
        override fun update(row: Postman.Outgoing) { rows[row.id] = row }
        override fun markSent(id: Long) { rows[id]?.let { if (it.status == Postman.Status.WAITING) rows[id] = it.copy(status = Postman.Status.SENT) } }
        override fun markHub(codes: Set<String>) {}
        override fun markDelivered(contact: AccountId, ack: Ack) {
            rows.values.toList().forEach {
                if (it.contact == contact && Bytes.equal(it.chainTag, ack.tag) && ack.covers(it.counter)) rows[it.id] = it.copy(status = Postman.Status.DELIVERED)
            }
        }
        override fun lastAck(contact: AccountId) = acks[contact]
        override fun saveAck(contact: AccountId, ack: Ack) { acks[contact] = ack }
        override fun hold(contact: AccountId, messageId: Long?, body: ByteArray, now: Long) { val id = nextId++; holds[id] = contact to Postman.Held(id, messageId, body) }
        override fun held(contact: AccountId) = holds.values.filter { it.first == contact }.map { it.second }
        override fun dropHeld(id: Long) { holds.remove(id) }
    }

    private class Side(val phone: Phone, other: Phone) {
        val store = MapStore()
        val them = AccountId.of(other.keys.identity)
        val postman = Postman(phone.keys, store, quietDelay = { 20_000L })
        init { store.sessions[them] = phone.state }
    }

    private fun pair(): Pair<Side, Side> {
        val (a, b) = Phone.qrPair()
        return Side(a, b) to Side(b, a)
    }

    private val sketch = byteArrayOf(1, 2, 3)
    private fun bytes(size: Int) = ByteArray(size) { (it * 31 + 5).toByte() }
    private fun packets(size: Int, id: Int = 7) = PictureWire.splitV2(id, PictureCodec.WEBP, 64, 48, bytes(size), sketch)

    private fun joined(side: Side): ByteArray? {
        val bodies = side.store.kept.map { it.body }
        val head = bodies.filterIsInstance<Body.PictureHead>().firstOrNull()?.let { PictureWire.decodeV2(it.bytes) as PictureWire.Head } ?: return null
        val parts = bodies.filterIsInstance<Body.PicturePart>().map { PictureWire.decodeV2(it.bytes) as PictureWire.Part }.associate { it.index to it.data }
        return PictureJoin.join(head, parts)
    }

    @Test fun everyPacketOfAPictureIsSealedAloneKeptAndFitsTheRadio() {
        val (a, _) = pair()
        val sent = a.postman.sendPicture(a.them, 1, packets(3000), 0).map { checkNotNull(it) }
        assertEquals(1 + PictureWire.partsForV2(3000), sent.size)
        assertTrue(sent.all { it.packet.size <= WireV2.MAX_PACKET })
        assertEquals("a full part fills the packet", WireV2.MAX_PACKET, sent[1].packet.size)
        assertEquals("each part has its own ciphertext", sent.size, sent.map { it.packet.toList() }.toSet().size)
        val rows = a.store.rows.values.sortedBy { it.id }
        assertEquals(sent.size, rows.size)
        assertTrue("the head names the bubble", rows[0].namesMessage)
        assertTrue("parts do not", rows.drop(1).none { it.namesMessage })
        assertTrue(rows.all { it.messageId == 1L })
    }

    @Test fun aPictureArrivesWhateverTheOrderOfItsPartsAndEveryCopy() {
        val (a, b) = pair()
        val sent = a.postman.sendPicture(a.them, 1, packets(3000), 0).map { checkNotNull(it) }
        val parts = sent.drop(1)
        b.postman.receive(sent[0].packet, 1_000)
        (parts + parts.take(4)).shuffled(Random(11)).forEach { b.postman.receive(it.packet, 1_000) }
        assertEquals("each packet is consumed once", sent.size, b.store.kept.size)
        assertArrayEquals(bytes(3000), joined(b))
    }

    @Test fun partsBeforeTheirHeadAreHeldAndConsumedOnceTheHeadArrives() {
        val (a, b) = pair()
        val sent = a.postman.sendPicture(a.them, 1, packets(500), 0).map { checkNotNull(it) }
        sent.drop(1).forEach { b.postman.receive(it.packet, 1_000) }
        b.postman.receive(sent[0].packet, 2_000)
        assertEquals(sent.size, b.store.kept.size)
        assertArrayEquals(bytes(500), joined(b))
    }

    @Test fun aCopyOfAConsumedPartIsNotConsumedAgain() {
        val (a, b) = pair()
        val sent = a.postman.sendPicture(a.them, 1, packets(500), 0).map { checkNotNull(it) }
        sent.forEach { b.postman.receive(it.packet, 1_000) }
        val before = b.store.kept.size
        assertNull(b.postman.receive(sent[2].packet, 1_500))
        assertEquals(before, b.store.kept.size)
    }

    @Test fun aPictureAndTextsShareTheTwentyPacketWindowAndHeldPartsKeepTheirOrder() {
        val (a, b) = pair()
        val picture = a.postman.sendPicture(a.them, 1, packets(3000), 0)
        val texts = (1..5L).map { a.postman.send(a.them, Body.Text("fr", "t$it"), 10 + it, 0) }
        val out = (picture + texts).filterNotNull()
        assertEquals("17 picture packets and 5 texts: 20 past the last ack", 20, out.size)
        assertEquals(2, a.store.holds.size)
        out.forEach { b.postman.receive(it.packet, 1_000) }
        a.postman.receive(b.postman.due(30_000).single().packet, 31_000)
        assertTrue("an ack wakes held work without new activity", (a.postman.nextDue() ?: Long.MAX_VALUE) <= 32_000)
        val freed = a.postman.due(32_000)
        assertEquals(2, freed.size)
        assertTrue(a.store.holds.isEmpty())
        freed.forEach { b.postman.receive(it.packet, 33_000) }
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), b.store.kept.map { it.body }.filterIsInstance<Body.Text>().map { it.text }.sorted())
    }

    @Test fun aPictureStartedInAFullWindowWaitsInOrderAndStillArrivesWhole() {
        val (a, b) = pair()
        val texts = (1..19L).map { a.postman.send(a.them, Body.Text("fr", "t$it"), it, 0)!! }
        val picture = a.postman.sendPicture(a.them, 99, packets(500), 0)
        assertNotNull("the 20th packet is the head", picture[0])
        assertTrue(picture.drop(1).all { it == null })
        assertEquals(picture.size - 1, a.store.holds.size)
        (texts + listOfNotNull(picture[0])).forEach { b.postman.receive(it.packet, 1_000) }
        a.postman.receive(b.postman.due(30_000).single().packet, 31_000)
        a.postman.due(32_000).forEach { b.postman.receive(it.packet, 33_000) }
        assertArrayEquals(bytes(500), joined(b))
    }

    @Test fun aLostPartIsResentUnchangedAfterTwoMinutesAndThePictureCompletes() {
        val (a, b) = pair()
        val sent = a.postman.sendPicture(a.them, 1, packets(500), 0).map { checkNotNull(it) }
        sent.forEach { a.postman.transmitted(it.outboxId) }
        assertEquals("the next wake-up is the first resend", 2 * 60_000L, a.postman.nextDue())
        sent.filterIndexed { i, _ -> i != 2 }.forEach { b.postman.receive(it.packet, 1_000) }
        assertTrue(joined(b) == null)
        val resent = a.postman.due(2 * 60_000)
        assertTrue("the stored bytes go again", resent.any { it.packet.contentEquals(sent[2].packet) })
        resent.forEach { b.postman.receive(it.packet, 2 * 60_000 + 1) }
        assertArrayEquals(bytes(500), joined(b))
    }

    @Test fun everyPacketOfAPictureGivesUpTogetherAfterThreeDaysAndIsNotResent() {
        val (a, _) = pair()
        val sent = a.postman.sendPicture(a.them, 1, packets(500), 0).map { checkNotNull(it) }
        sent.forEach { a.postman.transmitted(it.outboxId) }
        assertTrue(a.postman.due(Postman.GIVE_UP_MS + 1).isEmpty())
        assertTrue(a.store.rows.values.all { it.status == Postman.Status.FAILED })
    }
}

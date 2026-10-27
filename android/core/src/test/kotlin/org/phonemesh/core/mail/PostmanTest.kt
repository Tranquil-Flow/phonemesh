package org.phonemesh.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.session.Phone
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.HubAck
import kotlin.random.Random

/** Plan step 13: two phones through a shared radio that loses and reorders packets. */
class PostmanTest {
    /** One phone's storage, as the app's history keeps it. */
    internal class MapStore : MailStore {
        val sessions = LinkedHashMap<AccountId, SessionState>()
        val rows = LinkedHashMap<Long, Postman.Outgoing>()
        val acks = HashMap<AccountId, Ack>()
        val holds = LinkedHashMap<Long, Pair<AccountId, Postman.Held>>()
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
            if (refuseCommit) return null
            val old = sessions[contact]
            check(old == null || state.serial > old.serial) { "state must move forward" }
            incoming?.let { item ->
                replays.entries.removeAll { it.value.expires < item.at && it.value.due == null }
                if (replays.size >= MailStore.MAX_REPLAYS) throw MailStore.Refused()
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
        override fun markHub(codes: Set<String>) {
            rows.values.toList().forEach { if (SessionState.hexOf(it.code) in codes && it.status in setOf(Postman.Status.WAITING, Postman.Status.SENT)) rows[it.id] = it.copy(status = Postman.Status.HUB) }
        }
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
        fun status(messageId: Long) = rows.values.single { it.messageId == messageId }.status
    }

    private class Side(val phone: Phone, val other: Phone) {
        val store = MapStore()
        val them = AccountId.of(other.keys.identity)
        val postman = Postman(phone.keys, store, quietDelay = { 20_000L })
        init { store.sessions[them] = phone.state }
    }

    private fun pair(link: Boolean = false): Pair<Side, Side> {
        val (a, b) = if (link) Phone.linkPair() else Phone.qrPair()
        return Side(a, b) to Side(b, a)
    }

    private fun text(s: String) = Body.Text("fr", s)
    private fun texts(got: List<Postman.Received?>) = got.mapNotNull { (it?.body as? Body.Text)?.text }

    /** Profile spec PF5: a changed profile is a private body like a text, delivered once and acknowledged. */
    @Test fun aProfileUpdateIsDeliveredAndAcknowledged() {
        val (a, b) = pair()
        val profile = org.phonemesh.core.account.Profile("Awa", 0x1E88E5, org.phonemesh.core.account.Avatar.Emoji("🐟"), revision = 2)
        val sent = a.postman.send(a.them, Body.ProfileUpdate(profile), 7L, 0)!!
        a.postman.transmitted(sent.outboxId)
        val got = b.postman.receive(sent.packet, 1_000)
        assertEquals(Body.ProfileUpdate(profile), got?.body)
        assertNull("a repeat is not shown again", b.postman.receive(sent.packet, 2_000))
        val receipt = b.postman.due(61_000).single()
        a.postman.receive(receipt.packet, 62_000)
        assertEquals(Postman.Status.DELIVERED, a.store.status(7L))
    }

    @Test fun threeMessagesArriveAndOneReceiptAfterQuietConfirmsThemAll() {
        val (a, b) = pair()
        val sent = (1..3L).map { a.postman.send(a.them, text("m$it"), it, 0)!! }
        sent.forEach { a.postman.transmitted(it.outboxId!!) }
        assertEquals(listOf("m1", "m2", "m3"), texts(sent.map { b.postman.receive(it.packet, 1_000) }))
        assertTrue("no receipt before 20 s of quiet", b.postman.due(10_000).isEmpty())
        val receipts = b.postman.due(21_000)
        assertEquals(1, receipts.size)
        assertNull("a receipt shows nothing", a.postman.receive(receipts[0].packet, 22_000))
        (1..3L).forEach { assertEquals(Postman.Status.DELIVERED, a.store.status(it)) }
        assertTrue("delivered messages are not resent", a.postman.due(10 * 60_000).isEmpty())
    }

    @Test fun aReplyBeforeTheQuietEndsIsTheReceipt() {
        val (a, b) = pair(link = true)
        val m = a.postman.send(a.them, text("Bonjour"), 1, 0)!!
        b.postman.receive(m.packet, 1_000)
        val reply = b.postman.send(b.them, text("Salut"), 2, 5_000)!!
        assertTrue("no separate receipt", b.postman.due(60_000).none { it.isReceipt })
        assertEquals("Salut", (a.postman.receive(reply.packet, 6_000)!!.body as Body.Text).text)
        assertEquals(Postman.Status.DELIVERED, a.store.status(1))
    }

    @Test fun aLostPacketIsResentAt2MinutesAndALostReceiptIsSentAgain() {
        val (a, b) = pair()
        val m = a.postman.send(a.them, text("perdu"), 1, 0)!!
        assertTrue(a.postman.due(60_000).isEmpty())
        val resent = a.postman.due(2 * 60_000)
        assertEquals(1, resent.size)
        assertTrue("the same bytes go again", resent[0].packet.contentEquals(m.packet))
        assertEquals("perdu", (b.postman.receive(resent[0].packet, 2 * 60_000 + 1)!!.body as Body.Text).text)
        b.postman.due(3 * 60_000).forEach { b.postman.transmitted(it.outboxId) } // accepted, then lost on air
        val again = a.postman.due(12 * 60_000)
        assertEquals("next try at 10 minutes", 1, again.size)
        assertNull("the copy is not shown twice", b.postman.receive(again[0].packet, 12 * 60_000 + 1))
        val receipt = b.postman.due(13 * 60_000).single()
        a.postman.receive(receipt.packet, 13 * 60_000 + 1)
        assertEquals(Postman.Status.DELIVERED, a.store.status(1))
    }

    @Test fun lossAndReorderingStillDeliverEverythingOnce() {
        val (a, b) = pair()
        val random = Random(7)
        var now = 0L
        val got = ArrayList<String>()
        val air = ArrayList<ByteArray>()
        for (i in 1..30L) {
            a.postman.send(a.them, text("m$i"), i, now)?.let { air += it.packet }
            now += 5_000
        }
        repeat(200) {
            air += a.postman.due(now).map { it.packet }
            air.shuffle(random)
            for (p in air.toList()) {
                if (random.nextInt(4) == 0) continue // a quarter is lost
                b.postman.receive(p, now)?.let { r -> got += (r.body as Body.Text).text }
            }
            air.clear()
            b.postman.due(now).forEach { r -> b.postman.transmitted(r.outboxId); if (random.nextInt(4) != 0) a.postman.receive(r.packet, now) }
            now += 5 * 60_000
        }
        assertEquals((1..30).map { "m$it" }.sorted(), got.sorted())
        assertEquals("each shown once", 30, got.toSet().size)
        (1..30L).forEach { assertEquals("m$it", Postman.Status.DELIVERED, a.store.status(it)) }
    }

    @Test fun pastTheWindowNewMessagesWaitUntilAnAckFreesRoom() {
        val (a, b) = pair()
        val out = (1..25L).mapNotNull { a.postman.send(a.them, text("m$it"), it, 0) }
        assertEquals(20, out.size)
        assertEquals(5, a.store.holds.size)
        out.forEach { b.postman.receive(it.packet, 1_000) }
        a.postman.receive(b.postman.due(30_000).single().packet, 31_000)
        assertTrue("eligible held messages must wake the scheduler without new user activity",
            (a.postman.nextDue() ?: Long.MAX_VALUE) <= 32_000)
        val freed = a.postman.due(32_000)
        assertEquals(5, freed.size)
        assertTrue(a.store.holds.isEmpty())
        assertEquals(listOf("m21", "m22", "m23", "m24", "m25"), texts(freed.map { b.postman.receive(it.packet, 33_000) }))
    }

    @Test fun aPacketNoRadioTookIsUnsentUntilOneDoes() {
        val (a, _) = pair()
        val m = a.postman.send(a.them, text("plus tard"), 1, 0)!!
        assertEquals(listOf(m.outboxId), a.postman.unsent().map { it.outboxId })
        a.postman.transmitted(m.outboxId!!)
        assertTrue(a.postman.unsent().isEmpty())
    }

    @Test fun aHubAckMarksReachedHubAndDeliveryStillComesLater() {
        val (a, _) = pair()
        val m = a.postman.send(a.them, text("hub"), 1, 0)!!
        a.postman.transmitted(m.outboxId!!)
        a.postman.hubAck(HubAck.encode(listOf(m.packet.copyOfRange(1, 9))))
        assertEquals(Postman.Status.HUB, a.store.status(1))
        assertEquals("still resent until delivered", 1, a.postman.due(2 * 60_000).size)
    }

    @Test fun afterThreeDaysAMessageIsMarkedFailed() {
        val (a, _) = pair()
        a.postman.send(a.them, text("loin"), 1, 0)
        var now = 0L
        while (now < Postman.GIVE_UP_MS + 3_600_000) { now += 30 * 60_000; a.postman.due(now) }
        assertEquals(Postman.Status.FAILED, a.store.status(1))
        assertNull(a.postman.nextDue())
    }

    @Test fun aLongTextDefersTheAckRatherThanOverflowingThePacket() {
        val (a, b) = pair()
        val incoming = b.postman.send(b.them, text("Bonjour"), 1, 0)!!
        a.postman.receive(incoming.packet, 1_000)!!
        val out = a.postman.send(a.them, text("x".repeat(191)), 2, 2_000)!!
        assertTrue(out.packet.size <= 228)
        assertEquals("x".repeat(191), (b.postman.receive(out.packet, 3_000)!!.body as Body.Text).text)
        assertNotNull("omitting the piggyback must retain the receipt obligation", a.postman.nextDue())
        val receipt = a.postman.due(61_000).single { it.isReceipt }
        b.postman.receive(receipt.packet, 62_000)
        assertEquals(Postman.Status.DELIVERED, b.store.status(1))
    }

    @Test fun aLongReplyDefersTheAckRatherThanOverflowingThePacket() {
        val (a, b) = pair()
        val incoming = b.postman.send(b.them, text("Bonjour"), 1, 0)!!
        val got = a.postman.receive(incoming.packet, 1_000)!!
        val out = a.postman.send(a.them, Body.Reply(got.ref, "fr", "x".repeat(191)), 2, 2_000)!!
        assertTrue(out.packet.size <= 228)
        assertEquals("x".repeat(191), (b.postman.receive(out.packet, 3_000)!!.body as Body.Reply).text)
    }

    @Test fun emptyAndTruncatedPacketsAreRefusedWithoutStateChanges() {
        val (a, _) = pair()
        val before = a.store.sessions[a.them]!!.encode()
        for (length in 0..24) assertNull(a.postman.receive(ByteArray(length), 0))
        assertTrue(before.contentEquals(a.store.sessions[a.them]!!.encode()))
        assertNull(a.postman.nextDue())
    }

    @Test fun aRefusedCommitNeverReleasesAnUnstoredPacket() {
        val (a, _) = pair()
        val before = a.store.session(a.them)!!.encode()
        a.store.refuseCommit = true
        val attempted = runCatching { a.postman.send(a.them, text("must stay here"), 1, 0) }
        assertTrue("no packet escapes a refused durable commit", attempted.isFailure || attempted.getOrNull() == null)
        assertTrue(before.contentEquals(a.store.session(a.them)!!.encode()))
        assertTrue(a.store.rows.isEmpty())
    }

    @Test fun aRefusedReceiveNeitherShowsNorAcknowledgesAndCanBeRetried() {
        val (a, b) = pair()
        val packet = b.postman.send(b.them, text("keep me first"), 1, 0)!!.packet
        val before = a.store.session(a.them)!!.encode()
        a.store.refuseCommit = true
        val attempted = runCatching { a.postman.receive(packet, 1_000) }
        assertTrue("no body escapes a refused durable commit", attempted.isFailure || attempted.getOrNull() == null)
        assertTrue(before.contentEquals(a.store.session(a.them)!!.encode()))
        assertNull("refusal must not schedule an acknowledgement", a.postman.nextDue())
        a.store.refuseCommit = false
        assertEquals("keep me first", (a.postman.receive(packet, 2_000)!!.body as Body.Text).text)
    }

    @Test fun receiptDelayUsesBothEndsOfTheApprovedRandomInterval() {
        for (delay in listOf(15_000L, 60_000L)) {
            val (a, b) = pair()
            val recipient = Postman(a.phone.keys, a.store, quietDelay = { delay })
            recipient.receive(b.postman.send(b.them, text("jitter"), 1, 0)!!.packet, 1_000)
            assertEquals(1_000L + delay, recipient.nextDue())
            assertTrue(recipient.due(1_000L + delay - 1).isEmpty())
            assertEquals(1, recipient.due(1_000L + delay).size)
        }
    }

    @Test fun aReceiptCommitFailureKeepsItsDeadline() {
        val (a, b) = pair()
        a.postman.receive(b.postman.send(b.them, text("receipt must survive"), 1, 0)!!.packet, 1_000)
        val due = a.postman.nextDue()!!
        a.store.refuseCommit = true
        assertTrue(runCatching { a.postman.due(due) }.isFailure)
        assertEquals("failed sealing is not acknowledgement", due, a.postman.nextDue())
        a.store.refuseCommit = false
        assertEquals(1, a.postman.due(due + 1).size)
    }

    @Test fun someoneElsesPacketsAreIgnored() {
        val (a, _) = pair()
        val (c, _) = pair()
        val m = c.postman.send(c.them, text("pas pour toi"), 1, 0)!!
        assertNull(a.postman.receive(m.packet, 0))
        assertNull(a.postman.receive(ByteArray(40), 0))
        assertNotNull(a.store.sessions[a.them])
    }
}

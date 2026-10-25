package org.phonemesh.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Vectors
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.WireV2
import kotlin.random.Random

class RatchetTest {
    private fun lh(): Pair<Phone, Phone> = Phone.qrPair().let { (a, b) -> if (a.state.iAmL) a to b else b to a }

    @Test fun turnsStepTheRatchetAndTheKeyRidesUntilAnswered() {
        val (l, h) = lh()
        val first = l.send("1")
        assertFalse("nothing heard yet: no step", first.stepped)
        assertEquals("1", h.receive(first.packet))
        val hFirst = h.send("2")
        assertFalse("H waits for L's first step", hFirst.stepped)
        assertEquals("2", l.receive(hFirst.packet))
        val step = l.send("3")
        assertTrue(step.stepped && step.withKey)
        val again = l.send("4")
        assertTrue("repeated until answered", again.withKey && !again.stepped)
        assertEquals(WireV2.KEYED_HEADER, WireV2.parse(again.packet)!!.associated.size)
        assertEquals("4", h.receive(again.packet))
        assertEquals("3", h.receive(step.packet))
        val answer = h.send("5")
        assertTrue(answer.stepped)
        assertEquals("5", l.receive(answer.packet))
        assertFalse("answered: the announcement ends", l.state.announcing)
        assertTrue("the turn changed again: a new step", l.send("6").stepped)
    }

    /** Review F7: the ratchet key is masked, differently on each packet of a turn. */
    @Test fun aTurnsPacketsCannotBeLinkedByTheirKey() {
        val (l, h) = lh()
        l.receive(h.send("hi").packet)
        val one = l.send("a").packet; val two = l.send("b").packet
        val keyBytes = { p: ByteArray -> p.copyOfRange(WireV2.PLAIN_HEADER, WireV2.KEYED_HEADER) }
        assertFalse(keyBytes(one).contentEquals(keyBytes(two)))
        assertFalse("the raw key is nowhere in it", Vectors.hex(one).contains(Vectors.hex(l.state.mine!!.public)))
        assertEquals("b", h.receive(two)); assertEquals("a", h.receive(one))
    }

    /** Review F3: sealing never changes the state it was given; the caller stores the new one. */
    @Test fun sealingLeavesTheOldStateAlone() {
        val (l, h) = lh()
        l.receive(h.send("x").packet)
        val before = l.state.encode()
        val sealed = Ratchet.seal(l.state, Body.encode(Body.Text("fr", "y")))
        assertArrayEquals(before, l.state.encode())
        assertTrue(sealed.state.serial > l.state.serial)
        val opened = Ratchet.open(h.state, sealed.packet, h.keys)
        assertTrue(opened is Ratchet.Opened.Delivered)
        assertTrue("opening leaves it alone too", h.state.serial < (opened as Ratchet.Opened.Delivered).state.serial)
    }

    @Test fun aReplayIsRefusedAndOldPacketsCannotBeOpenedLater() {
        val (a, b) = Phone.qrPair()
        val packet = a.send("secret").packet
        assertEquals("secret", b.receive(packet))
        assertNull("replay", b.receive(packet))
        val stored = SessionState.decode(b.state.encode())!!
        assertTrue(Ratchet.open(stored, packet, b.keys) is Ratchet.Opened.NotMine)
        assertEquals(0, stored.skippedCount)
    }

    @Test fun aStolenStateHealsAfterTwoTurns() {
        val (a, b) = Phone.qrPair()
        b.receive(a.send("1").packet); a.receive(b.send("2").packet)
        val stolen = SessionState.decode(b.state.encode())!!
        repeat(2) { b.receive(a.send("x").packet); a.receive(b.send("y").packet) }
        val later = a.send("after healing").packet
        assertEquals("after healing", b.receive(later))
        assertTrue(Ratchet.open(stolen, later, b.keys) !is Ratchet.Opened.Delivered)
    }

    @Test fun aStoredSkippedPacketKeepsItsAcknowledgementIdentity() {
        val (a, b) = Phone.qrPair()
        val late = a.send("late")
        assertEquals("later", b.receive(a.send("later").packet))
        val reopened = SessionState.decode(b.state.encode())!!
        val delivered = Ratchet.open(reopened, late.packet, b.keys) as Ratchet.Opened.Delivered
        assertEquals(late.counter, delivered.counter)
        assertArrayEquals(late.chainTag, delivered.chainTag)
    }

    @Test fun versionOneStateStillOpensItsCurrentChainSkippedKey() {
        val (a, b) = Phone.qrPair()
        val late = a.send("legacy late")
        b.receive(a.send("later").packet)
        assertEquals(1, b.state.skippedCount)
        val encoded = b.state.encode()
        // v1 has the same header and key/time entry, but no presence/tag/counter suffix.
        val legacy = encoded.copyOf(encoded.size - 1 - Ack.TAG - 8).also { it[0] = 1 }
        val reopened = SessionState.decode(legacy)!!
        val delivered = Ratchet.open(reopened, late.packet, b.keys) as Ratchet.Opened.Delivered
        assertEquals(late.counter, delivered.counter)
        assertArrayEquals(late.chainTag, delivered.chainTag)
        assertNotNull(SessionState.decode(reopened.encode()))
    }

    @Test fun aSkippedOldChainPacketKeepsItsIdentityAfterTurnsAndReopen() {
        val (a, b) = Phone.qrPair()
        val late = a.send("old chain")
        b.receive(a.send("later").packet)
        repeat(3) { a.receive(b.send("reply").packet); b.receive(a.send("new turn").packet) }
        val reopened = SessionState.decode(b.state.encode())!!
        val delivered = Ratchet.open(reopened, late.packet, b.keys) as Ratchet.Opened.Delivered
        assertEquals(late.counter, delivered.counter)
        assertArrayEquals(late.chainTag, delivered.chainTag)
    }

    @Test fun stateSurvivesBeingStored() {
        val (a, b) = Phone.qrPair()
        repeat(5) { b.receive(a.send("a$it").packet); a.receive(b.send("b$it").packet) }
        val late = a.send("late").packet
        a.send("lost")
        assertEquals("next", b.receive(a.send("next").packet))
        val bytes = b.state.encode()
        b.state = SessionState.decode(bytes)!!
        assertArrayEquals(bytes, b.state.encode())
        assertEquals("a skipped key survives storage", "late", b.receive(late))
        assertNull(SessionState.decode(bytes + 0))
        assertNull(SessionState.decode(bytes.copyOf().also { it[0] = 9 }))
    }

    /** Review F15: a stored state with a bad key is refused, not a crash later. */
    @Test fun aStateWithBadKeysIsRefused() {
        val (l, h) = lh()
        l.receive(h.send("x").packet); l.send("step")
        val bytes = l.state.encode()
        val scalarAt = 2 + 32
        assertNull("zero scalar", SessionState.decode(bytes.copyOf().also { it.fill(0, scalarAt, scalarAt + 32) }))
        assertNull("scalar not the public key's", SessionState.decode(bytes.copyOf().also { it[scalarAt + 31] = (it[scalarAt + 31] + 1).toByte() }))
        assertNull("their key off the curve", SessionState.decode(bytes.copyOf().also { it[scalarAt + 65] = 5 }))
    }

    @Test fun aLongTextDelaysTheStepToTheNextPacket() {
        val (l, h) = lh()
        l.receive(h.send("hi").packet)
        val long = "é".repeat(90)
        val first = l.seal(Body.Text("fr", long))
        assertFalse(first.stepped)
        assertEquals(long, h.receive(first.packet))
        val next = l.send("court")
        assertTrue("the step waited for a short packet", next.stepped && next.withKey)
        assertEquals("court", h.receive(next.packet))
    }

    @Test fun aKeylessPacketOfANewChainWaitsForItsKey() {
        val (l, h) = lh()
        l.receive(h.send("hi").packet)
        val keyed = l.send("short")
        val long = l.seal(Body.Text("fr", "é".repeat(90)))
        assertFalse("too long for the key", long.withKey)
        assertTrue(h.open(long.packet) is Ratchet.Opened.Held)
        assertEquals("short", h.receive(keyed.packet))
        assertEquals("é".repeat(90), h.receive(long.packet))
    }

    /** Review F12: an ack names its chain, its highest counter and the gaps below it; a reply can carry one. */
    @Test fun acksNameTheirChainAndGaps() {
        val (l, h) = lh()
        val sent = (0 until 6).map { l.send("m$it") }
        for (i in listOf(0, 1, 3, 5)) h.receive(sent[i].packet)
        val ack = Ratchet.ack(h.state)!!
        assertArrayEquals(sent[0].chainTag, ack.tag)
        assertEquals(5, ack.highest)
        assertEquals(listOf(true, true, false, true, false, true), (0L until 6L).map { ack.covers(it) })
        val reply = h.seal(Body.Text("fr", "ok"), ack)
        val opened = l.open(reply.packet) as Ratchet.Opened.Delivered
        val decoded = Body.decodeWithAck(opened.body)!!
        assertEquals("ok", (decoded.body as Body.Text).text)
        assertEquals(ack, decoded.ack)
        assertArrayEquals(Ratchet.sendingTag(l.state), ack.tag)
    }

    /** Review F14: a step after many losses keeps the new chain's own late keys. */
    @Test fun aLongOldChainDoesNotCrowdOutTheNewChainsKeys() {
        val (l, h) = lh()
        l.receive(h.send("hi").packet)
        val old = (0 until 60).map { l.send("old$it") }
        for (i in 0 until 10) h.receive(old[i].packet)
        l.receive(h.send("turn").packet) // H answers L's step, L may step again
        val newOnes = (0 until 3).map { l.send("new$it") }
        assertEquals("new2", h.receive(newOnes[2].packet))
        assertEquals("new0", h.receive(newOnes[0].packet))
        assertEquals("new1", h.receive(newOnes[1].packet))
    }

    /** Review F24: kept keys can be forgotten after a while. */
    @Test fun keptKeysAgeOut() {
        val (a, b) = Phone.qrPair()
        val late = a.send("late").packet
        b.now = 1_000
        b.receive(a.send("next").packet)
        assertEquals(1, b.state.skippedCount)
        b.state = Ratchet.forget(b.state, before = 1_000 + 30L * 86_400)
        assertEquals(0, b.state.skippedCount)
        assertNull(b.receive(late))
    }

    @Test fun neverTheSameKeyAndNonceTwice() {
        val seen = HashSet<String>()
        var repeats = 0
        Aead.sealed = { k, n -> if (!seen.add(Vectors.hex(k + n))) repeats++ }
        try {
            val random = Random(7)
            repeat(30) {
                val (a, b) = if (it % 2 == 0) Phone.qrPair() else Phone.linkPair()
                repeat(40) {
                    val (from, to) = if (random.nextBoolean()) a to b else b to a
                    to.receive(from.send("m").packet)
                }
            }
        } finally {
            Aead.sealed = null
        }
        assertEquals(0, repeats)
        assertTrue(seen.size >= 1200)
    }

    /** Seeded: random order, loss and duplicates. Nothing is delivered twice or altered; without loss, all arrive. */
    @Test fun randomDeliveryTenThousandRuns() {
        val random = Random(20261014)
        var delivered = 0
        repeat(10_000) { run ->
            val lossy = run % 2 == 0
            val (a, b) = if (run % 3 == 0) Phone.linkPair() else Phone.qrPair()
            val phones = listOf(a, b)
            val flight = mutableListOf<Triple<Int, String, ByteArray>>()
            val held = listOf(mutableListOf<ByteArray>(), mutableListOf<ByteArray>())
            val sent = HashMap<String, Int>()
            val got = HashSet<String>()
            fun deliver(to: Int, packet: ByteArray) {
                when (val opened = phones[to].open(packet)) {
                    is Ratchet.Opened.Delivered -> {
                        val text = (Body.decodeWithAck(opened.body)!!.body as Body.Text).text
                        assertEquals("from the other phone", 1 - to, sent[text])
                        assertTrue("delivered once: $text", got.add(text))
                        val waiting = held[to].toList(); held[to].clear()
                        waiting.forEach { deliver(to, it) }
                    }
                    Ratchet.Opened.Held -> held[to] += packet
                    Ratchet.Opened.NotMine -> Unit
                }
            }
            var id = 0
            repeat(random.nextInt(4, 24)) {
                val from = random.nextInt(2)
                val inFlight = flight.count { it.first == 1 - from }
                if (random.nextInt(3) > 0 && inFlight < 12) {
                    val text = "r${run}m${id++}"
                    sent[text] = from
                    flight += Triple(1 - from, text, phones[from].send(text).packet)
                } else if (flight.isNotEmpty()) {
                    val pick = flight[random.nextInt(flight.size)]
                    if (!lossy || random.nextInt(4) > 0) deliver(pick.first, pick.third)
                    if (random.nextInt(5) > 0) flight.remove(pick)
                }
            }
            flight.shuffle(random)
            for ((to, _, packet) in flight) if (!lossy || random.nextInt(4) > 0) deliver(to, packet)
            if (!lossy) assertEquals("run $run: all arrive without loss", sent.keys, got)
            delivered += got.size
        }
        assertTrue(delivered > 50_000)
    }

    /** Review F5: a 25-packet blackout. Flow control stops new packets in time; resending the stored ones recovers. */
    @Test fun aLongBlackoutDoesNotSilenceADirection() {
        val (a, b) = Phone.qrPair()
        b.receive(a.send("hello").packet)
        var ack = Ratchet.ack(b.state)
        val stored = ArrayList<ByteArray>()
        var refused = 0
        repeat(25) { i -> if (Ratchet.maySealNew(a.state, ack)) stored += a.send("lost$i").packet else refused++ }
        assertTrue("flow control stopped new packets: $refused", refused == 25 - Ratchet.WINDOW && stored.size == Ratchet.WINDOW)
        // The air clears. A resends its oldest unacknowledged packet first, then the rest.
        for ((i, packet) in stored.withIndex()) assertEquals("lost$i", b.receive(packet))
        ack = Ratchet.ack(b.state)
        assertTrue(Ratchet.maySealNew(a.state, ack))
        assertEquals("after", b.receive(a.send("after").packet))
        // Without flow control, packet 21 of the same blackout would never open.
        val (c, d) = Phone.qrPair()
        repeat(Ratchet.WINDOW) { c.send("gone") }
        assertNull(d.receive(c.send("too far").packet))
    }

    @Test fun differentConversationsNeverShareCodes() {
        val (a, _) = Phone.qrPair(); val (c, _) = Phone.qrPair()
        assertNotEquals(Vectors.hex(a.send("x").code), Vectors.hex(c.send("x").code))
    }
}

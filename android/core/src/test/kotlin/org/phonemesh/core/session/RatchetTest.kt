package org.phonemesh.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.WireV2
import kotlin.random.Random

class RatchetTest {
    @Test fun turnsStepTheRatchetAndTheKeyRidesUntilAnswered() {
        val (a, b) = Phone.qrPair()
        val (l, h) = if (a.state.iAmL) a to b else b to a
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
        assertEquals(WireV2.KEYED_HEADER, (WireV2.parse(again.packet)!!.associated.size))
        assertEquals("4", h.receive(again.packet))
        assertEquals("3", h.receive(step.packet))
        val answer = h.send("5")
        assertTrue(answer.stepped)
        assertEquals("5", l.receive(answer.packet))
        assertFalse("answered: the announcement ends", l.state.announcing)
        val next = l.send("6")
        assertTrue("the turn changed again: a new step", next.stepped && next.withKey)
    }

    @Test fun aReplayIsRefusedAndOldPacketsCannotBeOpenedLater() {
        val (a, b) = Phone.qrPair()
        val packet = a.send("secret").packet
        assertEquals("secret", b.receive(packet))
        assertNull("replay", b.receive(packet))
        // Forward secrecy: nothing stored can open it again, and no message key is kept.
        val stored = SessionState.decode(b.state.encode())!!
        assertTrue(Ratchet.open(stored, packet, b.keys) is Ratchet.Opened.NotMine)
        assertTrue(stored.skipped.isEmpty())
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

    @Test fun stateSurvivesBeingStored() {
        val (a, b) = Phone.qrPair()
        repeat(5) { b.receive(a.send("a$it").packet); a.receive(b.send("b$it").packet) }
        val late = a.send("late").packet
        a.send("lost")
        val next = a.send("next").packet
        assertEquals("next", b.receive(next))
        val bytes = b.state.encode()
        b.state = SessionState.decode(bytes)!!
        assertArrayEquals(bytes, b.state.encode())
        assertEquals("a skipped key survives storage", "late", b.receive(late))
        assertNull(SessionState.decode(bytes + 0))
        assertNull(SessionState.decode(bytes.copyOf().also { it[0] = 9 }))
    }

    @Test fun aLongTextDelaysTheStepToTheNextPacket() {
        val (a, b) = Phone.qrPair()
        val (l, h) = if (a.state.iAmL) a to b else b to a
        l.receive(h.send("hi").packet)
        val long = "é".repeat(90) // 180 bytes: too long to ride with the key
        val first = Ratchet.seal(l.state, Body.encode(Body.Text("fr", long)), l.newPair)
        assertFalse(first.stepped)
        assertEquals(long, h.receive(first.packet))
        val next = l.send("court")
        assertTrue("the step waited for a short packet", next.stepped && next.withKey)
        assertEquals("court", h.receive(next.packet))
    }

    @Test fun aKeylessPacketOfANewChainWaitsForItsKey() {
        val (a, b) = Phone.qrPair()
        val (l, h) = if (a.state.iAmL) a to b else b to a
        l.receive(h.send("hi").packet)
        val keyed = l.send("short") // the step, with the key
        val long = Ratchet.seal(l.state, Body.encode(Body.Text("fr", "é".repeat(90))), l.newPair)
        assertFalse("too long for the key", long.withKey)
        assertTrue(Ratchet.open(h.state, long.packet, h.keys) is Ratchet.Opened.Held)
        assertEquals("short", h.receive(keyed.packet))
        assertEquals("é".repeat(90), h.receive(long.packet))
    }

    @Test fun neverTheSameKeyAndNonceTwice() {
        val seen = HashSet<String>()
        var repeats = 0
        Aead.sealed = { k, n -> if (!seen.add(k.joinToString(",") + "/" + n.joinToString(","))) repeats++ }
        try {
            val random = Random(7)
            repeat(30) {
                val (a, b) = Phone.qrPair()
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
            val (a, b) = Phone.qrPair()
            val phones = listOf(a, b)
            val flight = mutableListOf<Triple<Int, String, ByteArray>>() // to, id, packet
            val held = listOf(mutableListOf<ByteArray>(), mutableListOf<ByteArray>())
            val sent = HashMap<String, Int>()
            val got = HashSet<String>()
            fun deliver(to: Int, packet: ByteArray) {
                val phone = phones[to]
                when (val opened = Ratchet.open(phone.state, packet, phone.keys)) {
                    is Ratchet.Opened.Delivered -> {
                        val text = (Body.decode(opened.body) as Body.Text).text
                        assertEquals("from the other phone", 1 - to, sent[text])
                        assertTrue("delivered once: $text", got.add(text))
                        // Packets waiting for their key may open now.
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
                    if (random.nextInt(5) > 0) flight.remove(pick) // else it may come again: a duplicate
                }
            }
            flight.shuffle(random)
            for ((to, _, packet) in flight) if (!lossy || random.nextInt(4) > 0) deliver(to, packet)
            if (!lossy) assertEquals("run $run: all arrive without loss", sent.keys, got)
            delivered += got.size
        }
        assertTrue(delivered > 50_000)
    }
}

package org.phonemesh.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig.RebroadcastMode
import org.phonemesh.core.wire.HubAck
import org.phonemesh.hub.Packets.code
import org.phonemesh.hub.Packets.message

class HubTest {
    @get:Rule val folder = TemporaryFolder()
    private val radio = FakeRadio()
    private var now = 1_791_000_000_000L
    private val logs = ArrayList<String>()

    private fun hub(duty: Int = 10) = Hub(Mailbox(folder.root, 30), radio, duty = DutyCycle(duty), log = { logs += it }).also { it.clock = { now } }

    /** Runs the hub for [seconds], ticking every 250 ms as the real one does. */
    private fun run(hub: Hub, seconds: Int) = repeat(seconds * 4) { hub.tick(); now += 250 }

    @Test fun aPhonesPacketIsStoredThenBroadcast() {
        val hub = hub()
        assertEquals(Hub.Sent.STORED, hub.fromPhone("10.0.0.2", 301, message(1)))
        assertEquals("a resend reached the hub already", Hub.Sent.ALREADY, hub.fromPhone("10.0.0.2", 301, message(1)))
        run(hub, 3)
        assertEquals(1, radio.sent.size)
        assertEquals(301, radio.sent[0].first)
        assertArrayEquals(message(1), radio.sent[0].second)
    }

    @Test fun notPhonemeshPacketsAreRefused() {
        val hub = hub()
        assertEquals(Hub.Sent.REFUSED, hub.fromPhone("p", 302, HubAck.encode(listOf(code(1)))))
        assertEquals(Hub.Sent.REFUSED, hub.fromPhone("p", 1, message(1)))
        assertEquals(Hub.Sent.REFUSED, hub.fromPhone("p", 301, ByteArray(5)))
        run(hub, 5)
        assertTrue(radio.sent.isEmpty())
    }

    @Test fun phonesTakeTurnsOnTheRadio() {
        val hub = hub()
        repeat(10) { hub.fromPhone("busy", 301, message(it)) }
        hub.fromPhone("quiet", 301, message(100))
        run(hub, 8)
        val order = radio.sent.map { it.second[1].toInt() }
        assertEquals("the quiet phone goes second, not eleventh", listOf(0, 100), order.take(2))
    }

    @Test fun theRadioNeverGoesPastTheDutyCycle() {
        val hub = hub(duty = 1) // 36 s an hour
        repeat(Hub.MAX_QUEUED) { hub.fromPhone("p$it", 301, message(it, 228)) }
        run(hub, 3600)
        val millis = Airtime().millis(228)
        val sent = radio.sent.size
        assertTrue("$sent packets of $millis ms within 36 s", sent * millis <= 36_000 && (sent + 1) * millis > 36_000)
        run(hub, 600)
        assertTrue("budget comes back as the hour moves on", radio.sent.size > sent)
    }

    @Test fun aPhoneCannotFillTheQueueForever() {
        val hub = hub()
        repeat(Hub.MAX_QUEUED) { assertEquals(Hub.Sent.STORED, hub.fromPhone("p", 301, message(it))) }
        assertEquals(Hub.Sent.BUSY, hub.fromPhone("p", 301, message(Hub.MAX_QUEUED)))
        assertEquals(Hub.Sent.STORED, hub.fromPhone("other", 301, message(Hub.MAX_QUEUED)))
    }

    @Test fun heardMessagesAreAcknowledgedInBatches() {
        val hub = hub()
        repeat(30) { hub.onData(301, message(it), "snr=7") }
        hub.onData(301, message(0), "a relay's copy")
        run(hub, 6)
        val acks = radio.sent.filter { it.first == HubAck.PORT }
        assertEquals("25 codes go at once", 1, acks.size)
        assertEquals((0 until 25).map { Mailbox.hex(code(it)) }, HubAck.decode(acks[0].second)!!.map { Mailbox.hex(it) })
        run(hub, 30)
        val later = radio.sent.filter { it.first == HubAck.PORT }
        assertEquals("the other 5 after 30 s", 2, later.size)
        assertEquals(5, HubAck.decode(later[1].second)!!.size)
    }

    @Test fun hellosAndSharedPostsAreKeptButNotAcknowledged() {
        val hub = hub()
        hub.onData(301, Packets.hello(1), "")
        hub.onData(303, Packets.shared("bonjour"), "")
        run(hub, 40)
        assertTrue(radio.sent.isEmpty())
    }

    @Test fun aRadioThatDoesNotRelayOurPortsIsReported() {
        val hub = hub()
        hub.onRebroadcastMode(RebroadcastMode.ALL)
        assertTrue(logs.none { "WARNING" in it })
        hub.onRebroadcastMode(RebroadcastMode.LOCAL_ONLY)
        assertTrue(logs.any { "WARNING" in it && "LOCAL_ONLY" in it })
    }
}

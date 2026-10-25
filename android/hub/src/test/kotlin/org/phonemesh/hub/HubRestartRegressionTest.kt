package org.phonemesh.hub

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** File-backed engine recreation, not a power-cut/real-device test. Compiles on the baseline too. */
class HubRestartRegressionTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_791_000_000_000L
    private val mailDir get() = File(folder.root, "mail")

    private fun open(radio: Radio, percent: Int = 10): Hub {
        val mailbox = Mailbox(mailDir, 30)
        // Baseline has no durable constructor: deliberately exercise its existing in-memory composition,
        // so its failure is the absent broadcast/budget invariant, NOT compilation or ClassNotFound.
        val type = try { Class.forName("org.phonemesh.hub.Outbox") } catch (_: ClassNotFoundException) { null }
        val hub = if (type == null) Hub(mailbox, radio, duty = DutyCycle(percent)) else {
            val outbox = type.getConstructor(File::class.java).newInstance(File(folder.root, "outbox.bin"))
            val constructor = Hub::class.java.constructors.single { it.parameterTypes.size == 6 && it.parameterTypes.last() == type }
            constructor.newInstance(mailbox, radio, Airtime(), DutyCycle(percent), { _: String -> }, outbox) as Hub
        }
        return hub.also { it.clock = { now } }
    }

    @Test fun acceptedInitialForwardSurvivesRestartWithoutSenderResubmission() {
        val radio = FakeRadio()
        val first = open(radio)
        assertEquals(Hub.Sent.STORED, first.fromPhone("wifi", 301, Packets.message(1)))
        assertEquals(Hub.Sent.ALREADY, first.fromPhone("ble", 301, Packets.message(1)))
        assertTrue(radio.sent.isEmpty())
        val restarted = open(radio)
        restarted.tick()
        assertEquals("accepted initial forward was lost across process recreation", 1, radio.sent.size)
        assertArrayEquals(Packets.message(1), radio.sent.single().second)
        assertEquals(1, Mailbox(mailDir, 30).size())
        now += 10_000
        open(radio).tick()
        assertEquals("completed initial forwarding must not repeat", 1, radio.sent.size)
    }

    @Test fun radioHeardAndAlreadyForwardedMailDoNotBecomeNewObligations() {
        val radio = FakeRadio()
        val first = open(radio)
        first.onData(301, Packets.hello(7), "")
        first.onData(303, Packets.shared("heard"), "")
        first.fromPhone("p", 301, Packets.message(1)); first.tick()
        now += 60_000
        val restarted = open(radio)
        restarted.tick()
        assertEquals("restart and radio-heard mail alone must create no radio work", 1, radio.sent.size)
        // Owner-approved recovery (2026-10-25): an explicit resubmission is the sender's retry, never a new mailbox row.
        assertEquals(Hub.Sent.ALREADY, restarted.fromPhone("new-address", 301, Packets.message(1)))
        restarted.tick()
        assertEquals(2, radio.sent.size)
        assertEquals(3, Mailbox(mailDir, 30).size())
    }

    @Test fun restartCannotResetRollingDutyBudget() {
        val radio = FakeRadio()
        var hub = open(radio, 1)
        val millis = Airtime().millis(228)
        var attempts = 0
        while ((attempts + 1) * millis <= 36_000) {
            assertEquals(Hub.Sent.STORED, hub.fromPhone("p", 301, Packets.message(attempts, 228)))
            hub.tick(); now += 5_000; attempts++
        }
        assertEquals(attempts, radio.sent.size)
        hub = open(radio, 1)
        assertEquals(Hub.Sent.STORED, hub.fromPhone("p", 301, Packets.message(200, 228)))
        hub.tick()
        assertEquals("process restart reset the rolling airtime budget", attempts, radio.sent.size)
        now += 3_600_000
        hub.tick()
        assertEquals(attempts + 1, radio.sent.size)
    }

    @Test fun restartPreservesPacketGap() {
        val radio = FakeRadio()
        val hub = open(radio)
        hub.fromPhone("p", 301, Packets.message(1)); hub.tick()
        val restarted = open(radio)
        restarted.fromPhone("p", 301, Packets.message(2)); restarted.tick()
        assertEquals("restart bypassed radio pacing", 1, radio.sent.size)
        now += Airtime().millis(40).toLong() + Hub.GAP_MILLIS
        restarted.tick()
        assertEquals(2, radio.sent.size)
    }
}

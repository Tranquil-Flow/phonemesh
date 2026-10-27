package org.phonemesh.hub

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sender-demanded replay of identical ciphertext. Fake clock/radio, real mailbox and restart journal. */
class HubRetryRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_791_000_000_000L
    private val mailDir get() = File(folder.root, "mail")
    private val journal get() = File(folder.root, "outbox.bin")
    private val packet get() = Packets.message(1)
    /** Shorter than the phone's first 2-minute retry, so an honest retry is never swallowed by the hub. */
    private val cooldown = 60_000L
    private fun open(radio: Radio, store: Outbox = Outbox(journal), percent: Int = 10) =
        Hub(Mailbox(mailDir, 30), radio, duty = DutyCycle(percent), outbox = store).also { it.clock = { now } }

    @Test fun completedPrivateCiphertextCanBeForwardedAgainOnLaterSenderDemand() {
        val radio = FakeRadio(); val h = open(radio)
        assertEquals(Hub.Sent.STORED, h.fromPhone("wifi", 301, packet)); h.tick()
        assertEquals(1, radio.sent.size)
        now += 120_000
        assertEquals("mail is already stored, never a second mailbox row", Hub.Sent.ALREADY, h.fromPhone("wifi", 301, packet))
        h.tick()
        assertEquals("a lost single airing must not strand a radio-only recipient", 2, radio.sent.size)
        assertArrayEquals(packet, radio.sent.last().second)
        assertEquals(1, Mailbox(mailDir, 30).size())
    }

    @Test fun cooldownIsGlobalAcrossAddressesAndSurvivesRestart() {
        val radio = FakeRadio(); var h = open(radio)
        h.fromPhone("wifi", 301, packet); h.tick()
        now += cooldown - 1; h = open(radio)
        h.fromPhone("ble-new-address", 301, packet); h.tick(); assertEquals(1, radio.sent.size)
        now++; h.fromPhone("other-address", 301, packet); h.tick(); assertEquals(2, radio.sent.size)
        now += cooldown - 1; h = open(radio)
        h.fromPhone("third-address", 301, packet); h.tick(); assertEquals("restart reset retry cooldown", 2, radio.sent.size)
        now++; h.fromPhone("fourth-address", 301, packet); h.tick(); assertEquals(3, radio.sent.size)
    }

    @Test fun noAutonomousPeriodicReplayWithoutSenderDemand() {
        val radio = FakeRadio(); val h = open(radio)
        h.fromPhone("wifi", 301, packet); h.tick()
        repeat(20) { now += 120_000; h.tick() }
        assertEquals(1, radio.sent.size)
    }

    @Test fun manyDuplicateRequestsCoalesceIntoOneDurableRetry() {
        val radio = FakeRadio(); val h = open(radio)
        h.fromPhone("wifi", 301, packet); h.tick(); now += 120_000
        repeat(20) { assertEquals(Hub.Sent.ALREADY, h.fromPhone("transport-$it", 301, packet)) }
        assertEquals(1, Outbox(journal).entries.size)
        h.tick(); assertEquals(2, radio.sent.size)
        repeat(20) { h.fromPhone("transport-$it", 301, packet); h.tick() }
        assertEquals(2, radio.sent.size)
    }

    @Test fun acceptedRetrySurvivesRestartWithoutAnotherRequest() {
        val radio = FakeRadio(); val h = open(radio)
        h.fromPhone("wifi", 301, packet); h.tick(); now += 120_000
        h.fromPhone("wifi", 301, packet)
        open(radio).tick()
        assertEquals("accepted retry intent disappeared on restart", 2, radio.sent.size)
        now += 120_000; open(radio).tick(); assertEquals(2, radio.sent.size)
    }

    @Test fun failedRetryIntentCommitDoesNotInventWorkOrRefreshMailbox() {
        val radio = FakeRadio(); val store = Outbox(journal); val h = open(radio, store)
        h.fromPhone("wifi", 301, packet); h.tick(); now += 120_000
        val originalTime = Mailbox(mailDir, 30).after(0, setOf(Mailbox.hex(Packets.code(1))), false, false).single().at
        store.beforeCommit = { throw IOException("retry intent refused") }
        try { h.fromPhone("wifi", 301, packet); fail("retry must commit before its acceptance") } catch (_: IOException) { }
        assertTrue(Outbox(journal).entries.isEmpty()); assertTrue(h.waiting().isEmpty())
        assertEquals(originalTime, Mailbox(mailDir, 30).after(0, setOf(Mailbox.hex(Packets.code(1))), false, false).single().at)
        store.beforeCommit = {}; h.fromPhone("wifi", 301, packet); h.tick(); assertEquals(2, radio.sent.size)
    }

    @Test fun oldCandidateMailboxGetsOneDemandedRetryWithoutOldAttemptMetadata() {
        Mailbox(mailDir, 30).put(301, packet, now)
        // A real version-1 journal with no pending obligation, as left by rc4 after forwarding.
        java.io.DataOutputStream(journal.outputStream()).use {
            it.writeInt(1); it.writeLong(0); it.writeInt(0); it.writeInt(0)
        }
        now += 120_000
        val radio = FakeRadio(); val h = open(radio)
        h.fromPhone("wifi", 301, packet); h.tick()
        assertEquals("upgrade stranded previously accepted ciphertext", 1, radio.sent.size)
        assertArrayEquals(packet, radio.sent.single().second)
        assertEquals(1, Mailbox(mailDir, 30).size())
    }

    @Test fun replayNeverExtendsTheOriginalThreeDayForwardingHorizon() {
        val radio = FakeRadio(); val h = open(radio)
        h.fromPhone("wifi", 301, packet); h.tick()
        now += Outbox.MAX_AGE - 1
        h.fromPhone("wifi", 301, packet); h.tick(); assertEquals(2, radio.sent.size)
        // The cooldown is satisfied here, so only the original-age ceiling can refuse.
        now += cooldown; val reopened = open(radio)
        assertEquals(Hub.Sent.ALREADY, reopened.fromPhone("wifi", 301, packet)); reopened.tick()
        assertEquals("retry restarted retention", 2, radio.sent.size)
    }

    @Test fun radioCopiesAndSharedPostsDoNotReplayButHelloFollowsSenderDemand() {
        val radio = FakeRadio(); val h = open(radio)
        h.onData(301, packet, "")
        h.fromPhone("wifi", 301, Packets.hello(2)); h.tick(); now += 5_000; h.tick()
        h.fromPhone("wifi", 303, Packets.shared("hello")); now += 5_000; h.tick()
        val privateSends = radio.sent.count { it.first == 301 }
        now += 120_000
        h.onData(301, packet, "copy")
        h.fromPhone("wifi", 301, Packets.hello(2)); h.fromPhone("wifi", 303, Packets.shared("hello"))
        // AE4: the hub's own acknowledgement may go first; give the radio time for everything queued.
        repeat(3) { h.tick(); now += 5_000 }
        assertEquals("only the demanded hello replays; radio-heard copies never start work", privateSends + 1, radio.sent.count { it.first == 301 })
        assertEquals("shared posts keep single forwarding", 1, radio.sent.count { it.first == 303 })
    }

    @Test fun restartRecoveryOfAnAiredRetryStillHonoursThePersistedCooldown() {
        // Review witness: demanded retry -> radio write succeeds -> completion commit fails -> process restarts.
        val store = Outbox(journal)
        var writes = 0
        val crashing = object : Radio {
            override fun broadcast(port: Int, payload: ByteArray) {
                writes++; if (writes == 2) store.beforeCommit = { throw IOException("crash after serial") }
            }
        }
        val h = open(crashing, store)
        h.fromPhone("wifi", 301, packet); h.tick()
        now += 120_000; h.fromPhone("wifi", 301, packet); h.tick()
        assertEquals(2, writes); assertEquals("intent survives the failed completion", 1, Outbox(journal).entries.size)
        val radio = FakeRadio()
        now += 5_000; open(radio).tick()
        assertTrue("restart bypassed the per-packet cooldown", radio.sent.isEmpty())
        now += cooldown - 5_001; open(radio).tick(); assertTrue(radio.sent.isEmpty())
        now++; val recovered = open(radio); recovered.tick()
        assertEquals("one eligible recovery after the cooldown", 1, radio.sent.size)
        now += cooldown; recovered.tick(); open(radio).tick()
        assertEquals("completed recovery never repeats without demand", 1, radio.sent.size)
    }

    @Test fun ambiguousSubmissionWaitsForNewDemandRatherThanSpinningForever() {
        var attempts = 0
        val radio = object : Radio {
            override fun broadcast(port: Int, payload: ByteArray) {
                attempts++; if (attempts == 1) throw IOException("could have reached the radio")
            }
        }
        val h = open(radio); h.fromPhone("wifi", 301, packet); h.tick()
        repeat(11) { now += 5_000; h.tick() }; assertEquals("parked, not periodically rebroadcast", 1, attempts)
        h.fromPhone("wifi", 301, packet); h.tick(); assertEquals("early demand bypassed cooldown", 1, attempts)
        now += 5_000; h.fromPhone("wifi", 301, packet); h.tick()
        assertEquals("new bounded demand must recover an uncertain submission", 2, attempts)
        assertTrue(Outbox(journal).entries.isEmpty())
    }
}

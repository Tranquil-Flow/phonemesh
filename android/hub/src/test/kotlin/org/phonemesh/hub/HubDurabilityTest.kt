package org.phonemesh.hub

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** New fault seams: integrate after baseline-compatible behavioral REDs, not as a compile-failure RED. */
class HubDurabilityTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_791_000_000_000L
    private val mailDir get() = File(folder.root, "mail")
    private val journal get() = File(folder.root, "outbox.bin")
    private fun hub(radio: Radio, box: Mailbox = Mailbox(mailDir, 30), store: Outbox = Outbox(journal), duty: DutyCycle = DutyCycle(10)) =
        Hub(box, radio, duty = duty, outbox = store).also { it.clock = { now } }

    @Test fun realPartialAppendRollsBackAndSameProcessRetryIsNotAlready() {
        for (phase in listOf("opened", "header", "body", "synced")) {
            val dir = folder.newFolder(phase)
            val box = Mailbox(dir, 30)
            box.put(301, Packets.message(1), now)
            val file = File(dir, Mailbox.day(now) + ".mail")
            val original = file.readBytes()
            box.appendCheckpoint = { if (it == phase) throw IOException("injected $phase") }
            try { box.put(301, Packets.message(2), now); fail("expected append failure") } catch (_: IOException) { }
            assertArrayEquals("rollback must restore actual file bytes ($phase)", original, file.readBytes())
            assertEquals(1, box.size()); assertEquals(1L, box.latest())
            assertEquals(1, Mailbox(dir, 30).size())
            box.appendCheckpoint = {}
            assertNotNull("failed append poisoned seen ($phase)", box.put(301, Packets.message(2), now))
            assertEquals(2, Mailbox(dir, 30).size())
            assertEquals(2L, box.latest())
        }
    }

    @Test fun failedMailboxAppendRetainsIntentButCannotAcknowledgePhantomCustody() {
        val box = Mailbox(mailDir, 30)
        box.appendCheckpoint = { if (it == "header") throw IOException("disk failure") }
        val radio = FakeRadio()
        val h = hub(radio, box)
        repeat(2) {
            try { h.fromPhone("p", 301, Packets.message(1)); fail("failed append became successful custody") }
            catch (_: IOException) { }
            assertEquals(0, box.size())
        }
        h.tick()
        assertTrue(radio.sent.isEmpty())
        assertEquals(1, Outbox(journal).entries.size)
        now += Hub.FAILURE_BACKOFF_MILLIS
        val recovered = hub(radio)
        recovered.tick()
        assertEquals(1, radio.sent.size)
        assertEquals(1, Mailbox(mailDir, 30).size())
    }

    @Test fun failedIntentCommitCannotCreateMailboxCustodyOrRadioWork() {
        val store = Outbox(journal)
        store.beforeCommit = { throw IOException("journal refused") }
        val box = Mailbox(mailDir, 30)
        val radio = FakeRadio(); val h = hub(radio, box, store)
        try { h.fromPhone("p", 301, Packets.message(1)); fail("expected intent failure") } catch (_: IOException) { }
        assertEquals(0, box.size()); assertTrue(h.waiting().isEmpty()); h.tick()
        assertTrue(radio.sent.isEmpty()); assertTrue(Outbox(journal).entries.isEmpty())
        store.beforeCommit = {}
        assertEquals(Hub.Sent.STORED, h.fromPhone("p", 301, Packets.message(1)))
    }

    @Test fun failedReservationCommitPreventsSerialWriteAndRetriesWithBackoff() {
        val store = Outbox(journal); val radio = FakeRadio(); val h = hub(radio, store = store)
        h.fromPhone("p", 301, Packets.message(1))
        store.beforeCommit = { throw IOException("reservation refused") }
        h.tick(); assertTrue(radio.sent.isEmpty())
        store.beforeCommit = {}
        now += Hub.FAILURE_BACKOFF_MILLIS - 1; h.tick(); assertTrue(radio.sent.isEmpty())
        now++; h.tick(); assertEquals(1, radio.sent.size)
    }

    @Test fun knownNonSubmissionRetriesAfterBackoffWithoutLosingOtherPhones() {
        var refused = false
        val sent = ArrayList<ByteArray>()
        val radio = object : Radio {
            override fun broadcast(port: Int, payload: ByteArray) {
                if (!refused) { refused = true; throw RadioNotSubmittedException("no byte submitted") }
                sent += payload.copyOf()
            }
        }
        val h = hub(radio)
        h.fromPhone("first", 301, Packets.message(1)); h.fromPhone("second", 301, Packets.message(2))
        h.tick(); assertEquals(2, h.waiting().values.sum())
        now += Hub.FAILURE_BACKOFF_MILLIS - 1; h.tick()
        assertTrue("no immediate serial retry", sent.isEmpty())
        now++; h.tick()
        assertArrayEquals("next phone gets its turn", Packets.message(2), sent.single())
        now += Hub.FAILURE_BACKOFF_MILLIS; h.tick()
        assertEquals(2, sent.size); assertArrayEquals(Packets.message(1), sent.last())
    }

    @Test fun ambiguousWriteIsParkedNotPeriodicallyRebroadcastAndRecoversOnceOnRestart() {
        val observed = ArrayList<ByteArray>()
        val ambiguous = object : Radio {
            override fun broadcast(port: Int, payload: ByteArray) {
                observed += payload.copyOf(); throw IOException("could have reached serial")
            }
        }
        val h = hub(ambiguous)
        h.fromPhone("p", 301, Packets.message(1)); h.tick()
        repeat(20) { now += 5_000; h.tick() }
        assertEquals(1, observed.size); assertEquals(1, h.waiting().values.sum())
        assertEquals(Hub.Sent.ALREADY, h.fromPhone("other-transport", 301, Packets.message(1)))
        val radio = FakeRadio(); hub(radio).tick()
        assertEquals(1, radio.sent.size)
        now += 5_000; hub(radio).tick()
        assertEquals("successful recovery must complete durable intent", 1, radio.sent.size)
    }

    @Test fun completionFailureRetriesOnlyCommitInSameProcess() {
        val store = Outbox(journal)
        val radio = object : Radio {
            var writes = 0
            override fun broadcast(port: Int, payload: ByteArray) {
                writes++; store.beforeCommit = { throw IOException("completion refused") }
            }
        }
        val h = hub(radio, store = store)
        h.fromPhone("p", 301, Packets.message(1)); h.tick()
        repeat(3) { now += Hub.FAILURE_BACKOFF_MILLIS; h.tick() }
        assertEquals(1, radio.writes); assertEquals(1, Outbox(journal).entries.size)
        store.beforeCommit = {}; now += Hub.FAILURE_BACKOFF_MILLIS; h.tick()
        assertEquals(1, radio.writes); assertTrue(Outbox(journal).entries.isEmpty())
    }

    @Test fun crashAfterSerialBeforeCompletionHasOneChargedRecoveryAttempt() {
        val store = Outbox(journal)
        val radio = object : Radio {
            var writes = 0
            override fun broadcast(port: Int, payload: ByteArray) {
                writes++; store.beforeCommit = { throw IOException("crash boundary") }
            }
        }
        val h = hub(radio, store = store)
        h.fromPhone("p", 301, Packets.message(1)); h.tick()
        assertEquals(1, radio.writes)
        val persisted = Outbox(journal)
        assertEquals(1, persisted.reservations.size)
        val good = FakeRadio(); val restarted = hub(good)
        restarted.tick(); assertTrue("restart must preserve pacing", good.sent.isEmpty())
        // Decision 24 (2026-10-25): recovery of a possibly-aired write also waits out the per-packet cooldown.
        now += 5_000; restarted.tick(); assertTrue("restart bypassed the per-packet cooldown", good.sent.isEmpty())
        now += Hub.RETRY_COOLDOWN_MILLIS - 5_000; restarted.tick()
        assertEquals(1, good.sent.size)
        assertEquals("both possible writes consume airtime", 2, Outbox(journal).reservations.size)
        now += 5_000; hub(good).tick(); assertEquals(1, good.sent.size)
    }

    @Test fun exactDigestIsGlobalAndDuplicateDoesNotRefreshMailboxRetention() {
        val box = Mailbox(mailDir, 30); val radio = FakeRadio(); val h = hub(radio, box)
        val bytes = Packets.message(1)
        assertEquals(Hub.Sent.STORED, h.fromPhone("wifi", 301, bytes))
        now += 10_000
        repeat(20) { assertEquals(Hub.Sent.ALREADY, h.fromPhone("ble-$it", 301, bytes)) }
        val variant = bytes.copyOf().also { it[it.lastIndex] = 99 }
        assertEquals(Hub.Sent.STORED, h.fromPhone("ble", 301, variant))
        assertEquals(2, Outbox(journal).entries.size); assertEquals(2, box.size())
        val records = box.after(0, setOf(Mailbox.hex(Packets.code(1))), false, false)
        assertEquals(10_000L, records[1].at - records[0].at)
        h.tick(); now += 5_000; h.tick(); assertEquals(2, radio.sent.size)
    }

    @Test fun fullQueueDuplicateIsNonMutatingButNewPacketIsBusy() {
        val h = hub(FakeRadio())
        repeat(Hub.MAX_QUEUED) { h.fromPhone("p", 301, Packets.message(it)) }
        assertEquals(Hub.Sent.ALREADY, h.fromPhone("p", 301, Packets.message(1)))
        assertEquals(Hub.Sent.BUSY, h.fromPhone("p", 301, Packets.message(200)))
        assertEquals(Hub.MAX_QUEUED, h.waiting().values.sum())
    }

    @Test fun initialIntentExpiresAtExistingSenderHorizonNotMailboxHorizon() {
        val radio = FakeRadio(); val h = hub(radio)
        h.fromPhone("p", 301, Packets.message(1))
        now += Outbox.MAX_AGE
        hub(radio).tick()
        assertTrue(radio.sent.isEmpty()); assertTrue(Outbox(journal).entries.isEmpty())
        assertEquals(1, Mailbox(mailDir, 30).size())
    }
}

package org.phonemesh.ble

import org.junit.Assert.*
import org.junit.Test

/** The wait for Android's pairing, driven through its seams: a fake clock, a fake bond state, no Bluetooth. */
class PairingWaitTest {
    private companion object {
        const val NONE = 10
        const val BONDING = 11
        const val BONDED = 12
    }

    private class Rig(var bond: Int, var pairStarts: Boolean = true, limitMs: Long? = null) {
        val timers = mutableListOf<Pair<Runnable, Long>>()
        var pairRequests = 0
        var opens = 0
        val failures = mutableListOf<String>()
        val told = mutableListOf<Boolean>()
        private val later = { task: Runnable, delay: Long -> timers.add(task to delay); Unit }
        private val cancel = { task: Runnable -> timers.removeAll { it.first === task }; Unit }
        private val pair = { pairRequests++; if (pairStarts) bond = BONDING; pairStarts }
        val wait = if (limitMs == null) {
            PairingWait(later, cancel, { bond }, pair, { opens++ }, { failures.add(it) }, { told.add(it) })
        } else {
            PairingWait(later, cancel, { bond }, pair, { opens++ }, { failures.add(it) }, { told.add(it) }, limitMs)
        }
        /** The transport's operation queue, wired to the wait exactly as the transport wires it. */
        val queue = SafeGattQueue(later, cancel, { wait.linkFailed(it) })
        fun change(state: Int) { bond = state; wait.onBond(state) }
        fun fire(delay: Long) = timers.single { it.second == delay }.first.run()
    }

    @Test fun anUnpairedRadioIsPairedThenConnectedFromOneConnectCall() {
        val rig = Rig(NONE)
        rig.wait.begin()
        assertEquals("pairing is asked for once", 1, rig.pairRequests)
        assertEquals("nothing connects before pairing ends", 0, rig.opens)
        assertTrue(rig.wait.waiting)
        rig.change(BONDING)
        assertEquals(0, rig.opens)
        rig.change(BONDED)
        assertEquals("the same attempt carries on by itself", 1, rig.opens)
        assertFalse(rig.wait.waiting)
        assertEquals(listOf(true, false), rig.told)
        assertTrue(rig.failures.isEmpty())
        assertTrue("its time limit is gone", rig.timers.isEmpty())
        rig.change(BONDED)
        assertEquals("a repeated report connects nothing more", 1, rig.opens)
        assertEquals(1, rig.pairRequests)
    }

    @Test fun pairingThatOutlivesTheUsualTimeoutsDoesNotFail() {
        val rig = Rig(NONE)
        rig.wait.begin()
        assertEquals("the person gets ninety seconds", listOf(90_000L), rig.timers.map { it.second })
        // A step that would end the attempt on a paired radio: the 10 s operation timeout, the 30 s deadline, a refusal.
        assertTrue(rig.queue.enqueue("writeDescriptor") { true })
        rig.fire(10_000L)
        rig.wait.linkFailed("Radio connection/config timed out")
        rig.wait.linkFailed("GATT descriptor failed: status=5")
        assertTrue("nothing failed while pairing", rig.failures.isEmpty())
        assertTrue(rig.wait.waiting)
        assertEquals(listOf(true), rig.told)
        rig.change(BONDED)
        assertEquals(1, rig.opens)
        assertTrue(rig.failures.isEmpty())
    }

    @Test fun refusedPairingFailsOnce() {
        val rig = Rig(NONE)
        rig.wait.begin()
        rig.change(BONDING)
        rig.change(NONE)
        assertEquals(1, rig.failures.size)
        assertEquals(0, rig.opens)
        assertFalse(rig.wait.waiting)
        assertEquals(listOf(true, false), rig.told)
        assertTrue(rig.timers.isEmpty())
        rig.change(NONE)
        rig.change(BONDED)
        assertEquals("later reports change nothing", 1, rig.failures.size)
        assertEquals(0, rig.opens)
    }

    @Test fun pairingThatIsNeverAnsweredFailsOnceAtItsLimit() {
        val rig = Rig(NONE, limitMs = 500L)
        rig.wait.begin()
        rig.change(BONDING)
        rig.fire(500L)
        assertEquals(1, rig.failures.size)
        assertFalse(rig.wait.waiting)
        assertEquals(listOf(true, false), rig.told)
        rig.change(BONDED)
        assertEquals("too late to carry on", 0, rig.opens)
        assertEquals(1, rig.failures.size)
    }

    @Test fun aPairedRadioConnectsAtOnceAndKeepsEveryTimeout() {
        val rig = Rig(BONDED)
        rig.wait.begin()
        assertEquals(1, rig.opens)
        assertEquals("no pairing is asked for", 0, rig.pairRequests)
        assertFalse(rig.wait.waiting)
        assertTrue("the app is told nothing", rig.told.isEmpty())
        assertTrue("no pairing limit runs", rig.timers.isEmpty())
        // The operation timeout is fatal after its usual 10 s, as before.
        assertTrue(rig.queue.enqueue("writeDescriptor") { true })
        assertEquals(listOf(10_000L), rig.timers.map { it.second })
        rig.fire(10_000L)
        assertEquals(listOf("GATT operation timed out: writeDescriptor"), rig.failures)
        rig.wait.linkFailed("GATT descriptor failed: status=5")
        assertEquals(2, rig.failures.size)
        // Reports about the bond of a radio that is not being paired start nothing.
        rig.change(BONDED)
        rig.change(NONE)
        assertEquals(1, rig.opens)
        assertEquals(2, rig.failures.size)
    }

    @Test fun pairingAlreadyUnderWayIsWaitedForWithoutAskingAgain() {
        val rig = Rig(BONDING)
        rig.wait.begin()
        assertEquals(0, rig.pairRequests)
        assertTrue(rig.wait.waiting)
        rig.change(BONDED)
        assertEquals(1, rig.opens)
    }

    @Test fun whenPairingCannotBeAskedForItConnectsAndWaitsIfAndroidPairsByItself() {
        val rig = Rig(NONE, pairStarts = false)
        rig.wait.begin()
        assertEquals("connects as it always did", 1, rig.opens)
        assertFalse(rig.wait.waiting)
        // Android starts pairing during the connection's first protected step.
        assertTrue(rig.queue.enqueue("writeDescriptor") { true })
        rig.change(BONDING)
        assertTrue(rig.wait.waiting)
        rig.fire(10_000L)
        rig.wait.linkFailed("BLE radio disconnected: status=22")
        assertTrue(rig.failures.isEmpty())
        rig.change(BONDED)
        assertEquals("one fresh connection, by itself", 2, rig.opens)
        assertEquals(listOf(true, false), rig.told)
        rig.wait.linkFailed("GATT descriptor failed: status=5")
        assertEquals("after pairing, failures count again", 1, rig.failures.size)
    }

    @Test fun endingTheAttemptStopsTheWaitQuietly() {
        val rig = Rig(NONE)
        rig.wait.begin()
        rig.wait.end()
        assertFalse(rig.wait.waiting)
        assertTrue(rig.timers.isEmpty())
        assertEquals(listOf(true, false), rig.told)
        rig.change(BONDED)
        rig.change(NONE)
        assertEquals(0, rig.opens)
        assertTrue(rig.failures.isEmpty())
    }
}

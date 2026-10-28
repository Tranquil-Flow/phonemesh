package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.phonemesh.core.update.TestCatalogs.pack

/**
 * A crash at every store write, torn at every byte (U3 acceptance): after reopening, the state is exactly the one before the
 * write or exactly the one after it, never a mixture, and the interrupted step can simply be done again.
 */
class UpdateTrustCrashTest {
    private val roots get() = TestCatalogs.roots
    private val v2 = pack("voice-fr-siwis", 2)
    private val v3 = pack("voice-fr-siwis", 3)
    private val engine = pack("engine-whisper-tiny", 1, UpdateMetadata.ENGINE)

    // Signed once: signing is randomised, and a repeated step must carry the very same bytes.
    private val c2 = TestCatalogs.catalog(1, 2, listOf(v2, engine))
    private val c3 = TestCatalogs.catalog(1, 3, listOf(v3, engine), listOf(v2.digest))
    private val p2 = TestCatalogs.policy(2, TestKeys.catalog2, 2, minSequence = 1)
    private val e2c1 = TestCatalogs.catalog(2, 1, listOf(pack("voice-fr-siwis", 4), engine), key = TestKeys.catalog2, keyId = 2)
    private val p5 = TestCatalogs.policy(5, TestKeys.catalog3, 3, minSequence = 10)

    /** The steps of one phone's life; step 0 is the first start (the baseline write). */
    private val steps: List<Pair<String, (UpdateTrust) -> Unit>> = listOf(
        "first start" to { _ -> },
        "catalog 2" to { t -> t.acceptCatalog(c2) },
        "install v2" to { t -> t.recordInstalled(v2) },
        "catalog 3 revokes v2" to { t -> t.acceptCatalog(c3) },
        "install v3" to { t -> t.recordInstalled(v3) },
        "recovery epoch 2" to { t -> t.acceptRecovery(p2) },
        "catalog of epoch 2" to { t -> t.acceptCatalog(e2c1) },
        "recovery epoch 5" to { t -> t.acceptRecovery(p5) },
    )

    private fun open(store: TrustStore) = UpdateTrust.open(store, roots, 3, TestCatalogs.baseline, allowTestRoots = true)
    private fun trust(store: TrustStore) = (open(store) as UpdateTrust.Opened.Ready).trust

    @Test fun everyWriteTornAtEveryByteLeavesTheOldOrTheNewStateAndCanBeRepeated() {
        // The uninterrupted run: the store after each step and the state it holds.
        val store = MemoryTrustStore()
        val after = ArrayList<MemoryTrustStore>(); val states = ArrayList<ByteArray>()
        var t: UpdateTrust? = null
        for ((index, step) in steps.withIndex()) {
            if (index == 0) t = trust(store) else step.second(t!!)
            after += store.copy(); states += t!!.state.encode()
        }
        val writes = store.saves.size
        assertTrue("every step writes once", writes == steps.size)
        var tried = 0
        for (i in steps.indices) {
            val before = if (i == 0) MemoryTrustStore() else after[i - 1]
            val (slot, bytes) = store.saves[i]
            val oldState: ByteArray? = if (i == 0) null else states[i - 1]
            for (mode in 0..1) for (p in 0..bytes.size) {
                val torn = before.copy()
                val old = torn.slots[slot]
                // mode 0 overwrites in place (new prefix over the old bytes); mode 1 truncates and then writes the prefix.
                torn.slots[slot] = if (mode == 0) bytes.copyOf(p) + (old?.let { if (it.size > p) it.copyOfRange(p, it.size) else ByteArray(0) } ?: ByteArray(0)) else bytes.copyOf(p)
                val reopened = open(torn)
                if (reopened !is UpdateTrust.Opened.Ready) fail("step $i mode $mode prefix $p: $reopened")
                val state = (reopened as UpdateTrust.Opened.Ready).trust.state.encode()
                val isOld = oldState != null && state.contentEquals(oldState)
                val isNew = state.contentEquals(states[i])
                assertTrue("step '${steps[i].first}' mode $mode prefix $p/${bytes.size}: neither the old nor the new state", isOld || isNew)
                // Repeat the interrupted step from whatever was found: the end state is the new one.
                if (p % 211 == 0 || p == bytes.size || p == bytes.size - 1) {
                    val again = (reopened as UpdateTrust.Opened.Ready).trust
                    if (i > 0) steps[i].second(again)
                    assertArrayEquals("step '${steps[i].first}' prefix $p repeated", states[i], again.state.encode())
                }
                tried++
            }
        }
        assertTrue("tried $tried torn writes", tried > 5_000)
    }

    @Test fun aCrashThrownFromTheStoreLeavesTheOldStateInMemoryUnusableAndTheNewOneNeverHalfThere() {
        val store = MemoryTrustStore(); val t = trust(store)
        val before = t.state.encode()
        store.tear = { _, bytes, _ -> bytes.copyOf(bytes.size / 2) }
        try { t.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(v2, engine))); fail("expected the crash") } catch (_: MemoryTrustStore.Crash) { }
        val reopened = trust(store)
        assertArrayEquals(before, reopened.state.encode())
    }

    @Test fun anInstallThatFinishedBeforeTheFloorWasSavedIsNotLostForever() {
        // The pack files are on disk but the floor write crashed: the floor is still 0, and recording it again is allowed.
        val store = MemoryTrustStore(); val t = trust(store)
        t.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(v2, engine)))
        store.tear = { _, bytes, _ -> bytes.copyOf(7) }
        try { t.recordInstalled(v2); fail() } catch (_: MemoryTrustStore.Crash) { }
        val again = trust(store)
        org.junit.Assert.assertEquals(0L, again.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertTrue(again.recordInstalled(v2))
        org.junit.Assert.assertEquals(2L, again.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
    }
}

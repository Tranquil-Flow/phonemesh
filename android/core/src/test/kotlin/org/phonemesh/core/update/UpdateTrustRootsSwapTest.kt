package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.update.TestCatalogs.pack
import org.phonemesh.core.update.UpdateTrust.Reason

/**
 * U7-2 (a state records the roots it was made under; new roots start fresh), U7-4 (a policy that would not read back is
 * refused), U7-12 (a damaged newest slot is reported), U7-13 (burnt keys do not come back).
 */
class UpdateTrustRootsSwapTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val testRoots get() = TestCatalogs.roots
    private val realRoots by lazy {
        UpdateRoots.parse("catalog key_id=1 public_key=${hex(TestKeys.catalog2.compressed)}\nrecovery key_id=100 public_key=${hex(TestKeys.catalog3.compressed)}\n")
    }
    private val realBaseline by lazy { TestCatalogs.catalog(1, 1, TestCatalogs.baselineEntries, key = TestKeys.catalog2) }

    private fun ready(r: UpdateTrust.Opened) = (r as UpdateTrust.Opened.Ready)

    @Test fun aStateMadeUnderTestRootsStartsFreshUnderNewRootsAndNeverKeepsTheTestCatalogKey() {
        val store = MemoryTrustStore()
        val old = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        val v2 = pack("voice-fr-siwis", 2)
        old.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(v2))); old.recordInstalled(v2)
        val opened = ready(UpdateTrust.open(store, realRoots, 4, realBaseline, allowTestRoots = true))
        assertTrue(opened.startedFresh)
        val t = opened.trust
        assertArrayEquals("the catalog key is the new one", TestKeys.catalog2.public, t.state.key)
        assertEquals(1L, t.state.sequence); assertTrue("floors of the old roots are not carried over", t.state.floors.isEmpty())
        assertTrue("newer than both old slots", t.state.generation > old.state.generation)
        val forged = TestCatalogs.catalog(1, 2, listOf(pack("voice-fr-siwis", 9, seed = "evil")), key = TestKeys.catalog)
        assertEquals(Reason.NOT_SIGNED, (t.acceptCatalog(forged) as UpdateTrust.CatalogResult.Refused).reason)
        val good = TestCatalogs.catalog(1, 2, listOf(pack("voice-fr-siwis", 3)), key = TestKeys.catalog2)
        assertTrue(t.acceptCatalog(good) is UpdateTrust.CatalogResult.Accepted)
        // The next start reads the new state; it is not "fresh" again.
        val again = ready(UpdateTrust.open(store, realRoots, 4, realBaseline, allowTestRoots = true))
        assertFalse(again.startedFresh)
        assertEquals(2L, again.trust.state.sequence)
    }

    @Test fun newRootsWithAStaleBaselineGiveABaselineProblemAndKeepEverythingAsItWas() {
        val store = MemoryTrustStore()
        ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true))
        val before = store.slots.map { it?.toList() }
        // New public-keys.txt, but baseline-catalog.pmup still signed by the TEST key: the build is wrong, not the saved data.
        val r = UpdateTrust.open(store, realRoots, 4, TestCatalogs.baseline, allowTestRoots = true)
        assertTrue("$r", r is UpdateTrust.Opened.BadBaseline)
        assertEquals(before, store.slots.map { it?.toList() })
    }

    @Test fun aSavedStateOfAnOlderFormatBelongsToRootsByTheKeyItHolds() {
        val store = MemoryTrustStore()
        val t = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        // Rewrite as a schema 1 record (no roots name): what a phone had before this change.
        val legacy = t.state.copy(rootsId = null)
        store.slots[(legacy.generation % 2).toInt()] = legacy.encode()
        assertNull(TrustState.decode(store.slots[(legacy.generation % 2).toInt()]!!)!!.rootsId)
        val same = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true))
        assertFalse("same key: kept", same.startedFresh)
        assertEquals(1L, same.trust.state.sequence)
        assertArrayEquals("and now named", testRoots.id, same.trust.state.rootsId)
        // The same legacy record under other roots: fresh.
        store.slots[(legacy.generation % 2).toInt()] = legacy.encode()
        store.slots[((legacy.generation + 1) % 2).toInt()] = null
        val other = ready(UpdateTrust.open(store, realRoots, 4, realBaseline, allowTestRoots = true))
        assertTrue(other.startedFresh)
        assertArrayEquals(TestKeys.catalog2.public, other.trust.state.key)
    }

    @Test fun aLegacyStateStartedByARecoveryPolicyStaysOnlyWhileTheRecoveryKeyIsTheSame() {
        val store = MemoryTrustStore()
        val t = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        assertTrue(t.acceptRecovery(TestCatalogs.policy(2, TestKeys.catalog2, 2)) is UpdateTrust.RecoveryResult.Accepted)
        val legacy = t.state.copy(rootsId = null)
        val slot = (legacy.generation % 2).toInt()
        store.slots[slot] = legacy.encode()
        assertFalse(ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).startedFresh)
        store.slots[slot] = legacy.encode()
        assertTrue(ready(UpdateTrust.open(store, realRoots, 4, realBaseline, allowTestRoots = true)).startedFresh)
    }

    @Test fun aRecoveryPolicyThatNamesCatalogKeyIdZeroIsRefusedBeforeAnythingIsSaved() {
        val store = MemoryTrustStore()
        val t = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        val writes = store.saves.size
        val r = t.acceptRecovery(TestCatalogs.policy(2, TestKeys.catalog2, newKeyId = 0, minSequence = 1))
        assertEquals(Reason.MALFORMED, (r as UpdateTrust.RecoveryResult.Refused).reason)
        assertEquals(1L, t.state.epoch); assertEquals(writes, store.saves.size)
        // And the old key still rules, now and after a restart (P2).
        assertTrue(t.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(pack("voice-fr-siwis", 2)))) is UpdateTrust.CatalogResult.Accepted)
        // A good id works and survives a restart.
        assertTrue(t.acceptRecovery(TestCatalogs.policy(2, TestKeys.catalog2, newKeyId = 2, minSequence = 1)) is UpdateTrust.RecoveryResult.Accepted)
        assertArrayEquals("the new epoch's state still names its roots", testRoots.id, t.state.rootsId)
        val re = ready(UpdateTrust.open(store.copy(), testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        assertEquals(2L, re.state.epoch); assertEquals(2, re.state.keyId)
    }

    @Test fun aDamagedNewestSlotOnAtomicStorageIsReportedNotSilentlyRolledBack() {
        val store = MemoryTrustStore().also { it.atomic = true }
        val t = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        val v2 = pack("voice-fr-siwis", 2)
        t.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(v2))); t.recordInstalled(v2)
        val newest = (t.state.generation % 2).toInt()
        store.slots[newest] = store.slots[newest]!!.also { it[40] = (it[40] + 1).toByte() }
        val writes = store.saves.size
        val r = UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)
        assertTrue("$r", r is UpdateTrust.Opened.Corrupt)
        assertEquals("nothing was reset or replaced", writes, store.saves.size)
    }

    @Test fun aDamagedOlderSlotOnAtomicStorageIsAlsoReported() {
        val store = MemoryTrustStore().also { it.atomic = true }
        val t = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        t.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(pack("voice-fr-siwis", 2))))
        val older = ((t.state.generation + 1) % 2).toInt()
        store.slots[older] = byteArrayOf(1, 2, 3)
        assertTrue(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true) is UpdateTrust.Opened.Corrupt)
    }

    @Test fun aTornWriteOnStorageThatCanTearStillOpensTheLastWholeState() {
        val store = MemoryTrustStore()
        val t = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        val v2 = pack("voice-fr-siwis", 2)
        t.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(v2))); t.recordInstalled(v2)
        val newest = (t.state.generation % 2).toInt()
        store.slots[newest] = store.slots[newest]!!.also { it[40] = (it[40] + 1).toByte() }
        val r = ready(UpdateTrust.open(store, testRoots, 3, TestCatalogs.baseline, allowTestRoots = true))
        assertEquals("the state before the torn save", t.state.generation - 1, r.trust.state.generation)
    }

    @Test fun aBurntCatalogKeyCannotBeAdoptedByAReplayedRecoveryPolicy() {
        val burntRoots = UpdateRoots.parse("# status: TEST\ncatalog key_id=1 public_key=${hex(TestKeys.catalog.compressed)}\n" +
            "recovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\nburnt public_key=${hex(TestKeys.catalog2.compressed)}\n")
        val t = ready(UpdateTrust.open(MemoryTrustStore(), burntRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        val replay = TestCatalogs.policy(2, TestKeys.catalog2, newKeyId = 2)
        assertEquals(Reason.BAD_KEY, (t.acceptRecovery(replay) as UpdateTrust.RecoveryResult.Refused).reason)
        assertEquals(1L, t.state.epoch)
        // An unburnt key is fine.
        assertTrue(t.acceptRecovery(TestCatalogs.policy(3, TestKeys.catalog3, newKeyId = 3)) is UpdateTrust.RecoveryResult.Accepted)
    }

    @Test fun anOlderRecoveryPolicyIsRefusedOnceAHigherEpochIsHeld() {
        val t = ready(UpdateTrust.open(MemoryTrustStore(), testRoots, 3, TestCatalogs.baseline, allowTestRoots = true)).trust
        assertTrue(t.acceptRecovery(TestCatalogs.policy(3, TestKeys.catalog3, newKeyId = 3)) is UpdateTrust.RecoveryResult.Accepted)
        val replay = TestCatalogs.policy(2, TestKeys.catalog2, newKeyId = 2)
        assertEquals(Reason.NOT_NEWER_EPOCH, (t.acceptRecovery(replay) as UpdateTrust.RecoveryResult.Refused).reason)
        assertEquals(3L, t.state.epoch)
    }
}

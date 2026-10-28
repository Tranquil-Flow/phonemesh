package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.update.TestCatalogs.pack
import org.phonemesh.core.update.UpdateTrust.Reason

/** The phone's trust state (U3): baseline, catalogs, anti-rollback, floors and the recovery key path. */
class UpdateTrustTest {
    private val roots get() = TestCatalogs.roots
    private fun open(store: TrustStore = MemoryTrustStore(), reader: Long = 3): UpdateTrust =
        (UpdateTrust.open(store, roots, reader, TestCatalogs.baseline, allowTestRoots = true) as UpdateTrust.Opened.Ready).trust

    private fun cat(seq: Long, vararg e: UpdateMetadata.Entry, epoch: Long = 1, revoked: List<ByteArray> = emptyList(), minReader: Long = 1,
        key: TestKeys.Pair = TestKeys.catalog, keyId: Int = 1) = TestCatalogs.catalog(epoch, seq, e.toList(), revoked, minReader, key, keyId)

    private fun accepted(r: UpdateTrust.CatalogResult) = r is UpdateTrust.CatalogResult.Accepted
    private fun why(r: UpdateTrust.CatalogResult) = (r as UpdateTrust.CatalogResult.Refused).reason

    @Test fun aFreshInstallTrustsOnlyThePackagedBaseline() {
        val store = MemoryTrustStore()
        val t = open(store)
        assertEquals(1L, t.state.epoch); assertEquals(1L, t.state.sequence); assertEquals(1, t.state.keyId)
        assertArrayEquals(TestKeys.catalog.public, t.state.key)
        assertArrayEquals(TestCatalogs.baseline, t.state.catalog)
        assertNull(t.state.policy); assertTrue(t.state.floors.isEmpty())
        assertEquals("one write", 1, store.saves.size)
        val again = open(store)
        assertArrayEquals(t.state.encode(), again.state.encode())
        assertEquals("opening again writes nothing", 1, store.saves.size)
    }

    @Test fun aBaselineThatDoesNotVerifyIsNotTrusted() {
        val other = TestCatalogs.catalog(1, 1, TestCatalogs.baselineEntries, key = TestKeys.catalog2)
        for (bad in listOf(other, TestCatalogs.baseline.copyOf().also { it[40] = (it[40] + 1).toByte() }, TestCatalogs.policy(2, TestKeys.catalog2), ByteArray(5))) {
            val store = MemoryTrustStore()
            assertTrue(UpdateTrust.open(store, roots, 3, bad, allowTestRoots = true) is UpdateTrust.Opened.BadBaseline)
            assertTrue("nothing written", store.saves.isEmpty())
        }
        // A baseline of another epoch than 1 or with a lower reader than the app's own is also a build mistake.
        val needsNewer = TestCatalogs.catalog(1, 1, TestCatalogs.baselineEntries, minReader = 9)
        assertTrue(UpdateTrust.open(MemoryTrustStore(), roots, 3, needsNewer, allowTestRoots = true) is UpdateTrust.Opened.BadBaseline)
    }

    @Test fun aNewerCatalogIsAcceptedAndKept() {
        val store = MemoryTrustStore(); val t = open(store)
        val c2 = cat(2, pack("voice-fr-siwis", 2), pack("engine-whisper-tiny", 1, UpdateMetadata.ENGINE))
        assertTrue(accepted(t.acceptCatalog(c2)))
        assertEquals(2L, t.state.sequence); assertArrayEquals(c2, t.state.catalog)
        assertArrayEquals(c2, open(store).state.catalog)
    }

    @Test fun olderSameNumberedAndRepeatedCatalogsAreHandledExactly() {
        val store = MemoryTrustStore(); val t = open(store)
        val c5 = cat(5, pack("voice-fr-siwis", 2))
        t.acceptCatalog(c5)
        val writes = store.saves.size
        assertEquals(Reason.OLDER, why(t.acceptCatalog(cat(4, pack("voice-fr-siwis", 3)))))
        assertEquals(Reason.OLDER, why(t.acceptCatalog(TestCatalogs.baseline)))
        assertEquals("the same signed bytes again", UpdateTrust.CatalogResult.Same, t.acceptCatalog(c5))
        assertEquals("the same content signed again (a new signature)", UpdateTrust.CatalogResult.Same, t.acceptCatalog(cat(5, pack("voice-fr-siwis", 2))))
        assertEquals("same number, other content", Reason.CONFLICT, why(t.acceptCatalog(cat(5, pack("voice-fr-siwis", 2), pack("voice-en-US-ljspeech", 1)))))
        assertEquals("nothing was written by any of those", writes, store.saves.size)
        assertEquals(5L, t.state.sequence)
    }

    @Test fun onlyTheCurrentCatalogKeyInTheCurrentEpochSigns() {
        val t = open()
        assertEquals("another key", Reason.NOT_SIGNED, why(t.acceptCatalog(cat(2, pack("voice-fr-siwis", 2), key = TestKeys.catalog2))))
        assertEquals("the recovery key", Reason.NOT_SIGNED, why(t.acceptCatalog(cat(2, pack("voice-fr-siwis", 2), key = TestKeys.recovery))))
        assertEquals("the recovery key under the recovery id", Reason.NOT_SIGNED, why(t.acceptCatalog(cat(2, pack("voice-fr-siwis", 2), key = TestKeys.recovery, keyId = 100))))
        assertEquals("the right key under another id", Reason.NOT_SIGNED, why(t.acceptCatalog(cat(2, pack("voice-fr-siwis", 2), keyId = 2))))
        assertEquals("another epoch", Reason.WRONG_EPOCH, why(t.acceptCatalog(cat(2, pack("voice-fr-siwis", 2), epoch = 2))))
        assertEquals("a recovery policy is not a catalog", Reason.NOT_SIGNED, why(t.acceptCatalog(TestCatalogs.policy(2, TestKeys.catalog2))))
        assertEquals(1L, t.state.sequence)
    }

    @Test fun damagedAndCutCatalogsAreRefusedWithoutAChange() {
        val store = MemoryTrustStore(); val t = open(store)
        val good = cat(2, pack("voice-fr-siwis", 2))
        val before = t.state.encode()
        for (i in good.indices step 7) {
            val bad = good.copyOf().also { it[i] = (it[i] + 1).toByte() }
            assertFalse("byte $i", accepted(t.acceptCatalog(bad)))
        }
        for (n in listOf(0, 1, 11, 12, good.size - 65, good.size - 1)) assertFalse(accepted(t.acceptCatalog(good.copyOf(n))))
        assertFalse(accepted(t.acceptCatalog(good + 0)))
        assertArrayEquals(before, t.state.encode())
    }

    @Test fun aCatalogForANewerReaderWaitsForANewerApp() {
        val store = MemoryTrustStore(); val t = open(store, reader = 3)
        val c = cat(2, pack("voice-fr-siwis", 2), minReader = 4)
        assertEquals(Reason.NEEDS_NEWER_APP, why(t.acceptCatalog(c)))
        assertEquals(1L, t.state.sequence)
        val newer = open(store, reader = 4)
        assertTrue(accepted(newer.acceptCatalog(c)))
    }

    @Test fun theSameVersionWithOtherContentIsNotAcceptedLater() {
        val t = open()
        assertTrue(accepted(t.acceptCatalog(cat(2, pack("voice-fr-siwis", 2), pack("engine-whisper-tiny", 1, UpdateMetadata.ENGINE)))))
        val swapped = cat(3, pack("voice-fr-siwis", 2, seed = "other bytes"), pack("engine-whisper-tiny", 1, UpdateMetadata.ENGINE))
        assertEquals(Reason.CONFLICT, why(t.acceptCatalog(swapped)))
        assertEquals(2L, t.state.sequence)
    }

    @Test fun offersFollowTheSpecRules() {
        val t = open()
        val revoked = pack("voice-xx-revoked", 2, seed = "bad")
        t.acceptCatalog(cat(2,
            pack("voice-fr-siwis", 2), pack("voice-fr-siwis", 3), pack("voice-fr-siwis", 4, minApp = 9),           // the highest eligible is 3
            pack("engine-whisper-tiny", 1, UpdateMetadata.ENGINE),                                                 // not newer than the baseline: no floor, so offered unless installed
            pack("voice-new-only-api30", 1, minApi = 30), pack("voice-old-apps-only", 1, maxApp = 2), revoked, TestCatalogs.apk(5),
            revoked = listOf(revoked.digest)))
        val offers = t.offers(app = 3, sdk = 28, installed = mapOf("engine-whisper-tiny" to 1L, "voice-fr-siwis" to 1L))
        assertEquals(listOf("voice-fr-siwis"), offers.map { it.entry.id })
        assertEquals(3L, offers.single().entry.version)
        assertEquals(1L, offers.single().epoch); assertEquals(2L, offers.single().sequence)
        val high = t.offers(app = 9, sdk = 34, installed = mapOf("voice-fr-siwis" to 3L, "engine-whisper-tiny" to 1L)).map { it.entry.id + it.entry.version }
        assertEquals(listOf("voice-fr-siwis4", "voice-new-only-api301"), high.sorted())
        assertTrue("no app files are offered here", t.offers(3, 28).none { it.entry.kind == UpdateMetadata.APK })
        assertTrue(t.offers(3, 28).none { it.entry.id == "voice-xx-revoked" })
    }

    @Test fun floorsOnlyGoUpAndSurviveRestarts() {
        val store = MemoryTrustStore(); val t = open(store)
        val v2 = pack("voice-fr-siwis", 2)
        t.acceptCatalog(cat(2, v2, pack("engine-whisper-tiny", 1, UpdateMetadata.ENGINE)))
        assertEquals("seeing an offer is not installing", 0L, t.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertTrue(t.recordInstalled(v2))
        assertEquals(2L, t.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertTrue("again is fine", t.recordInstalled(v2))
        assertFalse("a lower version never lowers the floor", t.recordInstalled(pack("voice-fr-siwis", 1)))
        assertFalse("the same version with other content", t.recordInstalled(pack("voice-fr-siwis", 2, seed = "other")))
        assertEquals(2L, open(store).floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        t.acceptCatalog(cat(3, pack("voice-fr-siwis", 1), v2))
        assertTrue("older and equal versions are not offered once installed", t.offers(3, 28).none { it.entry.id == "voice-fr-siwis" })
        assertTrue("the floor is kept for a pack that was removed (no installed version given)", t.offers(3, 28).none { it.entry.id == "voice-fr-siwis" })
    }

    @Test fun anOfferIsRevalidatedBeforeUse() {
        val t = open()
        val v2 = pack("voice-fr-siwis", 2)
        t.acceptCatalog(cat(2, v2))
        val offer = t.offers(3, 28).single()
        assertTrue(t.stillAuthorized(offer))
        t.acceptCatalog(cat(3, v2, revoked = listOf(v2.digest)))
        assertFalse("revoked while it was downloading", t.stillAuthorized(offer))
        val t2 = open()
        t2.acceptCatalog(cat(2, v2)); val o2 = t2.offers(3, 28).single()
        t2.acceptCatalog(cat(3, pack("voice-fr-siwis", 3)))
        assertFalse("dropped from the newer catalog", t2.stillAuthorized(o2))
    }

    @Test fun anInstalledPackThatIsRevokedIsReported() {
        val t = open()
        val v2 = pack("voice-fr-siwis", 2)
        t.acceptCatalog(cat(2, v2)); t.recordInstalled(v2)
        assertTrue(t.revokedInstalled().isEmpty())
        t.acceptCatalog(cat(3, pack("voice-fr-siwis", 3), revoked = listOf(v2.digest)))
        assertEquals(listOf("voice-fr-siwis"), t.revokedInstalled())
    }

    // ---- the recovery key ----

    private fun policy(epoch: Long, key: TestKeys.Pair = TestKeys.catalog2, keyId: Int = 2, minSequence: Long = 1, minReader: Long = 1) = TestCatalogs.policy(epoch, key, keyId, minSequence, minReader)
    private fun pwhy(r: UpdateTrust.RecoveryResult) = (r as UpdateTrust.RecoveryResult.Refused).reason

    @Test fun onlyTheRecoveryKeyStartsANewEpochAndTheOldKeyStopsAtOnce() {
        val store = MemoryTrustStore(); val t = open(store)
        val v2 = pack("voice-fr-siwis", 2)
        t.acceptCatalog(cat(2, v2)); t.recordInstalled(v2)
        val p = policy(2)
        assertTrue(t.acceptRecovery(p) is UpdateTrust.RecoveryResult.Accepted)
        assertEquals(2L, t.state.epoch); assertEquals(2, t.state.keyId); assertArrayEquals(TestKeys.catalog2.public, t.state.key)
        assertNull("offers from the old epoch are dropped", t.state.catalog)
        assertEquals(0L, t.state.sequence); assertArrayEquals(p, t.state.policy)
        assertEquals("floors survive", 2L, t.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertEquals("the old key is refused at once", Reason.NOT_SIGNED, why(t.acceptCatalog(cat(9, pack("voice-fr-siwis", 5), epoch = 2))))
        assertEquals("a catalog of the old epoch", Reason.NOT_SIGNED, why(t.acceptCatalog(cat(9, pack("voice-fr-siwis", 5)))))
        assertTrue(accepted(t.acceptCatalog(cat(1, pack("voice-fr-siwis", 3), epoch = 2, key = TestKeys.catalog2, keyId = 2))))
        // ... and after a restart: no fallback to the old key.
        val again = open(store)
        assertEquals(2L, again.state.epoch)
        assertEquals(Reason.NOT_SIGNED, why(again.acceptCatalog(cat(9, pack("voice-fr-siwis", 5), epoch = 2))))
    }

    @Test fun aRecoveryPolicyMustComeFromTheRecoveryKeyAndMeanIt() {
        val t = open()
        assertEquals("signed by the catalog key under its own id", Reason.NOT_SIGNED, pwhy(t.acceptRecovery(TestCatalogs.policy(2, TestKeys.catalog2, by = TestKeys.catalog, signerId = 1))))
        assertEquals("signed by the catalog key under the recovery id", Reason.NOT_SIGNED, pwhy(t.acceptRecovery(TestCatalogs.policy(2, TestKeys.catalog2, by = TestKeys.catalog))))
        assertEquals("a catalog is not a policy", Reason.NOT_SIGNED, pwhy(t.acceptRecovery(cat(2, pack("voice-fr-siwis", 2)))))
        assertEquals("the new catalog key is the recovery key itself", Reason.BAD_KEY, pwhy(t.acceptRecovery(TestCatalogs.policy(2, TestKeys.recovery))))
        assertEquals("the new catalog key is the old one", Reason.BAD_KEY, pwhy(t.acceptRecovery(TestCatalogs.policy(2, TestKeys.catalog, newKeyId = 1))))
        assertEquals("the same epoch", Reason.NOT_NEWER_EPOCH, pwhy(t.acceptRecovery(policy(1))))
        assertEquals("a newer reader", Reason.NEEDS_NEWER_APP, pwhy(t.acceptRecovery(policy(2, minReader = 9))))
        assertEquals(1L, t.state.epoch)
    }

    @Test fun missedEpochsAreSkippedAndRepeatsAreHarmless() {
        val store = MemoryTrustStore(); val t = open(store)
        val p5 = policy(5, TestKeys.catalog3, 3)
        assertTrue(t.acceptRecovery(p5) is UpdateTrust.RecoveryResult.Accepted)
        assertEquals(5L, t.state.epoch)
        val writes = store.saves.size
        assertEquals(UpdateTrust.RecoveryResult.Same, t.acceptRecovery(p5))
        assertEquals(writes, store.saves.size)
        assertEquals("the policy of an earlier epoch", Reason.NOT_NEWER_EPOCH, pwhy(t.acceptRecovery(policy(2))))
        assertEquals("a different policy for the same epoch", Reason.NOT_NEWER_EPOCH, pwhy(t.acceptRecovery(policy(5, TestKeys.catalog2, 2))))
    }

    @Test fun aStolenCatalogKeyThatRacesAheadIsReplacedByTheRecoveryKey() {
        val t = open()
        val far = 1_000_000_000_000L
        assertTrue("the stolen key signs a catalog far ahead", accepted(t.acceptCatalog(cat(far, pack("voice-fr-siwis", 9)))))
        assertEquals(Reason.OLDER, why(t.acceptCatalog(cat(far - 1, pack("voice-fr-siwis", 2)))))
        assertTrue(t.acceptRecovery(policy(2, minSequence = 1)) is UpdateTrust.RecoveryResult.Accepted)
        assertTrue("the new key's numbering starts again", accepted(t.acceptCatalog(cat(1, pack("voice-fr-siwis", 3), epoch = 2, key = TestKeys.catalog2, keyId = 2))))
        assertTrue(t.offers(3, 28).isNotEmpty())
        assertEquals("the stolen key is dead", Reason.NOT_SIGNED, why(t.acceptCatalog(cat(far + 1, pack("voice-fr-siwis", 10), epoch = 2))))
    }

    @Test fun theNewEpochsMinimumSequenceIsAFloorForItsCatalogs() {
        val t = open()
        t.acceptRecovery(policy(2, minSequence = 7))
        assertEquals(Reason.OLDER, why(t.acceptCatalog(cat(6, pack("voice-fr-siwis", 3), epoch = 2, key = TestKeys.catalog2, keyId = 2))))
        assertTrue(accepted(t.acceptCatalog(cat(7, pack("voice-fr-siwis", 3), epoch = 2, key = TestKeys.catalog2, keyId = 2))))
    }

    // ---- the store ----

    @Test fun aStoreThatFailsLeavesTheOldStateAndCanBeRetried() {
        val store = MemoryTrustStore(); val t = open(store)
        val c2 = cat(2, pack("voice-fr-siwis", 2))
        store.failNext = true
        assertEquals(Reason.STORE_FAILED, why(t.acceptCatalog(c2)))
        assertEquals(1L, t.state.sequence)
        assertEquals(1L, open(store).state.sequence)
        assertTrue(accepted(t.acceptCatalog(c2)))
        store.failNext = true
        assertFalse(t.recordInstalled(pack("voice-fr-siwis", 2)))
        assertEquals(0L, t.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
    }

    @Test fun damagedStateIsNeverReplacedByTheBaseline() {
        val store = MemoryTrustStore(); val t = open(store)
        t.acceptCatalog(cat(2, pack("voice-fr-siwis", 2)))
        assertEquals(2, store.saves.size)
        val damaged = MemoryTrustStore(store.slots.map { it?.copyOf()?.also { b -> b[b.size / 2] = (b[b.size / 2] + 1).toByte() } })
        val r = UpdateTrust.open(damaged, roots, 3, TestCatalogs.baseline, allowTestRoots = true)
        assertTrue(r is UpdateTrust.Opened.Corrupt)
        assertTrue("and it wrote nothing", damaged.saves.isEmpty())
        val olderDamaged = MemoryTrustStore(listOf(store.slots[0], store.slots[1]?.copyOf()?.also { it[3] = 0 }))
        assertEquals("the newest slot stands", 2L, open(olderDamaged).state.sequence)
        val newestDamaged = MemoryTrustStore(listOf(store.slots[0]?.copyOf()?.also { it[3] = 0 }, store.slots[1]))
        assertEquals("a torn newest slot leaves the state before it", 1L, open(newestDamaged).state.sequence)
    }

    @Test fun aTornVeryFirstWriteIsJustAFreshInstall() {
        val store = MemoryTrustStore(); open(store)
        val first = store.saves.single()
        val torn = MemoryTrustStore(List(2) { if (it == first.first) first.second.copyOf(100) else null })
        val t = open(torn)
        assertArrayEquals(open(MemoryTrustStore()).state.encode(), t.state.encode())
        // The same garbage in the other slot of a one-slot history would be real corruption.
        val other = MemoryTrustStore(List(2) { if (it != first.first) first.second.copyOf(100) else null })
        assertTrue(UpdateTrust.open(other, roots, 3, TestCatalogs.baseline, allowTestRoots = true) is UpdateTrust.Opened.Corrupt)
    }

    @Test fun theStateRecordRoundTripsAndRejectsAnyDamage() {
        val t = open(); val v2 = pack("voice-fr-siwis", 2)
        t.acceptCatalog(cat(2, v2)); t.recordInstalled(v2)
        t.acceptRecovery(policy(2))
        val bytes = t.state.encode()
        assertArrayEquals(bytes, TrustState.decode(bytes)!!.encode())
        for (i in bytes.indices step 5) assertNull("byte $i", TrustState.decode(bytes.copyOf().also { it[i] = (it[i] + 1).toByte() }))
        for (n in listOf(0, 4, bytes.size - 1)) assertNull(TrustState.decode(bytes.copyOf(n)))
        assertNull(TrustState.decode(bytes + 0))
        assertNotNull(TrustState.decode(bytes))
    }

    @Test fun thePackagedRootsAndBaselineOpenAFreshStore() {
        val r = UpdateTrust.open(MemoryTrustStore(), UpdateRoots.compiled, 1, allowTestRoots = true)
        assertTrue(r.toString(), r is UpdateTrust.Opened.Ready)
    }
}

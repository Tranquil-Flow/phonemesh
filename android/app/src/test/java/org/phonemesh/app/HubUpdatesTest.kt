package org.phonemesh.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackStore
import org.phonemesh.core.update.UpdateController.Notice
import org.phonemesh.core.update.UpdateController.State
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import java.io.File
import java.util.concurrent.Executor

/** The phone's update state as the app opens it (U5a): durable files, floors repaired at start, damage never reset, keys decide. */
class HubUpdatesTest {
    @get:Rule val folder = TemporaryFolder()
    private val keys = UpdateKit.keys()
    private val v1 = UpdateKit.voice("voice-fr-siwis", 1)
    private val v2 = UpdateKit.voice("voice-fr-siwis", 2, modelBytes = 6_000)
    private val direct = Executor { it.run() }
    private val hub = UpdateKit.Hub()
    private val dir by lazy { File(folder.root, "updates") }
    private val packs by lazy { folder.newFolder("packs") }
    private val compiled get() = listOf(CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "x.zip", v1.zip.size.toLong(), UpdateKit.hex(v1.entry.sums)))
    private val baseline get() = UpdateKit.catalog(keys, 1, 1, listOf(v1.entry))

    private fun open(roots: UpdateRoots = keys.roots, transport: () -> org.phonemesh.core.update.HubTransport? = { hub }) =
        HubUpdates.open(dir, packs, PackStore(packs, compiled), transport, roots, baseline, compiled, direct, sdk = 28)

    @Test fun firstStartKeepsTheSignedBaselineInItsOwnFilesAndASecondStartReadsThemBack() {
        val first = open()
        assertNotNull(first.controller); assertNull(first.problem)
        val files = File(dir, "trust").listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("trust-1.bin"), files)
        val again = open()
        assertArrayEquals(first.trust!!.state.encode(), again.trust!!.state.encode())
    }

    @Test fun packsAlreadyInstalledGetTheirVersionFloorAtStart() {
        PackStore(packs, compiled).install(v1.zip.inputStream())
        val opened = open()
        assertEquals(1L, opened.trust!!.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
    }

    @Test fun anInstallThatFinishedJustBeforeACrashIsRepairedAtTheNextStart() {
        PackStore(packs, compiled).install(v1.zip.inputStream())
        open()
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(v2.entry))
        val live = open().also { it.controller!!.refresh(); it.controller!!.download("3/voice-fr-siwis") }
        assertEquals(State.Done, live.controller!!.view().rows.single().state)
        // The "crash": the floor is lost but the new pack is on disk.
        File(dir, "trust").listFiles()!!.forEach { it.delete() }
        val restarted = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub }, keys.roots, UpdateKit.catalog(keys, 1, 2, listOf(v2.entry)), compiled, direct, sdk = 28)
        assertEquals(2L, restarted.trust!!.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
    }

    @Test fun damagedSavedStateTurnsUpdatesOffAndIsNeverReplaced() {
        open()
        val file = File(dir, "trust/trust-1.bin")
        val saved = file.readBytes()
        File(dir, "trust/trust-0.bin").writeBytes(byteArrayOf(1, 2, 3))
        file.writeBytes(saved.copyOf(saved.size - 5))
        val off = open()
        assertNull(off.controller)
        assertEquals(R.string.updates_off, off.problem)
        assertEquals("nothing was reset", saved.size - 5, file.length().toInt())
    }

    @Test fun aCatalogSignedByAKeyOutsideTheRootsIsNotTrusted() {
        val stranger = UpdateKit.keys()
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(stranger, 1, 2, listOf(v2.entry))
        PackStore(packs, compiled).install(v1.zip.inputStream())
        val opened = open()
        opened.controller!!.refresh()
        assertEquals(Notice.LIST_NOT_TRUSTED, opened.controller!!.view().notice)
        assertTrue(opened.controller!!.view().rows.isEmpty())
        // Even a catalog signed by the stranger's key under the right id and number.
        assertEquals(1L, opened.trust!!.state.sequence)
    }

    /**
     * The default roots are update/public-keys.txt and the baseline is baseline-catalog.pmup: one file pair to swap. While they are
     * TEST keys, only a build that allows it (debug, minifiedDebug, demo) runs updates under them; a release build is "not set up" (U7-1).
     */
    @Test fun theKeysFileInTheBuildIsWhatDecidesAndOpensWithTheRealBaselineOnlyWhereTestKeysAreAllowed() {
        val opened = HubUpdates.open(dir, packs, PackStore(packs, compiled), { null })
        assertEquals(UpdateRoots.compiled.catalogKeyId, 1)
        if (BuildConfig.ALLOW_TEST_ROOTS) {
            assertNotNull(opened.controller)
            assertFalse(opened.trust!!.state.sequence < 1)
            assertEquals("the screen must say these are TEST keys", UpdateRoots.compiled.isTest, opened.testKeys)
        } else if (UpdateRoots.compiled.isTest) {
            assertNull("a release build with TEST keys runs no updates", opened.controller)
            assertEquals(R.string.updates_not_set_up, opened.problem)
            assertFalse("and writes nothing", File(dir, "trust").exists())
        }
    }

    /** U7-1: a catalog signed with the key anyone can compute from the public label is not even looked at by a build that refuses TEST roots. */
    @Test fun aCatalogForgedWithTheLabelDerivedKeyNeverReachesATestRootRefusingBuild() {
        fun scalar(label: String) = java.math.BigInteger(1, org.phonemesh.core.crypto.Bytes.sha256(label.toByteArray()))
            .mod(org.phonemesh.core.crypto.P256.n - java.math.BigInteger.ONE) + java.math.BigInteger.ONE
        val forger = org.phonemesh.core.crypto.P256.privateKey(org.phonemesh.core.crypto.Bytes.fixed(scalar("phonemesh TEST catalog key"), 32))
        val evil = UpdateKit.voice("voice-fr-siwis", 9, modelBytes = 7_000)
        hub.add(evil.zip)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(evil.entry), signer = java.security.KeyPair(null, forger))
        PackStore(packs, compiled).install(v1.zip.inputStream())
        val opened = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub })
        if (!BuildConfig.ALLOW_TEST_ROOTS && UpdateRoots.compiled.isTest) {
            assertNull(opened.controller)
            assertEquals(R.string.updates_not_set_up, opened.problem)
            assertTrue("nothing was asked of the hub", hub.requests.isEmpty())
        } else {
            // A build that allows TEST roots is not for real use: the forgery is signed by the compiled catalog key.
            assertTrue(opened.testKeys == UpdateRoots.compiled.isTest)
        }
    }

    /** U7-1: the release build type is the one that must refuse TEST roots; debug, minifiedDebug and demo may keep them for tests. */
    @Test fun onlyTheReleaseBuildTypeRefusesTestRoots() {
        assertEquals("build type ${BuildConfig.BUILD_TYPE}", BuildConfig.BUILD_TYPE != "release", BuildConfig.ALLOW_TEST_ROOTS)
        if (BuildConfig.BUILD_TYPE == "release" && UpdateRoots.compiled.isTest) {
            val opened = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub })
            assertNull(opened.controller)
            assertEquals(R.string.updates_not_set_up, opened.problem)
        }
    }

    @Test fun aBuildThatRefusesTestRootsRefusesThemWhateverTheCommentSays() {
        val roots = UpdateRoots.compiled
        if (!roots.isTest) return
        val opened = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub }, roots, UpdateRoots.baselineCatalog(), allowTestRoots = false)
        assertNull(opened.controller)
        assertEquals(R.string.updates_not_set_up, opened.problem)
        assertTrue(hub.requests.isEmpty())
    }

    @Test fun aBaselineThatDoesNotMatchTheKeysIsABuildProblemNotDamagedData() {
        val stranger = UpdateKit.keys()
        val off = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub }, keys.roots, UpdateKit.catalog(stranger, 1, 1, listOf(v1.entry)), compiled, direct, sdk = 28)
        assertNull(off.controller)
        assertEquals(R.string.updates_off_build, off.problem)
        assertFalse("not the damaged-data sentence", off.problem == R.string.updates_off)
    }

    /** U7-2 at the app: a phone that held other roots starts fresh under the new ones. */
    @Test fun aPhoneThatHeldOtherRootsStartsFreshAndNoLongerTrustsTheOldCatalogKey() {
        PackStore(packs, compiled).install(v1.zip.inputStream())
        val old = open()
        assertArrayEquals(keys.roots.catalogKey, old.trust!!.state.key)
        val renewed = UpdateKit.keys()
        val again = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub }, renewed.roots, UpdateKit.catalog(renewed, 1, 1, listOf(v1.entry)), compiled, direct, sdk = 28)
        assertNotNull(again.controller)
        assertArrayEquals(renewed.roots.catalogKey, again.trust!!.state.key)
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(v2.entry))
        again.controller!!.refresh()
        assertEquals("the old key's list is not believed", Notice.LIST_NOT_TRUSTED, again.controller!!.view().notice)
    }

    // ---- U7-5

    @Test fun anUpdateThatDoesNotOpenLeavesTheOldPackActiveAtEveryMoment() {
        PackStore(packs, compiled).install(v1.zip.inputStream())
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(v2.entry))
        val seen = ArrayList<Int>()
        val opens = PackOpener { info ->
            seen += PackStore(packs, compiled).list().single().version
            assertTrue("the candidate is not in the pack list yet", info.root.name.startsWith(".pending-"))
            false
        }
        val opened = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub }, keys.roots, baseline, compiled, direct, sdk = 28, opens = opens)
        opened.controller!!.refresh(); opened.controller!!.download("3/voice-fr-siwis")
        assertEquals(listOf(1), seen)
        val row = opened.controller!!.view().rows.single()
        assertEquals(org.phonemesh.core.update.UpdateController.Why.OPEN_FAILED, (row.state as State.Failed).why)
        assertEquals(1, PackStore(packs, compiled).list().single().version)
        assertEquals(0L, opened.trust!!.floor(UpdateMetadata.VOICE, "voice-fr-siwis").coerceAtMost(0L))
        assertTrue("no leftover pending copy", packs.list()!!.none { it.startsWith(".pending-") })
    }

    @Test fun anUpdateThatOpensBecomesTheActivePack() {
        PackStore(packs, compiled).install(v1.zip.inputStream())
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(v2.entry))
        val opened = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub }, keys.roots, baseline, compiled, direct, sdk = 28, opens = PackOpener { true })
        opened.controller!!.refresh(); opened.controller!!.download("3/voice-fr-siwis")
        assertEquals(State.Done, opened.controller!!.view().rows.single().state)
        assertEquals(2, PackStore(packs, compiled).list().single().version)
    }

    // ---- U7-6

    @Test fun aPackTheAcceptedListWithdrawsIsNoLongerLoadedAndTheScreenCanSayWhich() {
        val store = PackStore(packs, compiled)
        store.install(v1.zip.inputStream())
        val opened = HubUpdates.open(dir, packs, store, { hub }, keys.roots, baseline, compiled, direct, sdk = 28)
        assertEquals(emptySet<String>(), store.blockedIds)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(v1.entry), revoked = listOf(v1.entry.digest))
        opened.controller!!.refresh()
        assertEquals(setOf("voice-fr-siwis"), store.blockedIds)
        assertEquals(listOf("voice-fr-siwis"), opened.controller!!.view().revoked)
        assertTrue(store.usable().isEmpty()); assertEquals("still installed, so it can be replaced", 1, store.list().size)
        // A restart finds it withdrawn at once, before any look.
        store.blockedIds = emptySet()
        HubUpdates.open(dir, packs, store, { null }, keys.roots, baseline, compiled, direct, sdk = 28)
        assertEquals(setOf("voice-fr-siwis"), store.blockedIds)
    }

    @Test fun pairingAnEngineWithAVoiceAndAVoiceWithTheEngineInUse() {
        fun info(id: String, kind: org.phonemesh.core.PackKind, tag: String? = null) = org.phonemesh.core.PackInfo(File(folder.root, id), id, 1, kind, id, null, tag, tag)
        val tiny = info("engine-whisper-tiny", org.phonemesh.core.PackKind.ENGINE); val base = info("engine-whisper-base", org.phonemesh.core.PackKind.ENGINE)
        val fr = info("voice-fr-siwis", org.phonemesh.core.PackKind.VOICE, "fr"); val en = info("voice-en", org.phonemesh.core.PackKind.VOICE, "en")
        val newBase = info("engine-whisper-base", org.phonemesh.core.PackKind.ENGINE)
        assertEquals(newBase to fr, PackPairing.pair(newBase, listOf(tiny, base, fr, en), null))
        assertEquals("a voice is tried with the engine the person chose", base to fr, PackPairing.pair(fr, listOf(tiny, base, fr, en), "engine-whisper-base"))
        assertEquals("else with the tiny one", tiny to fr, PackPairing.pair(fr, listOf(base, tiny, fr), null))
        assertEquals("never with an older copy of itself", base to fr, PackPairing.pair(fr, listOf(base, fr), null))
        assertNull("no voice, nothing to try the engine with", PackPairing.pair(newBase, listOf(tiny, base), null))
        assertNull("no engine, nothing to try the voice with", PackPairing.pair(fr, listOf(fr, en), null))
    }

    @Test fun withoutTheHubsWifiTheTransportIsNotEvenTried() {
        var asked = 0
        val opened = open { asked++; null }
        PackStore(packs, compiled).install(v1.zip.inputStream())
        opened.controller!!.refresh()
        assertEquals(Notice.NO_WIFI, opened.controller!!.view().notice)
        assertTrue(hub.requests.isEmpty())
        assertTrue(asked >= 1)
    }

    @Test fun updatesAreOnlyEverOfferedForPacksThePhoneHas() {
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(v2.entry))
        val opened = open()
        opened.controller!!.refresh()
        assertTrue("no pack installed, so nothing to update", opened.controller!!.view().rows.isEmpty())
        assertEquals(0, opened.ready())
    }
}

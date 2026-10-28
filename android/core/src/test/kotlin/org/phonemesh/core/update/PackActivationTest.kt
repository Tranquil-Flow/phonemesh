package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackStore
import java.io.File

/** Activation through PackStore (U4): the old pack stays until the new one opens and its floor is saved. */
class PackActivationTest {
    @get:Rule val folder = TemporaryFolder()
    private val packs by lazy { folder.newFolder("packs") }
    private val work by lazy { folder.newFolder("work") }
    private val store = MemoryTrustStore()
    private val trust: UpdateTrust by lazy { (UpdateTrust.open(store, TestCatalogs.roots, 3, TestCatalogs.baseline) as UpdateTrust.Opened.Ready).trust }
    private val v1 = TestPackZips.voice(1)
    private val compiled get() = listOf(CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "x.zip", v1.zip.size.toLong(), hex(v1.entry.sums)))
    private val activation by lazy { PackActivation(packs, compiled) }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun installV1() = PackStore(packs, compiled).install(v1.zip.inputStream())
    private fun file(b: ByteArray) = File(work, "pack-${b.size}-${b.hashCode()}").also { it.writeBytes(b) }
    private fun offerFor(built: TestPackZips.Built, sequence: Long = 2, extra: List<UpdateMetadata.Entry> = emptyList()): UpdateTrust.Offer {
        trust.acceptCatalog(TestCatalogs.catalog(1, sequence, listOf(built.entry) + extra))
        return trust.offers(3, 28, mapOf("voice-fr-siwis" to 1L)).single { it.entry.id == built.entry.id && it.entry.version == built.entry.version }
    }
    private fun active() = PackStore(packs, compiled).list().single { it.id == "voice-fr-siwis" }
    private fun dirs() = packs.listFiles()!!.filter { it.isDirectory && !it.name.startsWith(".") }.size

    @Test fun aNewVersionReplacesTheOldOneOnlyAfterItOpensAndTheFloorIsSaved() {
        installV1()
        val v2 = TestPackZips.voice(2)
        val offer = offerFor(v2)
        var seenWhileOpening = 0
        val r = activation.activate(trust, offer, file(v2.zip)) { info ->
            seenWhileOpening = dirs() // old and new are both on disk while the new one is tried
            assertEquals("floor not yet raised", 0L, trust.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
            info.version == 2
        }
        assertTrue(r is PackActivation.Result.Activated)
        assertEquals(2, seenWhileOpening)
        assertEquals(2, active().version)
        assertEquals(1, dirs()); assertEquals(2L, trust.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
    }

    @Test fun aPackThatDoesNotOpenLeavesTheOldOneActiveAndTheFloorAlone() {
        installV1()
        val v2 = TestPackZips.voice(2)
        val offer = offerFor(v2)
        assertTrue(activation.activate(trust, offer, file(v2.zip)) { false } is PackActivation.Result.OpenFailed)
        assertTrue(activation.activate(trust, offer, file(v2.zip)) { throw IllegalStateException("model will not load") } is PackActivation.Result.OpenFailed)
        assertEquals(1, active().version); assertEquals(1, dirs())
        assertEquals(0L, trust.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
    }

    @Test fun aFileThatIsNotTheSignedOneIsNotInstalled() {
        installV1()
        val v2 = TestPackZips.voice(2)
        val offer = offerFor(v2)
        val flipped = v2.zip.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertTrue(activation.activate(trust, offer, file(flipped)) is PackActivation.Result.WrongFile)
        assertTrue(activation.activate(trust, offer, file(v2.zip.copyOf(v2.zip.size - 1))) is PackActivation.Result.WrongFile)
        assertTrue(activation.activate(trust, offer, File(work, "missing")) is PackActivation.Result.WrongFile)
        assertEquals(1, active().version); assertEquals(1, dirs())
    }

    @Test fun aPackWhoseContentIsNotTheSignedManifestIsRejectedEvenWithTheRightArchiveHash() {
        installV1()
        // The catalog signs the manifest of one pack but the archive digest of a different, well-formed one.
        val good = TestPackZips.voice(2); val other = TestPackZips.voice(2, seed = 9)
        val lying = good.entry.let { e -> UpdateMetadata.Entry(e.kind, e.id, e.label, e.abi, e.version, e.minApp, e.maxApp, e.minApi, e.packFormat, other.zip.size.toLong(),
            other.entry.digest, other.entry.expanded, other.entry.files, good.entry.sums, e.cert) }
        trust.acceptCatalog(TestCatalogs.catalog(1, 2, listOf(lying)))
        val offer = trust.offers(3, 28, mapOf("voice-fr-siwis" to 1L)).single()
        val r = activation.activate(trust, offer, file(other.zip))
        assertTrue(r.toString(), r is PackActivation.Result.Rejected)
        assertEquals(1, active().version); assertEquals(1, dirs())
    }

    @Test fun theSignedFileCountAndExpandedSizeMustMatch() {
        installV1()
        val v2 = TestPackZips.voice(2)
        for ((name, bad) in listOf("files" to v2.entry.files + 1, "bytes" to -1)) {
            val e = v2.entry.let { e -> UpdateMetadata.Entry(e.kind, e.id, e.label, e.abi, e.version, e.minApp, e.maxApp, e.minApi, e.packFormat, e.bytes, e.digest,
                if (name == "bytes") e.expanded - 3 else e.expanded, if (name == "files") bad else e.files, e.sums, e.cert) }
            trust.acceptCatalog(TestCatalogs.catalog(1, if (name == "files") 2 else 3, listOf(e)))
            val offer = trust.offers(3, 28, mapOf("voice-fr-siwis" to 1L)).single()
            assertTrue(name, activation.activate(trust, offer, file(v2.zip)) is PackActivation.Result.Rejected)
            assertEquals(name, 1, active().version); assertEquals(name, 1, dirs())
        }
    }

    @Test fun anOfferThatWasRevokedWhileItDownloadedIsNotActivated() {
        installV1()
        val v2 = TestPackZips.voice(2)
        val offer = offerFor(v2)
        trust.acceptCatalog(TestCatalogs.catalog(1, 3, listOf(v2.entry), revoked = listOf(v2.entry.digest)))
        assertTrue(activation.activate(trust, offer, file(v2.zip)) is PackActivation.Result.NotAuthorized)
        assertEquals(1, active().version)
    }

    @Test fun ifTheFloorCannotBeSavedTheNewPackIsRemovedAgain() {
        installV1()
        val v2 = TestPackZips.voice(2)
        val offer = offerFor(v2)
        store.failNext = true
        assertTrue(activation.activate(trust, offer, file(v2.zip)) is PackActivation.Result.StateNotSaved)
        assertEquals(1, active().version); assertEquals(1, dirs())
    }

    @Test fun aLowerVersionThanTheFloorIsNeverActivated() {
        val v2 = TestPackZips.voice(2)
        val offer2 = offerFor(v2)
        assertTrue(activation.activate(trust, offer2, file(v2.zip)) is PackActivation.Result.Activated)
        // A catalog that again offers version 1 as the best: no offer is made for it, and forcing one is refused.
        trust.acceptCatalog(TestCatalogs.catalog(1, 3, listOf(v1.entry, v2.entry)))
        assertTrue(trust.offers(3, 28, mapOf("voice-fr-siwis" to 2L)).isEmpty())
        val forced = UpdateTrust.Offer(v1.entry, trust.state.epoch, trust.state.sequence)
        assertTrue(activation.activate(trust, forced, file(v1.zip)) !is PackActivation.Result.Activated)
        assertEquals(2, active().version)
    }

    @Test fun theSameVersionAlreadyInstalledIsKeptNotDeleted() {
        val v2 = TestPackZips.voice(2)
        val offer = offerFor(v2)
        assertTrue(activation.activate(trust, offer, file(v2.zip)) is PackActivation.Result.Activated)
        assertTrue(activation.activate(trust, offer, file(v2.zip)) is PackActivation.Result.Activated)
        assertEquals(2, active().version); assertEquals(1, dirs())
        // Even when the second attempt fails to open, the copy that is already in use is not the "new" one to throw away.
        assertTrue(activation.activate(trust, offer, file(v2.zip)) { false } is PackActivation.Result.OpenFailed)
        assertEquals(2, active().version); assertEquals(1, dirs())
    }

    @Test fun aCrashBetweenInstallAndFloorIsRepairedByReconcile() {
        installV1()
        val v2 = TestPackZips.voice(2)
        val offer = offerFor(v2)
        store.tear = { _, bytes, _ -> bytes.copyOf(9) }
        try { activation.activate(trust, offer, file(v2.zip)); fail("expected the crash") } catch (_: MemoryTrustStore.Crash) { }
        // The new pack is on disk next to the old one; the floor never moved.
        assertEquals(2, dirs()); assertEquals(2, active().version)
        val again = (UpdateTrust.open(store, TestCatalogs.roots, 3, TestCatalogs.baseline) as UpdateTrust.Opened.Ready).trust
        assertEquals(0L, again.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertEquals("the accepted catalog is the one from before the crash", 2L, again.state.sequence)
        assertEquals(1, PackActivation(packs, compiled).reconcile(again))
        assertEquals(2L, again.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertEquals("a second reconcile has nothing to do", 0, PackActivation(packs, compiled).reconcile(again))
    }

    @Test fun packsThatCameWithTheAppGetTheirFloorsFromTheBaseline() {
        installV1()
        // A baseline that names the installed pack, as the real baseline names the real compiled packs.
        fun trustWithBaseline() = (UpdateTrust.open(MemoryTrustStore(), TestCatalogs.roots, 3, TestCatalogs.catalog(1, 1, listOf(v1.entry))) as UpdateTrust.Opened.Ready).trust
        val t = trustWithBaseline()
        assertEquals(0L, t.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertEquals(1, PackActivation(packs, compiled).reconcile(t))
        assertEquals(1L, t.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        // A pack whose manifest differs from the catalog's is not given a floor.
        val strange = TestPackZips.voice(1, seed = 5)
        PackStore(packs, compiled).remove("voice-fr-siwis")
        PackStore(packs, listOf(CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "x", strange.zip.size.toLong(), hex(strange.entry.sums)))).install(strange.zip.inputStream())
        assertEquals(0, PackActivation(packs, compiled).reconcile(trustWithBaseline()))
    }
}

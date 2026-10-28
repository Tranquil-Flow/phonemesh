package org.phonemesh.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackStore
import org.phonemesh.core.update.UpdateController.Notice
import org.phonemesh.core.update.UpdateController.State
import org.phonemesh.core.update.UpdateController.Why
import java.io.File
import java.util.concurrent.Executor

/** U7-7 (a pack the phone could not store keeps its verified download), U7-9 (honest "up to date"), U7-6, U7-15. */
class UpdateControllerU7Test {
    @get:Rule val folder = TemporaryFolder()
    private val packs by lazy { folder.newFolder("packs") }
    private val work by lazy { folder.newFolder("work") }
    private val hub = FakeHub()
    private val v1 = TestPackZips.voice(1)
    private val v2 = TestPackZips.voice(2, modelBytes = 6_000)
    private val v3 = TestPackZips.voice(3, modelBytes = 7_000)
    private val compiled get() = listOf(CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "x.zip", v1.zip.size.toLong(), hex(v1.entry.sums)))
    private val trust by lazy { (UpdateTrust.open(MemoryTrustStore(), TestCatalogs.roots, 3, TestCatalogs.baseline, allowTestRoots = true) as UpdateTrust.Opened.Ready).trust }
    private val direct = Executor { it.run() }
    private var clock = 1_800_000_000_000L

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private class RejectingActivation(dir: File, compiled: List<CatalogEntry>, val why: String) : PackActivation(dir, compiled) {
        override fun activate(trust: UpdateTrust, offer: UpdateTrust.Offer, file: File, open: (PackInfo) -> Boolean): Result = Result.Rejected(why)
    }

    private fun controller(
        activation: PackActivation = PackActivation(packs, compiled), onTrust: () -> Unit = {}, installedBytes: (String) -> Long = { 0L },
        downloaderFor: (HubTransport, File) -> UpdateDownloader = { h, d -> UpdateDownloader(trust, h, d, chunkBytes = 1024, bufferBytes = 512) },
    ): UpdateController {
        if (PackStore(packs, compiled).list().isEmpty()) PackStore(packs, compiled).install(v1.zip.inputStream())
        return UpdateController(trust, activation, work, { hub }, { PackStore(packs, compiled).list().associate { it.id to it.version.toLong() } }, 3, 28, direct,
            downloaderFor = downloaderFor, installedBytes = installedBytes, now = { clock }, onTrustChanged = onTrust)
    }

    private fun publish(vararg built: TestPackZips.Built, sequence: Long = 2, revoked: List<ByteArray> = emptyList()) {
        built.forEach { hub.add(it.zip) }
        hub.catalog = TestCatalogs.catalog(1, sequence, built.map { it.entry } + TestCatalogs.baselineEntries.filter { b -> built.none { it.entry.id == b.id } }, revoked)
    }

    private fun UpdateController.row() = view().rows.single { it.id == "voice-fr-siwis" }
    private fun ready() = File(work, "ready").listFiles().orEmpty().toList()

    // ---- U7-7

    @Test fun aPackTheFullPhoneCouldNotStoreKeepsItsVerifiedDownloadAndSaysNoSpace() {
        publish(v2)
        val c = controller(RejectingActivation(packs, compiled, "NO_SPACE"))
        c.refresh(); c.download("3/voice-fr-siwis")
        assertEquals(Why.NO_SPACE, (c.row().state as State.Failed).why)
        assertEquals("the verified file is still there: the next try needs no transfer", 1, ready().size)
        assertEquals(hex(v2.entry.digest), ready().single().name)
    }

    @Test fun aStorageFailureIsNotCalledUntrustedAndKeepsTheFile() {
        publish(v2)
        val c = controller(RejectingActivation(packs, compiled, "DAMAGED"))
        c.refresh(); c.download("3/voice-fr-siwis")
        assertEquals(Why.NOT_SAVED, (c.row().state as State.Failed).why)
        assertEquals(1, ready().size)
    }

    @Test fun aRealRejectionStillDeletesTheFileAndSaysNotTrusted() {
        publish(v2)
        val c = controller(RejectingActivation(packs, compiled, "UNSAFE"))
        c.refresh(); c.download("3/voice-fr-siwis")
        assertEquals(Why.NOT_TRUSTED, (c.row().state as State.Failed).why)
        assertTrue(ready().isEmpty())
    }

    @Test fun anotherTapAfterNoSpaceInstallsFromTheKeptFileWithoutNewTransfer() {
        publish(v2)
        var full = true
        val flaky = object : PackActivation(packs, compiled) {
            override fun activate(trust: UpdateTrust, offer: UpdateTrust.Offer, file: File, open: (PackInfo) -> Boolean): Result =
                if (full) Result.Rejected("NO_SPACE") else super.activate(trust, offer, file, open)
        }
        val c = controller(flaky)
        c.refresh(); c.download("3/voice-fr-siwis")
        val requests = hub.requests.size
        full = false
        assertTrue(c.download("3/voice-fr-siwis"))
        assertEquals(State.Done, c.row().state)
        assertEquals("no pack bytes were asked for again", requests, hub.requests.size)
    }

    @Test fun anIoErrorThatIsNotAFullDiskIsASaveProblem() {
        publish(v2)
        val c = controller()
        c.refresh()
        File(work, "partial").delete(); File(work, "partial").writeText("a file where a folder should be")
        c.download("3/voice-fr-siwis")
        assertEquals(Why.NOT_SAVED, (c.row().state as State.Failed).why)
    }

    @Test fun theOldPacksSizeCountsInTheSpaceCheck() {
        publish(v2)
        val free = 20L * 1024 * 1024 + v2.entry.expanded + v2.zip.size + 4_999_999
        val c = controller(installedBytes = { 5_000_000L }, downloaderFor = { h, d -> UpdateDownloader(trust, h, d, freeSpace = { free }, chunkBytes = 1024) })
        c.refresh(); c.download("3/voice-fr-siwis")
        assertEquals(Why.NO_SPACE, (c.row().state as State.Failed).why)
        assertEquals("nothing was copied", 0, hub.requests.count { it.first.contains("/objects/") })
    }

    // ---- U7-9

    @Test fun anOlderListFromTheHubIsNotUpToDate() {
        trust.acceptCatalog(TestCatalogs.catalog(1, 9, TestCatalogs.baselineEntries))
        hub.catalog = TestCatalogs.catalog(1, 8, TestCatalogs.baselineEntries)
        val c = controller()
        c.refresh()
        assertEquals(Notice.HUB_OLDER, c.view().notice)
    }

    @Test fun anOlderListFromTheHubLeavesTheHeldOffersAlone() {
        publish(v2, sequence = 5)
        val c = controller()
        c.refresh(); assertEquals(State.Ready, c.row().state)
        hub.catalog = TestCatalogs.catalog(1, 3, TestCatalogs.baselineEntries)
        c.refresh()
        assertEquals(Notice.NONE, c.view().notice)
        assertEquals(State.Ready, c.row().state)
    }

    @Test fun theSameOrANewerListIsUpToDateAndTheListDateIsShown() {
        hub.catalog = TestCatalogs.catalog(1, 2, TestCatalogs.baselineEntries)
        val c = controller()
        c.refresh()
        assertEquals(Notice.UP_TO_DATE, c.view().notice)
        assertEquals(1_793_145_600L, c.view().listIssuedAt)
        c.refresh() // the same list again
        assertEquals(Notice.UP_TO_DATE, c.view().notice)
    }

    @Test fun aListPastItsReviewDateIsFlaggedOnlyWhenTheClockIsPlausible() {
        hub.catalog = TestCatalogs.catalog(1, 2, TestCatalogs.baselineEntries)
        val c = controller()
        c.refresh()
        assertFalse(c.view().listOld)
        clock = 1_808_697_600_000L + 86_400_000L // a day after the review date
        assertTrue(c.view().listOld)
        clock = 0L // a clock that was never set says nothing
        assertFalse(c.view().listOld)
    }

    @Test fun aClockThatWasNeverSetIsNotTrustedEvenWhenItIsPastAnEarlyReviewDate() {
        // A list whose review date is 1970-01-01 00:00:10 and a phone clock reading a minute after 1970: not a real date.
        hub.catalog = UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, TestCatalogs.payload(1, 2, TestCatalogs.baselineEntries, review = 10), TestKeys.catalog.private)
        val c = controller()
        c.refresh()
        clock = 60_000L
        assertFalse("the clock says 1970", c.view().listOld)
        clock = 1_800_000_000_000L
        assertTrue(c.view().listOld)
    }

    // ---- U7-6

    @Test fun aRevokedInstalledPackIsListedAndTheAppIsToldAfterEveryLook() {
        var told = 0
        val c = controller(onTrust = { told++ })
        trust.recordInstalled(v1.entry)
        assertTrue(c.view().revoked.isEmpty())
        hub.catalog = TestCatalogs.catalog(1, 2, TestCatalogs.baselineEntries, revoked = listOf(v1.entry.digest))
        c.refresh()
        assertEquals(listOf("voice-fr-siwis"), c.view().revoked)
        assertEquals(1, told)
    }

    // ---- U7-15

    @Test fun aNewerOfferWhileAnOlderOneCopiesDoesNotInheritItsInstalledState() {
        publish(v2)
        val c = controller()
        c.refresh()
        var swapped = false
        hub.bend = { path, range, ok ->
            // In the middle of v2's copy the hub's list moves on to v3.
            if (!swapped && path.contains("/objects/") && range != null && range.first > 0) {
                swapped = true
                hub.add(v3.zip)
                hub.catalog = TestCatalogs.catalog(1, 3, listOf(v3.entry) + TestCatalogs.baselineEntries.filter { it.id != v3.entry.id })
                c.refresh()
            }
            ok
        }
        c.download("3/voice-fr-siwis")
        val row = c.row()
        assertEquals(3L, row.version)
        assertEquals("the v3 row shows nothing done for a pack that was never installed", State.Ready, row.state)
        assertEquals("v2 was no longer on the list, so it was not installed", 1, PackStore(packs, compiled).list().single().version)
    }

    @Test fun copiesNobodyCanUseAreSweptAfterALook() {
        publish(v2)
        File(work, "ready").mkdirs(); File(work, "partial").mkdirs()
        val stranger = "ab".repeat(32)
        File(work, "ready/$stranger").writeBytes(ByteArray(10))
        File(work, "partial/$stranger.part").writeBytes(ByteArray(10)); File(work, "partial/$stranger.state").writeBytes(ByteArray(10))
        File(work, "partial/.$stranger.state.tmp").writeBytes(ByteArray(10))
        val listed = hex(v2.entry.digest)
        File(work, "partial/$listed.part").writeBytes(ByteArray(10))
        val c = controller()
        c.refresh()
        assertEquals(listOf("$listed.part"), File(work, "partial").list()!!.toList())
        assertTrue(File(work, "ready").list()!!.isEmpty())
    }

    @Test fun aRevokedOffersLeftoverIsSwept() {
        publish(v2)
        val c = controller()
        c.refresh()
        File(work, "ready").mkdirs()
        File(work, "ready/${hex(v2.entry.digest)}").writeBytes(ByteArray(10))
        hub.catalog = TestCatalogs.catalog(1, 3, listOf(v2.entry) + TestCatalogs.baselineEntries, revoked = listOf(v2.entry.digest))
        c.refresh()
        assertTrue(File(work, "ready").list()!!.isEmpty())
    }
}

package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.phonemesh.core.update.UpdateController.Why
import java.io.File
import java.util.concurrent.Executor

/**
 * The Updates screen's rules without Android (U5): the phone only ever asks, never copies by itself; it copies only over the
 * transport it is given; every failure becomes a state that names what happened; pause keeps the bytes.
 */
class UpdateControllerTest {
    @get:Rule val folder = TemporaryFolder()
    private val packs by lazy { folder.newFolder("packs") }
    private val work by lazy { folder.newFolder("work") }
    private val hub = FakeHub()
    private val v1 = TestPackZips.voice(1)
    private val v2 = TestPackZips.voice(2, modelBytes = 6_000)
    private val en1 = TestPackZips.voice(1, id = "voice-en-amy")
    private val en2 = TestPackZips.voice(2, id = "voice-en-amy", modelBytes = 5_000)
    private val compiled get() = listOf(
        CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "x.zip", v1.zip.size.toLong(), hex(v1.entry.sums)),
        CatalogEntry("voice-en-amy", 1, PackKind.VOICE, "y.zip", en1.zip.size.toLong(), hex(en1.entry.sums)))
    private val trust by lazy { (UpdateTrust.open(MemoryTrustStore(), TestCatalogs.roots, 3, TestCatalogs.baseline) as UpdateTrust.Opened.Ready).trust }
    private var wifi: HubTransport? = hub
    private val tasks = ArrayDeque<Runnable>()
    private var direct = true
    private val executor = Executor { if (direct) it.run() else tasks.addLast(it) }
    private val seen = ArrayList<UpdateController.View>()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun runTasks() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }

    private fun controller(chunk: Int = 1024, installedIds: Map<String, Long>? = null): UpdateController {
        PackStore(packs, compiled).install(v1.zip.inputStream())
        PackStore(packs, compiled).install(en1.zip.inputStream())
        val activation = PackActivation(packs, compiled)
        return UpdateController(trust, activation, work, { wifi }, { installedIds ?: PackStore(packs, compiled).list().associate { it.id to it.version.toLong() } }, 3, 28, executor,
            downloaderFor = { t, d -> UpdateDownloader(trust, t, d, chunkBytes = chunk, bufferBytes = 512) })
            .also { c -> c.onChange = { seen += c.view() } }
    }

    private fun publish(vararg built: TestPackZips.Built, sequence: Long = 2, revoked: List<ByteArray> = emptyList()) {
        built.forEach { hub.add(it.zip) }
        hub.catalog = TestCatalogs.catalog(1, sequence, built.map { it.entry } + TestCatalogs.baselineEntries.filter { b -> built.none { it.entry.id == b.id } }, revoked)
    }

    private fun UpdateController.row(id: String = "voice-fr-siwis") = view().rows.single { it.id == id }
    private fun objectRequests() = hub.requests.count { it.first.contains("/objects/") }

    @Test fun lookingNeverCopiesAndTheOfferWaitsForTheTap() {
        publish(v2)
        val c = controller()
        c.refresh()
        assertEquals(State.Ready, c.row().state)
        assertEquals(1L, c.row().installed); assertEquals(2L, c.row().version)
        assertEquals("no pack bytes were asked for", 0, objectRequests())
        assertEquals(listOf("/updates/v1/recovery", "/updates/v1/catalog"), hub.requests.map { it.first })
        // Refreshing again, any number of times, still asks for nothing.
        c.refresh(); c.refresh()
        assertEquals(0, objectRequests())
        assertEquals(1, PackStore(packs, compiled).list().first { it.id == "voice-fr-siwis" }.version)
    }

    @Test fun tappingDownloadCopiesChecksAndInstalls() {
        publish(v2)
        val c = controller()
        c.refresh()
        assertTrue(c.download("3/voice-fr-siwis"))
        assertEquals(State.Done, c.row().state)
        assertEquals(2, PackStore(packs, compiled).list().first { it.id == "voice-fr-siwis" }.version)
        assertEquals(2L, trust.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertTrue("the downloaded archive is removed after install", File(work, "ready").listFiles().orEmpty().isEmpty())
        assertTrue("progress was reported on the way", seen.any { (it.rows.firstOrNull()?.state as? State.Copying)?.let { s -> s.done > 0 && s.done < s.total } == true })
        assertTrue(seen.any { it.rows.firstOrNull()?.state == State.Installing })
        assertEquals("the other pack was not touched", 1, PackStore(packs, compiled).list().first { it.id == "voice-en-amy" }.version)
    }

    @Test fun onlyPacksThePhoneHasAreOffered() {
        publish(v2, en2)
        val c = controller(installedIds = mapOf("voice-fr-siwis" to 1L))
        c.refresh()
        assertEquals(listOf("voice-fr-siwis"), c.view().rows.map { it.id })
    }

    @Test fun nothingNewIsSaidPlainly() {
        hub.catalog = TestCatalogs.catalog(1, 2, TestCatalogs.baselineEntries)
        val c = controller()
        c.refresh()
        assertEquals(Notice.UP_TO_DATE, c.view().notice)
        assertTrue(c.view().rows.isEmpty())
    }

    @Test fun withoutTheHubsWifiNothingIsAttemptedAndThePersonIsToldWhy() {
        publish(v2)
        wifi = null
        val c = controller()
        c.refresh()
        assertEquals(Notice.NO_WIFI, c.view().notice)
        assertTrue(hub.requests.isEmpty())
        assertFalse(c.download("3/voice-fr-siwis"))
    }

    @Test fun theWifiGoingBeforeTheTapStopsTheCopyBeforeItStarts() {
        publish(v2)
        val c = controller()
        c.refresh()
        wifi = null
        c.download("3/voice-fr-siwis")
        val s = c.row().state as State.Stopped
        assertEquals(Why.HUB_GONE, s.why)
        assertEquals(0, objectRequests())
        wifi = hub
        c.download("3/voice-fr-siwis")
        assertEquals(State.Done, c.row().state)
    }

    @Test fun aHubWithoutUpdatesIsOldNotBroken() {
        val c = controller()
        c.refresh()
        assertEquals(Notice.NO_UPDATES, c.view().notice)
    }

    @Test fun aHubThatCannotBeReachedSaysSo() {
        hub.bend = { _, _, _ -> throw java.io.IOException("down") }
        val c = controller()
        c.refresh()
        assertEquals(Notice.UNREACHABLE, c.view().notice)
    }

    @Test fun aCatalogSignedByAnotherKeyIsNotTrustedAndOffersNothing() {
        hub.add(v2.zip)
        hub.catalog = TestCatalogs.catalog(1, 2, listOf(v2.entry), key = TestKeys.catalog3, keyId = 1)
        val c = controller()
        c.refresh()
        assertEquals(Notice.LIST_NOT_TRUSTED, c.view().notice)
        assertTrue(c.view().rows.isEmpty())
        assertEquals(1L, trust.state.sequence)
    }

    @Test fun aCatalogForANewerAppSaysSo() {
        hub.add(v2.zip)
        hub.catalog = TestCatalogs.catalog(1, 2, listOf(v2.entry), minReader = 99)
        val c = controller()
        c.refresh()
        assertEquals(Notice.NEEDS_NEWER_APP, c.view().notice)
    }

    @Test fun pauseKeepsTheBytesAndResumeContinuesFromThem() {
        publish(v2)
        val c = controller()
        c.refresh()
        var asked = false
        c.onChange = { val s = c.view().rows.firstOrNull()?.state; if (!asked && s is State.Copying && s.done >= 1024) { asked = true; c.pause("3/voice-fr-siwis") } }
        c.download("3/voice-fr-siwis")
        val paused = c.row().state as State.Paused
        assertTrue(paused.done in 1024L until paused.total)
        val before = objectRequests()
        c.onChange = null
        c.download("3/voice-fr-siwis")
        assertEquals(State.Done, c.row().state)
        val ranges = hub.requests.filter { it.first.contains("/objects/") }.drop(before).map { it.second!!.first }
        assertEquals("resumed from where it stopped, not from zero", paused.done, ranges.first())
    }

    @Test fun threeDroppedConnectionsStopTheCopyWithResumeAndNothingIsLost() {
        publish(v2)
        val c = controller()
        c.refresh()
        var drops = 3
        hub.bend = { path, _, ok -> if (path.contains("/objects/") && drops-- > 0) throw java.io.IOException("reset") else ok }
        c.download("3/voice-fr-siwis")
        val s = c.row().state as State.Stopped
        assertEquals(Why.HUB_GONE, s.why)
        assertEquals("the old pack is untouched", 1, PackStore(packs, compiled).list().first { it.id == "voice-fr-siwis" }.version)
        hub.bend = null
        c.download("3/voice-fr-siwis")
        assertEquals(State.Done, c.row().state)
    }

    @Test fun notEnoughRoomIsSaidAndNothingIsStarted() {
        publish(v2)
        val c = UpdateController(trust, PackActivation(packs, compiled), work, { hub }, { mapOf("voice-fr-siwis" to 1L) }, 3, 28, executor,
            downloaderFor = { t, d -> UpdateDownloader(trust, t, d, freeSpace = { 1_000 }) })
        c.refresh()
        c.download("3/voice-fr-siwis")
        assertEquals(Why.NO_SPACE, (c.row().state as State.Failed).why)
        assertEquals(0, objectRequests())
    }

    @Test fun bytesThatAreNotTheSignedFileAreRefusedAndNothingIsInstalled() {
        publish(v2)
        val c = controller()
        c.refresh()
        val bad = v2.zip.copyOf().also { it[200] = (it[200] + 1).toByte() }
        hub.objects[hex(v2.entry.digest)] = bad
        c.download("3/voice-fr-siwis")
        assertEquals(Why.NOT_TRUSTED, (c.row().state as State.Failed).why)
        assertEquals(1, PackStore(packs, compiled).list().first { it.id == "voice-fr-siwis" }.version)
        assertEquals(0L, trust.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertTrue(File(work, "ready").listFiles().orEmpty().isEmpty())
    }

    @Test fun anOfferTheHubWithdrewWhileWaitingIsNotCopied() {
        publish(v2)
        val c = controller()
        c.refresh()
        trust.acceptCatalog(TestCatalogs.catalog(1, 3, TestCatalogs.baselineEntries, listOf(v2.entry.digest)))
        c.download("3/voice-fr-siwis")
        assertEquals(Why.NOT_OFFERED, (c.row().state as State.Failed).why)
        assertEquals(0, objectRequests())
        assertFalse("a withdrawn offer cannot be asked for again", c.download("3/voice-fr-siwis"))
    }

    @Test fun twoAskedCopiesRunOneAfterTheOtherAndWaitingOnesCanLeaveTheLine() {
        publish(v2, en2)
        direct = false
        val c = controller()
        c.refresh(); runTasks()
        assertEquals(2, c.view().rows.size)
        c.download("3/voice-fr-siwis"); c.download("3/voice-en-amy")
        assertEquals(listOf(State.Waiting, State.Waiting), c.view().rows.map { it.state })
        assertEquals(0, objectRequests())
        runTasks()
        assertEquals(listOf(State.Done, State.Done), c.view().rows.map { it.state })
        val order = hub.requests.filter { it.first.contains("/objects/") }.map { it.first }.distinct()
        assertEquals(listOf(hex(v2.entry.digest), hex(en2.entry.digest)), order.map { it.substringAfterLast('/') })
    }

    @Test fun aWaitingCopyThatIsPausedNeverStarts() {
        publish(v2, en2)
        direct = false
        val c = controller()
        c.refresh(); runTasks()
        c.download("3/voice-fr-siwis"); c.download("3/voice-en-amy")
        c.pause("3/voice-en-amy")
        runTasks()
        assertEquals(State.Done, c.row("voice-fr-siwis").state)
        assertTrue(c.row("voice-en-amy").state is State.Paused)
        assertTrue(hub.requests.none { it.first.endsWith(hex(en2.entry.digest)) })
    }

    @Test fun leavingTheScreenPausesEveryCopy() {
        publish(v2)
        val c = controller()
        c.refresh()
        var once = true
        c.onChange = { val s = c.view().rows.firstOrNull()?.state; if (once && s is State.Copying && s.done >= 1024) { once = false; c.pauseAll() } }
        c.download("3/voice-fr-siwis")
        assertTrue(c.row().state is State.Paused)
    }

    @Test fun aRefreshDuringTheListKeepsAFinishedRowUntilTheNextLook() {
        publish(v2)
        val c = controller()
        c.refresh(); c.download("3/voice-fr-siwis")
        assertEquals(State.Done, c.row().state)
        c.refresh()
        assertEquals(Notice.UP_TO_DATE, c.view().notice)
        assertTrue(c.view().rows.isEmpty())
    }

    @Test fun aPackThatWillNotOpenKeepsTheOldOneAndSaysSo() {
        publish(v2)
        val c = UpdateController(trust, PackActivation(packs, compiled), work, { hub }, { mapOf("voice-fr-siwis" to 1L) }, 3, 28, executor, open = { false },
            downloaderFor = { t, d -> UpdateDownloader(trust, t, d) })
        PackStore(packs, compiled).install(v1.zip.inputStream())
        c.refresh(); c.download("3/voice-fr-siwis")
        assertEquals(Why.OPEN_FAILED, (c.row().state as State.Failed).why)
        assertEquals(1, PackStore(packs, compiled).list().single().version)
    }

    @Test fun theRowsKeyIsKindAndId() {
        publish(v2)
        val c = controller(); c.refresh()
        assertEquals("3/voice-fr-siwis", c.view().rows.single().key)
        assertNull(c.view().rows.firstOrNull { it.id == "nope" })
        assertFalse(c.download("3/nope"))
    }

    @Test fun theHubScreenCanTellWithoutTheNetworkThatAVerifiedOfferIsHeld() {
        publish(v2, en2)
        val c = controller(installedIds = mapOf("voice-fr-siwis" to 1L))
        assertEquals("only the baseline is held", 0, c.pending())
        c.refresh()
        assertEquals(1, c.pending())
        val before = hub.requests.size
        c.pending()
        assertEquals("asking costs no request", before, hub.requests.size)
    }
}

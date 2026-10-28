package org.phonemesh.app

import android.app.Activity
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.update.HubTransport
import org.phonemesh.core.update.UpdateController
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Settings, Local hub, Updates (U5b; decisions 45 and 62) on the real AppCore, the real update state files and the real
 * downloader against an in-memory hub: the offer is announced, nothing is copied until the tap, progress, Pause and Resume and
 * every error appear beside the pack, and nothing needs the radio or mobile data.
 */
@RunWith(AndroidJUnit4::class)
class UpdatesScreenTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val core get() = TestCore.core
    private val id = "voice-fr-siwis"
    private var screen: Activity? = null
    private val dirs = ArrayList<File>()

    @After fun tearDown() {
        Views.finish(Views.resumed())
        Views.finish(screen)
        TestCore.uninstall()
        dirs.forEach { it.deleteRecursively() }
    }

    private class Rig(val keys: UpdateKit.Keys, val hub: UpdateKit.Hub, val next: UpdateKit.Pack, var wifi: Boolean = true)

    /** Installs the test core and gives it update state whose hub offers the fixture voice pack one version up. */
    private fun rig(modelBytes: Int = 700_000, wifi: Boolean = true, offer: Boolean = true, direct: Boolean = false, testKeys: Boolean = false, engine: Boolean = false): Rig {
        TestCore.install(language = "en-US", voices = listOf("fr"))
        val have = core.store.list().single { it.id == (if (engine) "engine-whisper-tiny" else id) }
        val keys = UpdateKit.keys(test = testKeys)
        val next = if (engine) UpdateKit.engine(have.version + 1) else UpdateKit.voice(id, have.version + 1, "Fran\u00e7ais", modelBytes)
        val hub = UpdateKit.Hub()
        if (offer) { hub.add(next.zip); hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(next.entry)) }
        val rig = Rig(keys, hub, next, wifi)
        val dir = File(context.filesDir, "updates-test-${System.nanoTime()}").also { dirs += it }
        val baseline = UpdateKit.catalog(keys, 1, 1, listOf(if (engine) UpdateKit.engine(have.version).entry else UpdateKit.voice(id, have.version).entry))
        val pool = java.util.concurrent.Executors.newCachedThreadPool { Thread(it, "test-updates").apply { isDaemon = true } }
        val transport: () -> HubTransport? = { if (rig.wifi) hub else null }
        TestCore.onMain {
            core.updates = HubUpdates.open(dir, Fixtures.packDir(context), core.store, transport, keys.roots, baseline, GeneratedPackCatalog.entries,
                executor = if (direct) java.util.concurrent.Executor { it.run() } else pool)
        }
        return rig
    }

    private fun open(): Activity = Views.start(UpdatesActivity::class.java).also { screen = it }

    private fun awaitTag(a: Activity, tag: String, ms: Long = 20_000): View {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { Views.tagged(a, tag)?.let { return it }; Thread.sleep(100) }
        error("No view tagged $tag within $ms ms; screen shows ${Views.all(a).filterIsInstance<TextView>().map { Views.textOf(it) }}")
    }

    private fun awaitText(a: Activity, tag: String, text: String, ms: Long = 20_000) {
        val end = System.currentTimeMillis() + ms
        var last = ""
        while (System.currentTimeMillis() < end) {
            val v = Views.tagged(a, tag)
            if (v != null) { last = Views.textOf(v); if (last == text) return }
            Thread.sleep(100)
        }
        error("$tag shows \"$last\", not \"$text\"")
    }

    private fun objectRequests(r: Rig) = r.hub.requests.count { it.first.contains("/objects/") }
    private fun noA11yProblems(a: Activity) = assertEquals(emptyList<String>(), A11yCheck.problems(a))

    @Test fun anOfferIsAnnouncedAndNothingIsCopiedUntilTheTap() {
        val r = rig()
        val a = open()
        awaitText(a, "update_state:$id", a.getString(R.string.updates_ready))
        assertNull("the offer says it itself, once", Views.tagged(a, "updates_notice"))
        assertEquals(a.getString(R.string.updates_row_detail, 1, 2, 1), Views.textOf(awaitTag(a, "update_detail:$id")))
        assertEquals(a.getString(R.string.updates_no_mobile_data), Views.textOf(awaitTag(a, "updates_data")))
        Thread.sleep(1_500)
        assertEquals("looking is not copying", 0, objectRequests(r))
        assertEquals("still the old pack before the tap", 1, core.store.list().single { it.id == id }.version)
        noA11yProblems(a)
        Views.tap(a, "update_download:$id")
        awaitText(a, "update_state:$id", a.getString(R.string.updates_done))
        assertEquals(2, core.store.list().single { it.id == id }.version)
        assertTrue(objectRequests(r) >= 2)
        noA11yProblems(a)
    }

    @Test fun notNowHidesTheOfferWithoutCopying() {
        val r = rig()
        val a = open()
        awaitTag(a, "update_skip:$id")
        Views.tap(a, "update_skip:$id")
        Thread.sleep(300)
        assertNull(Views.tagged(a, "update:$id"))
        assertEquals(0, objectRequests(r))
    }

    @Test fun progressPauseAndResumeAreBesideTheAction() {
        val r = rig()
        val held = CountDownLatch(1)
        r.hub.gate = { range -> if (range.first > 0) held.await(60, TimeUnit.SECONDS) }
        val a = open()
        awaitTag(a, "update_download:$id")
        Views.tap(a, "update_download:$id")
        val bar = awaitTag(a, "update_bar:$id") as ProgressBar
        awaitTag(a, "update_pause:$id")
        awaitText(a, "update_state:$id", a.getString(R.string.updates_copying))
        assertTrue("some of it is copied", TestCore.onMain { bar.progress } in 1..99)
        assertTrue(Views.textOf(awaitTag(a, "update_percent:$id")).contains("%"))
        noA11yProblems(a)
        Views.tap(a, "update_pause:$id")
        held.countDown()
        awaitText(a, "update_state:$id", a.getString(R.string.updates_paused))
        val before = objectRequests(r)
        Thread.sleep(800)
        assertEquals("paused means no more requests", before, objectRequests(r))
        noA11yProblems(a)
        Views.tap(a, "update_resume:$id")
        awaitText(a, "update_state:$id", a.getString(R.string.updates_done))
        val starts = r.hub.requests.filter { it.first.contains("/objects/") }.map { it.second!!.first }
        assertEquals("what was copied before the pause was not asked for again", listOf(0L, 262_144L, 524_288L), starts.distinct())
        assertEquals(2, core.store.list().single { it.id == id }.version)
    }

    @Test fun leavingTheScreenPausesTheCopyAndKeepsTheBytes() {
        val r = rig()
        val held = CountDownLatch(1)
        r.hub.gate = { range -> if (range.first > 0) held.await(60, TimeUnit.SECONDS) }
        val a = open()
        awaitTag(a, "update_download:$id")
        Views.tap(a, "update_download:$id")
        awaitTag(a, "update_pause:$id")
        val controller = checkNotNull(core.updates?.controller)
        Views.finish(a); screen = null
        // Let go of the held answer only once the screen is gone and has paused the copy (else the fast fake finishes first).
        val gone = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < gone && !TestCore.onMain { a.isDestroyed }) Thread.sleep(50)
        held.countDown()
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && controller.view().rows.single().state !is UpdateController.State.Paused) Thread.sleep(100)
        assertTrue(controller.view().rows.single().state is UpdateController.State.Paused)
        assertEquals("still the old pack", 1, core.store.list().single { it.id == id }.version)
    }

    @Test fun aFileThatIsNotTheSignedOneIsRefusedWithTheReasonBesideItsAction() {
        val r = rig()
        val bad = r.next.zip.copyOf().also { it[300] = (it[300] + 1).toByte() }
        r.hub.objects[UpdateKit.hex(r.next.entry.digest)] = bad
        val a = open()
        awaitTag(a, "update_download:$id")
        Views.tap(a, "update_download:$id")
        awaitText(a, "update_state:$id", a.getString(R.string.updates_fail_untrusted))
        assertEquals("the old pack stays", 1, core.store.list().single { it.id == id }.version)
        assertNotNull("and the person may try again", Views.tagged(a, "update_download:$id"))
        noA11yProblems(a)
    }

    @Test fun withoutTheHubWifiThePersonIsToldToJoinItAndNothingIsAsked() {
        val r = rig(wifi = false)
        val a = open()
        awaitText(a, "updates_notice", a.getString(R.string.updates_notice_no_wifi))
        assertTrue(r.hub.requests.isEmpty())
        assertNull(Views.tagged(a, "update_download:$id"))
        noA11yProblems(a)
    }

    @Test fun aWifiThatComesUpAfterTheScreenOpenedIsLookedAtAgain() {
        val r = rig(wifi = false)
        val a = open()
        awaitText(a, "updates_notice", a.getString(R.string.updates_notice_no_wifi))
        assertTrue(r.hub.requests.isEmpty())
        // The hub's Wi-Fi is joined a moment later: AppCore tells every screen something changed.
        r.wifi = true
        TestCore.onMain {
            core.hub = object : HubLink {
                override fun wire(): org.phonemesh.core.hub.HubWire = object : org.phonemesh.core.hub.HubWire {
                    override fun send(port: Int, packet: ByteArray) = org.phonemesh.core.hub.HubClient.Sent.STORED
                    override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean) = emptyList<org.phonemesh.core.hub.HubClient.Mail>()
                    override fun latest() = 0L
                }
                override fun join(card: org.phonemesh.core.hub.HubCard, changed: () -> Unit) = Unit
                override fun leave() = Unit
            }
            (a as UpdatesActivity).onCoreChange(AppCore.Change.RADIO)
        }
        awaitTag(a, "update_download:$id")
        assertEquals("looked again, and still asked for nothing to copy", 0, objectRequests(r))
    }

    @Test fun anHubWithNothingNewSaysSo() {
        val r = rig(offer = false)
        val a = open()
        awaitText(a, "updates_notice", a.getString(R.string.updates_notice_none))
        assertEquals(0, objectRequests(r))
    }

    @Test fun damagedUpdateStateSaysUpdatesAreOff() {
        rig()
        TestCore.onMain { core.updates = HubUpdates(null, R.string.updates_off) }
        val a = open()
        awaitText(a, "updates_notice", a.getString(R.string.updates_off))
        assertNull(Views.tagged(a, "updates_check"))
    }

    @Test fun theLocalHubScreenOpensUpdatesAndAnnouncesAnOfferAlreadyHeld() {
        rig(direct = true)
        TestCore.onMain {
            core.hub = object : HubLink {
                override fun wire(): org.phonemesh.core.hub.HubWire? = null
                override fun join(card: org.phonemesh.core.hub.HubCard, changed: () -> Unit) = Unit
                override fun leave() = Unit
            }
            core.settings.hubCard = org.phonemesh.core.hub.HubCard("Village", "password1", 8443, org.phonemesh.core.hub.HubCard.pinOf(ByteArray(91) { it.toByte() })).encode()
            core.updates!!.controller!!.refresh()
        }
        val hubScreen = Views.start(HubActivity::class.java).also { screen = it }
        assertNotNull(Views.tagged(hubScreen, "hub_updates"))
        assertEquals(hubScreen.getString(R.string.updates_ready), Views.textOf(awaitTag(hubScreen, "hub_updates_ready")))
        noA11yProblems(hubScreen)
        Views.tap(hubScreen, "hub_updates")
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && Views.resumed() !is UpdatesActivity) Thread.sleep(100)
        val updates = Views.resumed() as UpdatesActivity
        awaitTag(updates, "update_download:$id")
    }

    @Test fun theHubScreenHidesUpdatesWhenNoHubIsSaved() {
        rig(direct = true)
        val hubScreen = Views.start(HubActivity::class.java).also { screen = it }
        assertNull(Views.tagged(hubScreen, "hub_updates"))
        assertNull(Views.tagged(hubScreen, "hub_updates_ready"))
    }

    // ---- U7: honest words on the screen

    @Test fun testKeysAreSaidOnTheScreenWhileTheyAreInUseAndNeverOtherwise() {
        rig(testKeys = true)
        val a = open()
        awaitText(a, "updates_test", a.getString(R.string.updates_test_banner))
        noA11yProblems(a)
        Views.finish(a); screen = null
        TestCore.uninstall()
        rig()
        val b = open()
        awaitTag(b, "update_download:$id")
        assertNull("real keys: no banner", Views.tagged(b, "updates_test"))
    }

    @Test fun aBuildThatIsNotSetUpForUpdatesSaysSoPlainlyAndOffersNothing() {
        rig()
        TestCore.onMain { core.updates = HubUpdates(null, R.string.updates_not_set_up) }
        val a = open()
        awaitText(a, "updates_notice", a.getString(R.string.updates_not_set_up))
        assertNull("nothing to press", Views.tagged(a, "updates_check"))
        assertNull(Views.tagged(a, "update_download:$id"))
        assertNull(Views.tagged(a, "updates_test"))
        noA11yProblems(a)
    }

    @Test fun anUnreadyBuildIsNotCalledDamagedData() {
        rig()
        TestCore.onMain { core.updates = HubUpdates(null, R.string.updates_off_build) }
        val a = open()
        awaitText(a, "updates_notice", a.getString(R.string.updates_off_build))
    }

    @Test fun theDateOfTheHeldListIsShownBesideTheOffer() {
        rig()
        val a = open()
        awaitTag(a, "update_download:$id")
        val line = Views.textOf(awaitTag(a, "updates_list_date"))
        assertTrue(line, line.contains("2026"))
    }

    @Test fun aListFromAnOlderHubIsNotCalledUpToDate() {
        val r = rig(offer = false)
        r.hub.catalog = UpdateKit.catalog(r.keys, 1, 1, listOf(UpdateKit.voice(id, core.store.list().single { it.id == id }.version).entry))
        val a = open()
        // The same list as the one held: up to date. An older one would say the hub is behind; here the numbers are equal.
        awaitText(a, "updates_notice", a.getString(R.string.updates_notice_current))
        noA11yProblems(a)
    }

    @Test fun anEngineUpdateTellsThePersonToRestartTheApp() {
        rig(engine = true)
        val a = open()
        val tag = "engine-whisper-tiny"
        awaitTag(a, "update_download:$tag")
        Views.tap(a, "update_download:$tag")
        awaitText(a, "update_state:$tag", a.getString(R.string.updates_done_restart))
        noA11yProblems(a)
    }

    @Test fun aPackTheListWithdrawsIsNamedAndNoLongerCounted() {
        val r = rig(offer = false)
        val have = core.store.list().single { it.id == id }
        val entry = UpdateKit.voice(id, have.version).entry
        core.updates!!.trust!!.recordInstalled(entry)
        r.hub.catalog = UpdateKit.catalog(r.keys, 1, 2, listOf(entry), revoked = listOf(entry.digest))
        val a = open()
        val line = Views.textOf(awaitTag(a, "update_withdrawn:$id"))
        assertTrue(line, line.contains(have.label))
        assertNotNull(Views.tagged(a, "update_withdrawn_fix:$id"))
        assertTrue("the pack is not loaded any more", core.store.blockedIds.contains(id))
        assertTrue(core.readiness() !is org.phonemesh.core.Readiness.Ready)
        noA11yProblems(a)
    }
}

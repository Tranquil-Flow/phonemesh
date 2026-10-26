package org.phonemesh.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubDelivery
import org.phonemesh.core.hub.HubWire
import org.phonemesh.core.hub.MailPaths
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hub screen, roaming opt-in and its service lifecycle (accounts step 16), on the real AppCore, Settings and screens with
 * a fake [HubLink] pair and a fake [RoamingHost]. UNEXECUTED: written without a device or a compiler. The real
 * HubRoamingService, startForeground, notification and Android's start rules are NOT covered here (see INTEGRATION.md).
 */
@RunWith(AndroidJUnit4::class)
class HubScreenTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val core get() = TestCore.core
    private val card = HubCard("Village", "password1", 8443, HubCard.pinOf(ByteArray(91) { it.toByte() }))
    private var activity: Activity? = null

    @After fun tearDown() {
        Views.finish(Views.resumed())
        Views.finish(activity)
        TestCore.uninstall()
    }

    /** A wire that is only a connection until a request is made on it. */
    private class WireStub : HubWire {
        @Volatile var down = false
        var mailGate: java.util.concurrent.CountDownLatch? = null
        val sent = CopyOnWriteArrayList<ByteArray>()
        override fun send(port: Int, packet: ByteArray): HubClient.Sent {
            sent += packet.copyOf()
            if (down) throw IOException("no hub")
            return HubClient.Sent.STORED
        }
        override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<HubClient.Mail> {
            try { mailGate?.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("fixture poll cancelled").apply { initCause(failure) }
            }
            return emptyList()
        }
        override fun latest(): Long = 0L
    }

    @Test fun blockingWireCancellationPreservesTheIOExceptionContract() {
        TestCore.install(connected = false)
        val wire = WireStub().apply { mailGate = java.util.concurrent.CountDownLatch(1) }
        val started = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Pair<Throwable, Boolean>>()
        val worker = Thread({
            started.countDown()
            try { wire.mail(0, emptyList(), 0, false) }
            catch (failure: Throwable) { outcome.set(failure to Thread.currentThread().isInterrupted) }
            finally { done.countDown() }
        }, "phonemesh-screen-wire-cancel")
        worker.start()
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("the fake must obey the production HubWire failure contract",
                outcome.get()?.first is java.io.InterruptedIOException)
            assertEquals(true, outcome.get()?.second)
        } finally {
            wire.mailGate!!.countDown()
            worker.interrupt()
            worker.join(5_000)
            assertFalse(worker.isAlive)
        }
    }

    private class LinkFake : HubLink {
        @Volatile var wire: HubWire? = null
        var joins = 0
        var leaves = 0
        private var changed: (() -> Unit)? = null
        override fun wire(): HubWire? = wire
        override fun join(card: HubCard, changed: () -> Unit) { joins++; this.changed = changed }
        override fun leave() { leaves++; wire = null }
        fun up(w: HubWire) { wire = w; changed?.invoke() }
        fun down() { wire = null; changed?.invoke() }
    }

    private class HostFake : RoamingHost {
        @Volatile var accept = true
        val starts = CopyOnWriteArrayList<Int>()
        @Volatile var stops = 0
        override fun start(epoch: Int): Boolean { if (!accept) return false; starts += epoch; return true }
        override fun stop() { stops++ }
    }

    private class Rig(val wifi: LinkFake, val ble: LinkFake, val host: HostFake)

    private fun rig(saved: Boolean = true, language: String = "fr"): Rig {
        TestCore.install(language = language)
        val made = Rig(LinkFake(), LinkFake(), HostFake())
        TestCore.onMain {
            if (saved) core.settings.hubCard = card.encode()
            core.hub = made.wifi
            core.bluetoothHub = made.ble
            core.roamingHost = made.host
        }
        return made
    }

    private fun words(id: Int) = core.localized().getString(id)

    /** Views.waitUntil runs its predicate on the main thread, where TestCore.onMain must not be called. */
    private fun <T> here(block: () -> T): T =
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else TestCore.onMain(block)

    private fun status() = here { core.hubStatus() }

    private fun enable(r: Rig): Int {
        assertTrue(TestCore.onMain { core.setHubRoaming(true) })
        val epoch = r.host.starts.last()
        assertTrue(TestCore.onMain { core.roamingAttach(epoch) })
        return epoch
    }

    // ---- Settings navigation, default off, persistence

    @Test fun settingsOpensTheHubScreenAndRoamingStartsOff() {
        val r = rig(saved = false)
        activity = Views.start(SettingsActivity::class.java)
        assertEquals(words(R.string.hub_none), Views.textOf(Views.tagged(activity!!, "hub_value")!!))
        Views.tap(activity!!, "settings_hub")
        Views.waitUntil { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() is HubActivity }
        val screen = Views.resumed() as HubActivity
        assertEquals(words(R.string.hub_state_none), Views.textOf(Views.tagged(screen, "hub_state")!!))
        val roaming = Views.tagged(screen, "hub_roaming") as Switch
        assertFalse(TestCore.onMain { roaming.isChecked })
        assertEquals(words(R.string.roaming_off), Views.textOf(Views.tagged(screen, "roaming_state")!!))
        assertFalse(core.settings.hubRoaming)
        assertTrue("default off starts nothing", r.host.starts.isEmpty())
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
        Views.finish(screen)
    }

    @Test fun theSwitchPersistsButOnlyTheServiceMakesItRoaming() {
        val r = rig()
        activity = Views.start(HubActivity::class.java)
        Views.tap(activity!!, "hub_roaming")
        assertEquals(1, r.host.starts.size)
        assertTrue(core.settings.hubRoaming)
        assertFalse("asked for, not running yet", core.roamingActive)
        assertEquals(words(R.string.roaming_starting), Views.textOf(Views.tagged(activity!!, "roaming_state")!!))
        assertTrue(TestCore.onMain { core.roamingAttach(r.host.starts[0]) })
        Views.waitUntil { core.roamingActive }
        assertEquals(words(R.string.roaming_on), Views.textOf(Views.tagged(activity!!, "roaming_state")!!))
        Views.finish(activity)
        activity = null
        // A new process: the choice is remembered, but a flag alone is not roaming.
        TestCore.restart()
        val again = HostFake()
        TestCore.onMain { core.roamingHost = again }
        assertTrue(core.settings.hubRoaming)
        assertFalse(core.roamingActive)
        assertTrue(again.starts.isEmpty())
        TestCore.onMain { core.resumeRoaming() }
        assertEquals("a screen starts the standing choice again", 1, again.starts.size)
        assertFalse(core.roamingActive)
    }

    @Test fun aSettingsFlagAloneNeverKeepsTheHubWhenScreensClose() {
        val r = rig()
        TestCore.onMain { core.activityStarted() }
        r.wifi.up(WireStub())
        assertEquals(MailPaths.Path.WIFI_HUB, status().sending)
        TestCore.onMain { core.settings.hubRoaming = true; core.activityStopped() }
        assertNull("the flag alone does not allow a hub with no screen open", status().sending)
        assertTrue(TestCore.onMain { core.setHubRoaming(true) })
        TestCore.onMain { assertTrue(core.roamingAttach(r.host.starts.last())) }
        r.wifi.up(WireStub())
        assertEquals("a running service does", MailPaths.Path.WIFI_HUB, status().sending)
    }

    // ---- Enabling: guards, refusals, timeouts, failures

    @Test fun noHubMeansRefusedWithARemedy() {
        val r = rig(saved = false)
        assertFalse(TestCore.onMain { core.setHubRoaming(true) })
        assertEquals(RoamingBlock.NO_HUB, core.roamingBlock)
        assertEquals(words(R.string.roaming_block_no_hub), core.text(core.lastNote!!))
        assertFalse(core.settings.hubRoaming)
        assertTrue("no service is asked for", r.host.starts.isEmpty())
    }

    @Test fun missingBluetoothPermissionIsCheckedBeforeAnyServiceStart() {
        val r = rig()
        TestCore.access.radioMissing = listOf("android.permission.BLUETOOTH_CONNECT")
        assertFalse(TestCore.onMain { core.setHubRoaming(true) })
        assertEquals(RoamingBlock.PERMISSION, core.roamingBlock)
        assertEquals(words(R.string.roaming_block_permission), core.text(core.lastNote!!))
        assertFalse(core.settings.hubRoaming)
        assertTrue(r.host.starts.isEmpty())
        assertEquals(RoamingGate.STALE, TestCore.onMain { core.roamingGate(0) })
    }

    @Test fun androidRefusingTheStartLeavesRoamingOffWithAWord() {
        val r = rig()
        r.host.accept = false
        assertFalse(TestCore.onMain { core.setHubRoaming(true) })
        assertEquals(RoamingBlock.START_REFUSED, core.roamingBlock)
        assertEquals(words(R.string.roaming_block_refused), core.text(core.lastNote!!))
        assertFalse(core.settings.hubRoaming)
        assertFalse(core.roamingStarting)
    }

    @Test fun aServiceThatNeverReportsIsAFailureNotAFlagLeftOn() {
        val r = rig()
        TestCore.onMain { core.roamingStartWaitMs = 100 }
        assertTrue(TestCore.onMain { core.setHubRoaming(true) })
        Views.waitUntil { !core.settings.hubRoaming }
        assertEquals(RoamingBlock.START_REFUSED, core.roamingBlock)
        assertFalse(core.roamingStarting)
        assertTrue(r.host.stops >= 1)
        assertFalse("the late attach of that start is refused", TestCore.onMain { core.roamingAttach(r.host.starts[0]) })
    }

    @Test fun theServiceDyingTurnsRoamingOffAndClosesBothTransports() {
        val r = rig()
        val epoch = enable(r)
        val wifiLeaves = r.wifi.leaves
        val bleLeaves = r.ble.leaves
        TestCore.onMain { core.roamingServiceEnded(epoch, RoamingBlock.SERVICE_ENDED) }
        assertFalse(core.settings.hubRoaming)
        assertFalse(core.roamingActive)
        assertEquals(RoamingBlock.SERVICE_ENDED, core.roamingBlock)
        assertEquals(words(R.string.roaming_block_ended), core.text(core.lastNote!!))
        assertTrue("no screen is open, so both transports close now", r.wifi.leaves > wifiLeaves && r.ble.leaves > bleLeaves)
    }

    // ---- Disabling, forgetting, late callbacks

    @Test fun disablingStopsTheServiceAndBothTransports() {
        val r = rig()
        enable(r)
        val stops = r.host.stops
        val wifiLeaves = r.wifi.leaves
        val bleLeaves = r.ble.leaves
        assertTrue(TestCore.onMain { core.setHubRoaming(false) })
        assertFalse(core.settings.hubRoaming)
        assertFalse(core.roamingActive)
        assertTrue(r.host.stops > stops)
        assertTrue(r.wifi.leaves > wifiLeaves && r.ble.leaves > bleLeaves)
    }

    @Test fun forgettingTheHubFromTheScreenStopsRoamingAndBothTransports() {
        val r = rig()
        activity = Views.start(HubActivity::class.java)
        enable(r)
        val stops = r.host.stops
        val wifiLeaves = r.wifi.leaves
        val bleLeaves = r.ble.leaves
        Views.tap(activity!!, "hub_forget")
        val dialog: AlertDialog = checkNotNull(TestCore.onMain { (activity as HubActivity).forgetDialogForTest })
        TestCore.onMain { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        inst.waitForIdleSync()
        assertNull(core.settings.hubCard)
        assertFalse(core.settings.hubRoaming)
        assertFalse(core.roamingActive)
        assertTrue(r.host.stops > stops)
        assertTrue("Wi-Fi closed", r.wifi.leaves > wifiLeaves)
        assertTrue("Bluetooth closed", r.ble.leaves > bleLeaves)
        Views.waitUntil { (Views.descendants(activity!!.window.decorView).firstOrNull { it.tag == "hub_state" } as TextView).text == words(R.string.hub_state_none) }
    }

    @Test fun lateCallbacksCannotRestoreADisabledService() {
        val r = rig()
        val first = enable(r)
        TestCore.onMain { core.activityStarted(); core.activityStopped() }
        assertTrue(TestCore.onMain { core.setHubRoaming(false) })
        val joins = r.ble.joins
        TestCore.onMain {
            assertFalse("a late attach", core.roamingAttach(first))
            assertEquals(RoamingGate.STALE, core.roamingGate(first))
            core.roamingServiceEnded(first, RoamingBlock.SERVICE_ENDED)
        }
        assertNull("a late end of a stopped service says nothing", core.roamingBlock)
        assertFalse(core.roamingActive)
        r.wifi.up(WireStub())
        TestCore.onMain { }
        assertEquals("a late Wi-Fi callback does not rejoin Bluetooth", joins, r.ble.joins)
        assertNull("and does not allow the hub with no screen", status().sending)
        // A newer service is not ended by the older one's late callback.
        val second = enable(r)
        TestCore.onMain { core.roamingServiceEnded(first, RoamingBlock.SERVICE_ENDED) }
        assertTrue(core.roamingActive)
        assertTrue(second != first)
    }

    @Test fun roamingKeepsTheRadioFallbackAfterTheLastScreenGrace() {
        val r = rig()
        enable(r)
        val before = TestCore.radio.disconnects
        val grace = AppCore::class.java.getDeclaredField("backgroundStop").let {
            it.isAccessible = true
            it.get(core) as Runnable
        }
        TestCore.onMain { grace.run() }
        assertEquals("a running roaming service must retain its radio fallback", before, TestCore.radio.disconnects)
        TestCore.onMain { core.setHubRoaming(false) }
        assertTrue("switching off without a screen releases the retained radio", TestCore.radio.disconnects > before)
    }

    @Test fun roamingLooksAgainAfterAnEmptyAttemptAndStopsWhenDisabled() {
        val r = rig()
        enable(r)
        val before = r.ble.joins
        // Real production interval: absence of the retry is a behavioral RED, not a missing test hook.
        Views.waitUntil(65_000) { r.ble.joins > before }
        TestCore.onMain { core.setHubRoaming(false) }
        val after = r.ble.joins
        Thread.sleep(61_000)
        assertEquals("disabled roaming must not restart a background scan", after, r.ble.joins)
    }

    @Test fun foregroundLooksAgainAfterAnEmptyAttemptWithoutReopening() {
        val r = rig()
        activity = Views.start(HubActivity::class.java)
        val before = r.ble.joins
        assertFalse(core.settings.hubRoaming)
        Views.waitUntil(65_000) { r.ble.joins > before }
        assertTrue("foreground recovery must not opt into background roaming", r.host.starts.isEmpty())
        TestCore.onMain { core.forgetHub() }
        val after = r.ble.joins
        Thread.sleep(61_000)
        assertEquals("forget cancels rediscovery", after, r.ble.joins)
    }

    @Test fun foregroundRecoversAfterAConnectedHubDisappearsAndItsNextScanMisses() {
        val r = rig()
        activity = Views.start(HubActivity::class.java)
        TestCore.onMain { r.ble.up(WireStub()) }
        Views.waitUntil { status().bluetooth == HubWay.ANSWERED }
        TestCore.onMain { r.ble.down() }
        inst.waitForIdleSync()
        val afterImmediateAttempt = r.ble.joins
        Views.waitUntil(65_000) { r.ble.joins > afterImmediateAttempt }
        TestCore.onMain { r.ble.up(WireStub()) }
        Views.waitUntil { status().bluetooth == HubWay.ANSWERED }
        assertFalse(core.settings.hubRoaming)
    }

    @Test fun hubForgetDialogIsReadableInEnglish() = readableForget("en-US")
    @Test fun hubForgetDialogIsReadableInFrench() = readableForget("fr")

    private fun readableForget(language: String) {
        rig(language = language)
        val screen = Views.start(HubActivity::class.java).also { activity = it }
        Views.tap(screen, "hub_forget")
        val dialog = checkNotNull(TestCore.onMain { screen.forgetDialogForTest })
        Views.waitUntil {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).let { it.isShown && it.width > 0 && it.height > 0 } &&
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).let { it.isShown && it.width > 0 && it.height > 0 }
        }
        assertEquals("actual $language dialog window", emptyList<String>(), A11yCheck.problems(dialog))
        TestCore.onMain { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
        inst.waitForIdleSync()
        assertEquals("Close must retain the saved hub", card.encode(), core.settings.hubCard)
        assertNull(TestCore.onMain { screen.forgetDialogForTest })
    }

    @Test fun hubRefusalCopyIsTruthfulInEnglish() = failureCopy("en-US", refused = true)
    @Test fun hubRefusalCopyIsTruthfulInFrench() = failureCopy("fr", refused = true)
    @Test fun hubConnectionFailureKeepsItsOwnCopyInEnglish() = failureCopy("en-US", refused = false)
    @Test fun hubConnectionFailureKeepsItsOwnCopyInFrench() = failureCopy("fr", refused = false)

    private fun failureCopy(language: String, refused: Boolean) {
        val r = rig(language = language)
        TestCore.radio.acceptBroadcasts = true
        val bea = PersonChatTest.Bea()
        TestCore.onMain { core.accountsFlow.showCode() }
        val added = TestCore.onMain { core.accountsFlow.scanned(AddCode.of(bea.qr)) } as Accounts.Added.Contact
        val screen = Views.start(HubActivity::class.java).also { activity = it }
        val pollRelease = java.util.concurrent.CountDownLatch(1)
        val sent = CopyOnWriteArrayList<ByteArray>()
        val wire = object : HubWire {
            override fun send(port: Int, packet: ByteArray): HubClient.Sent {
                sent += packet.copyOf()
                if (!refused) throw IOException("injected connection failure")
                return HubClient.Sent.REFUSED
            }
            override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<HubClient.Mail> {
                // An unrelated successful poll must not erase the send-failure UI under inspection.
                check(pollRelease.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "poll was not released" }
                return emptyList()
            }
            override fun latest(): Long = 0L
        }
        try {
            TestCore.onMain { r.wifi.up(wire) }
            Views.waitUntil { status().sending == MailPaths.Path.WIFI_HUB }
            TestCore.onMain { core.setDraft(added.chatId, "hub copy fixture"); assertTrue(core.send(added.chatId)) }
            val result = if (refused) HubDelivery.Result.REFUSED else HubDelivery.Result.NOT_REACHED
            // The widget exists from onCreate but is intentionally hidden until the async send fails.
            val failure = TestCore.onMain { Views.descendants(screen.window.decorView).first { it.tag == "hub_failure" } as TextView }
            Views.waitUntil { status().failure?.result == result && failure.isShown }
            val expected = if (!refused) words(R.string.hub_failure_silent)
                else if (language == "fr") "Le relais a refusé ce message." else "Hub refused this message."
            assertEquals(expected, Views.textOf(failure))
            assertEquals("a refusal must not discard or replace the pin/card", card.encode(), core.settings.hubCard)
            assertFalse(status().wifi == HubWay.ANSWERED)
            Views.waitUntil { sent.isNotEmpty() && TestCore.radio.broadcasts.any { it.payload.contentEquals(sent.first()) } }
            TestCore.onMain {
                assertTrue("fallback retains the exact durable ciphertext", core.history.outbox(added.account.toString())
                    .any { it.packet.contentEquals(sent.first()) && it.state != "DELIVERED" })
            }
        } finally {
            // Fence the pending poll before releasing it; never leave fixture IO waiting for teardown.
            try { TestCore.onMain { core.forgetHub() } } finally { pollRelease.countDown() }
        }
    }

    // ---- Status never claims more than was proven

    @Test fun aLinkAloneIsLinkedNotAnswered() {
        val r = rig()
        activity = Views.start(HubActivity::class.java)
        val state = Views.tagged(activity!!, "hub_state") as TextView
        assertEquals(words(R.string.hub_state_searching), Views.textOf(state))
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            r.wifi.up(WireStub().apply { mailGate = gate })
            Views.waitUntil { state.text.toString() == words(R.string.hub_state_linked) }
            assertEquals(HubWay.LINKED, status().wifi)
            assertFalse(state.text.toString() == words(R.string.hub_state_answered))
        } finally { gate.countDown() }
    }

    @Test fun onlyAnAnswerOverThePinnedKeyEarnsAnsweredAndAFailureIsShownWithARemedy() {
        val r = rig()
        TestCore.radio.acceptBroadcasts = true
        val bea = PersonChatTest.Bea()
        TestCore.onMain { core.accountsFlow.showCode() }
        val added = TestCore.onMain { core.accountsFlow.scanned(AddCode.of(bea.qr)) } as Accounts.Added.Contact
        activity = Views.start(HubActivity::class.java)
        val wire = WireStub()
        r.wifi.up(wire)
        Views.waitUntil { status().wifi == HubWay.LINKED }
        TestCore.onMain { core.setDraft(added.chatId, "par le relais"); assertTrue(core.send(added.chatId)) }
        Views.waitUntil { status().wifi == HubWay.ANSWERED }
        assertEquals(1, wire.sent.size)
        val state = Views.tagged(activity!!, "hub_state") as TextView
        Views.waitUntil { state.text.toString() == words(R.string.hub_state_answered) }
        // Decision 39: while Wi-Fi answers, Bluetooth is not needed, so the screen does not say it is still looking.
        val bluetooth = Views.tagged(activity!!, "hub_bluetooth") as TextView
        assertEquals(activity!!.getString(R.string.hub_way_bluetooth, words(R.string.hub_way_not_needed)), Views.textOf(bluetooth))
        wire.down = true
        TestCore.onMain { core.setDraft(added.chatId, "relais perdu"); assertTrue(core.send(added.chatId)) }
        Views.waitUntil { status().failure != null }
        assertEquals(HubDelivery.Result.NOT_REACHED, status().failure!!.result)
        assertFalse("a failed way is no longer answered", status().wifi == HubWay.ANSWERED)
        val failure = Views.tagged(activity!!, "hub_failure")
        assertNotNull(failure)
        assertEquals(words(R.string.hub_failure_silent), Views.textOf(failure!!))
    }
}

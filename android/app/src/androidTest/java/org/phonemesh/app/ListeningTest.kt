package org.phonemesh.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubWire
import java.util.concurrent.CopyOnWriteArrayList

/**
 * v0.1.3 N3: keeping listening while the app is closed, with a paired radio or a trusted hub (either is enough), and
 * honouring a stop the person made in Android. Real AppCore, Settings and screens; fake radio, hub links, service host
 * and exit record. The real foreground service and Android's process rules are not exercised here; its notification
 * is only built, and Android is only asked whether it would show it (N3b).
 */
@RunWith(AndroidJUnit4::class)
class ListeningTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val core get() = TestCore.core
    private val card = HubCard("Village", "password1", 8443, HubCard.pinOf(ByteArray(91) { it.toByte() }))
    private var activity: Activity? = null

    @After fun tearDown() {
        Views.finish(activity)
        TestCore.uninstall()
    }

    private class LinkFake : HubLink {
        @Volatile var wire: HubWire? = null
        var joins = 0
        var leaves = 0
        override fun wire(): HubWire? = wire
        override fun join(card: HubCard, changed: () -> Unit) { joins++ }
        override fun leave() { leaves++; wire = null }
    }

    private class HostFake : RoamingHost {
        val starts = CopyOnWriteArrayList<Int>()
        @Volatile var stops = 0
        /** What Android would say about showing the listening notification (Android 13+ permission, app, channel). */
        @Volatile var shown = true
        /** Each time the running service's notification is posted again: connected or not. */
        val updates = CopyOnWriteArrayList<Boolean>()
        override fun start(epoch: Int): Boolean { starts += epoch; return true }
        override fun stop() { stops++ }
        override fun notificationShown() = shown
        override fun update(connected: Boolean, words: android.content.Context) { updates += connected }
    }

    private class Rig(val wifi: LinkFake, val ble: LinkFake, val host: HostFake)

    private fun rig(hub: Boolean, radio: Boolean): Rig {
        TestCore.install()
        val made = Rig(LinkFake(), LinkFake(), HostFake())
        TestCore.onMain {
            if (hub) core.settings.hubCard = card.encode()
            if (radio) { core.settings.radioAddress = RADIO; core.settings.radioName = "Kofi" }
            core.hub = made.wifi
            core.bluetoothHub = made.ble
            core.roamingHost = made.host
        }
        return made
    }

    private fun words(id: Int) = core.localized().getString(id)

    private fun enable(r: Rig): Int {
        assertTrue("listening may start", TestCore.onMain { core.setHubRoaming(true) })
        val epoch = r.host.starts.last()
        // As the real service does: it builds its notification from roamingNotice() before it attaches.
        TestCore.onMain { core.roamingNotice() }
        assertTrue(TestCore.onMain { core.roamingAttach(epoch) })
        return epoch
    }

    /** One of AppCore's own timers, run now instead of after its real delay. */
    private fun timer(name: String): Runnable = AppCore::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(core) as Runnable
    }

    /** A new process with a fresh service host, as after Android ended the app. */
    private fun newProcess(): HostFake {
        TestCore.restart()
        val again = HostFake()
        TestCore.onMain { core.roamingHost = again }
        return again
    }

    // ---- Either route is enough; neither is refused

    @Test fun aSavedRadioAloneIsEnoughToListen() {
        val r = rig(hub = false, radio = true)
        assertTrue(TestCore.onMain { core.setHubRoaming(true) })
        assertEquals("one service is asked for", 1, r.host.starts.size)
        assertTrue(core.settings.hubRoaming)
        assertTrue(TestCore.onMain { core.roamingAttach(r.host.starts[0]) })
        assertTrue(core.roamingActive)
        assertNull(core.roamingBlock)
        assertEquals("no hub is joined without a hub", 0, r.wifi.joins + r.ble.joins)
    }

    @Test fun noRadioAndNoHubIsRefusedWithARemedy() {
        val r = rig(hub = false, radio = false)
        assertFalse(TestCore.onMain { core.setHubRoaming(true) })
        assertEquals(RoamingBlock.NO_ROUTE, core.roamingBlock)
        assertEquals(words(R.string.roaming_block_no_route), core.text(core.lastNote!!))
        assertFalse(core.settings.hubRoaming)
        assertTrue("nothing is asked of Android", r.host.starts.isEmpty())
    }

    // ---- While listening with only a radio

    @Test fun listeningKeepsTheRadioAndReconnectsItWithoutAHub() {
        val r = rig(hub = false, radio = true)
        TestCore.onMain { TestCore.radio.connected(false) }
        enable(r)
        assertEquals("the start reconnects the saved radio", listOf(RADIO), TestCore.radio.connects)
        val disconnects = TestCore.radio.disconnects
        TestCore.onMain { timer("backgroundStop").run() }
        assertEquals("the last screen's grace keeps the radio while listening", disconnects, TestCore.radio.disconnects)
        // That attempt failed; the next retry tick asks again, though no hub is saved.
        TestCore.onMain { TestCore.radio.failConnect() }
        TestCore.onMain { timer("roamingRetryTick").run() }
        assertEquals(listOf(RADIO, RADIO), TestCore.radio.connects)
        assertEquals("still no hub work", 0, r.wifi.joins + r.ble.joins)
    }

    // ---- Forgetting routes

    @Test fun forgettingTheRadioKeepsListeningWhileAHubIsSaved() {
        val r = rig(hub = true, radio = true)
        enable(r)
        val stops = r.host.stops
        TestCore.onMain { core.disconnect() }
        assertNull(core.settings.radioAddress)
        assertTrue(core.settings.hubRoaming)
        assertTrue(core.roamingActive)
        assertEquals(stops, r.host.stops)
    }

    @Test fun forgettingTheLastRouteStopsListeningAndItsRetries() {
        val r = rig(hub = false, radio = true)
        enable(r)
        val stops = r.host.stops
        TestCore.onMain { core.disconnect() }
        assertFalse(core.settings.hubRoaming)
        assertFalse(core.roamingActive)
        assertTrue("the service is stopped", r.host.stops > stops)
        val connects = TestCore.radio.connects.size
        TestCore.onMain { TestCore.radio.connected(false); timer("roamingRetryTick").run() }
        assertEquals("no retry after the stop", connects, TestCore.radio.connects.size)
    }

    @Test fun forgettingTheHubKeepsListeningWhileARadioIsSaved() {
        val r = rig(hub = true, radio = true)
        enable(r)
        val stops = r.host.stops
        TestCore.onMain { core.forgetHub() }
        assertNull(core.settings.hubCard)
        assertTrue(core.settings.hubRoaming)
        assertTrue(core.roamingActive)
        assertEquals(stops, r.host.stops)
        assertTrue("the hub's ways are closed", r.wifi.leaves > 0 && r.ble.leaves > 0)
    }

    @Test fun forgettingTheHubOnItsScreenSaysListeningGoesOnByRadio() {
        val r = rig(hub = true, radio = true)
        val screen: HubActivity = Views.start(HubActivity::class.java)
        activity = screen
        enable(r)
        Views.tap(screen, "hub_forget")
        val dialog = checkNotNull(TestCore.onMain { screen.forgetDialogForTest })
        TestCore.onMain { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
        inst.waitForIdleSync()
        assertNull(core.settings.hubCard)
        assertTrue(core.roamingActive)
        assertEquals("not \"background use is off\"", words(R.string.hub_forgotten_listening), core.text(core.lastNote!!))
    }

    // ---- A stop the person made in Android

    @Test fun aStopFromAndroidAfterListeningStartedTurnsItOffWithAWord() {
        val r = rig(hub = true, radio = false)
        enable(r)
        // Force stop, Active apps Stop or a swipe that ended the app, after this start.
        TestCore.exits.stoppedAt = core.settings.listeningSince + 1
        val again = newProcess()
        TestCore.onMain { core.resumeRoaming() }
        assertTrue("the next screen does not start it again", again.starts.isEmpty())
        assertFalse(core.settings.hubRoaming)
        assertEquals(RoamingBlock.STOPPED_BY_PERSON, core.roamingBlock)
        assertEquals(words(R.string.roaming_block_stopped), core.text(core.lastNote!!))
        // The person may turn it on again; the same old record does not undo that.
        assertTrue(TestCore.onMain { core.setHubRoaming(true) })
        assertEquals(1, again.starts.size)
    }

    @Test fun aStopFromBeforeTheLatestStartChangesNothing() {
        val r = rig(hub = true, radio = false)
        enable(r)
        assertTrue("each start is dated", core.settings.listeningSince > 0)
        TestCore.exits.stoppedAt = core.settings.listeningSince - 1
        val again = newProcess()
        TestCore.onMain { core.resumeRoaming() }
        assertEquals("an older stop does not undo a newer choice", 1, again.starts.size)
        assertTrue(core.settings.hubRoaming)
    }

    @Test fun aLostPermissionRefusesOnTheNextScreen() {
        val r = rig(hub = true, radio = false)
        enable(r)
        val again = newProcess()
        TestCore.access.radioMissing = listOf("android.permission.BLUETOOTH_CONNECT")
        TestCore.onMain { core.resumeRoaming() }
        assertTrue(again.starts.isEmpty())
        assertFalse(core.settings.hubRoaming)
        assertEquals(RoamingBlock.PERMISSION, core.roamingBlock)
        assertEquals(words(R.string.roaming_block_permission), core.text(core.lastNote!!))
    }

    // ---- The screen being opened says why listening stayed off (N3b: its own start raises the refusal)

    private fun shownNote(screen: Activity): String? = Views.tagged(screen, ScreenActivity.NOTE_TAG)?.let { Views.textOf(it) }

    @Test fun theNextScreenShowsThatAStopFromAndroidKeptListeningOff() {
        val r = rig(hub = false, radio = true)
        enable(r)
        TestCore.exits.stoppedAt = core.settings.listeningSince + 1
        val again = newProcess()
        val chats: ChatsActivity = Views.start(ChatsActivity::class.java)
        activity = chats
        assertTrue("nothing is started again", again.starts.isEmpty())
        assertFalse(core.settings.hubRoaming)
        assertEquals("the screen itself says so, not only the core", words(R.string.roaming_block_stopped), shownNote(chats))
    }

    @Test fun theNextScreenShowsThatALostPermissionKeptListeningOff() {
        val r = rig(hub = false, radio = true)
        enable(r)
        val again = newProcess()
        TestCore.access.radioMissing = listOf("android.permission.BLUETOOTH_CONNECT")
        val chats: ChatsActivity = Views.start(ChatsActivity::class.java)
        activity = chats
        assertTrue(again.starts.isEmpty())
        assertFalse(core.settings.hubRoaming)
        assertEquals(words(R.string.roaming_block_permission), shownNote(chats))
    }

    @Test fun aScreenDoesNotRepeatANoteFromBeforeItStarted() {
        val r = rig(hub = false, radio = true)
        enable(r)
        // Raised while no screen was open; nobody saw it, and it is not news by the time a screen opens.
        TestCore.onMain { core.note(UiText(R.string.add_name_first)) }
        val chats: ChatsActivity = Views.start(ChatsActivity::class.java)
        activity = chats
        assertTrue("listening carries on", core.roamingActive)
        assertNull(Views.tagged(chats, "note_box"))
    }

    // ---- What the running service's notification says, and whether Android shows it (N3b)

    @Test fun theNotificationSaysWhenNoRouteIsConnectedAndWhenOneIsBack() {
        val r = rig(hub = false, radio = true)
        TestCore.onMain { TestCore.radio.connected(false) }
        enable(r)
        assertFalse("posted as not connected", TestCore.onMain { core.listeningConnected() })
        assertTrue("nothing changed since it was posted", r.host.updates.isEmpty())
        TestCore.onMain { TestCore.radio.connected(true) }
        assertEquals(listOf(true), r.host.updates.toList())
        TestCore.onMain { TestCore.radio.connected(false) }
        assertEquals(listOf(true, false), r.host.updates.toList())
        // A retry that changes nothing does not post it again.
        TestCore.onMain { timer("roamingRetryTick").run() }
        assertEquals(listOf(true, false), r.host.updates.toList())
    }

    @Test fun nothingIsPostedOnceListeningStopped() {
        val r = rig(hub = false, radio = true)
        TestCore.onMain { TestCore.radio.connected(false) }
        enable(r)
        TestCore.onMain { core.setHubRoaming(false) }
        TestCore.onMain { TestCore.radio.connected(true) }
        assertTrue("no notification may appear after the stop", r.host.updates.isEmpty())
    }

    @Test fun aScreenOpenedAfterNotificationsWereAllowedPostsTheNotificationOnce() {
        val r = rig(hub = false, radio = true)
        r.host.shown = false
        enable(r)
        assertTrue(r.host.updates.isEmpty())
        // The person allowed notifications in Android while listening ran without one.
        r.host.shown = true
        val first: ChatsActivity = Views.start(ChatsActivity::class.java)
        Views.finish(first)
        assertEquals("posted again, so Android shows it now", 1, r.host.updates.size)
        val second: ChatsActivity = Views.start(ChatsActivity::class.java)
        activity = second
        assertEquals("not again while nothing changed", 1, r.host.updates.size)
    }

    @Test fun bothScreensSayWhenListeningRunsWithoutANotification() {
        val r = rig(hub = true, radio = true)
        r.host.shown = false
        enable(r)
        val settings: SettingsActivity = Views.start(SettingsActivity::class.java)
        assertEquals(words(R.string.roaming_on_hidden), Views.textOf(Views.tagged(settings, "listening_state")!!))
        Views.finish(settings)
        val hub: HubActivity = Views.start(HubActivity::class.java)
        activity = hub
        assertEquals(words(R.string.roaming_on_hidden), Views.textOf(Views.tagged(hub, "roaming_state")!!))
    }

    /** The notification the real service posts, built here and never posted (tests run without notification permission). */
    @Test fun theListeningNotificationHasASecondLineOnlyWhileNothingIsConnected() {
        val context = inst.targetContext
        val fr = Locales.wrap(context, "fr")
        val off = HubRoamingService.build(context, fr, connected = false)
        assertEquals(HubRoamingService.CHANNEL, off.channelId)
        assertEquals(fr.getString(R.string.roaming_notification), off.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Pas connecté. Nouvel essai chaque minute.", off.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue("posting it again never sounds", off.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(fr.getString(R.string.roaming_stop), off.actions.single().title.toString())
        val on = HubRoamingService.build(context, fr, connected = true)
        assertEquals(fr.getString(R.string.roaming_notification), on.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertNull("connected: the title alone", on.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    /** What Android itself answers, as on every test run: on Android 13+ without the permission nothing would show. */
    @Test fun withoutTheNotificationPermissionAndroidShowsNoListeningNotification() {
        assumeTrue("the permission exists from Android 13", Build.VERSION.SDK_INT >= 33)
        val context = inst.targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(SystemRoamingHost(context).notificationShown())
    }

    // ---- Settings

    @Test fun settingsOffersListeningOffByDefaultAndItsSwitchStartsIt() {
        val r = rig(hub = false, radio = true)
        val screen: SettingsActivity = Views.start(SettingsActivity::class.java)
        activity = screen
        val switch = Views.tagged(screen, "keep_listening") as Switch
        val state = Views.tagged(screen, "listening_state") as TextView
        assertFalse(TestCore.onMain { switch.isChecked })
        assertEquals("Rester à l'écoute, application fermée", Views.textOf(switch))
        assertEquals(words(R.string.roaming_off), Views.textOf(state))
        assertEquals("Si l'application est ouverte ou à l'écoute.", Views.textOf(Views.tagged(screen, "alerts_scope")!!))
        assertTrue("default off starts nothing", r.host.starts.isEmpty())
        Views.tap(screen, "keep_listening")
        assertEquals(1, r.host.starts.size)
        assertTrue(core.settings.hubRoaming)
        // waitUntil runs on the main thread, where the Views lookups (which hop to it) must not be called.
        Views.waitUntil { state.text.toString() == words(R.string.roaming_starting) }
        assertTrue(TestCore.onMain { core.roamingAttach(r.host.starts[0]) })
        Views.waitUntil { state.text.toString() == words(R.string.roaming_on) }
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
    }

    @Test fun settingsSaysWhyListeningCannotStart() {
        val r = rig(hub = false, radio = false)
        val screen: SettingsActivity = Views.start(SettingsActivity::class.java)
        activity = screen
        Views.tap(screen, "keep_listening")
        val switch = Views.tagged(screen, "keep_listening") as Switch
        Views.waitUntil { !switch.isChecked }
        assertTrue(r.host.starts.isEmpty())
        assertEquals(words(R.string.roaming_block_no_route), Views.textOf(Views.tagged(screen, "listening_state")!!))
    }

    companion object {
        private const val RADIO = "AA:BB:CC:DD:EE:01"
    }
}

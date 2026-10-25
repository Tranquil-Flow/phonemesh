package org.phonemesh.app

import android.app.Activity
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.RecordGesture
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubWire
import org.phonemesh.core.wire.Body
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production ConversationActivity gestures + AppCore/SQLite/ratchet; no microphone, sound or network. */
class HubVoiceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val core get() = TestCore.core
    private var activity: Activity? = null
    private val releases = mutableListOf<CountDownLatch>()
    private val pin = HubCard.pinOf(ByteArray(91) { it.toByte() })

    private class Wire : HubWire {
        val sent = CopyOnWriteArrayList<ByteArray>()
        @Volatile var gate: CountDownLatch? = null
        @Volatile var failPoll = false
        override fun latest(): Long {
            if (failPoll) throw java.io.IOException("fixture poll failure")
            return 0L
        }
        override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<HubClient.Mail> {
            if (failPoll) throw java.io.IOException("fixture poll failure")
            try { gate?.await(10, TimeUnit.SECONDS) }
            catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("fixture poll cancelled").apply { initCause(failure) }
            }
            return emptyList()
        }
        override fun send(port: Int, packet: ByteArray): HubClient.Sent {
            sent += packet.copyOf()
            return HubClient.Sent.STORED
        }
    }
    private class Link : HubLink {
        var current: HubWire? = null
        var changed: (() -> Unit)? = null
        var joins = 0
        override fun wire() = current
        override fun join(card: HubCard, changed: () -> Unit) { joins++; this.changed = changed }
        override fun leave() { current = null }
        fun up(wire: HubWire) { current = wire; changed?.invoke() }
        fun down() { current = null; changed?.invoke() }
    }
    private class Rig(val bea: PersonFixture.Bea, val chat: Long, val link: Link, val wire: Wire)

    @After fun tearDown() {
        releases.forEach { it.countDown() }
        Views.finish(activity)
        TestCore.uninstall()
    }

    private fun rig(answered: Boolean = true, voice: Boolean = true, bluetooth: Boolean = true): Rig {
        TestCore.install(connected = false, voices = if (voice) listOf("fr") else emptyList())
        val (bea, chat) = PersonFixture.meet()
        val wire = Wire()
        if (!answered) wire.gate = CountDownLatch(1).also { releases += it }
        val link = Link()
        TestCore.onMain {
            core.settings.hubCard = HubCard("Village", "password1", 8443, pin).encode()
            if (bluetooth) core.bluetoothHub = link else core.hub = link
        }
        activity = Views.launch<ConversationActivity>(ConversationActivity.intent(inst.targetContext, chat))
        TestCore.onMain { link.up(wire) }
        if (answered) Views.waitUntil { core.hubStatus().let { it.wifi == HubWay.ANSWERED || it.bluetooth == HubWay.ANSWERED } }
        assertFalse("a hub must not impersonate a direct radio", core.connected)
        return Rig(bea, chat, link, wire)
    }

    private fun touch(action: Int, elapsed: Long) {
        val mic = checkNotNull(Views.tagged(activity!!, "mic"))
        TestCore.onMain {
            val event = MotionEvent.obtain(1_000, 1_000 + elapsed, action, mic.width / 2f, mic.height / 2f, 0)
            try { mic.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        inst.waitForIdleSync()
    }
    private fun record(rig: Rig, held: Boolean) {
        TestCore.speech.nextTranscript = "Un oiseau chante"
        touch(MotionEvent.ACTION_DOWN, 0)
        touch(MotionEvent.ACTION_UP, if (held) RecordGesture.TAP_MS + 500 else 50)
        if (!held) {
            assertEquals("quick touch locks recording rather than leaving for Find radio", AppCore.RecordState.RECORDING, core.recordState)
            assertTrue(core.recordLocked)
            Views.tap(activity!!, "mic")
        }
        assertEquals("Un oiseau chante", core.draft(rig.chat))
        assertFalse(activity!!.isFinishing)
        assertTrue("recording alone sends nothing", rig.wire.sent.isEmpty())
        assertTrue(TestCore.radio.broadcasts.isEmpty())
        assertTrue("nothing speaks without a tap", TestCore.speech.spoken.isEmpty())
    }
    private fun sendAndAcknowledge(rig: Rig) {
        Views.tap(activity!!, "draft_send")
        Views.waitUntil { core.history.lastMessage(rig.chat)?.status == DeliveryStatus.HUB }
        val packet = rig.wire.sent.single()
        assertEquals("Un oiseau chante", (rig.bea.postman.receive(packet, 1_000)!!.body as Body.Text).text)
        assertEquals("hub acceptance is not delivery", DeliveryStatus.HUB, core.history.lastMessage(rig.chat)!!.status)
        PersonFixture.deliver(rig.bea.postman.due(60_000).single().packet)
        assertEquals(DeliveryStatus.DELIVERED, core.history.lastMessage(rig.chat)!!.status)
        assertTrue(TestCore.radio.broadcasts.isEmpty())
    }

    @Test fun hubBluetoothOnlyQuickTapRecordsTranscribesAndDelivers() {
        val rig = rig()
        record(rig, held = false)
        sendAndAcknowledge(rig)
    }
    @Test fun hubBluetoothOnlyHoldRecordsTranscribesAndDelivers() {
        val rig = rig()
        record(rig, held = true)
        sendAndAcknowledge(rig)
    }
    @Test fun hubWifiOnlyQuickTapRecordsTranscribesAndDelivers() {
        val rig = rig(bluetooth = false)
        record(rig, held = false)
        sendAndAcknowledge(rig)
    }
    @Test fun anUnansweredHubConnectionDoesNotEnableTalk() {
        rig(answered = false)
        assertFalse(core.canTalk())
        Views.tap(activity!!, "mic")
        assertEquals(0, TestCore.speech.recordings)
        Views.waitUntil { activity!!.isFinishing }
    }
    @Test fun replacementHubMustAnswerBeforeItCanEnableTalk() {
        val rig = rig()
        assertTrue(core.canTalk())
        val replacement = Wire().apply { gate = CountDownLatch(1).also { releases += it } }
        TestCore.onMain { rig.link.down(); rig.link.up(replacement) }
        inst.waitForIdleSync()
        assertFalse("a previous connection's pinned answer cannot authenticate its replacement", core.canTalk())
        replacement.gate!!.countDown()
        Views.waitUntil { core.canTalk() }
    }

    @Test fun unansweredWifiKeepsTheAuthenticatedBluetoothVoiceRoute() {
        val rig = rig()
        val wifi = Link()
        val probing = Wire().apply { gate = CountDownLatch(1).also { releases += it } }
        TestCore.onMain { core.hub = wifi; core.retryHub(); wifi.up(probing) }
        inst.waitForIdleSync()
        assertEquals("a socket without a pinned answer must not discard the working BLE route",
            HubWay.ANSWERED, core.hubStatus().bluetooth)
        assertTrue("Talk may use the authenticated alternate while Wi-Fi is still being checked", core.canTalk())
        record(rig, held = false)
    }

    @Test fun wifiPollFailureRejoinsAndAuthenticatesTheBluetoothFallback() {
        val rig = rig()
        val wifi = Link()
        val wire = Wire()
        TestCore.onMain { core.hub = wifi; core.retryHub(); wifi.up(wire) }
        Views.waitUntil { core.hubStatus().wifi == HubWay.ANSWERED }
        val before = TestCore.onMain { rig.link.joins }
        wire.failPoll = true
        Views.waitUntil(20_000) { rig.link.joins > before }
        // A new BLE wire must answer for itself; an old cached answer cannot qualify it.
        TestCore.onMain { rig.link.up(Wire()) }
        Views.waitUntil(20_000) { core.hubStatus().bluetooth == HubWay.ANSWERED }
        assertTrue(core.canTalk())
        assertFalse(core.connected)
    }

    /** A linked socket is not enough to finish first-run setup; a pinned answer is. */
    private fun freshHubSetup(answered: Boolean): ChatsActivity {
        TestCore.install(connected = false, added = false)
        val link = Link()
        val wire = Wire()
        if (!answered) wire.gate = CountDownLatch(1).also { releases += it }
        TestCore.onMain {
            core.settings.hubCard = HubCard("Village", "password1", 8443, pin).encode()
            core.bluetoothHub = link
        }
        val chats = Views.start(ChatsActivity::class.java).also { activity = it }
        assertNotNull(Views.tagged(chats, "card_radio"))
        TestCore.onMain { link.up(wire) }
        if (answered) Views.waitUntil { core.hubStatus().bluetooth == HubWay.ANSWERED }
        inst.waitForIdleSync()
        return chats
    }

    @Test fun aPinnedHubLetsFreshSetupReachAddingWithoutADirectRadio() {
        val chats = freshHubSetup(answered = true)
        assertFalse(core.connected)
        assertFalse(core.settings.setupFinished)
        assertNotNull("an authenticated communal hub must complete the connection step", Views.tagged(chats, "card_add"))
        assertNotNull(Views.tagged(chats, "step_done:3"))
        Views.tap(chats, "setup_add")
        Views.waitUntil {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is AddSomeoneActivity }
        }
        val adding = TestCore.onMain {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first { it is AddSomeoneActivity }
        }
        try { assertTrue(adding is AddSomeoneActivity) }
        finally { Views.finish(adding); Views.waitUntil { adding.isDestroyed } }
        assertTrue(TestCore.radio.broadcasts.isEmpty())
    }

    @Test fun anUnansweredHubCannotCompleteTheFreshConnectionStep() {
        val chats = freshHubSetup(answered = false)
        assertNotNull(Views.tagged(chats, "card_radio"))
        assertNull(Views.tagged(chats, "card_add"))
        assertNull(Views.tagged(chats, "step_done:3"))
        assertFalse(core.connected)
    }

    @Test fun fixtureCancellationPreservesTheHubWireIOExceptionContract() {
        TestCore.install(connected = false)
        val wire = Wire().apply { gate = CountDownLatch(1) }
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Pair<Throwable, Boolean>>()
        val worker = Thread({
            started.countDown()
            try { wire.mail(0, emptyList(), 0, false) }
            catch (failure: Throwable) { outcome.set(failure to Thread.currentThread().isInterrupted) }
            finally { done.countDown() }
        }, "phonemesh-fixture-cancel")
        worker.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue("the fixture must obey the same cancellation contract as GattStreams",
                outcome.get()?.first is java.io.InterruptedIOException)
            assertEquals(true, outcome.get()?.second)
        } finally {
            wire.gate!!.countDown()
            worker.interrupt()
            worker.join(5_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun missingSpeechStillBlocksAnAnsweredHub() {
        rig(voice = false)
        assertFalse(core.canTalk())
        Views.tap(activity!!, "mic")
        assertEquals(0, TestCore.speech.recordings)
        Views.waitUntil { activity!!.isFinishing }
    }
    @Test fun neitherRouteStillReturnsToSetupWithoutRecording() {
        val rig = rig()
        TestCore.onMain { rig.link.down() }
        assertFalse(core.canTalk())
        Views.tap(activity!!, "mic")
        assertEquals(0, TestCore.speech.recordings)
        Views.waitUntil { activity!!.isFinishing }
    }
    @Test fun directRadioStillRecordsWithoutAHub() {
        TestCore.install()
        val chat = TestCore.onMain { core.groupChatId() }
        activity = Views.launch<ConversationActivity>(ConversationActivity.intent(inst.targetContext, chat))
        Views.tap(activity!!, "mic")
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        Views.tap(activity!!, "mic")
        assertEquals("Bonjour à tous", core.draft(chat))
        assertTrue(TestCore.radio.sent.isEmpty())
    }
    @Test fun hubLossDuringRecordingKeepsADurableQueuedMessageAcrossRestart() {
        val rig = rig()
        TestCore.speech.nextTranscript = "Un oiseau chante"
        Views.tap(activity!!, "mic")
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        TestCore.onMain { rig.link.down() }
        Views.tap(activity!!, "mic")
        assertEquals("Un oiseau chante", core.draft(rig.chat))
        Views.tap(activity!!, "draft_send")
        val packet = TestCore.onMain { core.history.outbox(rig.bea.account).single().packet.copyOf() }
        assertEquals(DeliveryStatus.WAITING, core.history.lastMessage(rig.chat)!!.status)
        Views.finish(activity)
        activity = null
        TestCore.restart()
        assertEquals(DeliveryStatus.WAITING, core.history.lastMessage(rig.chat)!!.status)
        val link = Link()
        TestCore.onMain { core.bluetoothHub = link }
        activity = Views.launch<ConversationActivity>(ConversationActivity.intent(inst.targetContext, rig.chat))
        TestCore.onMain { link.up(rig.wire) }
        Views.waitUntil { core.history.lastMessage(rig.chat)?.status == DeliveryStatus.HUB }
        assertArrayEquals("restart must resend the saved ciphertext, never reseal", packet, rig.wire.sent.single())
        assertEquals("Un oiseau chante", (rig.bea.postman.receive(packet, 1_000)!!.body as Body.Text).text)
        assertTrue(TestCore.radio.broadcasts.isEmpty())
    }
}

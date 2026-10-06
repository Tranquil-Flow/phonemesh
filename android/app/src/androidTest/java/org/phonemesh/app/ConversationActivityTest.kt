package org.phonemesh.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.RadioPeer

/** Fake speech and radio: gestures are synthetic touch events; nothing records, plays or transmits. */
@RunWith(AndroidJUnit4::class)
class ConversationActivityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var activity: Activity? = null
    private val core get() = TestCore.core

    @After fun tearDown() {
        activity?.let { a -> TestCore.onMain { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
        Views.finish(activity)
        TestCore.uninstall()
    }

    private fun open(chatId: Long): ConversationActivity =
        Views.launch<ConversationActivity>(ConversationActivity.intent(context, chatId)).also { activity = it }

    /** A touch on the microphone [dxDp], [dyDp] from where it went down, at [atMs] after touch-down. */
    private fun touch(a: Activity, action: Int, dxDp: Float = 0f, dyDp: Float = 0f, atMs: Long = 0) {
        val mic = checkNotNull(Views.tagged(a, "mic"))
        val density = context.resources.displayMetrics.density
        TestCore.onMain {
            val event = MotionEvent.obtain(1_000, 1_000 + atMs, action, 100f + dxDp * density, 100f + dyDp * density, 0)
            mic.dispatchTouchEvent(event)
            event.recycle()
        }
        inst.waitForIdleSync()
    }

    /** A finger on the microphone's centre, dispatched through the window so the parents register the touch target. */
    private fun windowTouch(a: Activity, action: Int, atMs: Long = 0) {
        val mic = checkNotNull(Views.tagged(a, "mic"))
        TestCore.onMain {
            val where = IntArray(2)
            mic.getLocationInWindow(where)
            val event = MotionEvent.obtain(2_000, 2_000 + atMs, action, where[0] + mic.width / 2f, where[1] + mic.height / 2f, 0)
            a.window.decorView.dispatchTouchEvent(event)
            event.recycle()
        }
        inst.waitForIdleSync()
    }

    private fun description(a: Activity, tag: String) = Views.tagged(a, tag)?.contentDescription?.toString()

    @Test fun holdAndReleaseMakesADraftThatNeverPlaysOrSendsByItself() {
        TestCore.install()
        val a = open(core.groupChatId())
        touch(a, MotionEvent.ACTION_DOWN)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        touch(a, MotionEvent.ACTION_UP, atMs = 1_500)
        assertEquals("Bonjour à tous", Views.textOf(Views.tagged(a, "draft_text")!!))
        assertNull(Views.tagged(a, "mic"))
        assertNotNull(Views.tagged(a, "draft_bar"))
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
        Views.tap(a, "draft_play")
        assertEquals(listOf("Bonjour à tous"), TestCore.speech.spoken)
        assertEquals("Arrêter l'écoute", description(a, "draft_play"))
        Views.tap(a, "draft_send")
        assertEquals(listOf("^all"), TestCore.radio.sent.map { it.destinationId })
        assertNull(core.draft(core.groupChatId()))
        assertNotNull(Views.withText(a, "Bonjour à tous"))
        assertNotNull(Views.tagged(a, "mic"))
    }

    @Test fun clearDiscardsTheDraft() {
        TestCore.install()
        val group = core.groupChatId()
        TestCore.onMain { core.startRecording(group); core.finishRecording() }
        val a = open(group)
        Views.tap(a, "draft_clear")
        assertNull(core.draft(group))
        assertNotNull(Views.tagged(a, "mic"))
        assertTrue(TestCore.radio.sent.isEmpty())
    }

    @Test fun slidingLeftCancelsAndSlidingUpLocks() {
        TestCore.install()
        val group = core.groupChatId()
        val a = open(group)
        touch(a, MotionEvent.ACTION_DOWN)
        touch(a, MotionEvent.ACTION_MOVE, dxDp = -120f, atMs = 400)
        assertEquals(AppCore.RecordState.IDLE, core.recordState)
        touch(a, MotionEvent.ACTION_UP, dxDp = -120f, atMs = 500)
        assertNull(core.draft(group))
        touch(a, MotionEvent.ACTION_DOWN)
        touch(a, MotionEvent.ACTION_MOVE, dyDp = -100f, atMs = 400)
        assertTrue(core.recordLocked)
        touch(a, MotionEvent.ACTION_UP, dyDp = -100f, atMs = 500)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertEquals("Arrêter", description(a, "mic"))
        assertNotNull(Views.described(a, "Enregistrement verrouillé"))
        Views.tap(a, "mic")
        assertEquals("Bonjour à tous", core.draft(group))
    }

    @Test fun aQuickTapLocksTheMicrophone() {
        TestCore.install()
        val a = open(core.groupChatId())
        touch(a, MotionEvent.ACTION_DOWN)
        touch(a, MotionEvent.ACTION_UP, atMs = 100)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertTrue(core.recordLocked)
    }

    @Test fun tooLongShowsAndDisablesSend() {
        TestCore.install()
        val group = core.groupChatId()
        TestCore.onMain { core.setDraft(group, "a".repeat(195)) }
        val a = open(group)
        assertEquals("Trop long. Raccourcissez.", Views.textOf(Views.tagged(a, "too_long")!!))
        assertFalse(Views.tagged(a, "draft_send")!!.isEnabled)
        Views.tap(a, "draft_send")
        assertTrue(TestCore.radio.sent.isEmpty())
    }

    @Test fun theKeyboardMakesAndFixesADraft() {
        TestCore.install()
        val group = core.groupChatId()
        val a = open(group)
        Views.tap(a, "keyboard")
        val edit = Views.tagged(a, "draft_edit") as EditText
        TestCore.onMain { edit.setText("Salut Kofi") }
        assertEquals("Salut Kofi", core.draft(group))
        TestCore.onMain { edit.onEditorAction(EditorInfo.IME_ACTION_DONE) }
        inst.waitForIdleSync()
        assertEquals("Salut Kofi", Views.textOf(Views.tagged(a, "draft_text")!!))
        Views.tap(a, "draft_text")
        assertNotNull(Views.tagged(a, "draft_edit"))
    }

    @Test fun withoutAVoiceTheMicrophoneIsGreyAndLeadsBackToChats() {
        TestCore.install(voices = emptyList())
        val a = open(core.groupChatId())
        val mic = Views.tagged(a, "mic")!!
        assertTrue(mic.alpha < 0.5f)
        Views.tap(a, "mic")
        assertTrue(a.isFinishing)
        assertEquals(0, TestCore.speech.recordings)
    }

    @Test fun groupBubblesNameTheSenderAndOnlyOneMessagePlays() {
        TestCore.install()
        val group = core.groupChatId()
        TestCore.onMain {
            TestCore.radio.setPeers(listOf(RadioPeer("!0000aaaa", "Kofi")))
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] On se voit au marché", false)
            TestCore.radio.receive("!0bad4a2f", "[pm1:fr] Je viens aussi", false)
        }
        val (first, second) = core.history.messages(group)
        val a = open(group)
        assertNotNull(Views.withText(a, "Kofi"))
        assertNotNull(Views.withText(a, "Radio 4a2f"))
        Views.tap(a, "play:${first.id}")
        assertEquals("Arrêter l'écoute", description(a, "play:${first.id}"))
        Views.tap(a, "play:${second.id}")
        assertEquals("Écouter", description(a, "play:${first.id}"))
        assertEquals("Arrêter l'écoute", description(a, "play:${second.id}"))
        assertEquals(2, TestCore.speech.spoken.size)
    }

    @Test fun privateTickGroupSentAndAFailureThatCanBeResent() {
        TestCore.install()
        val group = core.groupChatId()
        val direct = TestCore.onMain { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        TestCore.onMain { core.setDraft(direct, "Bien reçu ?"); core.send(direct) }
        val privateMessage = core.history.lastMessage(direct)!!
        TestCore.onMain { TestCore.radio.deliver(TestCore.radio.sent.last().packetId, DeliveryState.ACKNOWLEDGED) }
        var a: Activity = open(direct)
        assertEquals("Arrivé", description(a, "status:${privateMessage.id}"))
        assertNull(Views.tagged(a, "resend:${privateMessage.id}"))
        Views.finish(a)
        TestCore.onMain { core.setDraft(group, "Au groupe"); core.send(group) }
        val groupMessage = core.history.lastMessage(group)!!
        TestCore.onMain { TestCore.radio.deliver(TestCore.radio.sent.last().packetId, DeliveryState.QUEUED) }
        TestCore.onMain { TestCore.radio.ready = false; core.setDraft(group, "Perdu"); core.send(group) }
        val failed = core.history.lastMessage(group)!!
        a = open(group)
        assertEquals("parti", Views.textOf(Views.tagged(a, "status_label:${groupMessage.id}")!!))
        assertEquals("Échec", Views.textOf(Views.tagged(a, "status_label:${failed.id}")!!))
        TestCore.onMain { TestCore.radio.ready = true }
        val before = TestCore.radio.sent.size
        Views.tap(a, "resend:${failed.id}")
        assertEquals(before + 1, TestCore.radio.sent.size)
        assertEquals("En attente", description(a, "status:${failed.id}"))
        assertEquals(1, Views.all(a).count { it.tag == "message:${failed.id}" })
    }

    /** Review Focus 4. */
    @Test fun rotationKeepsTheDraftTheRecordingAndTheRadio() {
        TestCore.install()
        val group = core.groupChatId()
        var a: Activity = open(group)
        windowTouch(a, MotionEvent.ACTION_DOWN) // finger still down when the phone turns
        a = rotate(a, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertEquals("Arrêter", description(a, "mic"))
        Views.tap(a, "mic")
        assertEquals("Bonjour à tous", Views.textOf(Views.tagged(a, "draft_text")!!))
        a = rotate(a, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertEquals("Bonjour à tous", Views.textOf(Views.tagged(a, "draft_text")!!))
        assertEquals(0, TestCore.radio.disconnects)
        assertTrue(core.connected)
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun anUnconfirmedMessageAsksToResendAndAFailureSaysSo() {
        TestCore.install()
        val direct = TestCore.onMain { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        TestCore.onMain { core.setDraft(direct, "Premier"); core.send(direct) }
        val unconfirmed = core.history.lastMessage(direct)!!
        TestCore.onMain {
            val packet = TestCore.radio.sent.last().packetId
            TestCore.radio.deliver(packet, DeliveryState.QUEUED)
            TestCore.radio.deliver(packet, DeliveryState.UNCONFIRMED)
            core.setDraft(direct, "Second"); core.send(direct)
            TestCore.radio.deliver(TestCore.radio.sent.last().packetId, DeliveryState.FAILED)
        }
        val failed = core.history.lastMessage(direct)!!
        val a = open(direct)
        assertEquals("Pas confirmé", Views.textOf(Views.tagged(a, "status_label:${unconfirmed.id}")!!))
        assertNotNull(Views.tagged(a, "resend:${unconfirmed.id}"))
        assertEquals("Échec", Views.textOf(Views.tagged(a, "status_label:${failed.id}")!!))
        assertNotNull(Views.tagged(a, "resend:${failed.id}"))
    }

    @Test fun leavingTheScreenCancelsRecordingAndStopsPlayback() {
        TestCore.install()
        val group = core.groupChatId()
        TestCore.onMain { TestCore.radio.receive("!0bad4a2f", "[pm1:fr] Je viens aussi", false) }
        val heard = core.history.messages(group).first()
        var a: Activity = open(group)
        touch(a, MotionEvent.ACTION_DOWN)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        Views.finish(a)
        Views.waitUntil { AppCore.get(context).recordState == AppCore.RecordState.IDLE }
        assertNull(core.draft(group))
        a = open(group)
        Views.tap(a, "play:${heard.id}")
        assertEquals(heard.id, core.playingId)
        Views.finish(a)
        Views.waitUntil { AppCore.get(context).playingId == null }
        assertNull(core.playingId)
    }

    private fun rotate(old: Activity, orientation: Int): Activity {
        TestCore.onMain { old.requestedOrientation = orientation }
        Views.waitUntil {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
                .let { it is ConversationActivity && it !== old }
        }
        return checkNotNull(Views.resumed()).also { activity = it }
    }
}

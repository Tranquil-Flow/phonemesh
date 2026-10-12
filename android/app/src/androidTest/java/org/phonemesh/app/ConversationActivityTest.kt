package org.phonemesh.app

import android.app.Activity
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.DraftFit
import org.phonemesh.core.RecordGesture

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

    /** The top bar laid out again as on a 360 dp wide phone: the title's lines and cut letters, with the other parts checked whole. */
    private fun titleAt360(a: Activity): Pair<Int, Int> = TestCore.onMain {
        val title = Views.descendants(a.window.decorView).first { it.tag == "title" } as android.widget.TextView
        val people = Views.descendants(a.window.decorView).first { it.tag == "people" }
        val radio = Views.descendants(a.window.decorView).first { it.tag == "radio_status" }
        val bar = title.parent as View
        val width = Ui.dp(context, 360)
        val (peopleWide, radioWide) = people.width to radio.width
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        bar.layout(0, 0, width, bar.measuredHeight)
        assertTrue("the people count is shown", people.isShown && peopleWide > 0)
        assertEquals("the people count keeps its width", peopleWide, people.width)
        assertEquals("the radio icon keeps its width", radioWide, radio.width)
        assertTrue("title, people count, radio icon, in that order and inside the bar: ${title.right} ${people.left} ${people.right} ${radio.left} ${radio.right} $width",
            title.right <= people.left && people.right <= radio.left && radio.right <= width)
        val result = title.lineCount to title.layout.getEllipsisCount(0)
        val sp = context.resources.displayMetrics.scaledDensity
        assertTrue("letters no smaller than body text: ${title.textSize}", title.textSize >= Ui.BODY_SP * sp - 0.5f)
        // With room again the letters are full size again.
        val wide = Ui.dp(context, 600)
        bar.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        bar.layout(0, 0, wide, bar.measuredHeight)
        assertEquals(20f * sp, title.textSize, 0.5f)
        bar.requestLayout()
        result
    }

    @Test fun theGroupTitleFitsANarrowPhoneAndALongChannelNameIsCutAtItsEnd() {
        TestCore.install(connected = false)
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        val title = Views.tagged(a, "title")!!
        assertEquals("Tout le monde ici", Views.textOf(title))
        assertEquals("one line, nothing cut", 1 to 0, titleAt360(a))
        // The radio's owner named the channel: the open chat takes the name when the radio connects.
        TestCore.onMain {
            core.connect("AA:BB:CC:DD:EE:01", "Kofi")
            TestCore.radio.channelName = "Marché de Kpalimé et les villages autour"
            TestCore.radio.connected(true)
        }
        inst.waitForIdleSync()
        assertEquals("Marché de Kpalimé et les villages autour", Views.textOf(title))
        assertTrue("written by people: exempt from the word rules", Ui.isContent(title))
        val (lines, cut) = titleAt360(a)
        assertEquals(1, lines)
        assertTrue("cut at the end", cut > 0)
    }

    @Test fun aCutDraftSaysSoAndCanStillBeSent() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.speech.nextTranscript = "mot ".repeat(80).trim()
        val a = open(group)
        Views.tap(a, "mic")
        Views.tap(a, "mic")
        assertNotNull(Views.tagged(a, "draft_cut"))
        assertNotNull(Views.withText(a, "La fin a été coupée."))
        assertNotNull(Views.tagged(a, "howto:toolong"))
        assertNull(Views.tagged(a, "too_long"))
        assertTrue(Views.tagged(a, "draft_send")!!.isEnabled)
        assertTrue(TestCore.speech.spoken.isEmpty())
        assertTrue(TestCore.radio.sent.isEmpty())
        Views.tap(a, "draft_play")
        assertEquals(listOf(core.draft(group)), TestCore.speech.spoken)
        Views.tap(a, "draft_send")
        assertEquals(1, TestCore.radio.sent.size)
        assertNull(Views.tagged(a, "draft_cut"))
    }

    @Test fun aCutDraftKeepsItsMarkWhenTheEditorIsOnlyOpenedAndClosed() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.speech.nextTranscript = "mot ".repeat(80).trim()
        val a = open(group)
        makeCutDraft(a)
        val kept = core.draft(group)
        assertTrue(core.draftWasCut(group))
        Views.tap(a, "draft_text")
        val edit = Views.tagged(a, "draft_edit") as EditText
        assertEquals(kept, TestCore.onMain { edit.text.toString() })
        assertTrue("opening the editor is not an edit", core.draftWasCut(group))
        TestCore.onMain { edit.onEditorAction(EditorInfo.IME_ACTION_DONE) }
        inst.waitForIdleSync()
        assertEquals(kept, core.draft(group))
        assertNotNull(Views.tagged(a, "draft_cut"))
        assertTrue(Views.tagged(a, "draft_send")!!.isEnabled)
        Views.finish(a)
        val again = open(group)
        assertNotNull("reopening keeps the cut disclosure", Views.tagged(again, "draft_cut"))
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun aCutDraftKeepsItsMarkWhenTheEditorIsRecreated() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.speech.nextTranscript = "mot ".repeat(80).trim()
        var a: Activity = open(group)
        makeCutDraft(a)
        val kept = core.draft(group)
        Views.tap(a, "draft_text")
        a = rotate(a, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        val edit = Views.tagged(a, "draft_edit") as EditText
        assertEquals(kept, TestCore.onMain { edit.text.toString() })
        assertTrue("restoring the editor is not an edit", core.draftWasCut(group))
        TestCore.onMain { edit.onEditorAction(EditorInfo.IME_ACTION_DONE) }
        inst.waitForIdleSync()
        assertNotNull(Views.tagged(a, "draft_cut"))
        assertTrue(Views.tagged(a, "draft_send")!!.isEnabled)
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun editingACutDraftRetiresItsMark() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.speech.nextTranscript = "mot ".repeat(80).trim()
        val a = open(group)
        makeCutDraft(a)
        val kept = checkNotNull(core.draft(group))
        Views.tap(a, "draft_text")
        assertTrue("only a changed value retires the mark", core.draftWasCut(group))
        val edit = Views.tagged(a, "draft_edit") as EditText
        TestCore.onMain { edit.text.delete(0, 4) }
        assertEquals(kept.drop(4), core.draft(group))
        assertFalse("a real edit clears the mark", core.draftWasCut(group))
        TestCore.onMain { edit.onEditorAction(EditorInfo.IME_ACTION_DONE) }
        inst.waitForIdleSync()
        assertNull(Views.tagged(a, "draft_cut"))
        assertTrue(Views.tagged(a, "draft_send")!!.isEnabled)
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun theFrenchCutNoticeSpeakerReadsItsDisplayedSentence() {
        cutNoticeSpeakerReadsItsDisplayedSentence("fr", "La fin a été coupée.")
    }

    @Test fun theEnglishCutNoticeSpeakerReadsItsDisplayedSentence() {
        cutNoticeSpeakerReadsItsDisplayedSentence("en-US", "The end was cut off.")
    }

    @Test fun theFrenchCutNoticeSpeakerBecomesAvailableWithoutReopening() {
        cutNoticeSpeakerBecomesAvailableWithoutReopening("fr", "La fin a été coupée.")
    }

    @Test fun theEnglishCutNoticeSpeakerBecomesAvailableWithoutReopening() {
        cutNoticeSpeakerBecomesAvailableWithoutReopening("en-US", "The end was cut off.")
    }

    private fun cutNoticeSpeakerBecomesAvailableWithoutReopening(language: String, expected: String) {
        TestCore.install(language = language, voices = emptyList())
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        // The panel is built before the missing voice is installed, even while its notice is hidden.
        val notice = TestCore.onMain { Views.descendants(a.window.decorView).single { it.tag == "draft_cut" } }
        val panel = TestCore.onMain { Views.descendants(notice).filterIsInstance<HowToPanel>().single() }
        val picture = TestCore.onMain { Views.descendants(panel).filterIsInstance<HowToView>().single() }
        val speaker = TestCore.onMain { Views.descendants(panel).single { it.tag == "howto_say" } }
        assertEquals(View.GONE, TestCore.onMain { speaker.visibility })
        assertFalse(TestCore.onMain { core.canTalk() })
        assertEquals(0, TestCore.speech.recordings)
        TestCore.onMain {
            Fixtures.installReady(context, listOf(language))
            // Disk-only fixture installation does not emit the production PACKS notification.
            a.onCoreChange(AppCore.Change.PACKS)
        }
        assertSame("the conversation must not be reopened", a, Views.resumed())
        assertTrue(TestCore.onMain { core.canTalk() })
        TestCore.speech.nextTranscript = "mot ".repeat(80).trim()
        makeCutDraft(a)
        assertSame(notice, Views.tagged(a, "draft_cut"))
        assertSame(picture, Views.tagged(a, "howto:toolong"))
        assertEquals(expected, Views.textOf(TestCore.onMain { Views.descendants(panel).single { it.tag == "howto_sentence" } }))
        assertTrue("a newly available voice must reveal the cut-notice speaker", TestCore.onMain { speaker.isShown })
        TestCore.onMain {
            a.onCoreChange(AppCore.Change.PEERS)
            a.onCoreChange(AppCore.Change.PACKS)
        }
        assertSame("ordinary renders keep the existing panel", panel,
            TestCore.onMain { Views.descendants(notice).filterIsInstance<HowToPanel>().single() })
        assertSame("ordinary renders keep the existing picture", picture, Views.tagged(a, "howto:toolong"))
        assertTrue(TestCore.onMain { speaker.isShown })
        assertEquals(1, TestCore.speech.recordings)
        assertTrue("no automatic speech", TestCore.speech.spoken.isEmpty())
        assertTrue(TestCore.speech.clips.isEmpty())
        assertTrue("no automatic transmission", TestCore.radio.sent.isEmpty())
        TestCore.onMain { speaker.performClick() }
        inst.waitForIdleSync()
        assertEquals("the speaker reads exactly the displayed localized notice", listOf(expected), TestCore.speech.spoken)
        assertEquals(AppCore.HOWTO_PLAYBACK, core.playingId)
        assertTrue(core.draftWasCut(group))
        assertTrue(TestCore.radio.sent.isEmpty())
    }

    /** Drive both real UI callbacks before idle waits can reach the automatic recording limit. */
    private fun makeCutDraft(a: Activity) {
        val mic = checkNotNull(Views.tagged(a, "mic"))
        TestCore.onMain {
            mic.performClick()
            assertEquals(AppCore.RecordState.RECORDING, core.recordState)
            mic.performClick()
            assertEquals(AppCore.RecordState.IDLE, core.recordState)
        }
        inst.waitForIdleSync()
    }

    private fun cutNoticeSpeakerReadsItsDisplayedSentence(language: String, expected: String) {
        TestCore.install(language = language, voices = listOf(language))
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.speech.nextTranscript = "mot ".repeat(80).trim()
        val a = open(group)
        makeCutDraft(a)
        val notice = checkNotNull(Views.tagged(a, "draft_cut"))
        val sentence = TestCore.onMain { Views.descendants(notice).single { it.tag == "howto_sentence" } }
        assertEquals(expected, Views.textOf(sentence))
        val speaker = TestCore.onMain { Views.descendants(notice).single { it.tag == "howto_say" } }
        assertTrue(TestCore.onMain { speaker.isShown })
        assertTrue(TestCore.speech.spoken.isEmpty())
        TestCore.onMain { speaker.performClick() }
        inst.waitForIdleSync()
        assertEquals("the speaker must read the displayed cut notice", listOf(expected), TestCore.speech.spoken)
        assertEquals(AppCore.HOWTO_PLAYBACK, core.playingId)
        TestCore.onMain { TestCore.speech.finishPlayback(); core.sayHowTo("toolong") }
        assertEquals("the normal scene keeps its own sentence", listOf(expected, a.getString(R.string.howto_toolong)), TestCore.speech.spoken)
        assertTrue(core.draftWasCut(group))
        assertTrue(TestCore.speech.clips.isEmpty())
        assertTrue(TestCore.radio.sent.isEmpty())
    }

    @Test fun typingStopsAtTheLimit() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        Views.tap(a, "keyboard")
        val edit = Views.tagged(a, "draft_edit") as EditText
        TestCore.onMain { edit.setText("a".repeat(300)) }
        assertEquals(191, TestCore.onMain { edit.text.length })
        TestCore.onMain { edit.append("bbb") }
        assertEquals(191, TestCore.onMain { edit.text.length })
        assertEquals(DraftFit.FITS, core.draftFit(group))
        TestCore.onMain { edit.text.delete(0, 10) }
        assertEquals("deleting is always allowed", 181, TestCore.onMain { edit.text.length })
    }

    @Test fun pastingRespectsUtf8AndNeverSplitsAnEmoji() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        Views.tap(a, "keyboard")
        val edit = Views.tagged(a, "draft_edit") as EditText
        TestCore.onMain { edit.setText("😀".repeat(100)) }
        assertEquals("😀".repeat(47), TestCore.onMain { edit.text.toString() })
        assertEquals(DraftFit.FITS, core.draftFit(group))
        TestCore.onMain { edit.text.replace(0, 2, "é".repeat(100)) }
        assertEquals("ééé" + "😀".repeat(46), TestCore.onMain { edit.text.toString() })
        assertEquals(DraftFit.FITS, core.draftFit(group))
    }

    /** The clock must not starve idle observers until the automatic stop fires. */
    @Test fun theRecordingClockLetsTheMainQueueIdleBeforeTheLimit() {
        TestCore.install()
        val a = open(TestCore.onMain { core.groupChatId() })
        val mic = checkNotNull(Views.tagged(a, "mic"))
        TestCore.onMain { mic.performClick() }
        val idle = java.util.concurrent.CountDownLatch(1)
        inst.waitForIdle { idle.countDown() }
        try {
            val idledWhileVisible = idle.await(2, java.util.concurrent.TimeUnit.SECONDS)
            // A hidden-clock control isolates redraws from the real recording timer and fake speech.
            TestCore.onMain { Views.descendants(mic).single { it is RecordRing }.visibility = View.INVISIBLE }
            val hiddenIdle = java.util.concurrent.CountDownLatch(1)
            inst.waitForIdle { hiddenIdle.countDown() }
            assertTrue("recording itself must not starve idle", hiddenIdle.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("the control must keep recording active", AppCore.RecordState.RECORDING, TestCore.onMain { core.recordState })
            assertTrue("the visible recording clock must allow idle within two seconds", idledWhileVisible)
            assertEquals("idle must arrive before the real 15-second limit", AppCore.RecordState.RECORDING, TestCore.onMain { core.recordState })
            assertTrue(TestCore.speech.recording)
            assertNull(core.draft(core.groupChatId()))
        } finally {
            TestCore.onMain { core.cancelRecording() }
        }
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun theRecordingRingHasClockMarksAndHidesWhenIdle() {
        TestCore.install()
        TestCore.onMain {
            val ring = RecordRing(context, Color.RED)
            ring.layout(0, 0, 200, 200)
            val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            ring.draw(Canvas(bitmap))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(32, 100))
            ring.startedAt = SystemClock.elapsedRealtime()
            ring.draw(Canvas(bitmap))
            assertTrue("the nine-o'clock mark lies inside the ring", Color.alpha(bitmap.getPixel(32, 100)) > 0)
            bitmap.recycle()
        }
    }

    @Test fun anOddEmojiPasteCannotUseInvalidHalfCharactersToPassTheLimit() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        Views.tap(a, "keyboard")
        val edit = Views.tagged(a, "draft_edit") as EditText
        TestCore.onMain { edit.setText("😀".repeat(99)) }
        assertEquals("😀".repeat(47), TestCore.onMain { edit.text.toString() })
        assertEquals(DraftFit.FITS, core.draftFit(group))
    }

    @Test fun anOldOversizedDraftStaysIntactUntilDeleted() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val old = "a".repeat(300)
        TestCore.onMain { core.setDraft(group, old) }
        var a: Activity = open(group)
        Views.tap(a, "draft_text")
        var edit = Views.tagged(a, "draft_edit") as EditText
        assertEquals(old, TestCore.onMain { edit.text.toString() })
        assertEquals(old, core.draft(group))
        assertNotNull(Views.tagged(a, "too_long"))
        assertFalse(Views.tagged(a, "draft_send")!!.isEnabled)
        a = rotate(a, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        edit = Views.tagged(a, "draft_edit") as EditText
        assertEquals(old, TestCore.onMain { edit.text.toString() })
        TestCore.onMain { edit.append("b") }
        assertEquals(old, TestCore.onMain { edit.text.toString() })
        TestCore.onMain { edit.text.delete(0, 10) }
        assertEquals(290, TestCore.onMain { edit.text.length })
        assertEquals(DraftFit.TOO_LONG, core.draftFit(group))
        TestCore.onMain { edit.text.delete(0, 100) }
        assertEquals(190, TestCore.onMain { edit.text.length })
        assertEquals(DraftFit.FITS, core.draftFit(group))
        assertTrue(TestCore.radio.sent.isEmpty())
    }

    @Test fun theBackArrowLeavesTheScreen() {
        TestCore.install()
        activity = Views.launch<ConversationActivity>(
            ConversationActivity.intent(context, TestCore.onMain { TestCore.core.groupChatId() }))
        assertEquals("Retour", Views.tagged(activity!!, "back")!!.contentDescription)
        Views.tap(activity!!, "back")
        // System back asks Android to finish asynchronously.
        Views.waitUntil { activity!!.isFinishing }
        assertTrue((activity as Activity).isFinishing)
    }

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

    @Test fun theMicrophoneSaysHoldToTalkUntilTheFirstMessage() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        assertNotNull(Views.tagged(a, "mic_hint"))
        assertNotNull(Views.withText(a, "Maintenez pour parler"))
        Views.tap(a, "mic")
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertTrue(core.recordLocked)
        assertNull(Views.tagged(a, "mic_hint"))
        assertNull(Views.withText(a, "Maintenez pour parler"))
        Views.tap(a, "mic")
        assertNotNull(Views.tagged(a, "draft_text"))
        assertNull(Views.tagged(a, "mic_hint"))
        Views.tap(a, "draft_clear")
        assertNotNull(Views.tagged(a, "mic_hint"))
        touch(a, MotionEvent.ACTION_DOWN)
        assertNull(Views.tagged(a, "mic_hint"))
        touch(a, MotionEvent.ACTION_MOVE, dxDp = -120f, atMs = 400)
        touch(a, MotionEvent.ACTION_UP, dxDp = -120f, atMs = 500)
        assertNotNull(Views.tagged(a, "mic_hint"))
        assertFalse(core.settings.firstMessageSent)
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
        TestCore.onMain {
            core.setDraft(group, "Bonjour")
            assertTrue(core.send(group))
        }
        inst.waitForIdleSync()
        assertTrue(core.settings.firstMessageSent)
        assertNull(Views.tagged(a, "mic_hint"))
        Views.finish(a)
        val reopened = open(group)
        assertNull(Views.tagged(reopened, "mic_hint"))
        Views.finish(reopened)
        TestCore.restart()
        TestCore.onMain { TestCore.radio.connected(true) }
        val restarted = open(group)
        assertTrue(core.settings.firstMessageSent)
        assertNull(Views.tagged(restarted, "mic_hint"))
        assertNull(Views.withText(restarted, "Maintenez pour parler"))
    }

    @Test fun theEnglishHintPointsAtTheMicrophoneInDarkMode() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        TestCore.onMain { core.settings.theme = ThemeMode.DARK }
        val a = open(TestCore.onMain { core.groupChatId() })
        val hint = Views.tagged(a, "mic_hint") as? LinearLayout
        assertNotNull(hint)
        val words = checkNotNull(Views.withText(a, "Hold to talk"))
        val mic = checkNotNull(Views.tagged(a, "mic"))
        TestCore.onMain {
            val shown = checkNotNull(hint)
            assertEquals(LinearLayout.VERTICAL, shown.orientation)
            assertEquals(shown, words.parent)
            assertEquals(Palette(a).onAccent, words.currentTextColor)
            assertEquals(2, shown.childCount)
            val tail = shown.getChildAt(1) as ImageView
            assertEquals(Ui.dp(a, 16), tail.width)
            assertEquals(Ui.dp(a, 8), tail.height)
            assertEquals(Palette(a).accent, tail.imageTintList!!.defaultColor)
            assertFalse(tail.isClickable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, tail.importantForAccessibility)
            val hintAt = IntArray(2)
            val micAt = IntArray(2)
            shown.getLocationOnScreen(hintAt)
            mic.getLocationOnScreen(micAt)
            assertTrue(hintAt[1] + shown.height <= micAt[1])
            assertEquals(hintAt[0] + shown.width / 2, micAt[0] + mic.width / 2)
        }
        assertEquals(emptyList<String>(), A11yCheck.problems(a))
        assertTrue(TestCore.radio.sent.isEmpty())
        assertEquals(0, TestCore.speech.recordings)
        assertTrue(TestCore.speech.spoken.isEmpty())
        assertTrue(TestCore.speech.clips.isEmpty())
    }

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

    /** A finger kept down past a tap, so the recording is held and its gestures are taught. */
    private fun hold(a: Activity) {
        touch(a, MotionEvent.ACTION_DOWN)
        Thread.sleep(RecordGesture.TAP_MS + 200)
        inst.waitForIdleSync()
    }

    /** A finger down and up in place, as a phone sends it: through the touch listener, never performClick. */
    private fun fingerTap(a: Activity) {
        touch(a, MotionEvent.ACTION_DOWN)
        touch(a, MotionEvent.ACTION_UP, atMs = 100)
    }

    @Test fun aFingerTapStopsARecordingLockedBySlidingUp() {
        TestCore.install()
        val group = core.groupChatId()
        val a = open(group)
        touch(a, MotionEvent.ACTION_DOWN)
        touch(a, MotionEvent.ACTION_MOVE, dyDp = -100f, atMs = 400)
        assertTrue(core.recordLocked)
        touch(a, MotionEvent.ACTION_UP, dyDp = -100f, atMs = 500)
        assertEquals("lifting the finger that locked does not stop", AppCore.RecordState.RECORDING, core.recordState)
        assertNull(core.draft(group))
        fingerTap(a)
        assertEquals(AppCore.RecordState.IDLE, core.recordState)
        assertEquals("Bonjour à tous", Views.textOf(Views.tagged(a, "draft_text")!!))
        assertEquals(1, TestCore.speech.recordings)
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun aFingerTapStopsARecordingLockedByAQuickTap() {
        TestCore.install()
        val group = core.groupChatId()
        val a = open(group)
        fingerTap(a)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertTrue(core.recordLocked)
        assertEquals("Arrêter", description(a, "mic"))
        fingerTap(a)
        assertEquals(AppCore.RecordState.IDLE, core.recordState)
        assertEquals("Bonjour à tous", Views.textOf(Views.tagged(a, "draft_text")!!))
        assertEquals(1, TestCore.speech.recordings)
    }

    @Test fun aFingerTapOnTheGreyMicrophoneLeadsBackToChats() {
        TestCore.install(voices = emptyList())
        val a = open(core.groupChatId())
        fingerTap(a)
        assertTrue(a.isFinishing)
        assertEquals(0, TestCore.speech.recordings)
    }

    @Test fun aFingerTapWhileTheWordsAreWrittenNeitherStopsNorStartsAnything() {
        TestCore.install()
        val group = core.groupChatId()
        TestCore.speech.holdTranscript = true
        val a = open(group)
        fingerTap(a)
        fingerTap(a)
        assertEquals(AppCore.RecordState.TRANSCRIBING, core.recordState)
        fingerTap(a)
        assertEquals(AppCore.RecordState.TRANSCRIBING, core.recordState)
        assertEquals(1, TestCore.speech.recordings)
        assertFalse(a.isFinishing)
        TestCore.onMain { TestCore.speech.finishTranscript() }
        inst.waitForIdleSync()
        assertEquals("Bonjour à tous", Views.textOf(Views.tagged(a, "draft_text")!!))
    }

    @Test fun whileTheWordsAreWrittenTheMicrophoneSaysSoAndMoves() {
        TestCore.install()
        val group = core.groupChatId()
        TestCore.speech.holdTranscript = true
        val a = open(group)
        assertEquals("Parler", description(a, "mic"))
        assertNull(Views.tagged(a, "writing"))
        Views.tap(a, "mic")
        assertNull("recording is not writing", Views.tagged(a, "writing"))
        // Moving pictures only for the sign: a moving tip would keep the main thread from ever being idle.
        TestCore.onMain { StoryView.animationsEnabled = { true } }
        Views.tap(a, "mic")
        TestCore.onMain { StoryView.animationsEnabled = { false } }
        assertEquals(AppCore.RecordState.TRANSCRIBING, core.recordState)
        assertEquals("J'écris vos mots…", description(a, "mic"))
        val words = Views.withText(a, "J'écris vos mots…") as android.widget.TextView
        assertEquals("the sentence stays on one line", 1, TestCore.onMain { words.lineCount })
        assertNull(Views.withText(a, "Parler"))
        val sign = Views.tagged(a, "writing") as WritingDots
        assertTrue("the sign moves", TestCore.onMain { sign.isRunning })
        assertFalse(Views.tagged(a, "keyboard")!!.isEnabled)
        assertNull(Views.tagged(a, "cancel_hint"))
        assertNull(Views.tagged(a, "lock_hint"))
        assertNull("no invitation to talk yet", Views.tagged(a, "mic_hint"))
        assertEquals(emptyList<String>(), A11yCheck.problems(a))
        TestCore.onMain { TestCore.speech.finishTranscript() }
        inst.waitForIdleSync()
        assertEquals("Bonjour à tous", Views.textOf(Views.tagged(a, "draft_text")!!))
        assertFalse("the sign stops with the writing", TestCore.onMain { sign.isRunning })
        Views.tap(a, "draft_clear")
        assertNull(Views.tagged(a, "writing"))
        assertEquals("Parler", description(a, "mic"))
        assertNotNull(Views.withText(a, "Parler"))
        assertTrue(Views.tagged(a, "keyboard")!!.isEnabled)
        assertFalse(TestCore.onMain { sign.isRunning })
    }

    @Test fun withAnimationsOffTheWritingSignIsStill() {
        TestCore.install()
        val group = core.groupChatId()
        TestCore.speech.holdTranscript = true
        val a = open(group)
        Views.tap(a, "mic")
        Views.tap(a, "mic")
        val sign = Views.tagged(a, "writing") as WritingDots
        inst.waitForIdleSync()
        assertFalse(TestCore.onMain { sign.isRunning })
        assertEquals("J'écris vos mots…", description(a, "mic"))
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

    @Test fun theFirstRecordingTeachesCancelThenTheDraftTeachesListen() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        hold(a)
        assertNotNull(Views.tagged(a, "tip:cancel"))
        assertEquals("Changé d'avis ? Glissez à gauche.", Views.textOf(Views.tagged(a, "howto_sentence")!!))
        touch(a, MotionEvent.ACTION_UP, atMs = 1_500)
        assertNull("the recording ended, so its tip did", Views.tagged(a, "tip:cancel"))
        assertNotNull(Views.tagged(a, "tip:listen"))
        Views.tap(a, "tip_close")
        assertNull(Views.tagged(a, "tip:listen"))
        assertEquals("closed is learned; only shown is not", setOf("listen"), core.settings.tipsShown)
        assertEquals(mapOf("cancel" to 1), core.settings.tipShowings)
        Views.tap(a, "draft_clear")
        hold(a)
        assertNotNull("the second recording teaches lock", Views.tagged(a, "tip:lock"))
    }

    /** Hold and release: a recording that is neither locked nor cancelled. Returns the tip it showed. */
    private fun recordOnce(a: Activity): String? {
        hold(a)
        val shown = Views.all(a).mapNotNull { it.tag as? String }.filter { it.startsWith("tip:") }
        touch(a, MotionEvent.ACTION_UP, atMs = 1_500)
        assertNotNull(Views.tagged(a, "draft_text"))
        Views.tap(a, "draft_clear")
        assertTrue("one picture at a time: $shown", shown.size <= 1)
        return shown.firstOrNull()
    }

    @Test fun aTipLeftUnusedComesBackInTurnAndStopsAfterThreeMoments() {
        TestCore.install()
        val a = open(TestCore.onMain { core.groupChatId() })
        assertEquals("tip:cancel", recordOnce(a))
        assertEquals("the lock tip was only shown", "tip:lock", recordOnce(a))
        assertEquals(mapOf("cancel" to 1, "lock" to 1, "listen" to 2), core.settings.tipShowings)
        assertEquals(listOf("tip:cancel", "tip:lock", "tip:cancel", "tip:lock"), List(4) { recordOnce(a) })
        assertEquals(mapOf("cancel" to 3, "lock" to 3, "listen" to 3), core.settings.tipShowings)
        assertNull("three moments each, then no more", recordOnce(a))
        Views.tap(a, "mic")
        Views.tap(a, "mic")
        assertNull("the draft's tip had its three moments too", Views.tagged(a, "tip:listen"))
        assertTrue("none was used or closed", core.settings.tipsShown.isEmpty())
        assertEquals(mapOf("cancel" to 3, "lock" to 3, "listen" to 3), core.settings.tipShowings)
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun closingATipLearnsItAtOnceAndBringsNoOtherInThatMoment() {
        TestCore.install()
        val a = open(TestCore.onMain { core.groupChatId() })
        hold(a)
        assertNotNull(Views.tagged(a, "tip:cancel"))
        Views.tap(a, "tip_close")
        assertEquals(setOf("cancel"), core.settings.tipsShown)
        assertNull(Views.tagged(a, "tip:cancel"))
        assertNull("not a second picture in the same recording", Views.tagged(a, "tip:lock"))
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        touch(a, MotionEvent.ACTION_UP, atMs = 1_500)
        Views.tap(a, "draft_clear")
        assertEquals("closing is not a showing", mapOf("listen" to 1), core.settings.tipShowings)
        assertEquals(listOf("tip:lock", "tip:lock", "tip:lock", null), List(4) { recordOnce(a) })
        assertEquals(setOf("cancel"), core.settings.tipsShown)
    }

    @Test fun slidingLeftLearnsTheCancelTipAtOnce() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        hold(a)
        assertNotNull(Views.tagged(a, "tip:cancel"))
        touch(a, MotionEvent.ACTION_MOVE, dxDp = -120f, atMs = 400)
        assertEquals(AppCore.RecordState.IDLE, core.recordState)
        assertNull(Views.tagged(a, "tip:cancel"))
        assertEquals(setOf("cancel"), core.settings.tipsShown)
        assertTrue(core.settings.tipShowings.isEmpty())
        touch(a, MotionEvent.ACTION_UP, dxDp = -120f, atMs = 500)
        assertNull(core.draft(group))
        assertEquals("tip:lock", recordOnce(a))
    }

    @Test fun aTappedRecordingTeachesNoGesture() {
        TestCore.install()
        val a = open(TestCore.onMain { core.groupChatId() })
        Views.tap(a, "mic")
        assertTrue(core.recordLocked)
        assertNull("sliding does nothing in a locked recording", Views.tagged(a, "tip:cancel"))
        assertNull("it is locked already", Views.tagged(a, "tip:lock"))
        Views.tap(a, "mic")
        assertTrue(core.settings.tipShowings.isEmpty())
        assertTrue(core.settings.tipsShown.isEmpty())
    }

    @Test fun aQuickFingerTapLocksWithoutAFlashOfATip() {
        TestCore.install()
        val a = open(TestCore.onMain { core.groupChatId() })
        touch(a, MotionEvent.ACTION_DOWN)
        assertNull("not a hold yet", Views.tagged(a, "tip:cancel"))
        touch(a, MotionEvent.ACTION_UP, atMs = 100)
        assertTrue(core.recordLocked)
        Thread.sleep(RecordGesture.TAP_MS + 200)
        inst.waitForIdleSync()
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertNull(Views.tagged(a, "tip:cancel"))
        assertNull(Views.tagged(a, "tip:lock"))
        assertTrue(core.settings.tipShowings.isEmpty())
        assertTrue(core.settings.tipsShown.isEmpty())
    }

    @Test fun lockingByHandEndsTheCancelTipAsOneShowingAndBringsNoOther() {
        TestCore.install()
        val a = open(TestCore.onMain { core.groupChatId() })
        hold(a)
        assertNotNull(Views.tagged(a, "tip:cancel"))
        touch(a, MotionEvent.ACTION_MOVE, dyDp = -100f, atMs = 400)
        assertTrue(core.recordLocked)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertNull(Views.tagged(a, "tip:cancel"))
        assertNull("not a second picture in the same recording", Views.tagged(a, "tip:lock"))
        assertEquals(mapOf("cancel" to 1), core.settings.tipShowings)
        assertTrue(core.settings.tipsShown.isEmpty())
        touch(a, MotionEvent.ACTION_UP, dyDp = -100f, atMs = 500)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertNull(Views.tagged(a, "tip:lock"))
    }

    @Test fun rotationLiftsTheHoldSoItsTipLeavesWithoutCountingAShowing() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain { core.settings.tipShowings = mapOf("cancel" to 1) }
        var a: Activity = open(group)
        hold(a)
        assertNotNull(Views.tagged(a, "tip:lock"))
        a = rotate(a, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertTrue("no finger survives a rotation", core.recordLocked)
        assertNull(Views.tagged(a, "tip:lock"))
        assertNull(Views.tagged(a, "tip:cancel"))
        assertEquals(mapOf("cancel" to 1), core.settings.tipShowings)
        assertTrue(core.settings.tipsShown.isEmpty())
        Views.tap(a, "mic")
        assertEquals(AppCore.RecordState.IDLE, core.recordState)
        assertEquals("a rotation is not a showing", mapOf("cancel" to 1), core.settings.tipShowings)
    }

    /** Taps a button inside the dialog's own window (not the activity's). */
    private fun tapInDialog(d: android.app.AlertDialog, tag: String) {
        TestCore.onMain { checkNotNull(Views.descendants(d.window!!.decorView).firstOrNull { it.tag == tag }) { tag }.performClick() }
        inst.waitForIdleSync()
    }

    private fun dialogTags(d: android.app.AlertDialog): List<String> =
        TestCore.onMain { Views.descendants(d.window!!.decorView).mapNotNull { it.tag as? String }.filter { it.startsWith("action_") } }

    private fun longPress(a: ConversationActivity, messageId: Long): android.app.AlertDialog {
        TestCore.onMain { checkNotNull(Views.descendants(a.window.decorView).firstOrNull { it.tag == "bubble:$messageId" }).performLongClick() }
        inst.waitForIdleSync()
        return checkNotNull(a.dialogForTest)
    }

    @Test fun aLongPressOffersActionsAndDeletesOnThisPhoneOnlyAfterAsking() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val now = System.currentTimeMillis()
        val kept = TestCore.onMain { core.history.addIncoming(group, "!1234abcd", "Salut", "fr", now, countUnread = false) }
        val gone = TestCore.onMain { core.history.addOutgoing(group, "Bonjour", "fr", now + 1) }
        val a = open(group)
        val bubble = checkNotNull(Views.tagged(a, "bubble:${gone.id}"))
        val label = TestCore.onMain {
            bubble.createAccessibilityNodeInfo().actionList.single { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }.label
        }
        assertEquals("TalkBack says what a long press offers", "Copier, Transférer, Supprimer", label)
        assertEquals("the bubble is named by its words", "Bonjour", TestCore.onMain { bubble.contentDescription.toString() })
        val heard = TestCore.onMain { Views.descendants(a.window.decorView).first { it.tag == "bubble:${kept.id}" }.contentDescription.toString() }
        assertEquals("a group message also says who wrote it", "${TestCore.onMain { core.senderName("!1234abcd") }}, Salut", heard)
        val menu = longPress(a, gone.id)
        assertEquals("no other chat, so nothing to forward to", listOf("action_copy", "action_share", "action_delete"), dialogTags(menu))
        tapInDialog(menu, "action_delete")
        val asked = checkNotNull(a.dialogForTest)
        assertEquals("Seulement sur ce téléphone. Les autres le gardent.",
            TestCore.onMain { asked.findViewById<android.widget.TextView>(android.R.id.message).text.toString() })
        TestCore.onMain { asked.getButton(DialogInterface.BUTTON_NEGATIVE).performClick() }
        inst.waitForIdleSync()
        assertNull(a.dialogForTest)
        assertNotNull("Annuler keeps it", core.history.message(gone.id))
        tapInDialog(longPress(a, gone.id), "action_delete")
        TestCore.onMain { checkNotNull(a.dialogForTest).getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
        inst.waitForIdleSync()
        assertNull(core.history.message(gone.id))
        assertNull(Views.tagged(a, "bubble:${gone.id}"))
        assertNotNull(Views.tagged(a, "bubble:${kept.id}"))
        assertTrue("nothing goes over the radio", TestCore.radio.sent.isEmpty())
    }

    @Test fun copyPutsTheWordsOnTheClipboard() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val heard = TestCore.onMain { core.history.addIncoming(group, "!1234abcd", "Rendez-vous au puits", "fr", System.currentTimeMillis(), false) }
        val a = open(group)
        tapInDialog(longPress(a, heard.id), "action_copy")
        val clip = TestCore.onMain { a.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        assertEquals("Rendez-vous au puits", clip)
        assertNull(a.dialogForTest)
        assertTrue(TestCore.radio.sent.isEmpty())
    }

    @Test fun forwardingPutsTheWordsInTheOtherChatsDraftAndSendsNothing() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val kofi = TestCore.onMain { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val heard = TestCore.onMain { core.history.addIncoming(group, "!1234abcd", "Le marché est demain", "fr", System.currentTimeMillis(), false) }
        val a = open(group)
        val menu = longPress(a, heard.id)
        assertEquals(listOf("action_copy", "action_share", "action_forward", "action_delete"), dialogTags(menu))
        tapInDialog(menu, "action_forward")
        val choose = checkNotNull(a.dialogForTest)
        assertEquals("only the other chats", listOf("Kofi"),
            TestCore.onMain { (0 until choose.listView.adapter.count).map { choose.listView.adapter.getItem(it).toString() } })
        TestCore.onMain { choose.listView.performItemClick(null, 0, 0) }
        Views.waitUntil {
            val front = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
            front is ConversationActivity && front !== a
        }
        assertEquals("Le marché est demain", core.draft(kofi))
        assertNull(core.draft(group))
        assertTrue("nothing goes out until Send", TestCore.radio.sent.isEmpty())
        Views.finish(Views.resumed())
    }

    @Test fun dayDividersSayTodayYesterdayOrTheDate() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        fun noon(day: java.time.LocalDate) = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val (old, yesterday, firstToday, secondToday) = TestCore.onMain {
            listOf(
                core.history.addIncoming(group, "!1234abcd", "Avant", "fr", noon(today.minusDays(3)), false),
                core.history.addIncoming(group, "!1234abcd", "Hier soir", "fr", noon(today.minusDays(1)), false),
                core.history.addOutgoing(group, "Ce matin", "fr", System.currentTimeMillis() - 1),
                core.history.addOutgoing(group, "Encore", "fr", System.currentTimeMillis()),
            )
        }
        val a = open(group)
        val date = today.minusDays(3).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
            .withLocale(java.util.Locale.forLanguageTag("fr")))
        assertEquals(date, Views.textOf(Views.tagged(a, "day:${old.id}")!!))
        assertEquals("Hier", Views.textOf(Views.tagged(a, "day:${yesterday.id}")!!))
        assertEquals("Aujourd'hui", Views.textOf(Views.tagged(a, "day:${firstToday.id}")!!))
        assertNull("one divider per day", Views.tagged(a, "day:${secondToday.id}"))
        val short = checkNotNull(Views.tagged(a, "bubble:${secondToday.id}"))
        assertTrue("a short message's bubble fits its words, not the screen width",
            TestCore.onMain { short.width < a.window.decorView.width / 2 })
        assertEquals(emptyList<String>(), A11yCheck.problems(a))
    }

    @Test fun aFailedMessageTeachesSendAgainOnceAndResendingClosesIt() {
        TestCore.install()
        TestCore.onMain { core.settings.firstMessageSent = true }
        val group = TestCore.onMain { core.groupChatId() }
        val failed = TestCore.onMain {
            core.history.addOutgoing(group, "Bonjour", "fr", System.currentTimeMillis()).also {
                core.history.setStatus(it.id, DeliveryStatus.FAILED)
            }
        }
        val a = open(group)
        assertNotNull(Views.tagged(a, "tip:failed"))
        Views.tap(a, "resend:${failed.id}")
        assertNull(Views.tagged(a, "tip:failed"))
        assertTrue("failed" in core.settings.tipsShown)
        Views.finish(a)
        val again = open(group)
        assertNull(Views.tagged(again, "tip:failed"))
    }

    @Test fun noChatTipWhileTheHoldToTalkHintShows() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain {
            core.history.addIncoming(group, "!1234abcd", "Salut", "fr", System.currentTimeMillis(), countUnread = false)
        }
        val a = open(group)
        assertNotNull(Views.tagged(a, "mic_hint"))
        assertNull(Views.tagged(a, "tip:arrive"))
        assertTrue(core.settings.tipsShown.isEmpty())
        assertTrue(core.settings.tipShowings.isEmpty())
    }

    @Test fun slidingUpClosesTheLockTipWithoutEndingTheRecording() {
        TestCore.install()
        TestCore.onMain { core.settings.tipsShown = setOf("cancel") }
        val a = open(TestCore.onMain { core.groupChatId() })
        hold(a)
        assertNotNull(Views.tagged(a, "tip:lock"))
        touch(a, MotionEvent.ACTION_MOVE, dyDp = -100f, atMs = 400)
        assertNull(Views.tagged(a, "tip:lock"))
        assertTrue("lock" in core.settings.tipsShown)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertTrue(core.recordLocked)
        touch(a, MotionEvent.ACTION_UP, dyDp = -100f, atMs = 500)
        assertEquals(AppCore.RecordState.RECORDING, core.recordState)
        assertTrue("using a tip is not a showing", core.settings.tipShowings.isEmpty())
    }

    @Test fun listeningClosesTheDraftTipWithoutSendingOrClearing() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain { core.setDraft(group, "Bonjour") }
        val a = open(group)
        assertNotNull(Views.tagged(a, "tip:listen"))
        Views.tap(a, "draft_play")
        assertNull(Views.tagged(a, "tip:listen"))
        assertTrue("listen" in core.settings.tipsShown)
        assertEquals("Bonjour", core.draft(group))
        assertEquals(listOf("Bonjour"), TestCore.speech.spoken)
        assertTrue(TestCore.radio.sent.isEmpty())
        TestCore.onMain { core.setDraft(group, "Salut"); core.openChat(group) }
        inst.waitForIdleSync()
        assertNull(Views.tagged(a, "tip:listen"))
    }

    @Test fun playingAnIncomingMessageClosesArrivalAndRestartRemembersIt() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain {
            core.settings.firstMessageSent = true
            TestCore.radio.receive("!1234abcd", "[pm1:fr] Salut", false)
        }
        val incoming = core.history.messages(group).single()
        val a = open(group)
        assertNotNull(Views.tagged(a, "tip:arrive"))
        Views.tap(a, "play:${incoming.id}")
        assertNull(Views.tagged(a, "tip:arrive"))
        assertEquals(setOf("arrive"), core.settings.tipsShown)
        assertEquals(listOf("Salut"), TestCore.speech.spoken)
        Views.finish(a)
        TestCore.restart()
        val again = open(group)
        assertNull(Views.tagged(again, "tip:arrive"))
        assertEquals(setOf("arrive"), core.settings.tipsShown)
    }

    @Test fun leavingCountsOnlyTheVisibleTipAndDoesNotPublishAnotherDuringStop() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain {
            core.settings.firstMessageSent = true
            TestCore.radio.receive("!1234abcd", "[pm1:fr] Salut", false)
        }
        val a = open(group)
        hold(a)
        assertNotNull(Views.tagged(a, "tip:cancel"))
        Views.finish(a)
        Views.waitUntil { core.recordState == AppCore.RecordState.IDLE }
        assertTrue("shown and left unused is not learned", core.settings.tipsShown.isEmpty())
        assertEquals(mapOf("arrive" to 1, "cancel" to 1), core.settings.tipShowings)
        assertNull(TestCore.onMain { Views.descendants(a.window.decorView).firstOrNull { it.tag == "tip:lock" || it.tag == "tip:arrive" } })
        val again = open(group)
        assertNotNull("unused, so it is offered again", Views.tagged(again, "tip:arrive"))
        hold(again)
        assertNotNull(Views.tagged(again, "tip:lock"))
        assertEquals(mapOf("arrive" to 2, "cancel" to 1), core.settings.tipShowings)
    }

    @Test fun aRenderKeepsTheTipPictureAndReducedMotionNeverTicks() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        hold(a)
        val picture = Views.tagged(a, "howto:cancel") as HowToView
        TestCore.onMain { core.openChat(group) }
        inst.waitForIdleSync()
        assertSame(picture, Views.tagged(a, "howto:cancel"))
        assertEquals(1, Views.all(a).count { it.tag?.toString()?.startsWith("tip:") == true })
        assertEquals(emptyList<String>(), A11yCheck.problems(a))
        assertFalse(TestCore.onMain { picture.isRunning })
    }

    @Test fun clearingAndSendingEndTheDraftTipAndDeliveryStartsAfterTheFirstSend() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain { core.setDraft(group, "Bonjour") }
        val a = open(group)
        assertNotNull(Views.tagged(a, "tip:listen"))
        Views.tap(a, "draft_clear")
        assertNull(Views.tagged(a, "tip:listen"))
        assertEquals("shown once, not learned", mapOf("listen" to 1), core.settings.tipShowings)
        TestCore.onMain { core.setDraft(group, "Salut"); core.openChat(group) }
        inst.waitForIdleSync()
        assertNotNull("the next draft offers it again", Views.tagged(a, "tip:listen"))
        Views.tap(a, "draft_send")
        assertNull(Views.tagged(a, "tip:listen"))
        assertNull("a group message ends at Sent, so nothing promises Delivered", Views.tagged(a, "tip:delivery"))
        assertNull(Views.tagged(a, "mic_hint"))
        TestCore.onMain { core.openChat(group) }
        inst.waitForIdleSync()
        assertNull(Views.tagged(a, "tip:delivery"))
        Views.finish(a)
        assertTrue("nothing was used or closed", core.settings.tipsShown.isEmpty())
        assertEquals("the group never shows the delivery tip", mapOf("listen" to 2), core.settings.tipShowings)
        // The first send in a private chat is its moment.
        val direct = TestCore.onMain { core.startDirectChat(RadioPeer("!1234abcd", "Awa")) }
        val b = open(direct)
        assertNull("nothing was sent here yet", Views.tagged(b, "tip:delivery"))
        TestCore.onMain { core.setDraft(direct, "Salut"); core.openChat(direct) }
        inst.waitForIdleSync()
        Views.tap(b, "draft_send")
        assertEquals(listOf("^all", "!1234abcd"), TestCore.radio.sent.map { it.destinationId })
        assertNotNull(Views.tagged(b, "tip:delivery"))
        Views.tap(b, "tip_close")
        assertNull(Views.tagged(b, "tip:delivery"))
        assertEquals(setOf("delivery"), core.settings.tipsShown)
        assertEquals("closing is not a showing", mapOf("listen" to 3), core.settings.tipShowings)
        TestCore.onMain { core.openChat(direct) }
        inst.waitForIdleSync()
        assertNull(Views.tagged(b, "tip:delivery"))
    }

    private fun whole(view: View): Boolean = TestCore.onMain {
        val seen = android.graphics.Rect()
        view.getGlobalVisibleRect(seen) && seen.width() == view.width && seen.height() == view.height
    }

    @Test fun aTipShowsItsPictureLargeAndLeavesTheMessagesAndTheBarsInReach() {
        TestCore.install()
        val group = TestCore.onMain {
            core.settings.firstMessageSent = true
            repeat(6) { TestCore.radio.receive("!1234abcd", "[pm1:fr] Salut $it", false) }
            core.groupChatId()
        }
        val a = open(group)
        val tip = checkNotNull(Views.tagged(a, "tip:arrive"))
        val picture = checkNotNull(Views.tagged(a, "howto:arrive"))
        val words = checkNotNull(Views.tagged(a, "howto_sentence"))
        val close = checkNotNull(Views.tagged(a, "tip_close"))
        val list = checkNotNull(Views.tagged(a, "bubbles"))
        TestCore.onMain {
            assertTrue("the picture fills the card: ${picture.width} of ${tip.width}", picture.width >= tip.width - Ui.dp(a, 24))
            assertTrue("and most of the screen's width", picture.width >= a.window.decorView.width * 4 / 5)
            assertEquals(picture.width / 2, picture.height)
            val at = IntArray(2)
            val wordsAt = IntArray(2)
            picture.getLocationOnScreen(at)
            words.getLocationOnScreen(wordsAt)
            assertTrue("the sentence is under the picture", wordsAt[1] >= at[1] + picture.height)
            assertSame("close sits in the sentence row", (words.parent as View), close.parent)
            assertTrue("messages keep ${list.height} px", list.height >= Ui.dp(a, ConversationActivity.MESSAGES_KEPT_DP))
        }
        assertTrue(whole(picture) && whole(close) && whole(checkNotNull(Views.tagged(a, "mic"))) && whole(checkNotNull(Views.tagged(a, "keyboard"))))
        assertTrue("a message is in view", Views.all(a).any { it.tag?.toString()?.startsWith("message:") == true })
        assertEquals(emptyList<String>(), A11yCheck.problems(a))
        // A draft: the tip moves on; Clear, Listen and Send stay whole under a large picture.
        Views.tap(a, "tip_close")
        TestCore.onMain { core.setDraft(group, "Bonjour"); core.openChat(group) }
        inst.waitForIdleSync()
        val listen = checkNotNull(Views.tagged(a, "howto:listen"))
        assertTrue(TestCore.onMain { listen.width >= a.window.decorView.width * 4 / 5 })
        for (tag in listOf("draft_clear", "draft_play", "draft_send", "draft_text")) assertTrue(tag, whole(checkNotNull(Views.tagged(a, tag))))
        assertTrue(TestCore.onMain { list.height >= Ui.dp(a, ConversationActivity.MESSAGES_KEPT_DP) })
        assertEquals(emptyList<String>(), A11yCheck.problems(a))
    }

    @Test fun theCutNoticeIsLargeAndTheDraftTipWaitsForItsTurn() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.speech.nextTranscript = "mot ".repeat(80).trim()
        val a = open(group)
        makeCutDraft(a)
        val notice = checkNotNull(Views.tagged(a, "draft_cut"))
        val picture = checkNotNull(Views.tagged(a, "howto:toolong"))
        assertTrue("the notice's picture is as wide as the notice", TestCore.onMain { picture.width == notice.width && picture.height * 2 == picture.width })
        assertNull("one picture at a time", Views.tagged(a, "tip:listen"))
        assertTrue(core.settings.tipsShown.isEmpty())
        assertTrue("a tapped recording has no tip, and the waiting draft tip had no moment", core.settings.tipShowings.isEmpty())
        for (tag in listOf("draft_clear", "draft_play", "draft_send", "draft_text", "howto:toolong")) assertTrue(tag, whole(checkNotNull(Views.tagged(a, tag))))
        assertEquals(emptyList<String>(), A11yCheck.problems(a))
        // Editing ends the notice; the draft's own tip takes its turn.
        Views.tap(a, "draft_text")
        assertNull(Views.tagged(a, "draft_cut"))
        assertNotNull(Views.tagged(a, "tip:listen"))
    }

    @Test fun aLowWindowKeepsTheSmallPictureAndTheWholeDraftBar() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain { core.setDraft(group, "Bonjour") }
        var a: Activity = open(group)
        a = rotate(a, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        val low = a
        assertTrue("the emulator's landscape is low", TestCore.onMain { low.resources.configuration.screenHeightDp } < ConversationActivity.ROOMY_DP)
        val picture = checkNotNull(Views.tagged(low, "howto:listen"))
        assertEquals("the picture stays beside the words", Ui.dp(low, 120), TestCore.onMain { picture.width })
        for (tag in listOf("draft_clear", "draft_play", "draft_send")) assertTrue(tag, whole(checkNotNull(Views.tagged(low, tag))))
    }

    @Test fun theListenButtonIsAFilledRoundButtonInBothStatesAndThemes() {
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            TestCore.nextTheme = theme
            TestCore.install()
            val group = TestCore.onMain { core.groupChatId() }
            TestCore.onMain { core.setDraft(group, "Bonjour") }
            val a = open(group)
            val palette = TestCore.onMain { Palette(a) }
            val play = checkNotNull(Views.tagged(a, "draft_play")) as android.widget.ImageButton
            fun pixel(dx: Int): Int = TestCore.onMain {
                val bitmap = Bitmap.createBitmap(play.width, play.height, Bitmap.Config.ARGB_8888)
                play.draw(Canvas(bitmap))
                bitmap.getPixel(play.width / 2 + dx, play.height / 4).also { bitmap.recycle() }
            }
            assertEquals("$theme idle fill", palette.accent, pixel(0))
            assertEquals("$theme round: the corner is empty", Color.TRANSPARENT, pixel(-play.width / 2 + 2))
            assertEquals(palette.onAccent, TestCore.onMain { play.imageTintList!!.defaultColor })
            assertTrue(TestCore.onMain { play.width >= Ui.dp(a, 64) && play.height >= Ui.dp(a, 64) })
            assertEquals("Écouter", description(a, "draft_play"))
            Views.tap(a, "draft_play")
            assertEquals("$theme playing fill", palette.good, pixel(0))
            assertEquals(palette.background, TestCore.onMain { play.imageTintList!!.defaultColor })
            assertEquals("Arrêter l'écoute", description(a, "draft_play"))
            assertEquals(listOf("Bonjour"), TestCore.speech.spoken)
            Views.finish(a)
            activity = null
            TestCore.uninstall()
        }
    }

    @Test fun anEmptyEditorIsNotTheFirstDraftAndErasingEndsItsMoment() {
        TestCore.install()
        val group = TestCore.onMain { core.groupChatId() }
        val a = open(group)
        Views.tap(a, "keyboard")
        assertNull("no draft exists yet", Views.tagged(a, "tip:listen"))
        val edit = Views.tagged(a, "draft_edit") as EditText
        TestCore.onMain { edit.setText("   ") }
        inst.waitForIdleSync()
        assertNull("whitespace cannot be listened to", Views.tagged(a, "tip:listen"))
        TestCore.onMain { edit.setText("Bonjour") }
        inst.waitForIdleSync()
        assertNotNull(Views.tagged(a, "tip:listen"))
        TestCore.onMain { edit.setText("") }
        inst.waitForIdleSync()
        assertNull("erasing ends the draft moment", Views.tagged(a, "tip:listen"))
        assertTrue(core.settings.tipsShown.isEmpty())
        assertEquals(mapOf("listen" to 1), core.settings.tipShowings)
        TestCore.onMain { edit.setText("Salut") }
        inst.waitForIdleSync()
        assertNotNull("a new draft is a new moment", Views.tagged(a, "tip:listen"))
        assertTrue(TestCore.speech.spoken.isEmpty())
        assertTrue(TestCore.radio.sent.isEmpty())
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

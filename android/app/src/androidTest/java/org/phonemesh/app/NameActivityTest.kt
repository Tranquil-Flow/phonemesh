package org.phonemesh.app

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.RecordGesture

/** Accounts plan step 10: say your name, see it, hear it, pick a colour, save. */
@RunWith(AndroidJUnit4::class)
class NameActivityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val core get() = TestCore.core

    @After fun tearDown() {
        Views.finish(Views.resumed())
        TestCore.uninstall()
    }

    private fun touch(view: View, action: Int) = TestCore.onMain {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 10f, 10f, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    // Views.waitUntil runs its condition on the main thread: no Views.* helpers inside it.
    private fun resumedNow(): android.app.Activity? = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()

    private fun field(screen: NameActivity) = Views.tagged(screen, "name_field") as EditText
    // Views.tagged hops to the main thread itself, so the view is fetched first and read inside onMain.
    private fun enabled(screen: NameActivity, tag: String): Boolean = Views.tagged(screen, tag)!!.let { v -> TestCore.onMain { v.isEnabled } }
    private fun type(screen: NameActivity, text: String) = field(screen).let { f -> TestCore.onMain { f.setText(text) } }

    @Test fun holdingTheMicrophoneWritesTheNameAndReadsItBack() {
        TestCore.install(named = false)
        TestCore.speech.nextTranscript = "  Aminata\u200B "
        val screen = Views.start(NameActivity::class.java)
        assertEquals("", Views.textOf(field(screen)))
        assertFalse("no name yet", enabled(screen, "name_save"))
        val mic = Views.tagged(screen, "name_mic")!!
        touch(mic, MotionEvent.ACTION_DOWN)
        assertTrue(TestCore.speech.recording)
        Thread.sleep(RecordGesture.TAP_MS + 100)
        touch(mic, MotionEvent.ACTION_UP)
        val f = field(screen)
        Views.waitUntil { f.text.toString() == "Aminata" }
        assertEquals("read back to check", listOf("Aminata"), TestCore.speech.spoken)
        assertFalse("a colour is still needed", enabled(screen, "name_save"))
        Views.tap(screen, "colour_4")
        assertTrue(enabled(screen, "name_save"))
        Views.tap(screen, "name_save")
        Views.waitUntil { screen.isFinishing }
        assertEquals("Aminata" to 4, TestCore.onMain { core.account() })
        assertTrue("the account's keys exist", TestCore.onMain { core.accounts.identity.size == 33 })
    }

    @Test fun aQuickTapRecordsUntilTheNextTap() {
        TestCore.install(named = false)
        TestCore.speech.nextTranscript = "Kofi"
        val screen = Views.start(NameActivity::class.java)
        val mic = Views.tagged(screen, "name_mic")!!
        touch(mic, MotionEvent.ACTION_DOWN); touch(mic, MotionEvent.ACTION_UP)
        assertTrue("still listening", TestCore.speech.recording)
        touch(mic, MotionEvent.ACTION_DOWN); touch(mic, MotionEvent.ACTION_UP)
        val f = field(screen)
        Views.waitUntil { f.text.toString() == "Kofi" }
    }

    @Test fun typingWorksAndTooLongIsSaid() {
        TestCore.install(named = false)
        val screen = Views.start(NameActivity::class.java)
        type(screen, "Awa")
        Views.tap(screen, "colour_0")
        assertTrue(enabled(screen, "name_save"))
        type(screen, "Awa ".repeat(10))
        assertEquals("Trop long. Prenez un nom plus court.", Views.textOf(Views.tagged(screen, "name_problem")!!))
        assertFalse(enabled(screen, "name_save"))
        type(screen, "Awa Traoré")
        assertTrue(enabled(screen, "name_save"))
        Views.tap(screen, "name_listen")
        assertEquals(listOf("Awa Traoré"), TestCore.speech.spoken)
    }

    @Test fun theKeyboardIsAskedNotToCorrectNames() {
        // The emulator's keyboard turned "Esi" into "ESO" when focus moved: names like Esi, Afi or Kossi are not
        // dictionary words, so the field asks for no suggestions (and so no autocorrect) as well as a person's name.
        TestCore.install(named = false)
        val screen = Views.start(NameActivity::class.java)
        val type = TestCore.onMain { (Views.descendants(screen.window.decorView).first { it.tag == "name_field" } as android.widget.EditText).inputType }
        assertTrue("no suggestions", type and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME, type and android.text.InputType.TYPE_MASK_VARIATION)
    }

    @Test fun withoutAVoiceTheNameIsTyped() {
        TestCore.install(voices = emptyList(), named = false)
        val screen = Views.start(NameActivity::class.java)
        assertFalse(enabled(screen, "name_mic"))
        assertEquals("Pas encore de voix. Écrivez votre nom.", Views.textOf(Views.tagged(screen, "name_say")!!))
        type(screen, "Ama")
        Views.tap(screen, "colour_7")
        Views.tap(screen, "name_save")
        Views.waitUntil { screen.isFinishing }
        assertEquals("Ama" to 7, TestCore.onMain { core.account() })
    }

    @Test fun settingsShowsTheNameAndChangesIt() {
        TestCore.install()
        val settings = Views.start(SettingsActivity::class.java)
        assertEquals("Awa", Views.textOf(Views.tagged(settings, "name_value")!!))
        Views.tap(settings, "settings_name")
        Views.waitUntil { resumedNow() is NameActivity }
        val screen = Views.resumed() as NameActivity
        assertEquals("the saved name is there to change", "Awa", Views.textOf(field(screen)))
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
        val identity = TestCore.onMain { core.accounts.identity.toList() }
        type(screen, "Awa B")
        Views.tap(screen, "name_save")
        Views.waitUntil { resumedNow() === settings }
        val value = Views.tagged(settings, "name_value") as android.widget.TextView
        Views.waitUntil { value.text.toString() == "Awa B" }
        assertEquals("changing the name keeps the keys", identity, TestCore.onMain { core.accounts.identity.toList() })
    }
}

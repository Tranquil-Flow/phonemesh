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
        assertEquals("the card carries the colour itself" , "Aminata" to 0x1E88E5, TestCore.onMain { core.account() })
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
        assertEquals("Ama" to 0x6D4C41, TestCore.onMain { core.account() })
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

    /** Decision 48: any colour from the wheel is saved as it is, and the preview shows it. */
    @Test fun aColourFromTheWheelIsSavedAndPreviewed() {
        TestCore.install(named = false)
        val screen = Views.start(NameActivity::class.java)
        type(screen, "Esi")
        Views.tap(screen, "name_more_colours")
        val wheel = Views.tagged(screen, "colour_wheel") as ColourWheel
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
        // The right edge of the wheel is pure red at the wheel's lightness.
        val picked = TestCore.onMain { wheel.pick(wheel.width - 1f, wheel.height / 2f); wheel.rgb }
        assertEquals(android.graphics.Color.HSVToColor(floatArrayOf(0f, 1f, wheel.lightness)) and 0xFFFFFF, picked)
        assertTrue("a wheel colour is enough to save", enabled(screen, "name_save"))
        Views.tap(screen, "name_save")
        Views.waitUntil { screen.isFinishing }
        assertEquals("Esi" to picked, TestCore.onMain { core.account() })
        assertEquals(org.phonemesh.core.account.Avatar.Letter, TestCore.onMain { core.ownAvatar() })
    }

    /** Decision 49: an emoji every supported phone draws, chosen or typed, becomes the picture; others are refused. */
    @Test fun anEmojiIsChosenForThePictureAndSaved() {
        TestCore.install(named = false)
        val screen = Views.start(NameActivity::class.java)
        type(screen, "Kofi")
        Views.tap(screen, "colour_3")
        Views.tap(screen, "picture_emoji")
        Views.waitUntil { resumedNow() is EmojiActivity }
        val emoji = Views.resumed() as EmojiActivity
        assertEquals(emptyList<String>(), A11yCheck.problems(emoji))
        val typed = Views.tagged(emoji, "emoji_type") as EditText
        TestCore.onMain { typed.setText("🥰"); typed.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE) }
        val problem = TestCore.onMain { Views.descendants(emoji.window.decorView).first { it.tag == "emoji_problem" } }
        Views.waitUntil { problem.visibility == View.VISIBLE }
        assertFalse("an emoji newer than Android 8.0 stays on the screen", emoji.isFinishing)
        TestCore.onMain { typed.setText("🐟"); typed.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE) }
        Views.waitUntil { resumedNow() === screen }
        assertTrue(Views.tagged(screen, "picture_emoji")!!.let { v -> TestCore.onMain { v.isSelected } })
        Views.tap(screen, "name_save")
        Views.waitUntil { screen.isFinishing }
        val profile = TestCore.onMain { core.accounts.profile()!! }
        assertEquals(org.phonemesh.core.account.Avatar.Emoji("🐟"), profile.avatar)
        assertEquals(0x43A047, profile.colour)
        assertEquals("the card carries the picture", profile, TestCore.onMain { core.accounts.card().profile })
    }

    @Test fun theEmojiGridShowsEmojiEveryPhoneDrawsAndATapChoosesOne() {
        TestCore.install(named = false)
        val screen = Views.start(NameActivity::class.java)
        Views.tap(screen, "picture_emoji")
        Views.waitUntil { resumedNow() is EmojiActivity }
        val emoji = Views.resumed() as EmojiActivity
        val grid = Views.tagged(emoji, "emoji_grid") as android.widget.GridView
        val count = TestCore.onMain { grid.adapter.count }
        assertTrue("about 1,500 emoji without skin tones: $count", count in 1200..1700)
        assertEquals("😀", TestCore.onMain { grid.adapter.getItem(0) })
        // A real finger: down then up on the second cell, entering at the window like a touch on the screen.
        TestCore.onMain {
            val cell = grid.getChildAt(1)
            val at = IntArray(2).also { cell.getLocationInWindow(it) }
            val x = at[0] + cell.width / 2f; val y = at[1] + cell.height / 2f
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                emoji.window.decorView.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        Views.waitUntil { resumedNow() === screen }
        val tile = Views.tagged(screen, "picture_emoji")!!
        Views.waitUntil { tile.isSelected }
        type(screen, "Ama")
        Views.tap(screen, "colour_1")
        Views.tap(screen, "name_save")
        Views.waitUntil { screen.isFinishing }
        assertEquals("the tapped emoji is the picture", org.phonemesh.core.account.Avatar.Emoji("😁"), TestCore.onMain { core.ownAvatar() })
    }

    /** The group naming screen has its own words: nothing there says "you" or "your" about the person's own look. */
    @Test fun theGroupNamingScreenTalksAboutTheGroupNotAboutYou() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val screen = Views.launch<NameActivity>(android.content.Intent(inst.targetContext, NameActivity::class.java).putExtra("people", arrayOf("0011223344556677")))
        assertTrue(Views.withText(screen, "Pick the group\u2019s colour") != null)
        assertTrue(Views.withText(screen, "Pick the group\u2019s picture") != null)
        assertTrue(Views.withText(screen, "Everyone in the group sees this.") != null)
        for (personal in listOf("Pick your colour", "Pick your picture", "This is what others see.")) {
            assertTrue("\"$personal\" is for your own name", Views.withText(screen, personal) == null)
        }
    }

    @Test fun theNameScreenForYourOwnNameKeepsItsWords() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val screen = Views.start(NameActivity::class.java)
        assertTrue(Views.withText(screen, "Pick your colour") != null)
        assertTrue(Views.withText(screen, "This is what others see.") != null)
        assertTrue(Views.withText(screen, "Pick the group\u2019s colour") == null)
    }
}

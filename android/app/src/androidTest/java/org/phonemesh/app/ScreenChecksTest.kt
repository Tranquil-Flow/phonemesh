package org.phonemesh.app

import android.app.Activity
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenChecksTest {
    private var activity: Activity? = null

    @After fun tearDown() {
        ScreenStates.finish(activity)
        TestCore.uninstall()
    }

    /** Fails on any problem, and when the screen shows fewer touch targets, list rows or texts than it must. */
    private fun check(minTargets: Int, minRows: Int = 0, minTexts: Int = 1, state: () -> Activity) {
        activity = state()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val scan = A11yCheck.scan(activity!!)
        assertEquals(emptyList<String>(), scan.problems)
        assertTrue("targets ${scan.targets} < $minTargets", scan.targets >= minTargets)
        assertTrue("rows ${scan.rows} < $minRows", scan.rows >= minRows)
        assertTrue("texts ${scan.texts} < $minTexts", scan.texts >= minTexts)
    }

    @Test fun everyRequestedStateHasACapture() {
        val names = listOf(
            "01-welcome-1", "01-welcome-3", "01-welcome-5", "01-welcome-still",
            "01-language-from-settings", "02-chats-step1", "02-chats-step2", "02-chats-step3",
            "02-chats-radio-lost", "03-chats", "04-chats-english", "05-conversation",
            "05-conversation-mic-hint", "06-draft-too-long", "06-draft-cut", "07-recording",
            "07-recording-tip", "08-radio", "09-settings", "10-help", "11-note-picture",
        )
        assertEquals(names, ScreenStates.all.map { it.first })
        assertEquals(names.map { "$it-dark" }, ScreenStates.dark.map { it.first })
    }

    @Test fun language() = check(minTargets = 5) { ScreenStates.language() }
    @Test fun chatsWithSetupCards() = check(minTargets = 4) { ScreenStates.chatsSetup() }
    @Test fun chatsWithRows() = check(minTargets = 4, minRows = 2) { ScreenStates.chatsRows() }
    @Test fun chatsInEnglish() = check(minTargets = 4, minRows = 2) { ScreenStates.chatsRows("en-US") }
    @Test fun conversation() = check(minTargets = 6, minRows = 4) { ScreenStates.conversation() }
    @Test fun draftTooLong() = check(minTargets = 3) { ScreenStates.draftTooLong() }
    @Test fun draftCut() = check(minTargets = 4) { ScreenStates.draftCut() }
    @Test fun recordingLocked() = check(minTargets = 2) { ScreenStates.recordingLocked() }
    @Test fun radio() = check(minTargets = 3, minRows = 2) { ScreenStates.radio() }
    @Test fun settings() = check(minTargets = 3) { ScreenStates.settings() }

    @Test fun welcomeMoment1() = check(minTargets = 5) { ScreenStates.welcome(.2f) }
    @Test fun languageFromSettings() = check(minTargets = 6) { ScreenStates.languageFromSettings() }
    @Test fun chatsStep2() = check(minTargets = 4) { ScreenStates.chatsStep(2) }
    @Test fun chatsStep3() = check(minTargets = 4) { ScreenStates.chatsStep(3) }
    @Test fun chatsRadioLost() = check(minTargets = 3) { ScreenStates.chatsRadioLost() }
    @Test fun conversationMicHint() = check(minTargets = 6) { ScreenStates.conversationMicHint() }
    @Test fun microphoneHintIsFullyReadableInBothThemes() {
        val states = (ScreenStates.all + ScreenStates.dark).filter { it.first.startsWith("05-conversation-mic-hint") }
        for ((name, state) in states) {
            activity = state()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val hint = checkNotNull(Views.tagged(activity!!, "mic_hint"))
            val words = Views.descendants(hint).filterIsInstance<android.widget.TextView>().single()
            val mic = checkNotNull(Views.tagged(activity!!, "mic"))
            TestCore.onMain {
                val layout = checkNotNull(words.layout)
                assertEquals(name, words.text.length, layout.getLineEnd(layout.lineCount - 1))
                val neededHeight = layout.getLineBottom(layout.lineCount - 1) + words.compoundPaddingTop + words.compoundPaddingBottom
                assertTrue("$name clips the hint: height=${words.height}, needed=$neededHeight", words.height >= neededHeight)
                val visible = android.graphics.Rect()
                assertTrue(name, words.getGlobalVisibleRect(visible))
                assertEquals("$name clips hint width", words.width, visible.width())
                assertEquals("$name clips hint height", words.height, visible.height())
                val hintAt = IntArray(2)
                val micAt = IntArray(2)
                hint.getLocationOnScreen(hintAt)
                mic.getLocationOnScreen(micAt)
                assertEquals("$name points at mic", micAt[0] + mic.width / 2, hintAt[0] + hint.width / 2)
                assertTrue("$name hint stays above mic", hintAt[1] + hint.height <= micAt[1])
            }
            ScreenStates.finish(activity)
            activity = null
        }
    }

    @Test fun theEmptyHintNeverCoversAChatRowDuringSetup() {
        for (step in 1..3) {
            activity = ScreenStates.chatsStep(step)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val hint = checkNotNull(Views.tagged(activity!!, "empty"))
            val chats = checkNotNull(Views.tagged(activity!!, "chats")) as android.widget.ListView
            TestCore.onMain {
                val hintAt = IntArray(2)
                val rowAt = IntArray(2)
                hint.getLocationOnScreen(hintAt)
                val row = chats.getChildAt(chats.childCount - 1)
                row.getLocationOnScreen(rowAt)
                assertTrue("step $step: hint top ${hintAt[1]} is over the row ending at ${rowAt[1] + row.height}", hintAt[1] >= rowAt[1] + row.height)
            }
            ScreenStates.finish(activity)
            activity = null
        }
    }

    @Test fun appearanceNamesAreCentredInTheirTiles() {
        activity = ScreenStates.settings()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        for (key in listOf("auto", "light", "dark")) {
            val tile = checkNotNull(Views.tagged(activity!!, "theme:$key"))
            val words = Views.descendants(tile).filterIsInstance<android.widget.TextView>().single()
            TestCore.onMain {
                val layout = checkNotNull(words.layout)
                val centre = words.left + words.totalPaddingLeft + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
                assertEquals("theme:$key name centre", tile.width / 2f, centre, 2f)
            }
        }
    }

    @Test fun recordingTip() = check(minTargets = 3) { ScreenStates.recordingTip() }
    @Test fun noteWithPicture() = check(minTargets = 5) { ScreenStates.noteWithPicture() }
    @Test fun help() = check(minTargets = 27) { ScreenStates.help() }

    @Test fun regionSetUp() = check(minTargets = 4) { ScreenStates.regionSetUp() }
    @Test fun regionChange() = check(minTargets = 4) { ScreenStates.regionChange() }
    @Test fun regionHelper() = check(minTargets = 3) { ScreenStates.regionHelper() }
    @Test fun regionCountries() = check(minTargets = 4, minRows = 3) { ScreenStates.regionCountries() }
    @Test fun regionHelpers() = check(minTargets = 4, minRows = 3) { ScreenStates.regionHelpers() }

    @Test fun everyScreenPassesInLight() = checkEveryScreen(ScreenStates.all, 0xFFFFFFFF.toInt())
    @Test fun everyScreenPassesInDark() = checkEveryScreen(ScreenStates.dark, 0xFF121212.toInt())

    private fun checkEveryScreen(states: List<Pair<String, () -> Activity>>, background: Int) {
        for ((name, state) in states) {
            activity = state()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(name, emptyList<String>(), A11yCheck.scan(activity!!).problems)
            assertEquals(name, background, TestCore.onMain { Palette(activity!!).background })
            assertEquals("theme switch restored after $name", ThemeMode.AUTO, TestCore.nextTheme)
            ScreenStates.finish(activity)
            activity = null
        }
    }

    @Test fun everyScreenIsSecure() {
        assumeTrue("the screenshot build has no FLAG_SECURE", BuildConfig.SECURE_SCREENS)
        for ((name, state) in ScreenStates.all + ScreenStates.dark) {
            activity = state()
            val shown = activity!!
            val flags = TestCore.onMain { shown.window.attributes.flags }
            assertTrue("$name lacks FLAG_SECURE", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            ScreenStates.finish(activity)
            activity = null
        }
    }
}

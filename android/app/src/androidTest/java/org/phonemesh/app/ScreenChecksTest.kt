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
            "01-language-from-settings", "02-chats-step1", "02-chats-step1-offline", "02-chats-friend-guide",
            "02-chats-step-name", "02-chats-step-add", "02-chats-add-upgrade", "02-chats-step2", "02-chats-step3", "02-chats-radio-lost", "03-chats", "04-chats-english", "05-conversation",
            "05-conversation-mic-hint", "05-conversation-tip", "05-conversation-reply", "05-forward-to", "05-hold-tip",
            "05-picture-choose", "05-picture-ready", "05-picture-bubbles",
            "06-draft-too-long", "06-draft-cut", "06-draft-planned", "06-plan-window", "06-plan-window-day", "06-plan-clock", "06-plan-calendar",
            "07-recording", "07-recording-tip", "07-writing", "08-radio", "09-settings", "09-name", "09-name-filled", "09-add-someone", "09-add-request", "09-safety",
            "09-safety-mismatch", "09-contact", "09-contact-missing", "09-person-chat", "09-hub-none", "09-hub-searching", "09-hub-roaming", "09-updates-ready", "09-updates-copying", "09-updates-error", "10-help", "10-help-more", "10-help-accounts", "11-note-picture",
        )
        assertEquals(names, ScreenStates.all.map { it.first })
        assertEquals(names.map { "$it-dark" }, ScreenStates.dark.map { it.first })
    }

    @Test fun language() = check(minTargets = 5) { ScreenStates.language() }
    @Test fun chatsWithSetupCards() = check(minTargets = 4) { ScreenStates.chatsSetup() }
    @Test fun chatsNameStep() = check(minTargets = 4) { ScreenStates.chatsStepName() }
    @Test fun chatsAddStep() = check(minTargets = 4) { ScreenStates.chatsStepAdd() }
    @Test fun chatsAddUpgrade() = check(minTargets = 4) { ScreenStates.chatsStepAdd(finished = true) }
    @Test fun nameScreen() = check(minTargets = 10) { ScreenStates.nameScreen(filled = true) }
    @Test fun addSomeoneScreen() = check(minTargets = 5) { ScreenStates.addSomeone(request = true) }
    @Test fun safetyScreen() = check(minTargets = 3) { ScreenStates.safety() }
    @Test fun safetyMismatchScreen() = check(minTargets = 3) { ScreenStates.safety(mismatch = true) }
    @Test fun contactDetailsScreen() = check(minTargets = 5) { ScreenStates.contactDetails() }
    @Test fun missingContactVoiceScreen() = check(minTargets = 4) { ScreenStates.contactDetails(missing = true) }
    @Test fun hubWithoutSavedCode() = check(minTargets = 3) { ScreenStates.hub(saved = false) }
    @Test fun hubSearching() = check(minTargets = 4) { ScreenStates.hub() }
    @Test fun hubRoaming() = check(minTargets = 4) { ScreenStates.hub(roaming = true) }
    @Test fun updatesReady() = check(minTargets = 4) { ScreenStates.updates("ready") }
    @Test fun updatesCopying() = check(minTargets = 3) { ScreenStates.updates("copying") }
    @Test fun updatesError() = check(minTargets = 3) { ScreenStates.updates("error") }
    @Test fun personChatHeader() = check(minTargets = 4) { ScreenStates.contactDetails(conversation = true) }
    @Test fun chatsWithRows() = check(minTargets = 4, minRows = 2) { ScreenStates.chatsRows() }
    @Test fun chatsInEnglish() = check(minTargets = 4, minRows = 2) { ScreenStates.chatsRows("en-US") }
    @Test fun conversation() = check(minTargets = 6, minRows = 4) { ScreenStates.conversation() }
    @Test fun conversationReply() = check(minTargets = 4, minRows = 2) { ScreenStates.conversationReply() }
    @Test fun draftPlanned() = check(minTargets = 4, minRows = 2) { ScreenStates.draftPlanned() }
    @Test fun holdTip() = check(minTargets = 6, minRows = 2) { ScreenStates.holdTip() }
    @Test fun pictureChoose() = check(minTargets = 3) { ScreenStates.pictureScreen(withPhoto = false) }
    @Test fun pictureReady() = check(minTargets = 8) { ScreenStates.pictureScreen(withPhoto = true) }
    @Test fun pictureBubbles() = check(minTargets = 4, minRows = 2) { ScreenStates.pictureBubbles() }
    @Test fun helpMore() = check(minTargets = 8) { ScreenStates.help(group = 2) }
    @Test fun helpAccounts() = check(minTargets = 4) { ScreenStates.help(group = 5) }
    @Test fun conversationTip() = check(minTargets = 6, minRows = 2) { ScreenStates.conversationTip() }
    @Test fun draftTooLong() = check(minTargets = 3) { ScreenStates.draftTooLong() }
    @Test fun draftCut() = check(minTargets = 4) { ScreenStates.draftCut() }
    @Test fun recordingLocked() = check(minTargets = 2) { ScreenStates.recordingLocked() }
    @Test fun writing() = check(minTargets = 2) { ScreenStates.writing() }
    @Test fun radio() = check(minTargets = 3, minRows = 2) { ScreenStates.radio() }
    @Test fun settings() = check(minTargets = 3) { ScreenStates.settings() }

    @Test fun welcomeMoment1() = check(minTargets = 5) { ScreenStates.welcome(.2f) }
    @Test fun languageFromSettings() = check(minTargets = 6) { ScreenStates.languageFromSettings() }
    @Test fun chatsStepOneOffline() = check(minTargets = 6) { ScreenStates.chatsStepOneOffline() }
    // Radio status, Help, Settings, Choose the file and Back: during setup there is no chat row and no ➕.
    @Test fun chatsFriendGuide() = check(minTargets = 5, minTexts = 6) { ScreenStates.chatsFriendGuide() }
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

    @Test fun duringSetupOnlyTheStepsShowAndTheirButtonsAreWhole() {
        val buttons = listOf(listOf("voice_download", "voice_file"), listOf("radio_search"), listOf("hello_try"))
        for (step in 1..3) {
            activity = ScreenStates.chatsStep(step)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            for (hidden in listOf("chats", "empty", "new_chat")) {
                assertEquals("step $step shows $hidden", null, Views.tagged(activity!!, hidden))
            }
            for (tag in buttons[step - 1]) {
                val button = checkNotNull(Views.tagged(activity!!, tag)) { "step $step has no $tag" }
                TestCore.onMain {
                    val seen = android.graphics.Rect()
                    assertTrue("step $step: $tag is out of sight", button.getGlobalVisibleRect(seen))
                    assertEquals("step $step: $tag is cut", button.height, seen.height())
                    assertEquals("step $step: $tag is cut", button.width, seen.width())
                }
            }
            ScreenStates.finish(activity)
            activity = null
        }
    }

    @Test fun theEmptyHintNeverCoversAChatRowBesideAProblemCard() {
        activity = ScreenStates.chatsRadioLost()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val hint = checkNotNull(Views.tagged(activity!!, "empty"))
        val chats = checkNotNull(Views.tagged(activity!!, "chats")) as android.widget.ListView
        checkNotNull(Views.tagged(activity!!, "new_chat"))
        TestCore.onMain {
            assertTrue("the group row is listed", chats.childCount > 0)
            val hintAt = IntArray(2)
            val rowAt = IntArray(2)
            hint.getLocationOnScreen(hintAt)
            val row = chats.getChildAt(chats.childCount - 1)
            row.getLocationOnScreen(rowAt)
            assertTrue("hint top ${hintAt[1]} is over the row ending at ${rowAt[1] + row.height}", hintAt[1] >= rowAt[1] + row.height)
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

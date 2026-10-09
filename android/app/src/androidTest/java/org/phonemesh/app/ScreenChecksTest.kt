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
        Views.finish(activity)
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

    @Test fun language() = check(minTargets = 2) { ScreenStates.language() }
    @Test fun chatsWithSetupCards() = check(minTargets = 4) { ScreenStates.chatsSetup() }
    @Test fun chatsWithRows() = check(minTargets = 4, minRows = 2) { ScreenStates.chatsRows() }
    @Test fun chatsInEnglish() = check(minTargets = 4, minRows = 2) { ScreenStates.chatsRows("en-US") }
    @Test fun conversation() = check(minTargets = 6, minRows = 4) { ScreenStates.conversation() }
    @Test fun draftTooLong() = check(minTargets = 3) { ScreenStates.draftTooLong() }
    @Test fun draftCut() = check(minTargets = 4) { ScreenStates.draftCut() }
    @Test fun recordingLocked() = check(minTargets = 2) { ScreenStates.recordingLocked() }
    @Test fun radio() = check(minTargets = 3, minRows = 2) { ScreenStates.radio() }
    @Test fun settings() = check(minTargets = 3) { ScreenStates.settings() }

    @Test fun everyScreenIsSecure() {
        assumeTrue("the screenshot build has no FLAG_SECURE", BuildConfig.SECURE_SCREENS)
        for ((name, state) in ScreenStates.all) {
            activity = state()
            val shown = activity!!
            val flags = TestCore.onMain { shown.window.attributes.flags }
            assertTrue("$name lacks FLAG_SECURE", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            Views.finish(activity)
            activity = null
        }
    }
}

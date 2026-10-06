package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageActivityTest {
    private var activity: LanguageActivity? = null

    @After fun tearDown() {
        Views.finish(activity)
        TestCore.uninstall()
    }

    @Test fun frenchComesFirstAndEachButtonIsItsOwnName() {
        TestCore.install(language = null)
        activity = Views.start(LanguageActivity::class.java)
        val buttons = Views.all(activity!!).filter { (it.tag as? String)?.startsWith("language:") == true }
        assertEquals(listOf("language:fr", "language:en-US"), buttons.map { it.tag })
        assertEquals("Français", Views.textOf(buttons[0]))
        assertEquals("English", buttons[1].contentDescription)
    }

    @Test fun choosingALanguageSpeaksItsNameAndRemembersIt() {
        TestCore.install(language = null)
        activity = Views.start(LanguageActivity::class.java)
        Views.tap(activity!!, "language:en-US")
        assertEquals(listOf(R.raw.name_en), TestCore.speech.clips)
        assertEquals("en-US", TestCore.core.settings.language)
        assertTrue(activity!!.isFinishing)
        assertEquals("en-US", TestCore.restart().settings.language)
    }

    @Test fun backOnFirstLaunchLeavesTheApp() {
        TestCore.install(language = null)
        activity = Views.start(LanguageActivity::class.java)
        @Suppress("DEPRECATION")
        TestCore.onMain { activity!!.onBackPressed() }
        assertTrue(activity!!.isFinishing)
        assertNull(TestCore.core.settings.language)
    }

    @Test fun theChosenLanguageIsAppliedNotThePhones() {
        TestCore.install(language = "fr")
        activity = Views.start(LanguageActivity::class.java)
        assertEquals("Choisissez votre langue", activity!!.getString(R.string.language_title))
        assertNotNull(Views.described(activity!!, "Choisissez votre langue"))
    }
}

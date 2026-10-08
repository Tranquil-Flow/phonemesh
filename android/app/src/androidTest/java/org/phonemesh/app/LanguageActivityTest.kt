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

    @Test fun theSpeakerOnlySaysTheNameAndStays() {
        TestCore.install(language = null)
        activity = Views.start(LanguageActivity::class.java)
        Views.tap(activity!!, "speak:en-US")
        Views.tap(activity!!, "speak:en-US")
        Views.tap(activity!!, "speak:fr")
        assertEquals(listOf(R.raw.name_en, R.raw.name_en, R.raw.name_fr), TestCore.speech.clips)
        assertFalse(activity!!.isFinishing)
        assertNull(TestCore.core.settings.language)
        for (language in LanguageRegistry.all) {
            val speaker = Views.tagged(activity!!, "speak:${language.tag}")!!
            val choice = Views.tagged(activity!!, "language:${language.tag}")!!
            TestCore.onMain {
                assertTrue(speaker.width >= Ui.dp(activity!!, 64))
                assertTrue(speaker.height >= Ui.dp(activity!!, 64))
                assertTrue("speaker and choice have a gap", choice.left > speaker.right)
            }
        }
    }

    @Test fun captionsFollowThePhoneBeforeAChoiceAndCanBeHeard() {
        TestCore.install(language = null)
        activity = Views.start(LanguageActivity::class.java)
        val screen = activity!!
        val phone = TestCore.onMain { screen.resources.configuration.locales[0] }
        val captions = LanguageRegistry.forCaptions(null, phone)
        // Animations are off in tests: the still frame, moment 3.
        assertEquals(screen.getString(R.string.caption_radio), Views.textOf(Views.tagged(screen, "caption")!!))
        assertEquals("3", Views.textOf(Views.tagged(screen, "caption_number")!!))
        Views.tap(screen, "caption_speak")
        assertEquals(listOf(captions.captionClips[3]), TestCore.speech.clips)
        val story = Views.tagged(screen, "story") as StoryView
        assertFalse(TestCore.onMain { story.isRunning })
        Thread.sleep(300)
        assertEquals(2, TestCore.onMain { story.moment })
        assertFalse(TestCore.onMain { story.isRunning })
    }

    @Test fun theStoryRunsOnlyWhileTheScreenIsVisible() {
        TestCore.install(language = null)
        StoryView.animationsEnabled = { true }
        activity = Views.start(LanguageActivity::class.java)
        val story = Views.tagged(activity!!, "story") as StoryView
        assertTrue(TestCore.onMain { story.isRunning })
        val top = Views.start(RadioActivity::class.java)
        try {
            Views.waitUntil { !story.isRunning }
        } finally {
            Views.finish(top)
        }
        Views.waitUntil { story.isRunning }
    }

    @Test fun fromSettingsTheChoiceIsMarkedAndBackKeepsIt() {
        TestCore.install(language = "fr")
        activity = Views.start(LanguageActivity::class.java)
        assertTrue(Views.tagged(activity!!, "language:fr")!!.isSelected)
        assertFalse(Views.tagged(activity!!, "language:en-US")!!.isSelected)
        assertEquals(activity!!.getString(R.string.caption_radio), Views.textOf(Views.tagged(activity!!, "caption")!!))
        Views.tap(activity!!, "caption_speak")
        assertEquals(listOf(LanguageRegistry.byTag("fr")!!.captionClips[3]), TestCore.speech.clips)
        Views.tap(activity!!, "back")
        // System back asks Android to finish asynchronously, as on the other screens.
        Views.waitUntil { activity!!.isFinishing }
        assertTrue(activity!!.isFinishing)
        assertEquals("fr", TestCore.core.settings.language)
    }

    @Test fun firstLaunchHasNoBackArrow() {
        TestCore.install(language = null)
        activity = Views.start(LanguageActivity::class.java)
        assertNull(Views.tagged(activity!!, "back"))
    }

    @Test fun eachMomentShowsItsCaptionDotAndMatchingClip() {
        TestCore.install(language = null)
        activity = Views.start(LanguageActivity::class.java)
        val screen = activity!!
        val story = Views.tagged(screen, "story") as StoryView
        val captions = LanguageRegistry.forCaptions(null, TestCore.onMain { screen.resources.configuration.locales[0] })
        val times = listOf(0f, .2f, .375f, .54f, .67f, .85f, 1f)
        val moments = listOf(-1, 0, 1, 2, 3, 4, -1)
        val clipIndices = listOf(0, 1, 2, 3, 4, 5, 0)
        times.forEachIndexed { step, time ->
            TestCore.onMain { story.freezeAt(time) }
            val moment = moments[step]
            assertEquals(moment, TestCore.onMain { story.moment })
            assertEquals(screen.getString(StoryCaptions.TEXT[clipIndices[step]]), Views.textOf(Views.tagged(screen, "caption")!!))
            assertEquals(if (moment < 0) "" else (moment + 1).toString(), Views.textOf(Views.tagged(screen, "caption_number")!!))
            for (dot in 0..4) {
                val view = Views.tagged(screen, "dot:$dot")!!
                TestCore.onMain {
                    val palette = Palette(screen)
                    val fill = (view.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor
                    assertEquals("active dot for moment $moment", if (dot == moment) palette.text else palette.background, fill)
                }
            }
            Views.tap(screen, "caption_speak")
        }
        assertEquals(clipIndices.map { captions.captionClips[it] }, TestCore.speech.clips)
        assertNull(TestCore.core.settings.language)
        assertFalse(screen.isFinishing)
    }
}

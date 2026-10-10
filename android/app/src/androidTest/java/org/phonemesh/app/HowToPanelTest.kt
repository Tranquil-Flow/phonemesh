package org.phonemesh.app

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.HowToScene
import org.phonemesh.core.HowToScenes

@RunWith(AndroidJUnit4::class)
class HowToPanelTest {
    private var screen: SettingsActivity? = null

    @After fun tearDown() {
        Views.finish(screen)
        if (screen != null) TestCore.uninstall()
    }

    private fun start(): SettingsActivity {
        TestCore.install()
        return Views.start(SettingsActivity::class.java).also { screen = it }
    }

    private fun attach(activity: SettingsActivity, panel: HowToPanel) = TestCore.onMain {
        activity.setContentView(FrameLayout(activity).apply {
            addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    @Test fun partsSwitchTheSceneAndItsSentence() {
        val activity = start()
        val said = mutableListOf<String>()
        var speakable = false
        val panel = TestCore.onMain {
            HowToPanel(activity, Palette(activity), listOf("power", "close", "search").map { HowToScenes.get(it) },
                sentence = { scene: HowToScene -> activity.getString(HowTo.sentence(scene.name)) },
                canSay = { speakable }, say = { scene: HowToScene -> said += scene.name })
        }
        attach(activity, panel)
        assertNotNull(Views.tagged(activity, "howto:power"))
        assertEquals("Allumez la radio.", Views.textOf(Views.tagged(activity, "howto_sentence")!!))
        assertNull("no voice, no speaker", Views.tagged(activity, "howto_say"))
        Views.tap(activity, "part:3")
        assertNotNull(Views.tagged(activity, "howto:search"))
        assertEquals("Touchez « Trouver la radio ».", Views.textOf(Views.tagged(activity, "howto_sentence")!!))
        assertTrue(TestCore.onMain { Views.descendants(panel).first { it.tag == "part:3" }.isSelected })
        TestCore.onMain { speakable = true; panel.update() }
        Views.tap(activity, "howto_say")
        assertEquals(listOf("search"), TestCore.onMain { said.toList() })
        TestCore.onMain { speakable = false; panel.update() }
        assertNull(Views.tagged(activity, "howto_say"))
    }

    @Test fun oneSceneHasNoPartButtons() {
        val activity = start()
        val panel = TestCore.onMain {
            HowToPanel(activity, Palette(activity), listOf(HowToScenes.get("hello")),
                sentence = { "x" }, canSay = { true }, say = {}, compact = true)
        }
        attach(activity, panel)
        assertNull(Views.tagged(activity, "part:1"))
        assertNotNull(Views.tagged(activity, "howto_say"))
    }

    @Test fun aLargePanelGivesUpItsPictureHeightBeforeItsWords() {
        val activity = start()
        val extra = TestCore.onMain { android.view.View(activity).apply {
            tag = "extra"
            layoutParams = android.widget.LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48))
        } }
        val panel = TestCore.onMain {
            HowToPanel(activity, Palette(activity), listOf(HowToScenes.get("hello")), sentence = { "x" }, canSay = { true }, say = {})
                .apply { add(extra) }
        }
        attach(activity, panel)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val picture = Views.tagged(activity, "howto:hello")!!
        val words = Views.tagged(activity, "howto_sentence")!!
        val speaker = Views.tagged(activity, "howto_say")!!
        TestCore.onMain {
            assertEquals("as wide as the panel", panel.width, picture.width)
            assertEquals(picture.width / 2, picture.height)
            assertSame("an added button joins the sentence row, after the speaker", speaker.parent, extra.parent)
            val row = speaker.parent as ViewGroup
            assertEquals(row.childCount - 1, row.indexOfChild(extra))
            assertTrue(picture.bottom <= row.top)
        }
        val limit = TestCore.onMain { panel.height * 2 / 3 }
        TestCore.onMain { panel.layoutParams = panel.layoutParams.apply { height = limit } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        TestCore.onMain {
            val row = speaker.parent as ViewGroup
            assertEquals(limit, panel.height)
            assertEquals("the sentence row stays whole", limit, row.bottom)
            assertTrue(row.height >= speaker.height && words.height > 0)
            assertTrue("the picture is smaller", picture.width < panel.width)
            assertEquals("and keeps its shape", picture.height * 2, picture.width)
            assertEquals("in the middle", (panel.width - picture.width) / 2, picture.left)
        }
    }

    @Test fun compactMultipleScenesStillHaveAccessiblePartButtons() {
        val activity = start()
        val panel = TestCore.onMain {
            HowToPanel(activity, Palette(activity), listOf("power", "close").map { HowToScenes.get(it) },
                sentence = { activity.getString(HowTo.sentence(it.name)) }, canSay = { false }, say = {}, compact = true)
        }
        attach(activity, panel)
        Views.tap(activity, "part:2")
        assertEquals(1, TestCore.onMain { panel.part })
        val button = Views.tagged(activity, "part:2")!!
        assertTrue(TestCore.onMain { button.width >= Ui.dp(activity, 48) && button.height >= Ui.dp(activity, 48) })
        assertEquals("Partie 2", TestCore.onMain { button.contentDescription.toString() })
    }
}

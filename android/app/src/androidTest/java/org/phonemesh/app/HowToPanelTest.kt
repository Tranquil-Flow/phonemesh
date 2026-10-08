package org.phonemesh.app

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
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

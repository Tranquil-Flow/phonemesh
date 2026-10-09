package org.phonemesh.app

import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore
import org.phonemesh.core.SetupPlan

/** Fixture packs and fake speech. Settings choices, engine actions and appearance. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var activity: SettingsActivity? = null
    private var behind: ChatsActivity? = null

    @After fun tearDown() {
        Views.finish(Views.resumed())
        Views.finish(activity)
        Views.finish(behind)
        TestCore.uninstall()
    }

    @Test fun theBackArrowLeavesTheScreen() {
        TestCore.install()
        activity = Views.start(SettingsActivity::class.java)
        assertEquals("Retour", Views.tagged(activity!!, "back")!!.contentDescription)
        Views.tap(activity!!, "back")
        // System back asks Android to finish asynchronously.
        Views.waitUntil { activity!!.isFinishing }
        assertTrue(activity!!.isFinishing)
    }

    @Test fun theLanguageRowShowsTheChoiceAndOpensTheList() {
        TestCore.install()
        activity = Views.start(SettingsActivity::class.java)
        assertEquals("Français", Views.textOf(Views.tagged(activity!!, "language_value")!!))
        Views.tap(activity!!, "settings_language")
        Views.waitUntil {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() is LanguageActivity
        }
        Views.finish(Views.resumed())
    }

    @Test fun everyCatalogPackIsListedWithItsSize() {
        TestCore.install(voices = emptyList())
        activity = Views.start(SettingsActivity::class.java)
        GeneratedPackCatalog.entries.forEach { entry ->
            val button = Views.tagged(activity!!, "download:${entry.id}") as? Button
            assertNotNull("no download button for ${entry.id}", button)
            assertTrue(Views.textOf(button!!).contains("${SetupPlan.megabytes(listOf(entry))} Mo"))
        }
    }

    @Test fun anInstalledVoiceCanBeRemoved() {
        TestCore.install()
        activity = Views.start(SettingsActivity::class.java)
        assertNotNull(Views.tagged(activity!!, "in_use:engine-whisper-tiny"))
        assertNull(Views.tagged(activity!!, "remove:engine-whisper-tiny"))
        assertEquals("Retirer Français", Views.tagged(activity!!, "remove:voice-fr-siwis")!!.contentDescription)
        Views.tap(activity!!, "remove:voice-fr-siwis")
        TestCore.core.awaitWorkForTest()
        inst.waitForIdleSync()
        assertNotNull(Views.tagged(activity!!, "download:voice-fr-siwis"))
        assertTrue(TestCore.speech.releases >= 1)
        assertEquals("Pack retiré : Français", Views.textOf(Views.tagged(activity!!, "note")!!))
    }

    @Test fun theSpeechCardsShowSpeedAccuracyAndSwitch() {
        TestCore.install()
        TestCore.onMain { Fixtures.store(context).install(Fixtures.engine("base")) }
        activity = Views.start(SettingsActivity::class.java)
        assertNotNull(Views.tagged(activity!!, "in_use:engine-whisper-tiny"))
        assertEquals("Vitesse : 3 sur 3", Views.tagged(activity!!, "speed:engine-whisper-tiny")!!.contentDescription)
        assertEquals("Précision : 3 sur 3", Views.tagged(activity!!, "accuracy:engine-whisper-base")!!.contentDescription)
        assertEquals("60 Mo", Views.textOf(Views.tagged(activity!!, "size:engine-whisper-tiny")!!))
        assertEquals("94 Mo", Views.textOf(Views.tagged(activity!!, "size:engine-whisper-base")!!))
        val packs = Views.tagged(activity!!, "packs") as LinearLayout
        assertTrue(TestCore.onMain { Views.descendants(packs).none { it.tag == "pack:engine-whisper-tiny" || it.tag == "pack:engine-whisper-base" } })
        Views.tap(activity!!, "use:engine-whisper-base")
        assertEquals("engine-whisper-base", TestCore.core.settings.engine)
        assertNotNull(Views.tagged(activity!!, "in_use:engine-whisper-base"))
        assertNull(Views.tagged(activity!!, "remove:engine-whisper-base"))
        assertEquals("Retirer Rapide", Views.tagged(activity!!, "remove:engine-whisper-tiny")!!.contentDescription)
        Views.finish(activity)
        TestCore.restart()
        activity = Views.start(SettingsActivity::class.java)
        assertNotNull(Views.tagged(activity!!, "in_use:engine-whisper-base"))
        Views.tap(activity!!, "remove:engine-whisper-tiny")
        TestCore.core.awaitWorkForTest()
        inst.waitForIdleSync()
        assertNotNull(Views.tagged(activity!!, "download:engine-whisper-tiny"))
        assertNotNull(Views.tagged(activity!!, "in_use:engine-whisper-base"))
    }

    @Test fun aThemeChangeReachesTheScreensBehind() {
        TestCore.install()
        val dark = 0xFF121212.toInt()
        behind = Views.start(ChatsActivity::class.java)
        val settings = Views.start(SettingsActivity::class.java)
        activity = settings
        assertTrue(Views.tagged(settings, "theme:auto")!!.isSelected)
        Views.tap(settings, "theme:dark")
        assertEquals(ThemeMode.DARK, TestCore.core.settings.theme)
        Views.waitUntil {
            val front = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            front is SettingsActivity && front !== settings && Palette(front).background == dark
        }
        activity = Views.resumed() as SettingsActivity
        assertTrue(Views.tagged(activity!!, "theme:dark")!!.isSelected)
        Views.tap(activity!!, "back")
        Views.waitUntil {
            val front = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            front is ChatsActivity && Palette(front).background == dark
        }
        behind = Views.resumed() as ChatsActivity
        Views.finish(behind)
        TestCore.restart()
        activity = Views.start(SettingsActivity::class.java)
        assertTrue(Views.tagged(activity!!, "theme:dark")!!.isSelected)
        assertEquals(dark, TestCore.onMain { Palette(activity!!).background })
        // Light is forced, then Auto restores the phone's configuration.
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.AUTO)) {
            val previous = activity!!
            Views.tap(previous, "theme:${mode.key}")
            Views.waitUntil {
                val front = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
                front is SettingsActivity && front !== previous
            }
            activity = Views.resumed() as SettingsActivity
            assertEquals(mode, TestCore.core.settings.theme)
            assertTrue(Views.tagged(activity!!, "theme:${mode.key}")!!.isSelected)
            val expected = Palette(ContextThemeWrapper(Themes.wrap(context, mode), R.style.Theme_Phonemesh)).background
            assertEquals(expected, TestCore.onMain { Palette(activity!!).background })
        }
    }

    @Test fun anEngineDownloadShowsProgressAndCanBeCancelled() {
        TestCore.install()
        val base = GeneratedPackCatalog.entries.single { it.id == "engine-whisper-base" }
        // Existing downloader, unreachable local port: no real pack download.
        PackDownloads(context, PackStore(Fixtures.packDir(context), GeneratedPackCatalog.entries), "http://10.0.2.2:9/").start(base)
        activity = Views.start(SettingsActivity::class.java)
        assertNotNull(Views.tagged(activity!!, "progress:${base.id}"))
        assertEquals("Annuler Précis", Views.tagged(activity!!, "cancel:${base.id}")!!.contentDescription)
        Views.tap(activity!!, "cancel:${base.id}")
        assertTrue(TestCore.core.downloads().pending().isEmpty())
        assertNull(Views.tagged(activity!!, "progress:${base.id}"))
        assertNotNull(Views.tagged(activity!!, "download:${base.id}"))
        assertNotNull(Views.tagged(activity!!, "in_use:engine-whisper-tiny"))
    }

    @Test fun theEnglishCardsHavePlainNamesAndSizes() {
        TestCore.install(language = "en-US", voices = emptyList())
        activity = Views.start(SettingsActivity::class.java)
        assertNotNull(Views.withText(activity!!, "Fast"))
        assertNotNull(Views.withText(activity!!, "Accurate"))
        assertEquals("Speed: 3 of 3", Views.tagged(activity!!, "speed:engine-whisper-tiny")!!.contentDescription)
        assertEquals("Accuracy: 2 of 3", Views.tagged(activity!!, "accuracy:engine-whisper-tiny")!!.contentDescription)
        assertEquals("60 MB", Views.textOf(Views.tagged(activity!!, "size:engine-whisper-tiny")!!))
        assertEquals("Download Accurate", Views.tagged(activity!!, "download:engine-whisper-base")!!.contentDescription)
        assertEquals(emptyList<String>(), A11yCheck.problems(activity!!))
    }

    @Test fun licencesOpenInADialog() {
        TestCore.install()
        activity = Views.start(SettingsActivity::class.java)
        Views.tap(activity!!, "licences")
        val dialog = checkNotNull(activity!!.licencesDialogForTest)
        val text = TestCore.onMain { Views.descendants(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString { it.text } }
        assertTrue(text.contains("sherpa-onnx"))
        TestCore.onMain { dialog.dismiss() }
    }
}

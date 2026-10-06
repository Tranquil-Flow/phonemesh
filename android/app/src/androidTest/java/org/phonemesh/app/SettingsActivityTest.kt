package org.phonemesh.app

import android.widget.Button
import android.widget.RadioButton
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
import org.phonemesh.core.SetupPlan

/** Fixture packs and fake speech. ModelChoiceUiTest's engine choice now lives here. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var activity: SettingsActivity? = null

    private fun isChecked(engineId: String): Boolean {
        val radio = Views.tagged(activity!!, "engine:$engineId") as RadioButton
        return TestCore.onMain { radio.isChecked }
    }

    @After fun tearDown() {
        Views.finish(activity)
        TestCore.uninstall()
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
        assertNotNull(Views.tagged(activity!!, "installed:engine-whisper-tiny"))
        assertEquals("Retirer Français", Views.tagged(activity!!, "remove:voice-fr-siwis")!!.contentDescription)
        Views.tap(activity!!, "remove:voice-fr-siwis")
        TestCore.core.awaitWorkForTest()
        inst.waitForIdleSync()
        assertNotNull(Views.tagged(activity!!, "download:voice-fr-siwis"))
        assertTrue(TestCore.speech.releases >= 1)
        assertEquals("Pack retiré : Français", Views.textOf(Views.tagged(activity!!, "note")!!))
    }

    @Test fun theEngineChoiceIsRemembered() {
        TestCore.install()
        TestCore.onMain { Fixtures.store(context).install(Fixtures.engine("base")) }
        activity = Views.start(SettingsActivity::class.java)
        assertTrue(isChecked("engine-whisper-tiny"))
        assertEquals("Base · plus gros", Views.textOf(Views.tagged(activity!!, "engine:engine-whisper-base")!!))
        Views.tap(activity!!, "engine:engine-whisper-base")
        assertEquals("engine-whisper-base", TestCore.core.settings.engine)
        Views.finish(activity)
        activity = Views.start(SettingsActivity::class.java)
        assertTrue(isChecked("engine-whisper-base"))
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

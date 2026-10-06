package org.phonemesh.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val old = context.getSharedPreferences(Settings.OLD_PREFS, Context.MODE_PRIVATE)
    private val saved = mutableMapOf<String, String?>()

    @Before fun setUp() {
        context.deleteSharedPreferences(NAME)
        listOf("engine", "radioAddress", "radioName", "language").forEach { saved[it] = old.getString(it, null) }
    }

    @After fun tearDown() {
        context.deleteSharedPreferences(NAME)
        old.edit().also { e -> saved.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) } }.commit()
    }

    @Test fun choicesPersistAcrossInstances() {
        val first = Settings(context, NAME)
        assertNull(first.language)
        first.language = "en-US"
        first.engine = "engine-whisper-base"
        first.radioAddress = "AA:BB:CC:DD:EE:01"
        first.radioName = "Kofi"
        val again = Settings(context, NAME)
        assertEquals("en-US", again.language)
        assertEquals("engine-whisper-base", again.engine)
        assertEquals("AA:BB:CC:DD:EE:01", again.radioAddress)
        assertEquals("Kofi", again.radioName)
    }

    @Test fun anUnknownLanguageReadsAsNotChosen() {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("language", "xx").commit()
        assertNull(Settings(context, NAME).language)
    }

    @Test fun thePhaseTwoEngineAndRadioAreKept() {
        old.edit().putString("engine", "engine-whisper-base").putString("radioAddress", "AA:BB:CC:DD:EE:02")
            .putString("radioName", "Ama").putString("language", "en-US").commit()
        val settings = Settings(context, NAME)
        assertEquals("engine-whisper-base", settings.engine)
        assertEquals("AA:BB:CC:DD:EE:02", settings.radioAddress)
        assertEquals("Ama", settings.radioName)
        assertNull("the language is always asked on first launch", settings.language)
    }

    @Test fun theChosenLanguageIsAppliedNotThePhones() {
        assertEquals("fr", Locales.wrap(context, "fr").resources.configuration.locales[0].language)
        assertEquals("en", Locales.wrap(context, "en-US").resources.configuration.locales[0].language)
        assertSame(context, Locales.wrap(context, null))
    }

    private companion object { const val NAME = "settings-test" }
}

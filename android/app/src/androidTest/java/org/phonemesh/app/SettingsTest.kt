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
        // Drain apply() writes before deleting the fixture; otherwise they can recreate a migrated file.
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().commit()
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

    @Test fun appearanceAndSetupFlagsPersist() {
        val first = Settings(context, NAME)
        assertEquals(ThemeMode.AUTO, first.theme)
        assertFalse(first.firstMessageSent)
        assertFalse(first.setupFinished)
        first.theme = ThemeMode.DARK
        first.firstMessageSent = true
        first.setupFinished = true
        val again = Settings(context, NAME)
        assertEquals(ThemeMode.DARK, again.theme)
        assertTrue(again.firstMessageSent)
        assertTrue(again.setupFinished)
    }

    @Test fun shownTipsAreRemembered() {
        val first = Settings(context, NAME)
        assertEquals(emptySet<String>(), first.tipsShown)
        first.tipsShown = setOf("cancel")
        first.tipsShown = first.tipsShown + "listen"
        assertEquals(setOf("cancel", "listen"), Settings(context, NAME).tipsShown)
    }

    @Test fun shownTipSetsAreDefensiveCopies() {
        val settings = Settings(context, NAME)
        val input = mutableSetOf("cancel", "lock")
        settings.tipsShown = input
        input.add("listen")
        assertEquals(setOf("cancel", "lock"), settings.tipsShown)
        // Java's Set mutation avoids a test-only Kotlin mutable-cast helper stripped by R8.
        val read = settings.tipsShown
        java.util.Set::class.java.getMethod("add", Any::class.java).invoke(read, "failed")
        assertEquals(setOf("cancel", "lock"), Settings(context, NAME).tipsShown)
    }

    @Test fun regionMemoryIsPerRadio() {
        val settings = Settings(context, NAME)
        settings.keep("AA", "TG")
        settings.keep("AA", "DE")
        assertEquals(setOf("TG", "DE"), settings.kept("AA"))
        assertTrue(settings.kept("BB").isEmpty())
        settings.setRadioCountry("AA", "TG")
        assertEquals("TG", settings.radioCountry("AA"))
        settings.setRadioCountry("AA", null)
        assertNull(settings.radioCountry("AA"))
        settings.setHelperSet("AA", true)
        assertTrue(settings.helperSet("AA"))
        assertFalse(settings.helperSet("BB"))
        settings.setHelperSet("AA", false)
        assertFalse(settings.helperSet("AA"))
    }

    @Test fun regionMemoryPersistsAcrossInstances() {
        val first = Settings(context, NAME)
        first.keep("AA", "TG")
        first.setRadioCountry("AA", "DE")
        first.setHelperSet("BB", true)
        val again = Settings(context, NAME)
        assertEquals(setOf("TG"), again.kept("AA"))
        assertEquals("DE", again.radioCountry("AA"))
        assertTrue(again.helperSet("BB"))
        assertFalse(again.helperSet("AA"))
    }

    private companion object { const val NAME = "settings-test" }
}

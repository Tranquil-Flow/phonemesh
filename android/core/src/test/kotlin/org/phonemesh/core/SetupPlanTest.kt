package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupPlanTest {
    private val catalog = GeneratedPackCatalog.entries
    private fun installed(id: String) = catalog.first { it.id == id }.let { PackInfo(File(id), id, it.version, it.kind, id) }

    @Test fun nothingInstalledNeedsTinyAndTheVoice() {
        val missing = SetupPlan.missing(catalog, emptyList(), "voice-fr-siwis", "fr")
        assertEquals(listOf("engine-whisper-tiny", "voice-fr-siwis"), missing.map { it.id })
        assertEquals(66, SetupPlan.megabytes(missing))
    }
    @Test fun anyEngineCounts() = assertEquals(listOf("voice-fr-siwis"),
        SetupPlan.missing(catalog, listOf(installed("engine-whisper-base")), "voice-fr-siwis", "fr").map { it.id })
    @Test fun anyVoiceForTheLanguageCounts() {
        val other = PackInfo(File("x"), "voice-fr-other", 1, PackKind.VOICE, "Autre", languageTag = "fr")
        assertTrue(SetupPlan.missing(catalog, listOf(installed("engine-whisper-tiny"), other), "voice-fr-siwis", "fr").isEmpty())
    }
    @Test fun theEnglishVoiceAloneIs14Mo() {
        val missing = SetupPlan.missing(catalog, listOf(installed("engine-whisper-tiny")), "voice-en-US-ljspeech", "en-US")
        assertEquals(14, SetupPlan.megabytes(missing))
    }
}

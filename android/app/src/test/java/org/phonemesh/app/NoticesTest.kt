package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binary users can find attribution for every redistributed component, in the app's language. */
class NoticesTest {
    private val attributions = listOf(
        "ZXing", "Apache-2.0", "ncnn", "Tencent", "BSD-3-Clause", "ThumbHash", "Evan Wallace", "MIT", "sherpa-onnx",
        "ONNX Runtime", "espeak-ng", "GPL-3.0", "piper-phonemize", "Michael Hansen", "Unlicense", "protobuf",
        "Meshtastic", "Unicode License v3", "Emoji 5.0", "Whisper", "LJ Speech Dataset", "SIWIS", "CC-BY 4.0", "AGPL-3.0", "NOTICE.txt",
    )

    @Test fun bothLanguagesNameEveryComponentAndLicence() {
        for (language in listOf("en", "fr")) for (name in attributions) {
            assertTrue("$language missing attribution: $name", Notices.text(language).contains(name))
        }
    }

    @Test fun bothLanguagesCarryTheSameLinks() {
        val links = Regex("""https://\S+""")
        assertEquals(links.findAll(Notices.text("fr")).map { it.value }.toList(), links.findAll(Notices.text("en")).map { it.value }.toList())
    }

    @Test fun englishIsEnglishAndFrenchIsFrench() {
        val english = Notices.text("en")
        assertTrue(english.contains("Third-party components"))
        for (french in listOf("Licence :", "Composants tiers", "Code source", "domaine public", "Données")) assertFalse(french, english.contains(french))
        assertTrue(Notices.text("fr").contains("Composants tiers"))
        assertEquals("other languages read the English text", english, Notices.text("de"))
    }
}

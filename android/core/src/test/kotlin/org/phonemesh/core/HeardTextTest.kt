package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HeardTextTest {
    @Test fun soundTagsAloneAreNothingHeard() {
        // Whisper writes these for silence, noise or music; a name or draft must never become "[Music]".
        for (tag in listOf("[Music]", "[MUSIC]", "[BLANK_AUDIO]", "(music)", "*music*", "♪", "♪♪", " [Silence] ", "[Musique]", "(applaudissements)")) {
            assertEquals(tag, "", HeardText.clean(tag))
        }
    }

    @Test fun speechKeepsItsWordsWithTagsAndExtraSpacesRemoved() {
        assertEquals("My name is Kwame.", HeardText.clean("[Music] My name is Kwame."))
        assertEquals("Bonjour Ama, à demain.", HeardText.clean("Bonjour Ama,  (rires) à demain. ♪"))
        assertEquals("Kofi", HeardText.clean("  Kofi  "))
    }

    @Test fun ordinaryPunctuationIsKept() {
        assertEquals("Yes: 3 * 4 is 12!", HeardText.clean("Yes: 3 * 4 is 12!"))
        assertEquals("Is it 5? Yes.", HeardText.clean("Is it 5? Yes."))
    }
}

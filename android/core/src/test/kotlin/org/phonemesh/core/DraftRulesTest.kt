package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftRulesTest {
    @Test fun aDraftThatFitsIsKeptWhole() {
        assertEquals("Bonjour Kofi", DraftRules.trimToFit("Bonjour Kofi", "fr"))
        assertEquals("", DraftRules.trimToFit("", "fr"))
        assertEquals("a\u0000b", DraftRules.trimToFit("a\u0000b", "fr"))
    }

    @Test fun aLongDraftIsCutAfterAWholeWord() {
        val text = "mot ".repeat(80).trim()
        val cut = DraftRules.trimToFit(text, "fr")
        assertEquals(DraftFit.FITS, DraftRules.fit(cut, "fr"))
        assertTrue(text.startsWith(cut))
        assertTrue(cut.endsWith("mot"))
        assertEquals(' ', text[cut.length])
        assertEquals("one more word would not fit", DraftFit.TOO_LONG, DraftRules.fit("$cut mot", "fr"))
    }

    @Test fun oneWordLongerThanAPartIsCutBetweenCharacters() {
        val cut = DraftRules.trimToFit("a".repeat(300), "fr")
        assertEquals(191, cut.length)
        val emoji = DraftRules.trimToFit("😀".repeat(100), "fr")
        assertEquals(DraftFit.FITS, DraftRules.fit(emoji, "fr"))
        assertFalse("never half an emoji", Character.isHighSurrogate(emoji.last()))
    }

    @Test fun anyTextComesBackAsAFittingStart() {
        val random = java.util.Random(7)
        val pieces = listOf("a", "é", "mot", "😀", " ", " ", "Kofi", "marché")
        repeat(500) {
            val text = buildString { repeat(random.nextInt(120)) { append(pieces[random.nextInt(pieces.size)]) } }.trim()
            val cut = DraftRules.trimToFit(text, "en-US")
            assertTrue(text.startsWith(cut))
            val fit = DraftRules.fit(cut, "en-US")
            assertTrue("$fit for \"$text\"", fit == DraftFit.FITS || fit == DraftFit.EMPTY)
            if (DraftRules.fit(text, "en-US") == DraftFit.FITS) assertEquals(text, cut)
        }
    }

    @Test fun blankIsEmpty() = assertEquals(DraftFit.EMPTY, DraftRules.fit("   ", "fr"))
    @Test fun shortTextFits() = assertEquals(DraftFit.FITS, DraftRules.fit("Bonjour Kofi", "fr"))
    @Test fun theLimitCountsTheTagAndUtf8Bytes() {
        val tag = "[pm1:fr] ".length // 9 bytes
        assertEquals(DraftFit.FITS, DraftRules.fit("a".repeat(200 - tag), "fr"))
        assertEquals(DraftFit.TOO_LONG, DraftRules.fit("a".repeat(201 - tag), "fr"))
        assertEquals(DraftFit.FITS, DraftRules.fit("é".repeat(95), "fr"))
        assertEquals(DraftFit.TOO_LONG, DraftRules.fit("é".repeat(96), "fr"))
    }
    @Test fun theEnglishTagIsLonger() {
        assertEquals(DraftFit.FITS, DraftRules.fit("a".repeat(188), "en-US"))
        assertEquals(DraftFit.TOO_LONG, DraftRules.fit("a".repeat(189), "en-US"))
    }
    @Test fun controlCharactersAreInvalid() = assertEquals(DraftFit.INVALID, DraftRules.fit("a\u0000b", "fr"))
    @Test fun fitsAgreesWithTheCodec() {
        for (n in 150..220) {
            val text = "a".repeat(n)
            val codec = runCatching { MessageCodec.encode(text, "fr") }.isSuccess
            assertEquals("length $n", codec, DraftRules.fit(text, "fr") == DraftFit.FITS)
        }
    }
}

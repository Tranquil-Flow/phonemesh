package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftRulesTest {
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

package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyRulesTest {
    @Test fun wordsIgnoreSymbols() {
        assertEquals(7, CopyRules.words("Voix française · 66 Mo · une seule fois"))
        assertEquals(1, CopyRules.words("↓ 42 %"))
        assertEquals(8, CopyRules.words("Pas trouvée ? Allumez la radio. Approchez le téléphone."))
        assertEquals(0, CopyRules.words(""))
    }
    @Test fun technicalWordsAreFoundAsWholeWords() {
        assertEquals(listOf("nœud", "paquet"), CopyRules.bannedIn("Le nœud a reçu le paquet"))
        assertEquals(listOf("ack"), CopyRules.bannedIn("Radio message ACK"))
        assertEquals(listOf("gatt"), CopyRules.bannedIn("GATT error"))
        assertTrue(CopyRules.bannedIn("acknowledged").isEmpty())
        assertTrue(CopyRules.bannedIn("Radio connectée, message parti").isEmpty())
    }
}

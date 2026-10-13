package org.phonemesh.app

import org.junit.Assert.*
import org.junit.Test
import org.phonemesh.core.HowToScenes
import java.io.File

/** Every setup picture has a localized sentence; unqualified pairing stays off. */
class HowToWordsTest {
    private fun names(dir: String) = File("src/main/res/$dir/strings.xml").readText()
        .let { text -> Regex("<string name=\"(howto_[a-z_]+)\"").findAll(text).map { it.groupValues[1] }.toSet() }

    @Test fun everySceneHasASentence() {
        val english = names("values")
        val french = names("values-fr")
        HowToScenes.all.keys.forEach { scene ->
            assertTrue("sentence id: $scene", HowTo.sentence(scene) != 0)
            assertTrue("en: howto_$scene", "howto_$scene" in english)
            assertTrue("fr: howto_$scene", "howto_$scene" in french)
        }
    }

    @Test fun pairingIsOffUntilRealRadioEvidence() = assertFalse(HowTo.showPair)

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownSceneCannotBorrowAnotherSentence() { HowTo.sentence("unknown") }
}

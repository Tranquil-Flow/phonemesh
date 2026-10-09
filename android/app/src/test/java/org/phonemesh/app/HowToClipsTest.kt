package org.phonemesh.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Only the two step 1 sentences have built-in audio, in every offered language. */
class HowToClipsTest {
    @Test fun everyLanguageRecordsTheTwoStepOneSentences() {
        LanguageRegistry.all.forEach { language ->
            assertEquals(language.tag, setOf("download", "friend"), language.howToClips.keys)
            assertEquals(language.tag, 2, language.howToClips.values.toSet().size)
        }
        val names = listOf("fr", "en").map { File("src/main/res/raw/name_$it.m4a").readBytes() }
        val clips = listOf("fr", "en").flatMap { short ->
            listOf("download", "friend").map { scene ->
                val clip = File("src/main/res/raw/howto_${short}_$scene.m4a")
                assertTrue("missing $clip", clip.isFile && clip.length() > 1_000)
                val bytes = clip.readBytes()
                names.forEach { assertFalse("name placeholder: $clip", bytes.contentEquals(it)) }
                bytes.toList()
            }
        }
        assertEquals("each sentence must have its own audio", 4, clips.toSet().size)
    }

    @Test fun theClipsStayWithinTheirShareOfTheBudget() {
        val total = File("src/main/res/raw").listFiles().orEmpty()
            .filter { it.name.startsWith("howto_") }.sumOf { it.length() }
        assertTrue("how-to clips take $total B", total <= 30_000)
    }
}

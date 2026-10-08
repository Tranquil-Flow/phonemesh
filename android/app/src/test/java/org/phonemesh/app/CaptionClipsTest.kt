package org.phonemesh.app

import org.junit.Assert.*
import org.junit.Test
import org.phonemesh.core.StoryTimeline
import java.io.File

/** Every caption has its own real clip in every language, built into the APK. */
class CaptionClipsTest {
    @Test fun oneCaptionPerMomentPlusTheOpening() {
        assertEquals(StoryTimeline.MOMENTS + 1, StoryCaptions.TEXT.size)
        assertEquals(0, StoryCaptions.index(StoryTimeline.OPENING))
        (0 until StoryTimeline.MOMENTS).forEach { moment ->
            assertEquals(moment + 1, StoryCaptions.index(moment))
        }
        assertEquals(listOf(
            R.string.caption_opening, R.string.caption_speak, R.string.caption_text,
            R.string.caption_radio, R.string.caption_voice, R.string.caption_listen,
        ), StoryCaptions.TEXT)
    }

    @Test fun everyLanguageHasEveryCaptionClip() {
        LanguageRegistry.all.forEach { language ->
            assertEquals(language.tag, StoryCaptions.TEXT.size, language.captionClips.size)
            assertEquals(language.tag, language.captionClips.size, language.captionClips.toSet().size)
        }
        assertEquals(listOf(
            R.raw.caption_fr_0, R.raw.caption_fr_1, R.raw.caption_fr_2,
            R.raw.caption_fr_3, R.raw.caption_fr_4, R.raw.caption_fr_5,
        ), checkNotNull(LanguageRegistry.byTag("fr")).captionClips)
        assertEquals(listOf(
            R.raw.caption_en_0, R.raw.caption_en_1, R.raw.caption_en_2,
            R.raw.caption_en_3, R.raw.caption_en_4, R.raw.caption_en_5,
        ), checkNotNull(LanguageRegistry.byTag("en-US")).captionClips)
        val names = listOf("fr", "en").map { File("src/main/res/raw/name_$it.m4a").readBytes() }
        val clips = listOf("fr", "en").flatMap { short ->
            StoryCaptions.TEXT.indices.map { i ->
                val clip = File("src/main/res/raw/caption_${short}_$i.m4a")
                assertTrue("missing $clip", clip.isFile && clip.length() > 1_000)
                val bytes = clip.readBytes()
                names.forEach { assertFalse("name placeholder: $clip", bytes.contentEquals(it)) }
                bytes.toList()
            }
        }
        assertEquals("each caption must have its own audio", clips.size, clips.toSet().size)
    }

    @Test fun theClipsStayWithinTheirShareOfTheBudget() {
        val total = File("src/main/res/raw").listFiles().orEmpty()
            .filter { it.name.startsWith("caption_") }.sumOf { it.length() }
        assertTrue("caption clips take $total B", total <= 90_000)
    }
}

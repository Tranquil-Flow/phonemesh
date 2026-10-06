package org.phonemesh.app

import org.junit.Assert.*
import org.junit.Test
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.MessageCodec
import org.phonemesh.core.PackKind
import java.io.File

class LanguageRegistryTest {
    @Test fun frenchComesFirst() {
        assertEquals("fr", LanguageRegistry.all.first().tag)
        assertEquals("Français", LanguageRegistry.default.endonym)
    }

    @Test fun tagsAreUniqueAndCanonical() {
        val tags = LanguageRegistry.all.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        tags.forEach { assertEquals(it, MessageCodec.canonical(it)) }
    }

    @Test fun everyVoiceIsACatalogVoice() {
        LanguageRegistry.all.forEach { language ->
            val entry = GeneratedPackCatalog.entries.single { it.id == language.voicePackId }
            assertEquals(PackKind.VOICE, entry.kind)
        }
    }

    @Test fun messageTagsFindTheirLanguage() {
        assertEquals("en-US", LanguageRegistry.forMessage("en")?.tag)
        assertEquals("fr", LanguageRegistry.forMessage("fr")?.tag)
        assertNull(LanguageRegistry.forMessage("de"))
        assertNull(LanguageRegistry.forMessage(null))
        assertNull(LanguageRegistry.forMessage("!!"))
    }

    @Test fun everyLanguageHasASmallNameClip() {
        assertEquals(LanguageRegistry.all.size, LanguageRegistry.all.map { it.clip }.toSet().size)
        LanguageRegistry.all.forEach { language ->
            val clip = File("src/main/res/raw/name_${language.locale.language}.m4a")
            assertTrue("missing $clip", clip.isFile)
            assertTrue("$clip is ${clip.length()} B", clip.length() in 1_000..16_000)
        }
    }
}

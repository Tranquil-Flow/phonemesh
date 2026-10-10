package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedPackCatalogTest {
    /** The app offers exactly the packs that are published; a file name outside this list could never download. */
    @Test fun theCatalogIsThePublishedPacks() = assertEquals(
        setOf("phonemesh-engine-whisper-tiny-1.zip", "phonemesh-engine-whisper-base-1.zip",
            "phonemesh-voice-fr-siwis-1.zip", "phonemesh-voice-en-US-ljspeech-1.zip"),
        GeneratedPackCatalog.entries.map { it.fileName }.toSet())

    @Test fun everyEntryHasItsOwnIdAndFile() {
        val entries = GeneratedPackCatalog.entries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(entries.size, entries.map { it.fileName }.toSet().size)
    }
}

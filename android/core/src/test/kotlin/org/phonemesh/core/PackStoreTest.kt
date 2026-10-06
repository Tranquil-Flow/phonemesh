package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

class PackStoreTest {
    private fun zip(files: Map<String,String>): ByteArrayInputStream {
        val bytes=ByteArrayOutputStream()
        val hashes=files.filterKeys { it!="SHA256SUMS" }.map { (name,text) ->
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }+"  "+name
        }.joinToString("\n")
        val archive=if("SHA256SUMS" in files) files else files+mapOf("SHA256SUMS" to hashes)
        ZipOutputStream(bytes).use { z -> archive.forEach { (name,text) ->
            z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
        } }
        return ByteArrayInputStream(bytes.toByteArray())
    }
    private val valid = mapOf("pack.properties" to "format=1\nlanguageTag=fr\nwhisperLanguage=fr\nlabel=French Piper\n",
        "whisper/encoder.int8.onnx" to "fixture", "whisper/decoder.int8.onnx" to "fixture",
        "whisper/tokens.txt" to "fixture", "tts/model.onnx" to "fixture", "tts/tokens.txt" to "fixture",
        "tts/espeak-ng-data/phontab" to "fixture", "tts/espeak-ng-data/phondata" to "fixture",
        "tts/espeak-ng-data/phonindex" to "fixture", "tts/espeak-ng-data/intonations" to "fixture",
        "tts/espeak-ng-data/lang/roa/fr" to "fixture")
    @Test fun missingCoreEspeakFileAndCorruptChecksumAreRejected() {
        for(files in listOf(valid-"tts/espeak-ng-data/phondata",valid+mapOf("SHA256SUMS" to "bad"))) {
            val root=Files.createTempDirectory("incomplete-pack").toFile()
            assertThrows(Exception::class.java) { PackStore(root).install(zip(files)) }
            assertTrue(PackStore(root).list().isEmpty())
        }
    }
    @Test fun recognizerMetadataIsExplicitAndLegacyPacksRemainTiny() {
        val root=Files.createTempDirectory("model-choices").toFile()
        try {
            val store=PackStore(root)
            assertEquals("whisper-tiny",store.install(zip(valid)).recognizerModel)
            val base=valid+mapOf("pack.properties" to valid.getValue("pack.properties")+"recognizerModel=whisper-base\n")
            assertEquals("whisper-base",store.install(zip(base)).recognizerModel)
            assertEquals(setOf("whisper-tiny","whisper-base"),store.list().map { it.recognizerModel }.toSet())
            val unknown=valid+mapOf("pack.properties" to valid.getValue("pack.properties")+"recognizerModel=unknown\n")
            assertThrows(IllegalArgumentException::class.java) { store.install(zip(unknown)) }
            assertEquals(2,store.list().size)
        } finally { root.deleteRecursively() }
    }
    @Test fun importAndReload() {
        val root=Files.createTempDirectory("packs").toFile()
        val pack=PackStore(root).install(zip(valid))
        assertEquals("fr",pack.languageTag)
        assertEquals("French Piper",PackStore(root).list().single().label)
        assertTrue(pack.root.resolve("whisper/encoder.int8.onnx").isFile)
    }
    @Test fun traversalMissingModelsAndOversizedArchivesFailAtomically() {
        for(files in listOf(valid+mapOf("../escaped" to "no"),valid-mapOf("tts/model.onnx" to "fixture").keys)) {
            val root=Files.createTempDirectory("packs").toFile()
            assertThrows(Exception::class.java) { PackStore(root).install(zip(files)) }
            assertTrue(PackStore(root).list().isEmpty())
            assertFalse(root.parentFile.resolve("escaped").exists())
        }
        val root=Files.createTempDirectory("packs").toFile()
        assertThrows(Exception::class.java) { PackStore(root,10).install(zip(valid)) }
        assertTrue(PackStore(root).list().isEmpty())
    }
}

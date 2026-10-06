package org.phonemesh.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class SharedEspeakDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A voice pack root holding [files] under tts/espeak-ng-data and listing them in SHA256SUMS. */
    private fun voice(name: String, files: Map<String, String>): File {
        val root = tmp.newFolder(name)
        val sums = StringBuilder()
        (files + ("tts/model.onnx" to "model")).forEach { (path, text) ->
            root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
            sums.append(sha(text)).append("  ").append(path).append('\n')
        }
        root.resolve("SHA256SUMS").writeText(sums.toString())
        return root
    }
    private val core = mapOf("tts/espeak-ng-data/phontab" to "tab", "tts/espeak-ng-data/fr_dict" to "fr")

    @Test fun firstUseCopiesThePronunciationDataOutOfThePack() {
        val loaded = AtomicBoolean(false)
        val shared = SharedEspeakData(tmp.root.resolve("espeak"), loaded)
        val fr = voice("fr", core)
        val dir = shared.prepare(fr)
        assertEquals("tab", dir.resolve("phontab").readText())
        assertEquals("fr", dir.resolve("fr_dict").readText())
        assertFalse("model files are not copied", dir.resolve("model.onnx").exists())
        fr.deleteRecursively()
        assertEquals("fr", dir.resolve("fr_dict").readText())
    }

    @Test fun newFilesAreAddedEvenWhileInUse() {
        val loaded = AtomicBoolean(false)
        val shared = SharedEspeakData(tmp.root.resolve("espeak"), loaded)
        shared.prepare(voice("fr", core))
        loaded.set(true)
        val dir = shared.prepare(voice("en", core + ("tts/espeak-ng-data/en_dict" to "en")))
        assertEquals("en", dir.resolve("en_dict").readText())
        assertEquals("fr", dir.resolve("fr_dict").readText())
    }

    @Test fun changedFilesWaitUntilNothingHasLoadedThem() {
        val loaded = AtomicBoolean(true)
        val dirPath = tmp.root.resolve("espeak")
        SharedEspeakData(dirPath, AtomicBoolean(false)).prepare(voice("old", core))
        val newer = voice("new", core + ("tts/espeak-ng-data/phontab" to "tab2"))
        SharedEspeakData(dirPath, loaded).prepare(newer)
        assertEquals("tab", dirPath.resolve("phontab").readText())
        SharedEspeakData(dirPath, AtomicBoolean(false)).prepare(newer)
        assertEquals("tab2", dirPath.resolve("phontab").readText())
    }

    @Test fun aDamagedCopyIsRepaired() {
        val dirPath = tmp.root.resolve("espeak")
        val fr = voice("fr", core)
        SharedEspeakData(dirPath, AtomicBoolean(false)).prepare(fr)
        dirPath.resolve("fr_dict").delete()
        SharedEspeakData(dirPath, AtomicBoolean(true)).prepare(fr)
        assertTrue(dirPath.resolve("fr_dict").isFile)
    }
}

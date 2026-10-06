package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackStoreTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sums(files: Map<String, String>) =
        files.filterKeys { it != "SHA256SUMS" }.toSortedMap().map { (n, t) -> "${sha(t.toByteArray())}  $n\n" }.joinToString("")
    private fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        val all = if ("SHA256SUMS" in files) files else files + ("SHA256SUMS" to sums(files))
        ZipOutputStream(out).use { z -> all.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
    private fun entry(id: String, version: Int, kind: PackKind, files: Map<String, String>) =
        CatalogEntry(id, version, kind, "$id-$version.zip", 1L shl 20, sha(sums(files).toByteArray()))

    private fun engine(version: Int = 1, model: String = "whisper-tiny") = mapOf(
        "pack.properties" to "format=2\nkind=engine\nid=engine-$model\nversion=$version\nlabel=Engine\nrecognizerModel=$model\n",
        "whisper/encoder.int8.onnx" to "e", "whisper/decoder.int8.onnx" to "d",
        "whisper/tokens.txt" to "<|en|> 1\n<|fr|> 2\n", "NOTICE.txt" to "n", "SOURCES.json" to "[]")
    private fun voice(version: Int = 1) = mapOf(
        "pack.properties" to "format=2\nkind=voice\nid=voice-fr-siwis\nversion=$version\nlabel=Français\nlanguageTag=fr\nwhisperLanguage=fr\n",
        "tts/model.onnx" to "m", "tts/tokens.txt" to "t",
        "tts/espeak-ng-data/phontab" to "x", "tts/espeak-ng-data/phondata" to "x",
        "tts/espeak-ng-data/phonindex" to "x", "tts/espeak-ng-data/intonations" to "x",
        "tts/espeak-ng-data/fr_dict" to "x", "tts/espeak-ng-data/lang/roa/fr" to "x",
        "NOTICE.txt" to "n", "SOURCES.json" to "[]")
    private fun store(vararg packs: Pair<Map<String, String>, PackKind>, allowUnknown: Boolean = false): Pair<PackStore, java.io.File> {
        val dir = Files.createTempDirectory("packs").toFile()
        val catalog = packs.map { (files, kind) ->
            val props = files.getValue("pack.properties").lines().associate { it.substringBefore('=') to it.substringAfter('=') }
            entry(props.getValue("id"), props.getValue("version").toInt(), kind, files)
        }
        return PackStore(dir, catalog, allowUnknown) to dir
    }
    private fun install(s: PackStore, files: Map<String, String>) = s.install(ByteArrayInputStream(zip(files)))
    private fun rejected(reason: PackRejected.Reason, block: () -> Unit) {
        val e = assertThrows(PackRejected::class.java) { block() }
        assertEquals(reason, e.reason)
    }

    @Test fun pinnedEngineAndVoiceInstallAndReload() {
        val (s, dir) = store(engine() to PackKind.ENGINE, voice() to PackKind.VOICE)
        assertEquals(PackKind.ENGINE, install(s, engine()).kind)
        val v = install(s, voice())
        assertEquals("fr", v.languageTag)
        assertEquals(setOf("engine-whisper-tiny", "voice-fr-siwis"), PackStore(dir, emptyList(), true).list().map { it.id }.toSet())
    }
    @Test fun manifestNotInCatalogIsUnknown() {
        val (s, _) = store(engine() to PackKind.ENGINE)
        rejected(PackRejected.Reason.UNKNOWN_PACK) { install(s, voice()) }
    }
    @Test fun tamperedFileWithRewrittenManifestIsUnknown() {
        val (s, _) = store(voice() to PackKind.VOICE)
        rejected(PackRejected.Reason.UNKNOWN_PACK) { install(s, voice() + ("tts/model.onnx" to "evil")) }
    }
    @Test fun tamperedFileWithOriginalManifestIsDamaged() {
        val (s, _) = store(voice() to PackKind.VOICE)
        val files = voice()
        rejected(PackRejected.Reason.DAMAGED) { install(s, files + ("SHA256SUMS" to sums(files)) + ("tts/model.onnx" to "evil")) }
    }
    @Test fun entriesOutsideTheAllowlistAreRejectedBeforeWriting() {
        for (extra in listOf("META-INF/x", "foo", "whisper/encoder.int8.onnx")) {
            val (s, dir) = store(voice() to PackKind.VOICE)
            rejected(PackRejected.Reason.UNSAFE) { install(s, voice() + (extra to "x")) }
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun formatOneIsWrongFile() {
        val (s, _) = store(allowUnknown = true)
        rejected(PackRejected.Reason.WRONG_FILE) {
            install(s, voice() + ("pack.properties" to "format=1\nlanguageTag=fr\nwhisperLanguage=fr\n"))
        }
    }
    @Test fun olderVersionIsRejectedAndSameVersionIsNoOp() {
        val (s, dir) = store(voice(1) to PackKind.VOICE, voice(2) to PackKind.VOICE)
        val two = install(s, voice(2))
        rejected(PackRejected.Reason.OLDER_VERSION) { install(s, voice(1)) }
        assertEquals(two.root, install(s, voice(2)).root)
        assertEquals(1, dir.listFiles().orEmpty().count { !it.name.startsWith(".") })
    }
    @Test fun newerVersionReplacesOlder() {
        val (s, dir) = store(voice(1) to PackKind.VOICE, voice(2) to PackKind.VOICE)
        install(s, voice(1)); install(s, voice(2))
        assertEquals(listOf(2), s.list().map { it.version })
        assertEquals(1, dir.listFiles().orEmpty().count { !it.name.startsWith(".") })
    }
    @Test fun traversalAndOversizeFailAtomically() {
        val (s, dir) = store(voice() to PackKind.VOICE)
        rejected(PackRejected.Reason.UNSAFE) { install(s, voice() + ("../escaped" to "no")) }
        assertFalse(dir.parentFile.resolve("escaped").exists())
        val tiny = PackStore(dir, emptyList(), true, maxBytes = 10)
        assertThrows(PackRejected::class.java) { install(tiny, voice()) }
        assertTrue(dir.listFiles().orEmpty().none { !it.name.startsWith(".") })
    }
    @Test fun removeDeletesOnlyThatPack() {
        val (s, _) = store(engine() to PackKind.ENGINE, voice() to PackKind.VOICE)
        install(s, engine()); install(s, voice())
        assertTrue(s.remove("voice-fr-siwis"))
        assertEquals(listOf("engine-whisper-tiny"), s.list().map { it.id })
        assertFalse(s.remove("voice-fr-siwis"))
    }
    @Test fun noSpaceIsReportedDistinctly() {
        val (s, _) = store(voice() to PackKind.VOICE)
        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("No space left on device")
        }
        rejected(PackRejected.Reason.NO_SPACE) { s.install(failing) }
    }
    @Test fun readinessReportsEachMissingPart() {
        val (s, _) = store(engine() to PackKind.ENGINE, voice() to PackKind.VOICE)
        assertEquals(Readiness.MissingEngine, s.readiness(null, "fr"))
        install(s, engine())
        assertEquals(Readiness.MissingVoice("fr"), s.readiness(null, "fr"))
        install(s, voice())
        val ready = s.readiness(null, "fr") as Readiness.Ready
        assertEquals("engine-whisper-tiny", ready.engine.id)
        assertEquals("voice-fr-siwis", ready.voice.id)
    }
    @Test fun engineWithoutTheVoiceLanguageIsUnsupported() {
        val noFrench = engine() + ("whisper/tokens.txt" to "<|en|> 1\n")
        val (s, _) = store(noFrench to PackKind.ENGINE, voice() to PackKind.VOICE)
        install(s, noFrench); install(s, voice())
        assertTrue(s.readiness(null, "fr") is Readiness.UnsupportedLanguage)
    }
    @Test fun preferredEngineIsHonoured() {
        val base = engine(model = "whisper-base")
        val (s, _) = store(engine() to PackKind.ENGINE, base to PackKind.ENGINE, voice() to PackKind.VOICE)
        install(s, engine()); install(s, base); install(s, voice())
        assertEquals("engine-whisper-base", (s.readiness("engine-whisper-base", "fr") as Readiness.Ready).engine.id)
    }
    @Test fun engineLanguagesAreReadOnceAndForgottenOnRemove() {
        val (s, _) = store(engine() to PackKind.ENGINE, voice() to PackKind.VOICE)
        val tiny = install(s, engine()); install(s, voice())
        assertTrue(s.readiness(null, "fr") is Readiness.Ready)
        tiny.root.resolve("whisper/tokens.txt").writeText("<|en|> 1\n")
        assertTrue(s.readiness(null, "fr") is Readiness.Ready)
        assertTrue(s.remove("engine-whisper-tiny"))
        assertEquals(Readiness.MissingEngine, s.readiness(null, "fr"))
    }
    @Test fun engineTokensWithoutLanguageTokensAreMultilingual() {
        // sherpa-onnx Whisper tokens.txt holds only base64 BPE tokens, no <|xx|> lines.
        val real = engine() + ("whisper/tokens.txt" to "IQ== 0\nIg== 1\n= 50256\n")
        val (s, _) = store(real to PackKind.ENGINE, voice() to PackKind.VOICE)
        install(s, real); install(s, voice())
        assertTrue(s.readiness(null, "fr") is Readiness.Ready)
    }
    @Test fun sameVersionWithDifferentContentReplacesTheInstalledPack() {
        val rebuilt = voice() + ("tts/model.onnx" to "m2")
        val (s, dir) = store(allowUnknown = true)
        val first = install(s, voice())
        val second = install(s, rebuilt)
        assertNotEquals(first.root, second.root)
        assertEquals("m2", second.root.resolve("tts/model.onnx").readText())
        assertEquals(listOf(second.root), s.list().map { it.root })
        assertEquals(1, dir.listFiles().orEmpty().count { !it.name.startsWith(".") })
    }
}

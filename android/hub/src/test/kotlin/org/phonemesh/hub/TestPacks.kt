package org.phonemesh.hub

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small but complete format-2 pack archives for tests. */
object TestPacks {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = TestKeys.hex(b)

    /** A format-2 pack archive: the files, `pack.properties`, and a `SHA256SUMS` that matches (or is bent by [bend]). */
    fun zip(
        id: String = "voice-fr-siwis", version: Int = 1, kind: String = "voice", format: Int = 2, extra: Map<String, ByteArray> = emptyMap(),
        bend: (Map<String, ByteArray>, String) -> String = { _, s -> s }, props: Boolean = true, sums: Boolean = true,
    ): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        val model = ByteArray(3_000) { (it * 5 + version).toByte() }
        if (kind == "engine") {
            if (props) files["pack.properties"] = "format=$format\nkind=engine\nid=$id\nversion=$version\nlabel=Test engine\nrecognizerModel=whisper-tiny\n".toByteArray()
            files["whisper/encoder.int8.onnx"] = model; files["whisper/decoder.int8.onnx"] = model.reversedArray(); files["whisper/tokens.txt"] = "a 1\nb 2\n".toByteArray()
        } else {
            if (props) files["pack.properties"] = "format=$format\nkind=$kind\nid=$id\nversion=$version\nlabel=Fran\u00e7ais\nlanguageTag=fr\nwhisperLanguage=fr\n".toByteArray()
            files["tts/model.onnx"] = model; files["tts/tokens.txt"] = "a 1\n".toByteArray()
            for (n in listOf("phontab", "phondata", "phonindex", "intonations")) files["tts/espeak-ng-data/$n"] = n.toByteArray()
            files["tts/espeak-ng-data/lang/fr"] = "name French\n".toByteArray()
        }
        files.putAll(extra)
        val text = bend(files, files.entries.joinToString("") { "${hex(sha(it.value))}  ${it.key}\n" })
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            if (sums) { z.putNextEntry(ZipEntry("SHA256SUMS")); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }
}

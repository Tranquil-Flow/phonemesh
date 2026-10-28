package org.phonemesh.core.update

import org.phonemesh.core.crypto.Bytes
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A complete format-2 voice pack as a ZIP, and the signed catalog entry that describes it. */
object TestPackZips {
    class Built(val zip: ByteArray, val entry: UpdateMetadata.Entry)

    fun voice(version: Int, seed: Int = 0, id: String = "voice-fr-siwis", modelBytes: Int = 4_000, bend: (MutableMap<String, ByteArray>) -> Unit = {}): Built {
        val files = linkedMapOf<String, ByteArray>()
        files["pack.properties"] = "format=2\nkind=voice\nid=$id\nversion=$version\nlabel=Fran\u00e7ais\nlanguageTag=fr\nwhisperLanguage=fr\n".toByteArray()
        files["tts/model.onnx"] = ByteArray(modelBytes) { (it * 7 + version + seed).toByte() }
        files["tts/tokens.txt"] = "a 1\nb 2\n".toByteArray()
        for (n in listOf("phontab", "phondata", "phonindex", "intonations")) files["tts/espeak-ng-data/$n"] = "$n$version".toByteArray()
        files["tts/espeak-ng-data/lang/fr"] = "name French\n".toByteArray()
        bend(files)
        val sums = files.entries.joinToString("") { "${hex(Bytes.sha256(it.value))}  ${it.key}\n" }.toByteArray()
        val all = LinkedHashMap(files).also { it["SHA256SUMS"] = sums }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in all) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        val zip = out.toByteArray()
        val entry = UpdateMetadata.Entry(UpdateMetadata.VOICE, id, version.toString(), "", version.toLong(), 1, 0, 26, 2, zip.size.toLong(), Bytes.sha256(zip),
            all.values.sumOf { it.size.toLong() }, all.size, Bytes.sha256(sums), ByteArray(32))
        return Built(zip, entry)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

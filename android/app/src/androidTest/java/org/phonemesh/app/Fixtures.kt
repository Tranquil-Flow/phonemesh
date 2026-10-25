package org.phonemesh.app

import android.content.Context
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixture packs: the right layout with fake models. They install and give Readiness.Ready, but cannot run speech. */
object Fixtures {
    fun archive(files: Map<String, String>): ByteArrayInputStream {
        val all = LinkedHashMap(files)
        all["SHA256SUMS"] = files.toSortedMap().map { (path, text) ->
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } + "  " + path + "\n"
        }.joinToString("")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            all.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return ByteArrayInputStream(output.toByteArray())
    }

    fun engine(model: String = "tiny") = archive(linkedMapOf(
        "pack.properties" to "format=2\nkind=engine\nid=engine-whisper-$model\nversion=1\nlabel=Fixture $model\nrecognizerModel=whisper-$model\n",
        "whisper/encoder.int8.onnx" to "fixture", "whisper/decoder.int8.onnx" to "fixture",
        "whisper/tokens.txt" to "<|en|> 1\n<|fr|> 2\n"))

    fun voice(tag: String = "fr", alternateId: String? = null): ByteArrayInputStream {
        val (defaultId, whisper, espeak) = when (tag) {
            "fr" -> Triple("voice-fr-siwis", "fr", "roa/fr")
            "en-US" -> Triple("voice-en-US-ljspeech", "en", "gmw/en")
            else -> error("No fixture voice for $tag")
        }
        val id = alternateId ?: defaultId
        return archive(linkedMapOf(
            "pack.properties" to "format=2\nkind=voice\nid=$id\nversion=1\nlabel=Fixture ${alternateId ?: tag}\nlanguageTag=$tag\nwhisperLanguage=$whisper\n",
            "tts/model.onnx" to "fixture", "tts/tokens.txt" to "fixture", "tts/espeak-ng-data/phontab" to "fixture",
            "tts/espeak-ng-data/phondata" to "fixture", "tts/espeak-ng-data/phonindex" to "fixture",
            "tts/espeak-ng-data/intonations" to "fixture", "tts/espeak-ng-data/lang/$espeak" to "fixture"))
    }

    fun packDir(context: Context) = File(context.filesDir, "packs")

    fun store(context: Context) = PackStore(packDir(context), GeneratedPackCatalog.entries, allowUnknown = true)

    /** The fixture Tiny engine plus one fixture voice per tag, in the app's own pack folder. */
    fun installReady(context: Context, tags: List<String> = listOf("fr")): List<PackInfo> =
        listOf(store(context).install(engine())) + tags.map { store(context).install(voice(it)) }

    /** A real pack ZIP pushed by push_test_packs.sh, installed with catalog pinning. */
    fun real(context: Context, name: String, dir: File = packDir(context)): PackInfo {
        val archive = File(checkNotNull(context.getExternalFilesDir(null)), name)
        check(archive.isFile) { "Missing model pack $archive (run push_test_packs.sh)" }
        return archive.inputStream().use { PackStore(dir, GeneratedPackCatalog.entries).install(it) }
    }
}

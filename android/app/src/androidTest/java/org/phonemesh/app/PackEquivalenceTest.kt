package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackStore
import org.phonemesh.speech.LocalSpeech
import org.phonemesh.speech.SpeechPack
import java.io.File

/** Gates for trimmed espeak data and the requantized Whisper Tiny decoder. Packs are built with
 *  `build_packs.py --reference` and pushed to the app's external files dir. */
@RunWith(AndroidJUnit4::class)
class PackEquivalenceTest {
    private val sentences = listOf(
        "en-US" to "This is a radio test. Meet me at the library.",
        "en-US" to "The battery level is low. Switch to the mesh node.",
        "fr" to "Bonjour, ceci est un message radio.",
        "fr" to "La batterie est faible. Passez au noeud du maillage.",
    )
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Same-version installs with identical manifests are no-ops, so every test starts empty. */
    private fun freshStore(): PackStore {
        val dir = File(ctx.filesDir, "equivalence-packs")
        dir.deleteRecursively()
        return PackStore(dir, GeneratedPackCatalog.entries, allowUnknown = true)
    }

    private fun install(store: PackStore, name: String): PackInfo =
        File(ctx.getExternalFilesDir(null), name).also { assertTrue("missing $name", it.isFile) }
            .inputStream().use { store.install(it) }

    @Test fun trimmedEspeakSynthesizesIdenticalSamples() {
        val store = freshStore()
        val engine = install(store, "phonemesh-engine-whisper-tiny-1.zip")
        for ((tag, voiceName) in listOf("fr" to "fr-siwis", "en-US" to "en-US-ljspeech")) {
            val trimmed = install(store, "phonemesh-voice-$voiceName-1.zip")
            val full = install(store, "phonemesh-voice-$voiceName-full-1.zip")
            fun synth(voice: PackInfo, text: String): FloatArray =
                LocalSpeech(SpeechPack(engine.root, voice.root, tag, tag.substringBefore('-'), noiseScale = 0f, noiseScaleW = 0f))
                    .use { it.synthesize(text).samples }
            for ((_, text) in sentences.filter { it.first == tag }) {
                val full1 = synth(full, text)
                val full2 = synth(full, text)
                assertArrayEquals("synthesis is not deterministic (full vs full) for: $text", full1, full2, 0f)
                assertArrayEquals("espeak trimming changed audio for: $text", full1, synth(trimmed, text), 0f)
            }
        }
    }

    @Test fun candidateDecoderTranscriptsAndTiming() {
        val store = freshStore()
        val candidate = install(store, "phonemesh-engine-whisper-tiny-1.zip")
        val reference = install(store, "phonemesh-engine-whisper-tiny-reference-1.zip")
        val rows = JSONArray()
        for ((tag, text) in sentences) {
            val voice = install(store, if (tag == "fr") "phonemesh-voice-fr-siwis-1.zip" else "phonemesh-voice-en-US-ljspeech-1.zip")
            val lang = tag.substringBefore('-')
            val audio = LocalSpeech(SpeechPack(reference.root, voice.root, tag, lang)).use { it.synthesize(text) }
            val clips = listOf("normal" to audio.samples, "quiet" to FloatArray(audio.samples.size) { audio.samples[it] * 0.35f })
            for ((variant, samples) in clips) {
                fun decode(engine: PackInfo): Pair<String, Double> =
                    LocalSpeech(SpeechPack(engine.root, voice.root, tag, lang)).use {
                        val t = System.nanoTime()
                        val out = it.transcribe(samples, audio.sampleRate)
                        out to (System.nanoTime() - t) / 1e9
                    }
                val (refText, refSec) = decode(reference)
                val (candText, candSec) = decode(candidate)
                rows.put(
                    JSONObject().put("tag", tag).put("text", text).put("variant", variant)
                        .put("reference", refText).put("candidate", candText).put("identical", refText == candText)
                        .put("referenceSeconds", refSec).put("candidateSeconds", candSec),
                )
            }
        }
        File(ctx.getExternalFilesDir(null), "pack-equivalence.json").writeText(rows.toString(2))
        assertEquals(8, rows.length())
    }
}

package org.phonemesh.app

import android.Manifest
import android.os.Build
import android.os.Debug
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/**
 * Silent, fixed-input Tiny vs Whisper-Base comparison.
 *
 * - No microphone, no playback, no radio, no network, no adb, no Gradle here.
 * - Input WAVs are synthesised exactly once per sentence with Piper and reused
 *   byte-for-byte for both recognizer models.
 * - Results are written as structured JSON. The final assertions only check that
 *   the produced outputs are valid; they deliberately do NOT claim that the larger
 *   model must be better.
 */
@RunWith(AndroidJUnit4::class)
class SpeechComparisonTest {

    private data class Sentence(val tag: String, val id: String, val text: String, val quiet: Boolean)

    /** 4 sentences (2 EN, 2 FR); 2 of them additionally decoded from a quiet variant = 12 decodes. */
    private val sentences = listOf(
        Sentence("en-US", "en-01", "This is a radio test. Meet me at the library.", false),
        Sentence("en-US", "en-02", "The battery level is low. Switch to the mesh node.", true),
        Sentence("fr", "fr-01", "Bonjour, ceci est un message radio.", false),
        Sentence("fr", "fr-02", "La batterie est faible. Passez au noeud du maillage.", true),
    )

    private val models = listOf(
        Model("tiny", "phonemesh-engine-whisper-tiny-1.zip"),
        Model("base", "phonemesh-engine-whisper-base-1.zip"),
    )

    private data class Model(val id: String, val engineArchive: String)

    /** Deterministic, reproducible attenuation; keeps audio comfortably above LocalSpeech silence floors. */
    private fun attenuate(samples: FloatArray, gain: Float): FloatArray =
        FloatArray(samples.size) { (samples[it] * gain).toFloat() }

    private fun voiceArchive(tag: String): String {
        val voice = if (tag == "en-US") "ljspeech" else "siwis"
        return "phonemesh-voice-$tag-$voice-1.zip"
    }

    private fun install(store: PackStore, sourceDir: File, name: String): PackInfo {
        val archive = File(sourceDir, name)
        assertTrue("Missing model pack ${archive.name}", archive.isFile)
        return archive.inputStream().use { store.install(it) }
    }

    private fun speechPack(engine: PackInfo, voice: PackInfo) =
        SpeechPack(engine.root, voice.root, checkNotNull(voice.languageTag), checkNotNull(voice.whisperLanguage))

    @Test
    fun tinyVersusBaseOnFixedSyntheticInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName),
        )
        val sourceDir = checkNotNull(context.getExternalFilesDir(null))
        val benchmarkDir = File(context.filesDir, "speech-benchmark-packs")
        benchmarkDir.deleteRecursively()
        val store = PackStore(benchmarkDir, GeneratedPackCatalog.entries)
        val results = JSONArray()
        var decodeCalls = 0

        try {
            // Pass 1: synthesise each sentence exactly once and keep the PCM samples.
            val clips = ArrayList<Pair<Sentence, org.phonemesh.speech.AudioPcm>>()
            for (model in models) {
                // Only the tiny Piper voice is needed to create the fixed input; base reuses it.
                if (model.id != "tiny") continue
                val engine = install(store, sourceDir, model.engineArchive)
                for (tag in listOf("en-US", "fr")) {
                    val voice = install(store, sourceDir, voiceArchive(tag))
                    LocalSpeech(speechPack(engine, voice)).use { speech ->
                        for (s in sentences.filter { it.tag == tag }) {
                            val audio = speech.synthesize(s.text)
                            clips.add(s to audio)
                            WavOutput.write(File(sourceDir, "comparison-${s.id}.wav"), audio.samples, audio.sampleRate)
                        }
                    }
                }
            }
            assertTrue("No clips synthesised", clips.isNotEmpty())

            // Pass 2: decode the identical samples with each recognizer, sequentially.
            for (model in models) {
                var loadSecondsTotal = 0.0
                val engine = install(store, sourceDir, model.engineArchive)
                for (tag in listOf("en-US", "fr")) {
                    val voice = install(store, sourceDir, voiceArchive(tag))
                    val loadStart = System.nanoTime()
                    LocalSpeech(speechPack(engine, voice)).use { speech ->
                        val loadSeconds = (System.nanoTime() - loadStart) / 1e9
                        loadSecondsTotal += loadSeconds
                        for ((sentence, audio) in clips.filter { it.first.tag == tag }) {
                          for (quiet in if (sentence.quiet) listOf(false,true) else listOf(false)) {
                            val samples = if (quiet) attenuate(audio.samples, QUIET_GAIN) else audio.samples
                            val pcm = ByteBuffer.allocate(samples.size*4).order(ByteOrder.LITTLE_ENDIAN)
                            samples.forEach { pcm.putFloat(it) }
                            val pcmHash = MessageDigest.getInstance("SHA-256").digest(pcm.array()).joinToString("") { "%02x".format(it) }
                            val decodeStart = System.nanoTime()
                            val transcript = speech.transcribe(samples, audio.sampleRate)
                            val decodeSeconds = (System.nanoTime() - decodeStart) / 1e9
                            decodeCalls++
                            val expectedWords = normalise(sentence.text).split(' ').filter { it.isNotEmpty() }
                            val hypothesisWords = normalise(transcript).split(' ').filter { it.isNotEmpty() }
                            results.put(
                                JSONObject()
                                    .put("model", model.id)
                                    .put("languageTag", tag)
                                    .put("sampleId", sentence.id + if (quiet) "-quiet" else "-clean")
                                    .put("expected", sentence.text)
                                    .put("transcript", transcript)
                                    .put("variant", if (quiet) "quiet" else "clean")
                                    .put("gain", if (quiet) QUIET_GAIN else 1.0)
                                    .put("inputPcmSha256", pcmHash)
                                    .put("processPssKiB", Debug.getPss())
                                    .put("numThreads", NUM_THREADS)
                                    .put("loadSeconds", loadSeconds)
                                    .put("inferenceSeconds", decodeSeconds)
                                    .put("audioSeconds", samples.size / audio.sampleRate.toDouble())
                                    .put("rtf", decodeSeconds / (samples.size / audio.sampleRate.toDouble()))
                                    .put("expectedWordCount", expectedWords.size)
                                    .put("transcriptWordCount", hypothesisWords.size)
                                    .put("expectedWordErrorCount", wordErrors(expectedWords, hypothesisWords)),
                            )
                          }
                        }
                    }
                }
                results.put(
                    JSONObject()
                        .put("model", model.id)
                        .put("summary", true)
                        .put("loadSecondsTotal", loadSecondsTotal)
                        .put("decodeCalls", decodeCalls),
                )
            }
            assertTrue("Expected exactly 12 decode calls, got $decodeCalls", decodeCalls == EXPECTED_DECODE_CALLS)
        } finally {
            // Close and delete the benchmark-only pack installs; keep the results.
            benchmarkDir.deleteRecursively()
        }

        val out = File(sourceDir, "speech-comparison-results.json")
        out.writeText(
            JSONObject()
                .put("schema", 1)
                .put("threads", NUM_THREADS)
                .put("decodeCalls", decodeCalls)
                .put("samples", results)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("notes", "Outputs only. No claim that the larger model is more accurate.")
                .toString(2),
        )

        // Validity-only assertions.
        for (i in 0 until results.length()) {
            val row = results.getJSONObject(i)
            if (row.optBoolean("summary", false)) continue
            assertTrue("Missing transcript", row.getString("transcript").isNotBlank())
            assertTrue("Non-positive inference time", row.getDouble("inferenceSeconds") > 0.0)
            assertTrue("Non-positive load time", row.getDouble("loadSeconds") > 0.0)
            assertTrue("Non-positive audio duration", row.getDouble("audioSeconds") > 0.0)
        }
        assertTrue("Results file missing", out.isFile && out.length() > 0)
    }

    private fun normalise(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace('’','\'')
        .replace(Regex("[^\\p{L}\\p{N}']+")," ").trim()

    /** Levenshtein distance over whitespace tokens: a real, measured error count. */
    private fun wordErrors(expected: List<String>, hypothesis: List<String>): Int {
        val rows = expected.size + 1
        val cols = hypothesis.size + 1
        val table = Array(rows) { IntArray(cols) }
        for (i in 0 until rows) table[i][0] = i
        for (j in 0 until cols) table[0][j] = j
        for (i in 1 until rows) {
            for (j in 1 until cols) {
                table[i][j] = if (expected[i - 1] == hypothesis[j - 1]) table[i - 1][j - 1]
                else 1 + minOf(table[i - 1][j], table[i][j - 1], table[i - 1][j - 1])
            }
        }
        return table[rows - 1][cols - 1]
    }

    private companion object {
        const val QUIET_GAIN = 0.25f
        const val NUM_THREADS = 2 // LocalSpeech pins CPU provider, 2 threads
        const val EXPECTED_DECODE_CALLS = 12
    }
}

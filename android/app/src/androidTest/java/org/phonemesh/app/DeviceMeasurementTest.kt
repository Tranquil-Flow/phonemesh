package org.phonemesh.app

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
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

/**
 * One Piper synthesis plus Whisper decodes of that same clip, for device time and memory
 * measurements (memory is sampled from the host with `dumpsys meminfo` while this runs).
 *
 * Instrumentation argument `engine` names the engine ZIP in the app's external files dir
 * (default: the catalog Tiny engine), so a candidate engine can be measured on identical input.
 * Synthesis uses zero VITS noise, so every run decodes the same samples (hash recorded).
 * No microphone, no playback, no radio.
 */
@RunWith(AndroidJUnit4::class)
class DeviceMeasurementTest {
    private val text = "This is a radio test. Meet me at the library."

    @Test fun synthesizeOnceAndDecodeTheSameClip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val engineName = args.getString("engine") ?: "phonemesh-engine-whisper-tiny-1.zip"
        val decodes = args.getString("decodes")?.toInt() ?: 3
        val sourceDir = checkNotNull(ctx.getExternalFilesDir(null))
        val storeDir = File(ctx.filesDir, "measurement-packs")
        // Cleared before the run only: espeak-ng keeps reading the first voice's data directory for
        // the rest of the process, so deleting it afterwards would break later tests in the same run.
        storeDir.deleteRecursively()
        // Unknown packs are allowed so a candidate engine that is not in the catalog can be measured.
        val store = PackStore(storeDir, GeneratedPackCatalog.entries, allowUnknown = true)
        fun install(name: String): PackInfo =
            File(sourceDir, name).also { assertTrue("missing $name", it.isFile) }.inputStream().use { store.install(it) }
        val engine = install(engineName)
        val voice = install("phonemesh-voice-en-US-ljspeech-1.zip")
        val pack = SpeechPack(engine.root, voice.root, "en-US", "en", noiseScale = 0f, noiseScaleW = 0f)
        val loadStart = System.nanoTime()
        LocalSpeech(pack).use { speech ->
            val loadSeconds = (System.nanoTime() - loadStart) / 1e9
            val synthStart = System.nanoTime()
            val audio = speech.synthesize(text)
            val synthSeconds = (System.nanoTime() - synthStart) / 1e9
            val pcm = ByteBuffer.allocate(audio.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            audio.samples.forEach { pcm.putFloat(it) }
            val pcmSha = MessageDigest.getInstance("SHA-256").digest(pcm.array()).joinToString("") { "%02x".format(it) }
            val rows = JSONArray()
            repeat(decodes) {
                val start = System.nanoTime()
                val transcript = speech.transcribe(audio.samples, audio.sampleRate)
                rows.put(JSONObject().put("seconds", (System.nanoTime() - start) / 1e9).put("transcript", transcript))
                assertTrue("Unexpected transcript: $transcript", transcript.lowercase().contains("radio"))
            }
            File(sourceDir, "device-measurement-$engineName.json").writeText(
                JSONObject()
                    .put("engine", engineName)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("process64Bit", Process.is64Bit())
                    .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS))
                    .put("text", text)
                    .put("pcmSha256", pcmSha)
                    .put("samples", audio.samples.size)
                    .put("sampleRate", audio.sampleRate)
                    .put("loadSeconds", loadSeconds)
                    .put("synthesisSeconds", synthSeconds)
                    .put("decodes", rows)
                    .toString(2),
            )
        }
    }
}

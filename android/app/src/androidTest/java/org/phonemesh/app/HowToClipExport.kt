package org.phonemesh.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore
import org.phonemesh.core.Readiness
import org.phonemesh.core.readiness
import java.io.File

/**
 * Offline file export, never playback. With -e howto fr|en-US, writes the two step 1 WAVs using
 * the project's Piper voice. One language per process (espeak data path); skips in normal suites.
 */
@RunWith(AndroidJUnit4::class)
class HowToClipExport {
    @Test fun exportHowToClips() {
        val tag = InstrumentationRegistry.getArguments().getString("howto")
        assumeTrue("runs only with -e howto", tag != null)
        val voice = when (tag) {
            "fr" -> "phonemesh-voice-fr-siwis-1.zip"
            "en-US" -> "phonemesh-voice-en-US-ljspeech-1.zip"
            else -> error("Unknown language $tag")
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val words = Locales.wrap(context, tag)
        val dir = File(context.cacheDir, "howto-export-${System.nanoTime()}")
        check(dir.mkdirs())
        val speech = DeviceSpeech(context)
        try {
            val engine = Fixtures.real(context, "phonemesh-engine-whisper-tiny-1.zip", dir)
            Fixtures.real(context, voice, dir)
            val pack = PackStore(dir, GeneratedPackCatalog.entries).readiness(engine.id, checkNotNull(tag)) as Readiness.Ready
            listOf("download", "friend").forEach { scene ->
                val sentence = words.getString(HowTo.sentence(scene))
                val audio = speech.synthesizeForTest(pack, sentence)
                assertTrue("silent how-to $tag/$scene", audio.samples.any { it > .0001f || it < -.0001f })
                val out = File(checkNotNull(context.getExternalFilesDir(null)), "howto_${tag}_$scene.wav")
                WavOutput.write(out, audio.samples, audio.sampleRate)
                assertTrue("empty how-to $tag/$scene", out.length() > 1_000)
                inst.sendStatus(0, Bundle().apply {
                    putString("stream", "HOWTO_EXPORTED $tag $scene $sentence ${audio.samples.size} ${audio.sampleRate}\n")
                })
            }
        } finally {
            inst.runOnMainSync { speech.close() }
            dir.deleteRecursively()
        }
    }
}

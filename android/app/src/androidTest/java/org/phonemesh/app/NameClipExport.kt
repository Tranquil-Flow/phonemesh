package org.phonemesh.app

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
 * A tool, not a check: with `-e clip fr|en-US` it writes name_<tag>.wav (the language's own name in its
 * Piper voice) to the app's external files dir. One language per process: espeak-ng keeps the first
 * data path it loads. Skips in the normal suite.
 */
@RunWith(AndroidJUnit4::class)
class NameClipExport {
    @Test fun exportNameClip() {
        val tag = InstrumentationRegistry.getArguments().getString("clip")
        assumeTrue("runs only with -e clip", tag != null)
        val (text, voice) = when (tag) {
            "fr" -> "Français" to "phonemesh-voice-fr-siwis-1.zip"
            "en-US" -> "English" to "phonemesh-voice-en-US-ljspeech-1.zip"
            else -> error("Unknown clip $tag")
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val dir = File(context.filesDir, "clip-packs").apply { deleteRecursively() }
        val speech = DeviceSpeech(context)
        try {
            val engine = Fixtures.real(context, "phonemesh-engine-whisper-tiny-1.zip", dir)
            Fixtures.real(context, voice, dir)
            val pack = PackStore(dir, GeneratedPackCatalog.entries).readiness(engine.id, checkNotNull(tag)) as Readiness.Ready
            val audio = speech.synthesizeForTest(pack, text)
            val out = File(checkNotNull(context.getExternalFilesDir(null)), "name_$tag.wav")
            WavOutput.write(out, audio.samples, audio.sampleRate)
            assertTrue(out.length() > 1_000)
        } finally {
            inst.runOnMainSync { speech.close() }
            dir.deleteRecursively()
        }
    }
}

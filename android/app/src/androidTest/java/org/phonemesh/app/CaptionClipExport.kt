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
 * Offline file export, never playback. With -e captions fr|en-US, writes six caption WAVs using
 * the project's Piper voice. One language per process (espeak data path); skips in normal suites.
 */
@RunWith(AndroidJUnit4::class)
class CaptionClipExport {
    @Test fun exportCaptionClips() {
        val tag = InstrumentationRegistry.getArguments().getString("captions")
        assumeTrue("runs only with -e captions", tag != null)
        val voice = when (tag) {
            "fr" -> "phonemesh-voice-fr-siwis-1.zip"
            "en-US" -> "phonemesh-voice-en-US-ljspeech-1.zip"
            else -> error("Unknown language $tag")
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val words = Locales.wrap(context, tag)
        val dir = File(context.cacheDir, "caption-export-${System.nanoTime()}")
        check(dir.mkdirs())
        val speech = DeviceSpeech(context)
        try {
            val engine = Fixtures.real(context, "phonemesh-engine-whisper-tiny-1.zip", dir)
            Fixtures.real(context, voice, dir)
            val pack = PackStore(dir, GeneratedPackCatalog.entries).readiness(engine.id, checkNotNull(tag)) as Readiness.Ready
            StoryCaptions.TEXT.forEachIndexed { i, text ->
                val sentence = words.getString(text)
                val audio = speech.synthesizeForTest(pack, sentence)
                assertTrue("silent caption $tag/$i", audio.samples.any { it > .0001f || it < -.0001f })
                val out = File(checkNotNull(context.getExternalFilesDir(null)), "caption_${tag}_$i.wav")
                WavOutput.write(out, audio.samples, audio.sampleRate)
                assertTrue("empty caption $tag/$i", out.length() > 1_000)
                inst.sendStatus(0, Bundle().apply {
                    putString("stream", "CAPTION_EXPORTED $tag $i $sentence ${audio.samples.size} ${audio.sampleRate}\n")
                })
            }
        } finally {
            inst.runOnMainSync { speech.close() }
            dir.deleteRecursively()
        }
    }
}

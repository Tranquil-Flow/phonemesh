package org.phonemesh.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.PackStore
import org.phonemesh.speech.LocalSpeech
import org.phonemesh.speech.SpeechPack
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeSpeechTest {
    @Test fun realWhisperPiperRoundTripWithoutNetworkOrPlayback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.INTERNET,context.packageName))
        val sourceDir=checkNotNull(context.getExternalFilesDir(null))
        val store=PackStore(File(context.filesDir,"native-test-packs"))
        val receipt=File(sourceDir,"native-speech-results.txt")
        receipt.writeText("Real Android JNI speech test; synthetic input; no microphone/playback/radio.\n")
        for(tag in listOf("en-US","fr")) {
            val voice=if(tag=="en-US") "ljspeech" else "siwis"
            val archive=File(sourceDir,"phonemesh-$tag-whisper-tiny-piper-$voice-int8.zip")
            assertTrue("Missing model pack $archive",archive.isFile)
            val pack=archive.inputStream().use { store.install(it) }
            val text=if(tag=="fr") "Bonjour, ceci est un message radio." else "This is a radio test. Meet me at the library."
            LocalSpeech(SpeechPack(pack.root,pack.languageTag,pack.whisperLanguage)).use { speech ->
                val start=System.nanoTime()
                val audio=speech.synthesize(text)
                assertTrue(audio.samples.isNotEmpty())
                assertTrue(audio.samples.any { kotlin.math.abs(it)>0.001f })
                val transcript=speech.transcribe(audio.samples,audio.sampleRate)
                assertTrue("Unexpected transcript: $transcript",transcript.lowercase().contains(if(tag=="fr") "bonjour" else "radio"))
                WavOutput.write(File(sourceDir,"android-$tag-preview.wav"),audio.samples,audio.sampleRate)
                receipt.appendText("$tag\t$text\t$transcript\t${audio.samples.size}\t${audio.sampleRate}\t${(System.nanoTime()-start)/1e9}\n")
            }
        }
    }
}

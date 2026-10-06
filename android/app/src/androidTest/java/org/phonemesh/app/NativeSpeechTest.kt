package org.phonemesh.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackStore
import org.phonemesh.core.Readiness
import org.phonemesh.core.readiness
import org.phonemesh.speech.LocalSpeech
import org.phonemesh.speech.SpeechPack
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeSpeechTest {
    @Test fun realWhisperPiperRoundTripWithoutNetworkOrPlayback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // Network is used only for setup-time pack downloads (PackDownloads).
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(Manifest.permission.INTERNET,context.packageName))
        val sourceDir=checkNotNull(context.getExternalFilesDir(null))
        val store=PackStore(File(context.filesDir,"native-test-packs"),GeneratedPackCatalog.entries)
        val receipt=File(sourceDir,"native-speech-results.txt")
        receipt.writeText("Real Android JNI speech test; synthetic input; no microphone/playback/radio.\n")
        fun install(name:String):PackInfo {
            val archive=File(sourceDir,name)
            assertTrue("Missing model pack $archive",archive.isFile)
            return archive.inputStream().use { store.install(it) }
        }
        val engine=install("phonemesh-engine-whisper-tiny-1.zip")
        for(tag in listOf("en-US","fr")) {
            val voiceName=if(tag=="en-US") "ljspeech" else "siwis"
            val voice=install("phonemesh-voice-$tag-$voiceName-1.zip")
            assertEquals(Readiness.Ready(engine,voice),store.readiness(engine.id,tag))
            val text=if(tag=="fr") "Bonjour, ceci est un message radio." else "This is a radio test. Meet me at the library."
            LocalSpeech(SpeechPack(engine.root,voice.root,checkNotNull(voice.languageTag),
                checkNotNull(voice.whisperLanguage))).use { speech ->
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

package org.phonemesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore
import org.phonemesh.core.Readiness
import org.phonemesh.core.readiness
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real recognizer and voice on samples: no microphone (permission stays denied), no playback. */
@RunWith(AndroidJUnit4::class)
class DeviceSpeechTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val dir = File(context.filesDir, "device-speech-test-packs")
    private var speech: DeviceSpeech? = null

    @Before fun setUp() { dir.deleteRecursively() }
    @After fun tearDown() {
        inst.runOnMainSync { speech?.close() }
        dir.deleteRecursively()
    }

    private fun ready(): Readiness.Ready {
        val engine = Fixtures.real(context, "phonemesh-engine-whisper-tiny-1.zip", dir)
        Fixtures.real(context, "phonemesh-voice-fr-siwis-1.zip", dir)
        return PackStore(dir, GeneratedPackCatalog.entries).readiness(engine.id, "fr") as Readiness.Ready
    }

    @Test fun transcribesWhatItSynthesizedWithoutPlaying() {
        val pack = ready()
        val device = DeviceSpeech(context).also { speech = it }
        val audio = device.synthesizeForTest(pack, "Bonjour, ceci est un message radio.")
        assertTrue(audio.samples.isNotEmpty())
        val heard = device.transcribeForTest(pack, audio)
        assertTrue("Unexpected transcript: $heard", heard.lowercase().contains("radio"))
    }

    @Test fun releaseEngineUnloadsTheModels() {
        val pack = ready()
        val device = DeviceSpeech(context).also { speech = it }
        device.synthesizeForTest(pack, "Bonjour.")
        assertTrue(device.loadedForTest())
        device.releaseEngine()
        assertFalse(device.loadedForTest())
    }

    @Test fun recordingWithoutPermissionSaysSoOnTheMainThread() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val latch = CountDownLatch(1)
        var samples: FloatArray? = floatArrayOf(1f)
        var error: RecordError? = null
        var onMain = false
        val device = DeviceSpeech(context).also { speech = it }
        inst.runOnMainSync {
            device.startRecording({}) { got, why ->
                samples = got; error = why; onMain = Looper.myLooper() == Looper.getMainLooper(); latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNull(samples)
        assertEquals(RecordError.NO_PERMISSION, error)
        assertTrue(onMain)
    }
}

package org.phonemesh.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.phonemesh.core.Readiness
import org.phonemesh.speech.AudioPcm
import org.phonemesh.speech.LocalSpeech
import org.phonemesh.speech.SharedEspeakData
import org.phonemesh.speech.SpeechPack
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [Speech] on the phone: one worker thread for the models, MediaPlayer on the main thread. */
class DeviceSpeech(private val context: Context) : Speech {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val microphone = MicrophoneCapture(context)
    private var engine: LocalSpeech? = null // work thread only
    private var engineKey: String? = null // work thread only
    private var player: MediaPlayer? = null // main thread only
    private var playingFile: File? = null
    private var playEnd: ((Boolean) -> Unit)? = null
    private var playEpoch = 0

    override fun startRecording(onStarted: () -> Unit, onFinished: (FloatArray?, RecordError?) -> Unit) {
        try {
            microphone.start(onStarted = { main.post(onStarted) }) { samples, error ->
                main.post { onFinished(samples, if (samples != null) null else errorOf(error)) }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "microphone busy", e)
            main.post { onFinished(null, RecordError.FAILED) }
        }
    }

    override fun stopRecording() = microphone.stop()

    override fun transcribe(pack: Readiness.Ready, samples: FloatArray, onResult: (String?) -> Unit) {
        work.execute {
            val text = try {
                load(pack).transcribe(samples).trim().takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.w(TAG, "transcription failed", e); release(); null
            } catch (e: LinkageError) {
                Log.w(TAG, "native speech unavailable", e); release(); null
            }
            main.post { onResult(text) }
        }
    }

    override fun speak(pack: Readiness.Ready, text: String, onEnd: (Boolean) -> Unit) {
        stopPlayback()
        val epoch = ++playEpoch
        playEnd = onEnd
        work.execute {
            val file = try {
                val audio = load(pack).synthesize(text)
                File.createTempFile("speech-", ".wav", context.cacheDir).also { WavOutput.write(it, audio.samples, audio.sampleRate) }
            } catch (e: Exception) {
                Log.w(TAG, "synthesis failed", e); release(); null
            } catch (e: LinkageError) {
                Log.w(TAG, "native speech unavailable", e); release(); null
            }
            main.post {
                if (epoch != playEpoch) { file?.delete(); return@post }
                if (file == null) { end(false); return@post }
                start(MediaPlayer(), file)
            }
        }
    }

    override fun playClip(clip: Int, onEnd: () -> Unit) {
        stopPlayback()
        playEpoch++
        playEnd = { _ -> onEnd() }
        val clipPlayer = MediaPlayer.create(context, clip)
        if (clipPlayer == null) { end(false); return }
        start(clipPlayer, null)
    }

    /** [file] null: [next] is already prepared (MediaPlayer.create). */
    private fun start(next: MediaPlayer, file: File?) {
        try {
            player = next
            playingFile = file
            next.setOnCompletionListener { end(true) }
            next.setOnErrorListener { _, _, _ -> end(false); true }
            if (file != null) {
                next.setDataSource(file.absolutePath)
                next.prepare()
            }
            next.start()
        } catch (e: Exception) {
            Log.w(TAG, "playback failed", e)
            end(false)
        }
    }

    private fun end(ok: Boolean) {
        runCatching { player?.stop() }
        player?.release()
        player = null
        playingFile?.delete()
        playingFile = null
        val callback = playEnd
        playEnd = null
        callback?.invoke(ok)
    }

    override fun stopPlayback() {
        playEpoch++
        end(true)
    }

    override fun releaseEngine() {
        work.submit { release() }.get(120, TimeUnit.SECONDS)
    }

    override fun close() {
        microphone.close()
        stopPlayback()
        work.execute { release() }
        work.shutdown()
    }

    /** Work thread only. One recognizer and voice stay loaded until the packs change. */
    private fun load(pack: Readiness.Ready): LocalSpeech {
        val key = "${pack.engine.root.absolutePath}|${pack.voice.root.absolutePath}"
        if (engineKey != key) {
            release()
            // Every voice shares one espeak-ng copy that pack removal never deletes (see SharedEspeakData).
            val pronunciation = SharedEspeakData(File(context.filesDir, "espeak-ng-data"))
            engine = LocalSpeech(
                SpeechPack(
                    pack.engine.root, pack.voice.root,
                    checkNotNull(pack.voice.languageTag), checkNotNull(pack.voice.whisperLanguage),
                    espeakDataDir = pronunciation.prepare(pack.voice.root),
                ),
            )
            pronunciation.markLoaded()
            engineKey = key
        }
        return checkNotNull(engine)
    }

    private fun release() {
        runCatching { engine?.close() }
        engine = null
        engineKey = null
    }

    // Test hook
    internal fun loadedForTest(): Boolean = work.submit(Callable { engine != null }).get(120, TimeUnit.SECONDS)

    // Test hook: samples only, no playback.
    internal fun synthesizeForTest(pack: Readiness.Ready, text: String): AudioPcm =
        work.submit(Callable { load(pack).synthesize(text) }).get(120, TimeUnit.SECONDS)

    // Test hook: no microphone.
    internal fun transcribeForTest(pack: Readiness.Ready, audio: AudioPcm): String =
        work.submit(Callable { load(pack).transcribe(audio.samples, audio.sampleRate) }).get(120, TimeUnit.SECONDS)

    companion object {
        private const val TAG = "DeviceSpeech"

        /** MicrophoneCapture's fixed sentences, as kinds the UI words in the chosen language. */
        internal fun errorOf(message: String?): RecordError = when (message) {
            "Enregistrement trop court. Réessayez." -> RecordError.TOO_SHORT
            "Permission microphone refusée ou révoquée" -> RecordError.NO_PERMISSION
            else -> RecordError.FAILED
        }
    }
}

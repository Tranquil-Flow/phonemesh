package org.phonemesh.app

import org.phonemesh.core.Readiness

/** Why a recording produced nothing. */
enum class RecordError { TOO_SHORT, NO_PERMISSION, FAILED }

/**
 * Microphone, recognizer, voice and playback. [DeviceSpeech] is the real one; tests use a fake so
 * nothing records or plays. Every callback runs on the main thread, exactly once per call.
 */
interface Speech : AutoCloseable {
    /** Starts the microphone. [onFinished] delivers the samples, or null with the reason. */
    fun startRecording(onStarted: () -> Unit, onFinished: (samples: FloatArray?, error: RecordError?) -> Unit)

    /** Ends the recording; its [startRecording] callback then delivers what was captured. */
    fun stopRecording()

    /** Transcribes off the main thread; [onResult] gets the text, or null when nothing was understood. */
    fun transcribe(pack: Readiness.Ready, samples: FloatArray, onResult: (String?) -> Unit)

    /** Synthesizes and plays [text]; [onEnd] gets true when it ended or was stopped, false on failure. */
    fun speak(pack: Readiness.Ready, text: String, onEnd: (Boolean) -> Unit)

    /** Plays a raw resource clip (a language's own name). */
    fun playClip(clip: Int, onEnd: () -> Unit)

    /** Stops any playback; a pending [speak] then ends with true. */
    fun stopPlayback()

    /** Closes the loaded recognizer and voice before their files change. Blocks; never call on the main thread. */
    fun releaseEngine()
}

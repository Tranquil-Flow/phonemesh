package org.phonemesh.app

import org.phonemesh.core.Readiness

/** Test speech: no microphone, no models, no sound. Main thread only (releaseEngine may come from any thread). */
class FakeSpeech : Speech {
    var recording = false
        private set
    var recordings = 0
        private set
    var nextTranscript: String? = "Bonjour à tous"
    val spoken = mutableListOf<String>()
    val clips = mutableListOf<Int>()
    @Volatile var releases = 0
        private set
    val playing: Boolean get() = playEnd != null
    private var finished: ((FloatArray?, RecordError?) -> Unit)? = null
    /** The words stay "being written" until finishTranscript. */
    var holdTranscript = false
    private var heard: ((String?) -> Unit)? = null
    private var playEnd: ((Boolean) -> Unit)? = null

    override fun startRecording(onStarted: () -> Unit, onFinished: (FloatArray?, RecordError?) -> Unit) {
        recording = true
        recordings++
        finished = onFinished
        onStarted()
    }

    override fun stopRecording() {
        val done = finished ?: return
        finished = null
        recording = false
        done(FloatArray(16000), null)
    }

    fun failRecording(error: RecordError) {
        val done = finished ?: return
        finished = null
        recording = false
        done(null, error)
    }

    override fun transcribe(pack: Readiness.Ready, samples: FloatArray, onResult: (String?) -> Unit) {
        if (holdTranscript) heard = onResult else onResult(nextTranscript)
    }

    fun finishTranscript() {
        val done = heard ?: return
        heard = null
        done(nextTranscript)
    }

    override fun speak(pack: Readiness.Ready, text: String, onEnd: (Boolean) -> Unit) {
        stopPlayback()
        spoken += text
        playEnd = onEnd
    }

    fun finishPlayback() {
        val end = playEnd ?: return
        playEnd = null
        end(true)
    }

    override fun playClip(clip: Int, onEnd: () -> Unit) {
        clips += clip
        onEnd()
    }

    override fun stopPlayback() {
        val end = playEnd ?: return
        playEnd = null
        end(true)
    }

    override fun releaseEngine() { releases++ }

    override fun close() = stopPlayback()
}

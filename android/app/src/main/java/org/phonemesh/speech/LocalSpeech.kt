package org.phonemesh.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Local, offline speech model pack.
 *
 * Required fixed layout:
 *   root/whisper/encoder.int8.onnx
 *   root/whisper/decoder.int8.onnx
 *   root/whisper/tokens.txt
 *   root/tts/model.onnx
 *   root/tts/tokens.txt
 *   root/tts/espeak-ng-data/
 */
data class SpeechPack(
    val root: File,
    val languageTag: String,
    val whisperLanguage: String,
)

/** Mono floating-point PCM samples normalized to [-1, 1]. */
data class AudioPcm(
    val samples: FloatArray,
    val sampleRate: Int,
)

/**
 * CPU-only, file-backed offline Whisper ASR + Piper/VITS TTS via sherpa-onnx.
 *
 * This class intentionally has no network/download/cloud fallback and uses sherpa-onnx's
 * file constructors (assetManager = null). The caller owns serialization; no concurrent close.
 */
class LocalSpeech(pack: SpeechPack) : Closeable {
    private val packPaths: PackPaths = PackPaths.from(pack)
    private var recognizer: OfflineRecognizer? = null
    private var tts: OfflineTts? = null
    private var closed: Boolean = false

    init {
        try {
            recognizer = OfflineRecognizer(
                assetManager = null,
                config = whisperRecognizerConfig(packPaths, pack.whisperLanguage),
            )
            tts = OfflineTts(
                assetManager = null,
                config = piperTtsConfig(packPaths),
            )
        } catch (t: Throwable) {
            val closeFailure = closeInternal(markClosed = true)
            if (closeFailure != null) {
                t.addSuppressed(closeFailure)
            }
            throw t
        }
    }

    /** Transcribe up to 30 seconds of finite, non-silent mono PCM. */
    fun transcribe(samples: FloatArray, sampleRate: Int = SHERPA_SAMPLE_RATE): String {
        ensureOpen()
        validateInputAudio(samples, sampleRate)

        return withNativeFailureRelease {
            val recognizer = requireNotNull(recognizer) { "LocalSpeech recognizer is not allocated" }
            var stream: OfflineStream? = null
            try {
                stream = recognizer.createStream()
                stream.acceptWaveform(samples = samples, sampleRate = sampleRate)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                require(text.isNotEmpty()) { "Offline recognizer returned an empty transcript" }
                text
            } finally {
                stream?.release()
            }
        }
    }

    /** Synthesize non-empty text up to 200 UTF-8 bytes to finite, non-silent mono PCM. */
    fun synthesize(text: String): AudioPcm {
        ensureOpen()
        validateInputText(text)

        return withNativeFailureRelease {
            val tts = requireNotNull(tts) { "LocalSpeech TTS is not allocated" }
            val audio = tts.generate(text = text, sid = 0, speed = 1.0f)
            validateOutputAudio(audio.samples, audio.sampleRate)
            AudioPcm(samples = audio.samples, sampleRate = audio.sampleRate)
        }
    }

    override fun close() {
        val failure = closeInternal(markClosed = true)
        if (failure != null) {
            throw failure
        }
    }

    private inline fun <T> withNativeFailureRelease(block: () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            val closeFailure = closeInternal(markClosed = true)
            if (closeFailure != null) {
                t.addSuppressed(closeFailure)
            }
            throw t
        }
    }

    private fun closeInternal(markClosed: Boolean): Throwable? {
        if (closed && markClosed) {
            return null
        }
        if (markClosed) {
            closed = true
        }

        var failure: Throwable? = null

        fun captureRelease(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                if (failure == null) {
                    failure = t
                } else {
                    failure?.addSuppressed(t)
                }
            }
        }

        val ttsToRelease = tts
        tts = null
        if (ttsToRelease != null) {
            captureRelease { ttsToRelease.release() }
        }

        val recognizerToRelease = recognizer
        recognizer = null
        if (recognizerToRelease != null) {
            captureRelease { recognizerToRelease.release() }
        }

        return failure
    }

    private fun ensureOpen() {
        check(!closed) { "LocalSpeech is closed" }
    }

    private fun validateInputAudio(samples: FloatArray, sampleRate: Int) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(samples.isNotEmpty()) { "Audio samples must not be empty" }

        val maxSamples = sampleRate.toLong() * MAX_RECORD_SECONDS.toLong()
        require(samples.size.toLong() <= maxSamples) {
            "Audio is longer than ${MAX_RECORD_SECONDS}s: ${samples.size} samples at $sampleRate Hz"
        }

        var peak = 0.0f
        var sumSquares = 0.0
        for (sample in samples) {
            require(sample.isFinite()) { "Audio samples must be finite" }
            require(abs(sample) <= MAX_ABS_PCM_SAMPLE) {
                "Audio samples must be normalized to [-1, 1]"
            }
            val magnitude = abs(sample)
            if (magnitude > peak) {
                peak = magnitude
            }
            sumSquares += (sample.toDouble() * sample.toDouble())
        }

        val rms = sqrt(sumSquares / samples.size.toDouble())
        require(peak >= SILENCE_PEAK_FLOOR && rms >= SILENCE_RMS_FLOOR) {
            "Audio samples are silent or too quiet"
        }
    }

    private fun validateInputText(text: String) {
        require(text.isNotBlank()) { "Text must not be empty" }
        val utf8Bytes = text.toByteArray(Charsets.UTF_8).size
        require(utf8Bytes <= MAX_TTS_TEXT_UTF8_BYTES) {
            "Text is $utf8Bytes UTF-8 bytes; max is $MAX_TTS_TEXT_UTF8_BYTES"
        }
    }

    private fun validateOutputAudio(samples: FloatArray, sampleRate: Int) {
        check(sampleRate > 0) { "Offline TTS returned an invalid sample rate: $sampleRate" }
        check(samples.isNotEmpty()) { "Offline TTS returned empty audio" }

        var peak = 0.0f
        var sumSquares = 0.0
        for (sample in samples) {
            check(sample.isFinite()) { "Offline TTS returned non-finite audio" }
            val magnitude = abs(sample)
            if (magnitude > peak) {
                peak = magnitude
            }
            sumSquares += (sample.toDouble() * sample.toDouble())
        }

        val rms = sqrt(sumSquares / samples.size.toDouble())
        check(peak >= SILENCE_PEAK_FLOOR && rms >= SILENCE_RMS_FLOOR) {
            "Offline TTS returned silent audio"
        }
    }

    private data class PackPaths(
        val root: File,
        val languageTag: String,
        val whisperEncoder: File,
        val whisperDecoder: File,
        val whisperTokens: File,
        val ttsModel: File,
        val ttsTokens: File,
        val espeakData: File,
    ) {
        companion object {
            fun from(pack: SpeechPack): PackPaths {
                require(pack.languageTag.isNotBlank()) { "languageTag must not be blank" }
                require(pack.whisperLanguage.isNotBlank()) { "whisperLanguage must not be blank" }
                require(pack.whisperLanguage.length <= MAX_LANGUAGE_CODE_CHARS) {
                    "whisperLanguage is too long"
                }
                require(pack.whisperLanguage.none { it == '/' || it == '\\' }) {
                    "whisperLanguage must be a language code, not a path"
                }

                val root = pack.root.canonicalFile
                require(root.isDirectory) { "Speech pack root is not a directory: ${root.path}" }
                require(root.canRead()) { "Speech pack root is not readable: ${root.path}" }

                val whisperEncoder = root.requiredFile("whisper encoder", "whisper", "encoder.int8.onnx")
                val whisperDecoder = root.requiredFile("whisper decoder", "whisper", "decoder.int8.onnx")
                val whisperTokens = root.requiredFile("whisper tokens", "whisper", "tokens.txt")
                val ttsModel = root.requiredFile("Piper/VITS model", "tts", "model.onnx")
                val ttsTokens = root.requiredFile("Piper/VITS tokens", "tts", "tokens.txt")
                val espeakData = root.requiredDirectory("espeak-ng-data", "tts", "espeak-ng-data")

                return PackPaths(
                    root = root,
                    languageTag = pack.languageTag,
                    whisperEncoder = whisperEncoder,
                    whisperDecoder = whisperDecoder,
                    whisperTokens = whisperTokens,
                    ttsModel = ttsModel,
                    ttsTokens = ttsTokens,
                    espeakData = espeakData,
                )
            }

            private fun File.requiredFile(label: String, vararg segments: String): File {
                val file = canonicalChild(*segments)
                require(file.isFile) { "Missing $label at ${segments.joinToString("/")}" }
                require(file.canRead()) { "$label is not readable: ${segments.joinToString("/")}" }
                require(file.length() > 0L) { "$label is empty: ${segments.joinToString("/")}" }
                return file
            }

            private fun File.requiredDirectory(label: String, vararg segments: String): File {
                val file = canonicalChild(*segments)
                require(file.isDirectory) { "Missing $label directory at ${segments.joinToString("/")}" }
                require(file.canRead()) { "$label directory is not readable: ${segments.joinToString("/")}" }
                return file
            }

            private fun File.canonicalChild(vararg segments: String): File {
                val child = segments.fold(this) { current, segment -> File(current, segment) }.canonicalFile
                val rootPath = path
                val childPath = child.path
                require(childPath == rootPath || childPath.startsWith(rootPath + File.separatorChar)) {
                    "Speech pack path escapes root: ${segments.joinToString("/")}"
                }
                return child
            }
        }
    }

    companion object {
        const val MAX_RECORD_SECONDS: Int = 30
        const val MAX_TTS_TEXT_UTF8_BYTES: Int = 200
        const val SHERPA_SAMPLE_RATE: Int = 16000
        const val SHERPA_FEATURE_DIM: Int = 80
        private const val SHERPA_NUM_THREADS: Int = 2
        private const val CPU_PROVIDER: String = "cpu"
        private const val WHISPER_TASK: String = "transcribe"
        private const val WHISPER_TAIL_PADDINGS: Int = 1000
        private const val MAX_LANGUAGE_CODE_CHARS: Int = 32
        private const val MAX_ABS_PCM_SAMPLE: Float = 1.0001f
        private const val SILENCE_PEAK_FLOOR: Float = 1.0e-5f
        private const val SILENCE_RMS_FLOOR: Double = 1.0e-6

        private fun whisperRecognizerConfig(paths: PackPaths, whisperLanguage: String): OfflineRecognizerConfig {
            return OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SHERPA_SAMPLE_RATE,
                    featureDim = SHERPA_FEATURE_DIM,
                    dither = 0.0f,
                ),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = paths.whisperEncoder.path,
                        decoder = paths.whisperDecoder.path,
                        language = whisperLanguage,
                        task = WHISPER_TASK,
                        tailPaddings = WHISPER_TAIL_PADDINGS,
                        enableTokenTimestamps = false,
                        enableSegmentTimestamps = false,
                    ),
                    numThreads = SHERPA_NUM_THREADS,
                    debug = false,
                    provider = CPU_PROVIDER,
                    modelType = "whisper",
                    tokens = paths.whisperTokens.path,
                ),
                decodingMethod = "greedy_search",
            )
        }

        private fun piperTtsConfig(paths: PackPaths): OfflineTtsConfig {
            return OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = paths.ttsModel.path,
                        lexicon = "",
                        tokens = paths.ttsTokens.path,
                        dataDir = paths.espeakData.path,
                        dictDir = "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f,
                    ),
                    numThreads = SHERPA_NUM_THREADS,
                    debug = false,
                    provider = CPU_PROVIDER,
                ),
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )
        }
    }
}

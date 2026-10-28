package org.phonemesh.app

import android.content.Context
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackStore
import org.phonemesh.speech.LocalSpeech
import org.phonemesh.speech.SharedEspeakData
import org.phonemesh.speech.SpeechPack
import java.io.File

/** Whether a not-yet-accepted pack really starts (U7-5): the update is put into use only if this says yes. */
fun interface PackOpener {
    fun opens(candidate: PackInfo): Boolean
}

/** Which installed pack a candidate is tried with: the speech loop needs one engine and one voice together. */
object PackPairing {
    /** (engine, voice) to load for [candidate], or null when the other half is not installed (then nothing can be tried). */
    fun pair(candidate: PackInfo, installed: List<PackInfo>, preferredEngine: String?): Pair<PackInfo, PackInfo>? {
        val others = installed.filter { it.id != candidate.id }
        return if (candidate.kind == PackKind.ENGINE) {
            val voice = others.firstOrNull { it.kind == PackKind.VOICE && it.languageTag != null && it.whisperLanguage != null } ?: return null
            candidate to voice
        } else {
            val engines = others.filter { it.kind == PackKind.ENGINE }
            val engine = engines.firstOrNull { it.id == preferredEngine } ?: engines.firstOrNull { it.id == "engine-whisper-tiny" } ?: engines.firstOrNull() ?: return null
            engine to candidate
        }
    }
}

/**
 * The real check: loads the candidate with a partner pack through the same code the speech loop uses ([LocalSpeech], sherpa-onnx),
 * then lets go of it. Any failure to load, native or not, is "does not open". [release] first frees the engine the app holds, so two
 * copies of the models are never in memory together. With no partner installed nothing can be tried, and the structural checks stand.
 */
class LocalPackOpener(
    private val context: Context, private val store: PackStore, private val preferredEngine: () -> String?, private val release: () -> Unit,
) : PackOpener {
    override fun opens(candidate: PackInfo): Boolean {
        val (engine, voice) = PackPairing.pair(candidate, store.usable(), preferredEngine()) ?: return true
        val tag = voice.languageTag ?: return true
        val whisper = voice.whisperLanguage ?: return true
        release()
        var speech: LocalSpeech? = null
        return try {
            val espeak = SharedEspeakData(File(context.filesDir, "espeak-ng-data")).prepare(voice.root)
            speech = LocalSpeech(SpeechPack(engine.root, voice.root, tag, whisper, espeakDataDir = espeak))
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { speech?.close() }
        }
    }
}

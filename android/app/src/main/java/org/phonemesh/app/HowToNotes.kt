package org.phonemesh.app

/** The matching how-to picture under a note, keyed by the note's own sentence. */
object HowToNotes {
    private val byNote = mapOf(
        R.string.record_too_short to "short",
        R.string.transcribe_failed to "notheard",
        R.string.mic_denied to "mic",
        R.string.bluetooth_off to "bt",
        R.string.bluetooth_refused to "allow",
        R.string.pack_damaged to "damaged",
        R.string.pack_wrong_file to "refused",
        R.string.pack_unknown to "refused",
        R.string.pack_unsafe to "refused",
        R.string.pack_older to "older",
        R.string.no_space to "space",
        R.string.wait_for_operation to "wait",
        R.string.download_failed_retry to "dlfail",
        R.string.connection_problem to "dlfail",
        R.string.play_failed to "playfail",
        R.string.voice_missing to "download",
    )

    val scenes: Set<String> get() = byNote.values.toSet()

    fun sceneFor(note: UiText): String? = byNote[note.id]
}

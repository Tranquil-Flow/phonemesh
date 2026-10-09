package org.phonemesh.app

import org.junit.Assert.*
import org.junit.Test
import org.phonemesh.core.HowToScenes

class HowToNotesTest {
    @Test fun notesPointAtTheirPicture() {
        val expected = mapOf(
            R.string.record_too_short to "short", R.string.transcribe_failed to "notheard",
            R.string.mic_denied to "mic", R.string.bluetooth_off to "bt",
            R.string.bluetooth_refused to "allow", R.string.pack_damaged to "damaged",
            R.string.pack_wrong_file to "refused", R.string.pack_unknown to "refused",
            R.string.pack_unsafe to "refused", R.string.pack_older to "older",
            R.string.no_space to "space", R.string.wait_for_operation to "wait",
            R.string.download_failed_retry to "dlfail", R.string.connection_problem to "dlfail",
            R.string.play_failed to "playfail", R.string.voice_missing to "download",
        )
        expected.forEach { (id, scene) ->
            assertEquals("note $id", scene, HowToNotes.sceneFor(UiText(id)))
            assertEquals("arguments do not change the picture", scene, HowToNotes.sceneFor(UiText(id, listOf("x"))))
        }
        assertEquals(expected.values.toSet(), HowToNotes.scenes)
        assertNull(HowToNotes.sceneFor(UiText(R.string.pack_installed, listOf("x"))))
        assertNull(HowToNotes.sceneFor(UiText(R.string.share_choose)))
    }

    @Test fun everyNotePictureExists() = HowToNotes.scenes.forEach { HowToScenes.get(it) }
}

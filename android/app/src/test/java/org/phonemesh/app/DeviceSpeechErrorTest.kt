package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** MicrophoneCapture reports fixed French sentences; the UI needs kinds it can word in any language. */
class DeviceSpeechErrorTest {
    @Test fun microphoneSentencesBecomeErrorKinds() {
        assertEquals(RecordError.TOO_SHORT, DeviceSpeech.errorOf("Enregistrement trop court. Réessayez."))
        assertEquals(RecordError.NO_PERMISSION, DeviceSpeech.errorOf("Permission microphone refusée ou révoquée"))
        assertEquals(RecordError.FAILED, DeviceSpeech.errorOf("Microphone indisponible."))
        assertEquals(RecordError.FAILED, DeviceSpeech.errorOf(null))
    }
}

package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.ble.MeshRadio
import org.phonemesh.core.PictureWire

/** The radio listens on the port the picture format sends on; a mismatch would drop every picture silently. */
class PicturePortTest {
    @Test fun theRadioAndThePictureFormatAgree() = assertEquals(PictureWire.PORT, MeshRadio.PICTURE_PORT)
}

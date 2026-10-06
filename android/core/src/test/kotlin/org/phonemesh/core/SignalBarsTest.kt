package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalBarsTest {
    @Test fun strengthBecomesOneToFourBars() {
        mapOf(-50 to 4, -60 to 4, -61 to 3, -70 to 3, -75 to 2, -80 to 2, -81 to 1, -127 to 1, 0 to 1, 127 to 1)
            .forEach { (rssi, bars) -> assertEquals("rssi $rssi", bars, SignalBars.of(rssi)) }
    }
}

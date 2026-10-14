package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class LoraAirtimeTest {
    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-6)

    /** Values from the Semtech AN1200.13 formula; the LoRaWAN airtime calculators give the same. */
    @Test fun matchesKnownFigures() {
        near(0.046336, LoraAirtime.seconds(14, LoraShape(7, 125.0, 5), preamble = 8)) // LoRaWAN 1-byte uplink at SF7
        near(2.793472, LoraAirtime.seconds(64, LoraShape(12, 125.0, 5), preamble = 8)) // LoRaWAN 51-byte uplink at SF12
        near(9.019392, LoraAirtime.seconds(255, LoraShape(12, 125.0, 5), preamble = 8))
        near(2.156544, LoraAirtime.seconds(255, LoraShape(11, 250.0, 5)))
        near(14.29504, LoraAirtime.seconds(255, LoraShape(12, 125.0, 8)))
        near(0.293376, LoraAirtime.seconds(100, LoraShape(9, 250.0, 5)))
    }

    @Test fun presetsMatchFirmware() {
        assertEquals(LoraShape(11, 250.0, 5), LoraAirtime.preset("LONG_FAST"))
        assertEquals(LoraShape(7, 250.0, 5), LoraAirtime.preset("SHORT_FAST"))
        assertEquals(LoraShape(11, 125.0, 8), LoraAirtime.preset("LONG_MODERATE"))
        assertEquals(LoraShape(12, 125.0, 8), LoraAirtime.preset("LONG_SLOW"))
        assertNull(LoraAirtime.preset("SOMETHING_NEW"))
    }

    @Test fun pictureEstimateCountsEveryPacketAndItsAcknowledgement() {
        val shape = LoraShape(11, 250.0, 5)
        val packets = listOf(37, 204, 204, 35)
        val expected = packets.sumOf { LoraAirtime.seconds(it + LoraAirtime.OVERHEAD_BYTES, shape) } +
            packets.size * LoraAirtime.seconds(LoraAirtime.ACK_BYTES, shape)
        near(expected, LoraAirtime.pictureSeconds(packets, shape))
        assertTrue(LoraAirtime.pictureSeconds(packets, shape) in 5.0..9.0)
    }
}

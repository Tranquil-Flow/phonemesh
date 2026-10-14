package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class T1ProbeTest {
    @Test fun aProbeFillsTheWholePrivatePortPayloadAndReadsBack() {
        val packet = T1Probe.packet(run = 7, seq = 12, sentAtMillis = 1_760_000_000_123L)
        assertEquals(233, packet.size)
        assertEquals(T1Probe.Got(7, 12, 1_760_000_000_123L), T1Probe.read(packet))
    }

    @Test fun anyChangedByteIsCaught() {
        val packet = T1Probe.packet(1, 2, 3L)
        for (i in packet.indices) {
            val bad = packet.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull("byte $i", T1Probe.read(bad))
        }
    }

    @Test fun anySizeTheRadioTakesReadsBack() {
        for (size in listOf(T1Probe.MIN_SIZE, 200, 228, 233)) {
            val packet = T1Probe.packet(4, 5, 6L, size)
            assertEquals(size, packet.size)
            assertEquals(T1Probe.Got(4, 5, 6L), T1Probe.read(packet))
        }
    }

    @Test fun aShortOrForeignPacketIsNotAProbe() {
        assertNull("cut short, its filler no longer checks", T1Probe.read(T1Probe.packet(1, 2, 3L).copyOf(232).also { it[231] = (it[231] + 1).toByte() }))
        assertNull(T1Probe.read(ByteArray(20)))
        assertNull(T1Probe.read(ByteArray(233)))
    }
}

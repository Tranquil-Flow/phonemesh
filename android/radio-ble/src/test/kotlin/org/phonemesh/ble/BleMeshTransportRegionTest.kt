package org.phonemesh.ble

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test

/**
 * The real transport's public entry, called directly (Android stubs, no Bluetooth): a region that is not
 * allowed is refused on the caller's thread, before anything is handed to the radio thread.
 */
class BleMeshTransportRegionTest {
    private val listener = object : MeshRadio.Listener {
        override fun onState(connected: Boolean, detail: String) = Unit
        override fun onText(sender: String, text: String, channel: Int, direct: Boolean) = Unit
        override fun onDevice(address: String, name: String, rssi: Int) = Unit
    }

    @Test fun setRegionRefusesWhatIsNotAllowed() {
        val transport: MeshRadio = BleMeshTransport(ContextWrapper(null), listener)
        val results = mutableListOf<RegionResult>()
        listOf(
            "ITU1_2M", "ITU2_2M", "ITU3_2M", "ITU1_70CM", "ITU2_70CM", "ITU3_70CM", "ITU2_125CM",
            "UNSET", "UNRECOGNIZED", "MARS", "",
        ).forEach { name ->
            try {
                transport.setRegion(name) { results += it }
                fail("accepted '$name'")
            } catch (_: IllegalArgumentException) {
            }
        }
        // Positive control: an allowed region passes the gate.
        transport.setRegion("EU_868") { results += it }
        assertTrue(results.isEmpty())
    }

    @Test fun regionIsUnknownBeforeAnyHandshake() {
        assertNull(BleMeshTransport(ContextWrapper(null), listener).region())
    }
}

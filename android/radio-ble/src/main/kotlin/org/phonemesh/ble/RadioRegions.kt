package org.phonemesh.ble

import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode

/** What a region write ended with. SENT means the radio took the three writes, not that it applied them. */
enum class RegionResult { SENT, DROPPED, NO_SETTINGS }

/**
 * Region names the app may offer (the For helpers list) and write, in the proto's order.
 * UNSET and the licensed amateur bands are left out; [MeshRadio.setRegion] refuses any other name.
 */
object RadioRegions {
    val all: List<String> = RegionCode.values().filter { RegionWriter.allowed(it) }.map { it.name }
}

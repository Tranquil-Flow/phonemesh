package org.phonemesh.ble

import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode

class RadioRegionsTest {
    /** The seven licensed amateur entries of the bundled config.proto (their comments say "Amateur Radio"). */
    private val amateur = listOf("ITU1_2M", "ITU2_2M", "ITU3_2M", "ITU1_70CM", "ITU2_70CM", "ITU3_70CM", "ITU2_125CM")

    @Test fun everyWritableRegionIsListedOnce() {
        assertFalse("UNSET" in RadioRegions.all)
        assertFalse("UNRECOGNIZED" in RadioRegions.all)
        assertEquals(listOf("US", "EU_433", "EU_868"), RadioRegions.all.take(3))
        assertEquals(RadioRegions.all.size, RadioRegions.all.toSet().size)
        RadioRegions.all.forEach { assertNotNull(it, RegionWriter.code(it)) }
    }

    /** Owner choice: no licensed amateur band is offered; everything else stays, in the proto's order. */
    @Test fun amateurBandsAreNotOfferedAndTheRestKeepProtoOrder() {
        amateur.forEach { name ->
            assertNotNull("$name is in the bundled proto", RegionCode.values().firstOrNull { it.name == name })
            assertFalse(name, name in RadioRegions.all)
        }
        assertEquals(
            listOf(
                "US", "EU_433", "EU_868", "CN", "JP", "ANZ", "KR", "TW", "RU", "IN", "NZ_865", "TH", "LORA_24",
                "UA_433", "UA_868", "MY_433", "MY_919", "SG_923", "PH_433", "PH_868", "PH_915", "ANZ_433",
                "KZ_433", "KZ_863", "NP_865", "BR_902", "EU_866", "EU_874", "EU_917", "EU_N_868",
            ),
            RadioRegions.all,
        )
        // Nothing in the proto is unaccounted for: offered + amateur + UNSET + UNRECOGNIZED is every value.
        assertEquals(
            RegionCode.values().map { it.name }.toSet(),
            (RadioRegions.all + amateur + "UNSET" + "UNRECOGNIZED").toSet(),
        )
        assertEquals(RegionCode.values().map { it.name }.filter { it in RadioRegions.all }, RadioRegions.all)
    }

    /** The offered list and the write gate are one list. */
    @Test fun theOfferedListIsExactlyWhatMayBeWritten() {
        RegionCode.values().forEach { code ->
            assertEquals(code.name, code.name in RadioRegions.all, RegionWriter.code(code.name) != null)
            assertEquals(code.name, code.name in RadioRegions.all, RegionWriter.allowed(code))
        }
    }
}

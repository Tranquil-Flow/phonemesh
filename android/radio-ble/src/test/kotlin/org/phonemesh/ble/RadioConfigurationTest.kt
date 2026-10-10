package org.phonemesh.ble

import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos

class RadioConfigurationTest {
    @Test fun loraConfigurationIsKeptWhole() {
        val lora = ConfigProtos.Config.LoRaConfig.newBuilder().setHopLimit(5)
            .setRegion(ConfigProtos.Config.LoRaConfig.RegionCode.EU_868).setTxPower(17).build()
        val event = MeshtasticProtocol.parseFromRadio(
            MeshProtos.FromRadio.newBuilder().setConfig(ConfigProtos.Config.newBuilder().setLora(lora)).build())
        assertEquals(RadioEvent.LoraSettings(lora), event)
        val packet = MeshProtos.ToRadio.parseFrom(
            MeshtasticProtocol.buildTextToRadio("hello", "!12345678", 0, hopLimit = (event as RadioEvent.LoraSettings).lora.hopLimit))
        assertEquals(5, packet.packet.hopLimit)
    }

    @Test fun configWithoutLoraIsNotLoraSettings() {
        val config = ConfigProtos.Config.newBuilder().setDevice(ConfigProtos.Config.DeviceConfig.getDefaultInstance())
        assertNull(MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setConfig(config).build()))
    }

    /** A region from newer firmware than the bundled proto: parsed from the wire, kept, never mistaken for UNSET. */
    @Test fun unknownFirmwareRegionSurvivesTheHandshake() {
        val lora = ConfigProtos.Config.LoRaConfig.newBuilder().setHopLimit(3).setRegionValue(99).build()
        val bytes = MeshProtos.FromRadio.newBuilder().setConfig(ConfigProtos.Config.newBuilder().setLora(lora)).build().toByteArray()
        val event = MeshtasticProtocol.parseFromRadio(bytes) as RadioEvent.LoraSettings
        assertEquals(ConfigProtos.Config.LoRaConfig.RegionCode.UNRECOGNIZED, event.lora.region)
        assertEquals("UNRECOGNIZED", event.lora.region.name)
        assertNotEquals(ConfigProtos.Config.LoRaConfig.RegionCode.UNSET, event.lora.region)
        assertEquals(99, event.lora.regionValue)
    }
}

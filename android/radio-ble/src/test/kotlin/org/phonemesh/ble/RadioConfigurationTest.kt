package org.phonemesh.ble

import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos

class RadioConfigurationTest {
    @Test fun loraConfigurationIsReadForTheOutgoingHopLimit() {
        val config=ConfigProtos.Config.newBuilder().setLora(
            ConfigProtos.Config.LoRaConfig.newBuilder().setHopLimit(5)).build()
        val event=MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setConfig(config).build())
        assertEquals(RadioEvent.HopLimit(5),event)
        val packet=MeshProtos.ToRadio.parseFrom(
            MeshtasticProtocol.buildTextToRadio("hello","!12345678",0,hopLimit=(event as RadioEvent.HopLimit).value))
        assertEquals(5,packet.packet.hopLimit)
    }
}

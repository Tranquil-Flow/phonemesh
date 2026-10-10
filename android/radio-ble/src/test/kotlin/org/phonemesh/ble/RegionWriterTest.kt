package org.phonemesh.ble

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.ConfigProtos.Config
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums

class RegionWriterTest {
    /** Every LoRaConfig field set to a non-default value, so a dropped field shows up as a difference. */
    private val full: LoRaConfig = LoRaConfig.newBuilder()
        .setUsePreset(true).setModemPreset(LoRaConfig.ModemPreset.MEDIUM_FAST)
        .setBandwidth(250).setSpreadFactor(9).setCodingRate(6).setFrequencyOffset(1.5f)
        .setRegion(RegionCode.US).setHopLimit(4).setTxEnabled(true).setTxPower(17)
        .setChannelNum(3).setOverrideDutyCycle(true).setSx126XRxBoostedGain(true).setOverrideFrequency(869.5f)
        .setPaFanDisabled(true).addIgnoreIncoming(0x1234).addIgnoreIncoming(0x5678).setIgnoreMqtt(true).setConfigOkToMqtt(true)
        .setFemLnaMode(LoRaConfig.FEM_LNA_Mode.ENABLED).setSerialHalOnly(true)
        .build()
    private val passkey = ByteString.copyFrom(byteArrayOf(1, 2, 3, 4))
    private val local = 0x0a0b0c0dL

    /** The seven licensed amateur entries of the bundled config.proto (their comments say "Amateur Radio"). */
    private val amateur = listOf(
        RegionCode.ITU1_2M, RegionCode.ITU2_2M, RegionCode.ITU3_2M,
        RegionCode.ITU1_70CM, RegionCode.ITU2_70CM, RegionCode.ITU3_70CM, RegionCode.ITU2_125CM,
    )

    /** Guards the fixture: a field added to the bundled proto but not to [full] fails here, not silently. */
    @Test fun theFixtureSetsEveryLoraFieldOfTheBundledProto() {
        val proto = File("src/main/proto/meshtastic/config.proto").readText()
        val block = proto.substringAfter("message LoRaConfig {").substringBefore("message BluetoothConfig {")
        val declared = Regex("""(?m)^    (?:repeated |optional )?[A-Za-z0-9_.]+ [a-z_0-9]+ = (\d+)""")
            .findAll(block).map { it.groupValues[1].toInt() }.toSortedSet()
        assertEquals(sortedSetOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 103, 104, 105, 106, 107), declared)
        assertEquals(declared, fieldNumbers(full))
    }

    @Test fun onlyTheRegionChanges() {
        val messages = RegionWriter.messages(full, RegionCode.EU_868, passkey)
        assertEquals(3, messages.size)
        assertEquals(AdminMessage.PayloadVariantCase.BEGIN_EDIT_SETTINGS, messages[0].payloadVariantCase)
        assertTrue(messages[0].beginEditSettings)
        assertEquals(AdminMessage.PayloadVariantCase.SET_CONFIG, messages[1].payloadVariantCase)
        assertEquals(Config.PayloadVariantCase.LORA, messages[1].setConfig.payloadVariantCase)
        assertFalse(messages[1].setConfig.hasDevice())
        assertEquals(AdminMessage.PayloadVariantCase.COMMIT_EDIT_SETTINGS, messages[2].payloadVariantCase)
        assertTrue(messages[2].commitEditSettings)
        messages.forEach { assertEquals(passkey, it.sessionPasskey) }

        val sent = messages[1].setConfig.lora
        assertEquals(RegionCode.EU_868, sent.region)
        assertEquals(full.toBuilder().setRegion(RegionCode.EU_868).build(), sent)
        // Field by field: everything but the region is what the radio reported.
        assertEquals(full.usePreset, sent.usePreset)
        assertEquals(full.modemPreset, sent.modemPreset)
        assertEquals(full.bandwidth, sent.bandwidth)
        assertEquals(full.spreadFactor, sent.spreadFactor)
        assertEquals(full.codingRate, sent.codingRate)
        assertEquals(full.frequencyOffset, sent.frequencyOffset, 0f)
        assertEquals(full.hopLimit, sent.hopLimit)
        assertEquals(full.txEnabled, sent.txEnabled)
        assertEquals(full.txPower, sent.txPower)
        assertEquals(full.channelNum, sent.channelNum)
        assertEquals(full.overrideDutyCycle, sent.overrideDutyCycle)
        assertEquals(full.sx126XRxBoostedGain, sent.sx126XRxBoostedGain)
        assertEquals(full.overrideFrequency, sent.overrideFrequency, 0f)
        assertEquals(full.paFanDisabled, sent.paFanDisabled)
        assertEquals(full.ignoreIncomingList, sent.ignoreIncomingList)
        assertEquals(full.ignoreMqtt, sent.ignoreMqtt)
        assertEquals(full.configOkToMqtt, sent.configOkToMqtt)
        assertEquals(full.femLnaMode, sent.femLnaMode)
        assertEquals(full.serialHalOnly, sent.serialHalOnly)
        // And on the wire: the same fields, and identical bytes once the region is put back.
        assertEquals(fieldNumbers(full), fieldNumbers(sent))
        assertEquals(full.toByteString(), sent.toBuilder().setRegion(full.region).build().toByteString())
    }

    /** A radio on defaults (nothing but a region): the write still carries only LoRa and adds no other value. */
    @Test fun aDefaultConfigGainsOnlyTheRegion() {
        val sent = RegionWriter.messages(LoRaConfig.getDefaultInstance(), RegionCode.EU_868, ByteString.EMPTY)[1].setConfig.lora
        assertEquals(LoRaConfig.newBuilder().setRegion(RegionCode.EU_868).build(), sent)
        assertEquals(sortedSetOf(7), fieldNumbers(sent))
    }

    @Test fun noPasskeyMeansNoneIsSent() =
        RegionWriter.messages(full, RegionCode.EU_868, ByteString.EMPTY).forEach { assertTrue(it.sessionPasskey.isEmpty) }

    @Test fun regionNamesAreChecked() {
        assertEquals(RegionCode.EU_868, RegionWriter.code("EU_868"))
        assertEquals(RegionCode.US, RegionWriter.code("US"))
        assertEquals(RegionCode.EU_433, RegionWriter.code("EU_433"))
        assertNull(RegionWriter.code("UNSET"))
        assertNull(RegionWriter.code("UNRECOGNIZED"))
        assertNull(RegionWriter.code("MARS"))
        assertNull(RegionWriter.code(""))
        assertNull(RegionWriter.code("eu_868"))
        assertNull(RegionWriter.code(" EU_868"))
    }

    /** Owner choice: licensed amateur bands are not writable, by name or by code. */
    @Test fun amateurBandsAreNotWritable() {
        assertEquals(7, amateur.size)
        amateur.forEach { code ->
            assertNull(code.name, RegionWriter.code(code.name))
            assertFalse(code.name, RegionWriter.allowed(code))
        }
        assertFalse(RegionWriter.allowed(RegionCode.UNSET))
        assertFalse(RegionWriter.allowed(RegionCode.UNRECOGNIZED))
        assertTrue(RegionWriter.allowed(RegionCode.EU_868))
        // Only those nine are refused: the other 433 MHz ISM regions stay writable.
        val refused = RegionCode.values().filterNot { RegionWriter.allowed(it) }
        assertEquals((amateur + RegionCode.UNSET + RegionCode.UNRECOGNIZED).toSet(), refused.toSet())
        listOf("EU_433", "UA_433", "MY_433", "PH_433", "ANZ_433", "KZ_433").forEach { assertNotNull(it, RegionWriter.code(it)) }
    }

    /** The message builder itself refuses, so no caller can build a write for a region that is not allowed. */
    @Test fun noMessagesAreBuiltForARegionThatIsNotAllowed() {
        (amateur + RegionCode.UNSET + RegionCode.UNRECOGNIZED).forEach { code ->
            try {
                RegionWriter.messages(full, code, passkey)
                fail("built messages for ${code.name}")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(3, RegionWriter.messages(full, RegionCode.EU_868, passkey).size)
    }

    /** A radio on a region this app does not know can still be given a known one; nothing else is lost. */
    @Test fun anUnknownFirmwareRegionCanBeReplaced() {
        val base = full.toBuilder().setRegionValue(99).build()
        assertEquals(RegionCode.UNRECOGNIZED, base.region)
        val sent = RegionWriter.messages(base, RegionCode.EU_868, passkey)[1].setConfig.lora
        assertEquals(full.toBuilder().setRegion(RegionCode.EU_868).build(), sent)
    }

    @Test fun getRequestAsksForLora() {
        assertEquals(AdminMessage.PayloadVariantCase.GET_CONFIG_REQUEST, RegionWriter.getLora().payloadVariantCase)
        assertEquals(AdminMessage.ConfigType.LORA_CONFIG, RegionWriter.getLora().getConfigRequest)
        assertTrue(RegionWriter.getLora().sessionPasskey.isEmpty)
    }

    @Test fun adminPacketGoesToTheLocalNodeOnAdminApp() {
        val bytes = MeshtasticProtocol.buildAdmin(RegionWriter.getLora(), localNode = 0x0a0b0c0d, packetId = 77, wantResponse = true)
        val packet = MeshProtos.ToRadio.parseFrom(bytes).packet
        assertEquals(0x0a0b0c0d, packet.to)
        assertEquals(77, packet.id)
        assertEquals(0, packet.channel)
        assertEquals(Portnums.PortNum.ADMIN_APP, packet.decoded.portnum)
        assertTrue(packet.decoded.wantResponse)
        assertEquals(RegionWriter.getLora(), AdminMessage.parseFrom(packet.decoded.payload))
        val quiet = MeshProtos.ToRadio.parseFrom(MeshtasticProtocol.buildAdmin(RegionWriter.getLora(), localNode = 5)).packet
        assertFalse(quiet.decoded.wantResponse)
        assertNotEquals(0, quiet.id)
    }

    @Test fun localAdminReplyIsParsed() {
        val reply = AdminMessage.newBuilder().setSessionPasskey(passkey)
            .setGetConfigResponse(Config.newBuilder().setLora(full)).build()
        val event = MeshtasticProtocol.parseFromRadio(fromRadioAdmin(reply, from = 0x0a0b0c0d))
        assertEquals(RadioEvent.AdminLora(local, full, passkey), event)
        // Node numbers are unsigned: a high number is not turned negative.
        val high = MeshtasticProtocol.parseFromRadio(fromRadioAdmin(reply, from = 0x89abcdef.toInt())) as RadioEvent.AdminLora
        assertEquals(0x89abcdefL, high.from)
    }

    @Test fun adminRepliesThatAreNotALoraAnswerCarryNoSettings() {
        val device = AdminMessage.newBuilder().setSessionPasskey(passkey)
            .setGetConfigResponse(Config.newBuilder().setDevice(Config.DeviceConfig.getDefaultInstance())).build()
        assertEquals(RadioEvent.AdminLora(local, null, passkey), MeshtasticProtocol.parseFromRadio(fromRadioAdmin(device, from = 0x0a0b0c0d)))
        // Not a get_config_response at all: nothing to act on.
        val other = AdminMessage.newBuilder().setSessionPasskey(passkey).setBeginEditSettings(true).build()
        assertNull(MeshtasticProtocol.parseFromRadio(fromRadioAdmin(other, from = 0x0a0b0c0d)))
        // A corrupt admin payload is dropped, not thrown into the radio loop.
        val corrupt = MeshProtos.FromRadio.newBuilder().setPacket(
            MeshProtos.MeshPacket.newBuilder().setFrom(0x0a0b0c0d).setDecoded(
                MeshProtos.Data.newBuilder().setPortnum(Portnums.PortNum.ADMIN_APP)
                    .setPayload(ByteString.copyFrom(byteArrayOf(0x32, 0x7f, 0x01)))),
        ).build()
        assertNull(MeshtasticProtocol.parseFromRadio(corrupt))
    }

    @Test fun adminRepliesFromOtherNodesAreIgnored() {
        val handshake = full.toBuilder().setTxPower(5).build()
        val fromOther = RadioEvent.AdminLora(0x99L, full, passkey)
        assertEquals(handshake, RegionWriter.base(fromOther, local = local, handshake = handshake))
        val noLora = RadioEvent.AdminLora(local, null, passkey)
        assertEquals(handshake, RegionWriter.base(noLora, local = local, handshake = handshake))
        assertEquals(full, RegionWriter.base(RadioEvent.AdminLora(local, full, passkey), local, handshake))
        assertNull(RegionWriter.base(null, local, null))
        // Another node's settings never become the base, even with nothing else to go on.
        assertNull(RegionWriter.base(fromOther, local, null))
        assertNull(RegionWriter.base(noLora, local, null))
        assertEquals(handshake, RegionWriter.base(null, local, handshake))
    }

    /** The session passkey comes only from the local radio's own get_config_response. */
    @Test fun onlyTheLocalRadiosPasskeyIsUsed() {
        assertEquals(passkey, RegionWriter.passkey(RadioEvent.AdminLora(local, full, passkey), local))
        // The local reply carried no LoRa (handshake settings are copied), but its passkey still counts.
        assertEquals(passkey, RegionWriter.passkey(RadioEvent.AdminLora(local, null, passkey), local))
        assertEquals(ByteString.EMPTY, RegionWriter.passkey(RadioEvent.AdminLora(0x99L, full, passkey), local))
        assertEquals(ByteString.EMPTY, RegionWriter.passkey(null, local))
    }

    private fun fieldNumbers(lora: LoRaConfig): java.util.SortedSet<Int> {
        val seen = sortedSetOf<Int>()
        val input = CodedInputStream.newInstance(lora.toByteArray())
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            seen += WireFormat.getTagFieldNumber(tag)
            input.skipField(tag)
        }
        return seen
    }

    private fun fromRadioAdmin(admin: AdminMessage, from: Int): MeshProtos.FromRadio =
        MeshProtos.FromRadio.newBuilder().setPacket(
            MeshProtos.MeshPacket.newBuilder().setFrom(from).setTo(from).setDecoded(
                MeshProtos.Data.newBuilder().setPortnum(Portnums.PortNum.ADMIN_APP).setPayload(admin.toByteString())),
        ).build()
}

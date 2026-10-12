package org.phonemesh.ble

import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums

class MeshtasticProtocolTest {
    @Test
    fun destinationIdsAcceptBroadcastAndCanonicalNodeHex() {
        assertEquals(0xffffffffL, MeshtasticProtocol.parseDestinationId("^all"))
        assertEquals(0xffffffffL, MeshtasticProtocol.parseDestinationId("broadcast"))
        assertEquals(0x1234abcdL, MeshtasticProtocol.parseDestinationId("!1234ABCD"))
        assertEquals(0x1234abcdL, MeshtasticProtocol.parseDestinationId("0x1234abcd"))
        assertEquals("!1234abcd", MeshtasticProtocol.nodeId(0x1234abcdL))
    }

    @Test(expected = IllegalArgumentException::class)
    fun destinationRejectsNonHexNodeId() {
        MeshtasticProtocol.parseDestinationId("node-7")
    }

    @Test(expected = IllegalArgumentException::class)
    fun sendRejectsChannelOutsideMeshtasticRange() {
        MeshtasticProtocol.buildTextToRadio("hello", "^all", 8, packetId = 7)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sendRejectsOversizedText() {
        MeshtasticProtocol.buildTextToRadio("x".repeat(MeshtasticProtocol.MAX_TEXT_BYTES + 1), "^all", 0, packetId = 7)
    }

    @Test
    fun sendBuildsOfficialToRadioMeshPacketForTextMessageApp() {
        val encoded = MeshtasticProtocol.buildTextToRadio("hello", "!1234abcd", 2, packetId = 0x10203040)
        val toRadio = MeshProtos.ToRadio.parseFrom(encoded)
        val packet = toRadio.packet
        val decoded = packet.decoded

        assertEquals(0x1234abcd.toInt(), packet.to)
        assertEquals(2, packet.channel)
        assertEquals(0x10203040, packet.id)
        assertEquals(MeshProtos.MeshPacket.Priority.RELIABLE, packet.priority)
        assertEquals(Portnums.PortNum.TEXT_MESSAGE_APP, decoded.portnum)
        assertEquals("hello", decoded.payload.toStringUtf8())
    }

    @Test
    fun aReplyAndAReactionUseMeshtasticsOwnFields() {
        val plain = MeshProtos.ToRadio.parseFrom(MeshtasticProtocol.buildTextToRadio("hello", "^all", 0, packetId = 5)).packet.decoded
        assertEquals("no reply: the field stays unset", 0, plain.replyId)
        assertEquals(0, plain.emoji)
        val reply = MeshProtos.ToRadio.parseFrom(
            MeshtasticProtocol.buildTextToRadio("[pm1:fr] Oui", "^all", 0, packetId = 6, replyTo = 0xfedcba98L),
        ).packet.decoded
        assertEquals(0xfedcba98L.toInt(), reply.replyId)
        assertEquals(0, reply.emoji)
        val reaction = MeshProtos.ToRadio.parseFrom(
            MeshtasticProtocol.buildTextToRadio("👍", "!1234abcd", 0, packetId = 7, replyTo = 42, reaction = true),
        ).packet.decoded
        assertEquals(42, reaction.replyId)
        assertEquals(1, reaction.emoji)
        assertEquals("👍", reaction.payload.toStringUtf8())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aReactionNeedsTheMessageItAnswers() {
        MeshtasticProtocol.buildTextToRadio("👍", "^all", 0, packetId = 8, reaction = true)
    }

    @Test
    fun fromRadioTextKeepsItsIdAndTheReplyAndReactionMarks() {
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8("❤️"))
            .setReplyId(0xfedcba98L.toInt())
            .setEmoji(1)
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setFrom(0x89abcdef.toInt()).setTo(0xffffffff.toInt()).setId(0x80000001L.toInt()).setDecoded(data).build()
        val event = MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setPacket(packet).build()) as RadioEvent.Text
        assertEquals("ids read as unsigned", 0x80000001L, event.packetId)
        assertEquals(0xfedcba98L, event.replyTo)
        assertTrue(event.reaction)
        val plain = MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setPacket(
            packet.toBuilder().setDecoded(data.toBuilder().clearReplyId().clearEmoji()),
        ).build()) as RadioEvent.Text
        assertEquals(0L, plain.replyTo)
        assertEquals(false, plain.reaction)
    }

    @Test
    fun fromRadioTextMessageMapsSenderPortPayloadAndChannel() {
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8("hello"))
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setFrom(0x89abcdef.toInt())
            .setTo(0xffffffff.toInt())
            .setChannel(3)
            .setDecoded(data)
            .build()
        val fromRadio = MeshProtos.FromRadio.newBuilder().setPacket(packet).build()

        val event = MeshtasticProtocol.parseFromRadio(fromRadio) as RadioEvent.Text
        assertEquals("!89abcdef", event.sender)
        assertEquals("hello", event.text)
        assertEquals(3, event.channel)
        assertEquals(0xffffffffL, event.to)
    }

    @Test
    fun fromRadioTextKeepsItsDestination() {
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8("salut"))
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setFrom(0x0a0b0c0d)
            .setTo(0x1234abcd)
            .setChannel(0)
            .setDecoded(data)
            .build()
        val event = MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setPacket(packet).build()) as RadioEvent.Text
        assertEquals(0x1234abcdL, event.to)
    }

    @Test
    fun textAudienceSeparatesDirectBroadcastAndOthers() {
        assertEquals(false, MeshtasticProtocol.textAudience(0xffffffffL, 0x1234abcdL))
        assertEquals(true, MeshtasticProtocol.textAudience(0x1234abcdL, 0x1234abcdL))
        assertNull(MeshtasticProtocol.textAudience(0x0badf00dL, 0x1234abcdL))
        assertNull(MeshtasticProtocol.textAudience(0x1234abcdL, null))
        assertEquals(false, MeshtasticProtocol.textAudience(0xffffffffL, null))
    }

    @Test
    fun ignoresWrongPortInvalidUtf8AndOversizePayloads() {
        val wrongPort = packetWithPayload(byteArrayOf(1, 2, 3), Portnums.PortNum.POSITION_APP)
        assertNull(MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setPacket(wrongPort).build()))

        val invalidUtf8 = packetWithPayload(byteArrayOf(0xc3.toByte(), 0x28), Portnums.PortNum.TEXT_MESSAGE_APP)
        assertNull(MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setPacket(invalidUtf8).build()))

        val oversize = packetWithPayload(ByteArray(MeshtasticProtocol.MAX_TEXT_BYTES + 1) { 'x'.code.toByte() }, Portnums.PortNum.TEXT_MESSAGE_APP)
        assertNull(MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setPacket(oversize).build()))
    }

    @Test
    fun nodeInfoBuildsPeerWithStableIdFallbacks() {
        val user = MeshProtos.User.newBuilder()
            .setId("!1234abcd")
            .setLongName("Long Name")
            .setShortName("LN")
            .build()
        val info = MeshProtos.NodeInfo.newBuilder().setNum(0x1234abcd.toInt()).setUser(user).build()
        val event = MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setNodeInfo(info).build()) as RadioEvent.NodeDiscovered
        assertEquals("!1234abcd", event.id)
        assertEquals("Long Name", event.name)
    }

    @Test
    fun ownNameIsTheNameTheConnectedRadioAnnounces() {
        fun record(num: Int, long: String, short: String = "") = MeshtasticProtocol.parseFromRadio(
            MeshProtos.FromRadio.newBuilder().setNodeInfo(
                MeshProtos.NodeInfo.newBuilder().setNum(num).setUser(MeshProtos.User.newBuilder().setLongName(long).setShortName(short)),
            ).build(),
        ) as RadioEvent.NodeDiscovered
        assertEquals("Meshtastic 8d88", MeshtasticProtocol.ownName(record(0x8d88, "Meshtastic 8d88"), 0x8d88L, null))
        assertEquals("another radio's record changes nothing", "Meshtastic 8d88", MeshtasticProtocol.ownName(record(0x1111, "Ama"), 0x8d88L, "Meshtastic 8d88"))
        assertNull("no own number yet", MeshtasticProtocol.ownName(record(0x8d88, "Meshtastic 8d88"), null, null))
        assertNull("a record with no name is not a name", MeshtasticProtocol.ownName(record(0x8d88, " "), 0x8d88L, "Old"))
        assertEquals("the short name, as chats show it", "8d88", MeshtasticProtocol.ownName(record(0x8d88, "", "8d88"), 0x8d88L, null))
    }

    @Test
    fun channelNameIsOnlyANameTheOwnerChose() {
        assertEquals("Marché", MeshtasticProtocol.channelName("Marché"))
        assertEquals("Marché", MeshtasticProtocol.channelName(" Marché "))
        assertNull("Meshtastic's default channel has no name", MeshtasticProtocol.channelName(""))
        assertNull(MeshtasticProtocol.channelName("  "))
        val presets = listOf(
            "LongFast", "LongSlow", "MediumFast", "MediumSlow", "ShortFast", "ShortSlow", "ShortTurbo", "LongModerate", "VeryLongSlow", "LongTurbo",
        )
        for (preset in presets) {
            assertNull(preset, MeshtasticProtocol.channelName(preset))
            assertNull(preset, MeshtasticProtocol.channelName(preset.uppercase()))
            assertNull(preset, MeshtasticProtocol.channelName(" ${preset.lowercase()} "))
        }
        assertEquals("a name that only begins like a preset is a name", "LongFast2", MeshtasticProtocol.channelName("LongFast2"))
        assertEquals("Fast", MeshtasticProtocol.channelName("Fast"))
    }

    @Test
    fun onlyThePrimaryChannelRecordNamesTheGroup() {
        fun record(index: Int, name: String) = MeshtasticProtocol.parseFromRadio(
            MeshProtos.FromRadio.newBuilder().setChannel(
                ChannelProtos.Channel.newBuilder().setIndex(index).setSettings(ChannelProtos.ChannelSettings.newBuilder().setName(name)),
            ).build(),
        )
        assertEquals(RadioEvent.PrimaryChannel("Marché"), record(0, "Marché"))
        assertEquals(RadioEvent.PrimaryChannel(null), record(0, ""))
        assertEquals(RadioEvent.PrimaryChannel(null), record(0, "LongFast"))
        assertNull("another channel is not the group", record(1, "Admin"))
    }

    @Test
    fun wantConfigRoundTripsConfigNonce() {
        val encoded = MeshtasticProtocol.buildWantConfig(12345)
        val toRadio = MeshProtos.ToRadio.parseFrom(encoded)
        assertEquals(12345, toRadio.wantConfigId)
        assertTrue(toRadio.hasWantConfigId())
    }

    @Test
    fun fromNumUsesLittleEndianInt() {
        assertEquals(0x78563412, MeshtasticProtocol.fromNumLittleEndian(byteArrayOf(0x12, 0x34, 0x56, 0x78)))
        assertNull(MeshtasticProtocol.fromNumLittleEndian(byteArrayOf(0x01, 0x02)))
    }

    private fun packetWithPayload(payload: ByteArray, port: Portnums.PortNum): MeshProtos.MeshPacket {
        val data = MeshProtos.Data.newBuilder().setPortnum(port).setPayload(ByteString.copyFrom(payload)).build()
        return MeshProtos.MeshPacket.newBuilder().setFrom(1).setChannel(0).setDecoded(data).build()
    }
}

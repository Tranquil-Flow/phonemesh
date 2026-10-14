package org.phonemesh.ble

import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.MeshProtos

/** Private-port data packets (pictures): built as direct messages with want_ack, parsed apart from text. */
class PrivateDataProtocolTest {
    private fun fromRadio(port: Int, payload: ByteArray, from: Int = 0x89abcdef.toInt(), to: Int = 0x1234abcd): MeshProtos.FromRadio {
        val data = MeshProtos.Data.newBuilder().setPortnumValue(port).setPayload(ByteString.copyFrom(payload)).build()
        val packet = MeshProtos.MeshPacket.newBuilder().setFrom(from).setTo(to).setChannel(0).setDecoded(data).build()
        return MeshProtos.FromRadio.newBuilder().setPacket(packet).build()
    }

    @Test fun buildsADirectReliablePacketOnThePrivatePort() {
        val payload = ByteArray(204) { it.toByte() }
        val packet = MeshProtos.ToRadio.parseFrom(
            MeshtasticProtocol.buildDataToRadio(300, payload, "!1234abcd", 0, packetId = 0x0a0b0c0d, hopLimit = 3),
        ).packet
        assertEquals(0x1234abcd, packet.to)
        assertEquals(0x0a0b0c0d, packet.id)
        assertTrue(packet.wantAck)
        assertEquals(3, packet.hopLimit)
        assertEquals(MeshProtos.MeshPacket.Priority.RELIABLE, packet.priority)
        assertEquals(300, packet.decoded.portnumValue)
        assertArrayEquals(payload, packet.decoded.payload.toByteArray())
    }

    @Test fun buildsABroadcastWithoutAckOnTheAccountsPort() {
        val payload = ByteArray(233) { (it * 3).toByte() }
        val packet = MeshProtos.ToRadio.parseFrom(
            MeshtasticProtocol.buildBroadcastDataToRadio(301, payload, 0, packetId = 0x01020304, hopLimit = 3),
        ).packet
        assertEquals(-1, packet.to)
        assertEquals(0x01020304, packet.id)
        assertEquals("a broadcast gets no radio acknowledgement", false, packet.wantAck)
        assertEquals(3, packet.hopLimit)
        assertEquals(301, packet.decoded.portnumValue)
        assertArrayEquals(payload, packet.decoded.payload.toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun broadcastsOnlyOnTheAccountsPort() { MeshtasticProtocol.buildBroadcastDataToRadio(300, byteArrayOf(1), 0) }

    @Test fun aBroadcastOnTheAccountsPortArrivesWithItsSignalAndHops() {
        val data = MeshProtos.Data.newBuilder().setPortnumValue(301).setPayload(ByteString.copyFrom(byteArrayOf(9, 8))).build()
        val packet = MeshProtos.MeshPacket.newBuilder().setFrom(0x89abcdef.toInt()).setTo(-1).setChannel(0).setId(77)
            .setRxRssi(-97).setRxSnr(5.25f).setHopStart(3).setHopLimit(2).setDecoded(data).build()
        val event = MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setPacket(packet).build()) as RadioEvent.Data
        assertEquals(301, event.port)
        assertEquals(MeshtasticProtocol.BROADCAST_NUM, event.to)
        assertEquals("rssi=-97 snr=5.25 hops=1", event.signal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesBroadcast() { MeshtasticProtocol.buildDataToRadio(300, byteArrayOf(1), "^all", 0) }

    @Test(expected = IllegalArgumentException::class)
    fun refusesPortsOutsideThePrivateRange() { MeshtasticProtocol.buildDataToRadio(1, byteArrayOf(1), "!1234abcd", 0) }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMoreThanTheRadioCarries() {
        MeshtasticProtocol.buildDataToRadio(300, ByteArray(MeshtasticProtocol.MAX_DATA_BYTES + 1), "!1234abcd", 0)
    }

    @Test fun parsesPrivatePortPackets() {
        val event = MeshtasticProtocol.parseFromRadio(fromRadio(300, byteArrayOf(0x10, 0, 1, 0, 9))) as RadioEvent.Data
        assertEquals("!89abcdef", event.sender)
        assertEquals(300, event.port)
        assertArrayEquals(byteArrayOf(0x10, 0, 1, 0, 9), event.payload)
        assertEquals(0x1234abcdL, event.to)
    }

    @Test fun privatePacketsNeverReachTheTextPath() {
        val event = MeshtasticProtocol.parseFromRadio(fromRadio(300, "hello".toByteArray()))
        assertTrue(event is RadioEvent.Data)
    }

    @Test fun otherPortsAndEmptyOrOversizedPayloadsAreIgnored() {
        assertNull(MeshtasticProtocol.parseFromRadio(fromRadio(257, byteArrayOf(1))))
        assertNull(MeshtasticProtocol.parseFromRadio(fromRadio(300, ByteArray(0))))
        assertNull(MeshtasticProtocol.parseFromRadio(fromRadio(300, ByteArray(MeshtasticProtocol.MAX_DATA_BYTES + 1))))
    }
}

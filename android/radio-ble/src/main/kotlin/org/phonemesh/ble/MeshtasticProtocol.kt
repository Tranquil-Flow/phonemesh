package org.phonemesh.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import com.google.protobuf.ByteString
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums

internal const val MESHTASTIC_SERVICE_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"
internal const val TORADIO_UUID = "f75c76d2-129e-4dad-a1dd-7866124401e7"
internal const val FROMRADIO_UUID = "2c55e69e-4993-11ed-b878-0242ac120002"
internal const val FROMNUM_UUID = "ed9da18c-a800-4f66-a670-aa7547e34453"
internal const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

/** Pure Meshtastic protobuf helpers; deliberately has no Android dependency. */
internal object MeshtasticProtocol {
    const val MAX_TEXT_BYTES = 200
    const val BROADCAST_NUM: Long = 0xffffffffL
    const val FIRST_HEARTBEAT_NONCE = 2

    private val random = SecureRandom()

    fun newNonce(): Int {
        var value = 0
        while (value == 0) value = random.nextInt()
        return value
    }

    fun newPacketId(): Int {
        var value = 0
        while (value == 0) value = random.nextInt()
        return value
    }

    fun fromNumLittleEndian(bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        return ByteBuffer.wrap(bytes.copyOfRange(0, 4)).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun buildWantConfig(wantConfigId: Int): ByteArray =
        MeshProtos.ToRadio.newBuilder().setWantConfigId(wantConfigId).build().toByteArray()

    fun buildDisconnect(): ByteArray =
        MeshProtos.ToRadio.newBuilder().setDisconnect(true).build().toByteArray()

    fun buildHeartbeat(nonce: Int): ByteArray =
        MeshProtos.ToRadio.newBuilder()
            .setHeartbeat(MeshProtos.Heartbeat.newBuilder().setNonce(nonce).build())
            .build()
            .toByteArray()

    fun buildTextToRadio(text: String, destinationId: String, channel: Int, packetId: Int = newPacketId(), hopLimit: Int = 3): ByteArray {
        val destination = parseDestinationId(destinationId)
        require(channel in 0..7) { "channel must be in 0..7" }
        require(hopLimit in 0..7) { "hop limit must be in 0..7" }
        val payload = text.encodeToByteArray()
        require(payload.isNotEmpty()) { "text must not be empty" }
        require(payload.size <= MAX_TEXT_BYTES) { "text is ${payload.size} UTF-8 bytes; max is $MAX_TEXT_BYTES" }

        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFrom(payload))
            .setWantResponse(false)
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(destination.toInt())
            .setChannel(channel)
            .setId(packetId)
            .setPriority(MeshProtos.MeshPacket.Priority.RELIABLE)
            .setWantAck(destination != BROADCAST_NUM)
            .setHopLimit(hopLimit)
            .setDecoded(data)
            .build()
        return MeshProtos.ToRadio.newBuilder().setPacket(packet).build().toByteArray()
    }

    fun parseDestinationId(id: String): Long {
        val trimmed = id.trim()
        require(trimmed.isNotEmpty()) { "destinationId must not be blank" }
        val normalized = trimmed.lowercase()
        if (normalized == "^all" || normalized == "broadcast" || normalized == "all") return BROADCAST_NUM
        val hex = when {
            normalized.startsWith("!") -> normalized.drop(1)
            normalized.startsWith("0x") -> normalized.drop(2)
            else -> normalized
        }
        require(Regex("[0-9a-f]{8}").matches(hex)) {
            "destinationId must be ^all/broadcast or an 8-hex Meshtastic node id like !1234abcd"
        }
        return hex.toLong(16) and BROADCAST_NUM
    }

    fun nodeId(num: Long): String = "!%08x".format(num and BROADCAST_NUM)

    /** Total function: a truncated or corrupt BLE read yields null instead of throwing into the radio loop. */
    fun parseFromRadio(bytes: ByteArray): RadioEvent? = try {
        parseFromRadio(MeshProtos.FromRadio.parseFrom(bytes))
    } catch (_: Exception) {
        null
    }

    fun parseFromRadio(fromRadio: MeshProtos.FromRadio): RadioEvent? {
        return when (fromRadio.payloadVariantCase) {
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> RadioEvent.ConfigComplete(fromRadio.configCompleteId)
            MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> RadioEvent.MyInfo(fromRadio.myInfo.myNodeNum)
            MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> nodeInfoEvent(fromRadio.nodeInfo)
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG ->
                if (fromRadio.config.hasLora()) RadioEvent.HopLimit(fromRadio.config.lora.hopLimit) else null
            MeshProtos.FromRadio.PayloadVariantCase.QUEUESTATUS -> RadioEvent.QueueStatus(
                fromRadio.queueStatus.res,
                fromRadio.queueStatus.free,
                fromRadio.queueStatus.maxlen,
                fromRadio.queueStatus.meshPacketId,
            )
            MeshProtos.FromRadio.PayloadVariantCase.PACKET -> packetEvent(fromRadio.packet)
            else -> null
        }
    }

    private fun nodeInfoEvent(info: MeshProtos.NodeInfo): RadioEvent.NodeDiscovered {
        val user = if (info.hasUser()) info.user else null
        val id = user?.id?.takeIf { it.isNotBlank() } ?: nodeId(info.num.toLong())
        val name = user?.longName?.takeIf { it.isNotBlank() }
            ?: user?.shortName?.takeIf { it.isNotBlank() }
            ?: id
        return RadioEvent.NodeDiscovered(info.num.toLong() and BROADCAST_NUM, id, name)
    }

    private fun packetEvent(packet: MeshProtos.MeshPacket): RadioEvent? {
        if (!packet.hasDecoded()) return null
        val decoded = packet.decoded
        if (decoded.portnum == Portnums.PortNum.ROUTING_APP) return routingEvent(packet, decoded)
        if (decoded.portnum != Portnums.PortNum.TEXT_MESSAGE_APP) return null
        val payload = decoded.payload.toByteArray()
        if (payload.isEmpty() || payload.size > MAX_TEXT_BYTES) return null
        val text = try {
            strictUtf8(payload)
        } catch (_: CharacterCodingException) {
            return null
        }
        val sender = nodeId(packet.`from`.toLong())
        return RadioEvent.Text(sender, text, packet.channel)
    }

    /**
     * Routing packets carry mesh ACK/NAK for a packet we sent. Malformed or undecodable routing payloads
     * yield null so a truncated read can never be mistaken for a confirmation.
     */
    private fun routingEvent(packet: MeshProtos.MeshPacket, decoded: MeshProtos.Data): RadioEvent? {
        val routing = try {
            MeshProtos.Routing.parseFrom(decoded.payload)
        } catch (_: Exception) {
            return null
        }
        // Routing carries no request id of its own; Data.request_id is the correlation key (proto field 6).
        val requestId = decoded.requestId.toLong() and BROADCAST_NUM
        if (requestId == 0L) return null
        if (!routing.hasErrorReason()) return null // RouteDiscovery/empty payload is not an ACK.
        val error = if (routing.errorReason != MeshProtos.Routing.Error.NONE) {
            routing.errorReason.name
        } else {
            null
        }
        return RadioEvent.Routing(
            from = packet.`from`.toLong() and BROADCAST_NUM,
            to = packet.to.toLong() and BROADCAST_NUM,
            requestId = requestId,
            error = error,
        )
    }

    fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

internal sealed class RadioEvent {
    data class ConfigComplete(val id: Int) : RadioEvent()
    data class MyInfo(val nodeNum: Int) : RadioEvent()
    data class HopLimit(val value: Int) : RadioEvent()
    data class NodeDiscovered(val nodeNum: Long, val id: String, val name: String) : RadioEvent()
    data class Text(val sender: String, val text: String, val channel: Int) : RadioEvent()
    data class QueueStatus(val res: Int, val free: Int, val maxlen: Int, val meshPacketId: Int) : RadioEvent()
    data class Routing(
        val from: Long,
        val to: Long,
        val requestId: Long,
        val error: String?,
    ) : RadioEvent()
}

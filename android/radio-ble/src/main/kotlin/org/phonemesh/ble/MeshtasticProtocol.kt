package org.phonemesh.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import com.google.protobuf.ByteString
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.ConfigProtos
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

    /** Meshtastic's Data payload limit (mesh.proto DATA_PAYLOAD_LEN); PKI direct messages fit less on air. */
    const val MAX_DATA_BYTES = 233

    /** Private ports this app sends and listens on: 300 = pictures (core PictureWire.PORT). */
    const val PICTURE_PORT = MeshRadio.PICTURE_PORT
    val PRIVATE_DATA_PORTS = setOf(PICTURE_PORT)
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

    fun buildTextToRadio(
        text: String, destinationId: String, channel: Int, packetId: Int = newPacketId(), hopLimit: Int = 3,
        replyTo: Long = 0, reaction: Boolean = false,
    ): ByteArray {
        val destination = parseDestinationId(destinationId)
        require(channel in 0..7) { "channel must be in 0..7" }
        require(hopLimit in 0..7) { "hop limit must be in 0..7" }
        val payload = text.encodeToByteArray()
        require(payload.isNotEmpty()) { "text must not be empty" }
        require(payload.size <= MAX_TEXT_BYTES) { "text is ${payload.size} UTF-8 bytes; max is $MAX_TEXT_BYTES" }

        require(!reaction || replyTo != 0L) { "a reaction needs the message it answers" }
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFrom(payload))
            .setWantResponse(false)
            .apply {
                if (replyTo != 0L) setReplyId(replyTo.toInt())
                if (reaction) setEmoji(1)
            }
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

    /**
     * One private-port packet for one other radio, with want_ack so that radio's acknowledgement comes back.
     * Never broadcast: pictures go only in private chats.
     */
    fun buildDataToRadio(port: Int, payload: ByteArray, destinationId: String, channel: Int, packetId: Int = newPacketId(), hopLimit: Int = 3): ByteArray {
        val destination = parseDestinationId(destinationId)
        require(destination != BROADCAST_NUM) { "private data goes to one radio, never to everyone" }
        require(port in 256..511) { "port $port is not a private Meshtastic port" }
        require(channel in 0..7) { "channel must be in 0..7" }
        require(hopLimit in 0..7) { "hop limit must be in 0..7" }
        require(payload.size in 1..MAX_DATA_BYTES) { "payload is ${payload.size} bytes; must be 1..$MAX_DATA_BYTES" }
        val data = MeshProtos.Data.newBuilder()
            .setPortnumValue(port)
            .setPayload(ByteString.copyFrom(payload))
            .setWantResponse(false)
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(destination.toInt())
            .setChannel(channel)
            .setId(packetId)
            .setPriority(MeshProtos.MeshPacket.Priority.RELIABLE)
            .setWantAck(true)
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

    /**
     * Who a text was for: true = addressed to this radio (private), false = broadcast (group),
     * null = addressed to some other radio, or private before our own number is known (not shown).
     */
    fun textAudience(to: Long, local: Long?): Boolean? = when {
        to == BROADCAST_NUM -> false
        local != null && to == local -> true
        else -> null
    }

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
            MeshProtos.FromRadio.PayloadVariantCase.CHANNEL ->
                if (fromRadio.channel.index == 0) RadioEvent.PrimaryChannel(channelName(fromRadio.channel.settings.name)) else null
            MeshProtos.FromRadio.PayloadVariantCase.CONFIG ->
                if (fromRadio.config.hasLora()) RadioEvent.LoraSettings(fromRadio.config.lora) else null
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

    /**
     * The connected radio's own name after [event]: the name of its own record (number [local]), null when that
     * record names nothing; any other radio's record leaves [known] as it was.
     */
    fun ownName(event: RadioEvent.NodeDiscovered, local: Long?, known: String?): String? =
        if (event.nodeNum != local) known else event.name.takeIf { it != event.id }

    /**
     * A channel's name when the radio's owner chose one. Blank is Meshtastic's default channel, which apps show as
     * the radio preset ("LongFast", "MediumSlow", ...); a name that is just a preset counts as none too.
     */
    fun channelName(name: String): String? = name.trim().takeIf { it.isNotEmpty() && !presetName.matches(it) }

    private val presetName = Regex("(very)?(long|medium|short)(fast|slow|moderate|turbo)", RegexOption.IGNORE_CASE)

    private fun packetEvent(packet: MeshProtos.MeshPacket): RadioEvent? {
        if (!packet.hasDecoded()) return null
        val decoded = packet.decoded
        if (decoded.portnum == Portnums.PortNum.ROUTING_APP) return routingEvent(packet, decoded)
        if (decoded.portnum == Portnums.PortNum.ADMIN_APP) return adminEvent(packet, decoded)
        if (decoded.portnumValue in PRIVATE_DATA_PORTS) return dataEvent(packet, decoded)
        if (decoded.portnum != Portnums.PortNum.TEXT_MESSAGE_APP) return null
        val payload = decoded.payload.toByteArray()
        if (payload.isEmpty() || payload.size > MAX_TEXT_BYTES) return null
        val text = try {
            strictUtf8(payload)
        } catch (_: CharacterCodingException) {
            return null
        }
        val sender = nodeId(packet.`from`.toLong())
        return RadioEvent.Text(
            sender, text, packet.channel, packet.to.toLong() and BROADCAST_NUM,
            packet.id.toLong() and BROADCAST_NUM, decoded.replyId.toLong() and BROADCAST_NUM, decoded.emoji != 0,
        )
    }

    private fun dataEvent(packet: MeshProtos.MeshPacket, decoded: MeshProtos.Data): RadioEvent? {
        val payload = decoded.payload.toByteArray()
        if (payload.isEmpty() || payload.size > MAX_DATA_BYTES) return null
        return RadioEvent.Data(
            nodeId(packet.`from`.toLong()), decoded.portnumValue, payload, packet.channel, packet.to.toLong() and BROADCAST_NUM,
            packet.id.toLong() and BROADCAST_NUM,
        )
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

    /**
     * An admin reply (get_config_response) from a radio. The caller checks it came from the local node;
     * [RadioEvent.AdminLora.lora] is null when the reply carried some other config.
     */
    private fun adminEvent(packet: MeshProtos.MeshPacket, decoded: MeshProtos.Data): RadioEvent? {
        val admin = try {
            AdminProtos.AdminMessage.parseFrom(decoded.payload)
        } catch (_: Exception) {
            return null
        }
        if (!admin.hasGetConfigResponse()) return null
        val lora = admin.getConfigResponse.takeIf { it.hasLora() }?.lora
        return RadioEvent.AdminLora(packet.`from`.toLong() and BROADCAST_NUM, lora, admin.sessionPasskey)
    }

    /** One AdminMessage to this phone's own radio, on ADMIN_APP. */
    fun buildAdmin(admin: AdminProtos.AdminMessage, localNode: Int, packetId: Int = newPacketId(), wantResponse: Boolean = false): ByteArray {
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.ADMIN_APP)
            .setPayload(admin.toByteString())
            .setWantResponse(wantResponse)
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(localNode)
            .setId(packetId)
            .setPriority(MeshProtos.MeshPacket.Priority.RELIABLE)
            .setDecoded(data)
            .build()
        return MeshProtos.ToRadio.newBuilder().setPacket(packet).build().toByteArray()
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
    data class LoraSettings(val lora: ConfigProtos.Config.LoRaConfig) : RadioEvent()
    data class AdminLora(val from: Long, val lora: ConfigProtos.Config.LoRaConfig?, val passkey: ByteString) : RadioEvent()
    data class PrimaryChannel(val name: String?) : RadioEvent()
    data class NodeDiscovered(val nodeNum: Long, val id: String, val name: String) : RadioEvent()
    data class Text(
        val sender: String, val text: String, val channel: Int, val to: Long,
        val packetId: Long = 0, val replyTo: Long = 0, val reaction: Boolean = false,
    ) : RadioEvent()
    /** [packetId]: the mesh packet's own id, what a reply or reaction to it names. */
    class Data(val sender: String, val port: Int, val payload: ByteArray, val channel: Int, val to: Long, val packetId: Long = 0) : RadioEvent()
    data class QueueStatus(val res: Int, val free: Int, val maxlen: Int, val meshPacketId: Int) : RadioEvent()
    data class Routing(
        val from: Long,
        val to: Long,
        val requestId: Long,
        val error: String?,
    ) : RadioEvent()
}

package org.phonemesh.hub

import org.meshtastic.proto.ConfigProtos
import org.phonemesh.core.wire.HubAck
import org.phonemesh.core.wire.WireV2

/**
 * The hub's engine (accounts plan step 15): stores what the radio hears and what phones send, sends phones' packets
 * over the radio in turn within the duty cycle, and acknowledges what it heard on port 302. Driven by [tick] with the
 * time, so tests run it on a fake clock.
 */
class Hub(
    private val mailbox: Mailbox,
    private val radio: Radio,
    private val airtime: Airtime = Airtime(),
    private val duty: DutyCycle = DutyCycle(10),
    private val log: (String) -> Unit = {},
) : RadioListener {
    class Outgoing(val port: Int, bytes: ByteArray) { val bytes: ByteArray = bytes.copyOf() }

    /** Outgoing radio packets by who gave them (a phone's address, or [HUB] for acknowledgements), served in turn. */
    private val queues = LinkedHashMap<String, ArrayDeque<Outgoing>>()
    private var turn = 0
    private var radioFreeAt = 0L
    private val pendingAcks = ArrayList<ByteArray>()
    private var oldestAck = 0L
    @Volatile var clock: () -> Long = System::currentTimeMillis
    @Volatile var rebroadcastMode: ConfigProtos.Config.DeviceConfig.RebroadcastMode? = null
        private set

    enum class Sent { STORED, ALREADY, REFUSED, BUSY }

    /** A phone on the hub's Wi-Fi hands over one packet. STORED and ALREADY both mean "reached the hub". */
    @Synchronized fun fromPhone(phone: String, port: Int, bytes: ByteArray): Sent {
        if (port != WireV2.PORT && port != org.phonemesh.core.wire.SharedChatWire.PORT) return Sent.REFUSED
        if (mailbox.classify(port, bytes) == null) return Sent.REFUSED
        val queue = queues.getOrPut(phone) { ArrayDeque() }
        if (queue.size >= MAX_QUEUED) return Sent.BUSY
        mailbox.put(port, bytes, clock()) ?: return Sent.ALREADY
        queue.addLast(Outgoing(port, bytes))
        return Sent.STORED
    }

    @Synchronized override fun onData(port: Int, payload: ByteArray, signal: String) {
        val record = mailbox.put(port, payload, clock()) ?: return
        log("heard ${record.kind} on $port, ${payload.size} B, $signal")
        if (record.kind == Mailbox.Kind.CODE) {
            if (pendingAcks.isEmpty()) oldestAck = clock()
            pendingAcks += WireV2.parse(payload)!!.header.code
            if (pendingAcks.size >= ACK_CODES) flushAcks()
        }
    }

    override fun onRebroadcastMode(mode: ConfigProtos.Config.DeviceConfig.RebroadcastMode) {
        rebroadcastMode = mode
        if (mode != ConfigProtos.Config.DeviceConfig.RebroadcastMode.ALL && mode != ConfigProtos.Config.DeviceConfig.RebroadcastMode.ALL_SKIP_DECODING)
            log("WARNING: the hub radio's rebroadcast mode is $mode; it must be ALL to relay Phonemesh packets")
    }

    override fun onLog(line: String) = log("radio: $line")

    /** Called a few times a second: flushes due acknowledgements, sends one packet if the radio and budget allow. */
    @Synchronized fun tick() {
        val now = clock()
        if (pendingAcks.isNotEmpty() && now - oldestAck >= ACK_MILLIS) flushAcks()
        if (now < radioFreeAt) return
        val next = nextInTurn() ?: return
        val millis = airtime.millis(next.second.bytes.size)
        if (!duty.allows(now, millis)) return
        queues.getValue(next.first).removeFirst()
        radio.broadcast(next.second.port, next.second.bytes)
        duty.record(now, millis)
        radioFreeAt = now + millis.toLong() + GAP_MILLIS
    }

    /** Packets waiting for the radio, per sender. */
    @Synchronized fun waiting(): Map<String, Int> = queues.filterValues { it.isNotEmpty() }.mapValues { it.value.size }

    private fun nextInTurn(): Pair<String, Outgoing>? {
        val senders = queues.keys.toList()
        for (i in senders.indices) {
            val sender = senders[(turn + i) % senders.size]
            val packet = queues.getValue(sender).firstOrNull() ?: continue
            turn = (turn + i + 1) % senders.size
            return sender to packet
        }
        return null
    }

    private fun flushAcks() {
        val codes = pendingAcks.take(ACK_CODES)
        pendingAcks.subList(0, codes.size).clear()
        queues.getOrPut(HUB) { ArrayDeque() }.addLast(Outgoing(HubAck.PORT, HubAck.encode(codes)))
        oldestAck = clock()
    }

    companion object {
        const val HUB = "hub"
        const val ACK_CODES = 25
        const val ACK_MILLIS = 30_000L
        const val MAX_QUEUED = 200
        /** Room after each packet so the radio's own queue never fills. */
        const val GAP_MILLIS = 500L
    }
}

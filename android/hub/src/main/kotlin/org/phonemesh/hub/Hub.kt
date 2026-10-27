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
    private val outbox: Outbox = Outbox(),
) : RadioListener {
    /** Radio time for phones' packets (AE4): 180 s an hour to start, inside the legal [duty], room left for relays. */
    private val share = DutyCycle(APP_SHARE_PERCENT)
    class Outgoing(val port: Int, bytes: ByteArray, val id: String? = null, var retryAt: Long = 0) {
        val bytes: ByteArray = bytes.copyOf()
    }

    /** Outgoing radio packets by who gave them (a phone's address, or [HUB] for acknowledgements), served in turn. */
    private val queues = LinkedHashMap<String, ArrayDeque<Outgoing>>()
    private var turn = 0
    /** Radio time each sender used in the last hour, for fairness (not persisted: a restart starts everyone equal). */
    private val usedBy = HashMap<String, DutyCycle>()
    /** Recipient codes phones on this hub's Wi-Fi asked for, and when (AE7). Memory only: codes, no phone address. */
    private val listening = HashMap<String, Long>()
    private var radioFreeAt = outbox.freeAt
    private var storageRetryAt = 0L
    private val uncertain = HashSet<Outgoing>()
    private val completed = HashSet<Outgoing>()
    private val pendingAcks = ArrayList<ByteArray>()
    private var oldestAck = 0L
    @Volatile var clock: () -> Long = System::currentTimeMillis
    @Volatile var rebroadcastMode: ConfigProtos.Config.DeviceConfig.RebroadcastMode? = null
        private set

    init {
        // After a restart the phones' share counts every charged airing, the hub's own too: never less than was used.
        outbox.reservations.forEach { duty.record(it.at, it.millis); share.record(it.at, it.millis) }
        for (entry in outbox.entries) {
            require(mailbox.classify(entry.port, entry.bytes) != null) { "invalid outbox packet" }
            // A restart never shortens the per-packet cooldown: a write charged just before a crash may have aired.
            val cooled = outbox.lastAired(entry.id)?.let { maxOf(entry.retryAt, it + RETRY_COOLDOWN_MILLIS) } ?: entry.retryAt
            queues.getOrPut(entry.phone) { ArrayDeque() }.addLast(Outgoing(entry.port, entry.bytes, entry.id, cooled))
        }
    }

    enum class Sent { STORED, ALREADY, REFUSED, BUSY }

    /**
     * A phone on the hub's Wi-Fi hands over one packet. STORED and ALREADY both mean "reached the hub".
     * A private packet the hub already holds is the sender's retry (it has no "delivered" yet): the identical bytes are
     * forwarded once more, at most every [RETRY_COOLDOWN_MILLIS] per packet across all phones and restarts, and never
     * after [Outbox.MAX_AGE] from first storage. The mailbox row, its time and its retention never change. Nothing is
     * re-aired without such a request, and shared-chat posts (port 303) keep single forwarding.
     */
    @Synchronized fun fromPhone(phone: String, port: Int, bytes: ByteArray, air: Boolean = true): Sent {
        if (port != WireV2.PORT && port != org.phonemesh.core.wire.SharedChatWire.PORT) return Sent.REFUSED
        if (mailbox.classify(port, bytes) == null) return Sent.REFUSED
        // Decision 64: photo avatars go only through a hub. Such a packet is kept for phones that fetch their mail here
        // (Wi-Fi or Bluetooth) and is never queued for the radio, now or on a retry.
        if (!air) {
            if (port != WireV2.PORT) return Sent.REFUSED
            if (mailbox.contains(port, bytes)) return Sent.ALREADY
            return if (mailbox.put(port, bytes, clock()) != null) Sent.STORED else Sent.BUSY
        }
        val id = Outbox.key(port, bytes)
        val now = clock()
        val pending = outbox.entries.firstOrNull { it.id == id }
        if (pending != null) {
            // A parked ambiguous write waits for exactly this: a later, eligible demand re-arms the same intent.
            if (port == WireV2.PORT && mayRetry(id, pending.at, now)) {
                queues.values.flatMap { it.toList() }.filter { it.id == id && it in uncertain }.forEach { uncertain.remove(it) }
            }
            // An earlier append may have failed after intent commit. Never turn that into phantom custody.
            return if (mailbox.put(port, bytes, now) == null) Sent.ALREADY else Sent.STORED
        }
        if (mailbox.contains(port, bytes)) {
            if (port != WireV2.PORT) return Sent.ALREADY
            val first = mailbox.storedAt(port, bytes) ?: return Sent.ALREADY
            if (!mayRetry(id, first, now)) return Sent.ALREADY
            if ((queues[phone]?.size ?: 0) >= MAX_QUEUED || outbox.entries.size >= Outbox.MAX_PENDING) return Sent.BUSY
            // Durable before the answer; the entry keeps the first storage time so expiry is never renewed.
            outbox.add(Outbox.Entry(phone, port, bytes, first))
            queues.getOrPut(phone) { ArrayDeque() }.addLast(Outgoing(port, bytes, id))
            return Sent.ALREADY
        }
        if ((queues[phone]?.size ?: 0) >= MAX_QUEUED || outbox.entries.size >= Outbox.MAX_PENDING) return Sent.BUSY
        // AE7: when the recipient's phone is on this hub's Wi-Fi, give it a moment to fetch the packet before radio.
        val local = port == WireV2.PORT && mailbox.classify(port, bytes)?.second?.let { code ->
            listening[code]?.let { now - it <= LISTEN_FRESH_MILLIS }
        } == true
        val holdUntil = if (local) now + LOCAL_GRACE_MILLIS else 0L
        // Write-ahead phone intent precedes mailbox publication and therefore precedes HTTP 200.
        outbox.add(Outbox.Entry(phone, port, bytes, clock(), retryAt = holdUntil))
        queues.getOrPut(phone) { ArrayDeque() }.addLast(Outgoing(port, bytes, id, holdUntil))
        mailbox.put(port, bytes, clock())
        return Sent.STORED
    }

    /** A phone on the Wi-Fi asked for mail to [codes] (hex): packets for them may wait for that phone (AE7). */
    @Synchronized fun listened(codes: Set<String>) {
        val now = clock()
        listening.entries.removeAll { now - it.value > LISTEN_FRESH_MILLIS }
        for (code in codes.take(MAX_LISTENING)) listening[code.lowercase()] = now
        while (listening.size > MAX_LISTENING) listening.remove(listening.minByOrNull { it.value }!!.key)
    }

    /**
     * A phone on the Wi-Fi took these packets (AE7): its own receipt, not the hub, will tell the sender. Packets still
     * waiting for the radio are not aired; one already handed to the radio is not affected. A sender's later retry
     * (no receipt yet) still goes by radio, as before.
     */
    @Synchronized fun fetched(packets: List<Pair<Int, ByteArray>>) {
        for ((port, bytes) in packets) {
            if (port != WireV2.PORT) continue
            val id = Outbox.key(port, bytes)
            val queued = queues.values.flatMap { it.toList() }.filter { it.id == id && it !in uncertain && it !in completed }
            if (queued.isEmpty()) continue
            outbox.remove(id)
            queued.forEach(::discard)
            log("fetched over Wi-Fi; not aired")
        }
    }

    private fun mayRetry(id: String, firstStored: Long, now: Long): Boolean {
        if (now - firstStored >= Outbox.MAX_AGE) return false
        val last = outbox.lastAired(id) ?: return true
        return now - last >= RETRY_COOLDOWN_MILLIS
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
        if (now < storageRetryAt) return
        try {
            // Retry only the completion COMMIT, never an already successful serial write in this process.
            for (packet in completed.toList()) {
                outbox.remove(packet.id!!)
                discard(packet)
            }
            for (entry in outbox.entries.filter { now - it.at >= Outbox.MAX_AGE }) {
                outbox.remove(entry.id)
                queues.values.flatMap { it.toList() }.filter { it.id == entry.id }.forEach(::discard)
                log("initial forwarding intent expired")
            }
            if (pendingAcks.isNotEmpty() && now - oldestAck >= ACK_MILLIS) flushAcks()
            if (now < radioFreeAt) return
            val next = nextFair(now) ?: return
            val packet = next.second
            // Recover a crash/append failure between intent and mailbox commit before sending any bytes.
            if (packet.id != null) {
                val entry = outbox.entries.first { it.id == packet.id }
                mailbox.put(packet.port, packet.bytes, entry.at)
            }
            val millis = airtime.millis(packet.bytes.size)
            if (!duty.allows(now, millis)) return
            val freeAt = now + millis.toLong() + GAP_MILLIS
            // Charge even uncertain writes and definitely refused writes conservatively. No restart bypass.
            outbox.reserve(now, millis, freeAt, duty.windowMillis, packet.id)
            duty.record(now, millis)
            if (next.first != HUB) share.record(now, millis)
            usedBy.getOrPut(next.first) { DutyCycle(100) }.record(now, millis)
            radioFreeAt = freeAt
            try {
                radio.broadcast(packet.port, packet.bytes)
            } catch (_: RadioNotSubmittedException) {
                packet.retryAt = maxOf(freeAt, now + FAILURE_BACKOFF_MILLIS)
                radioFreeAt = packet.retryAt
                if (packet.id != null) outbox.retry(packet.id, packet.retryAt) else outbox.delay(packet.retryAt)
                log("radio refused before submission; initial forwarding retained with backoff")
                return
            } catch (_: Exception) {
                // OutputStream cannot tell us whether a failed write/flush reached the device.
                // Park, don't automatically re-air. Reopening the process recovers one charged attempt.
                if (packet.id != null) uncertain += packet else discard(packet)
                radioFreeAt = maxOf(freeAt, now + FAILURE_BACKOFF_MILLIS)
                outbox.delay(radioFreeAt)
                log("radio submission uncertain; initial forwarding retained for process recovery")
                return
            }
            if (packet.id != null) {
                completed += packet
                outbox.remove(packet.id)
            }
            discard(packet)
        } catch (_: Exception) {
            storageRetryAt = now + FAILURE_BACKOFF_MILLIS
            log("hub storage operation failed; work retained, retrying after backoff")
        }
    }

    private fun discard(packet: Outgoing) {
        queues.values.forEach { it.remove(packet) }
        uncertain.remove(packet)
        completed.remove(packet)
    }

    /** Packets waiting for the radio, per sender. */
    @Synchronized fun waiting(): Map<String, Int> = queues.filterValues { it.isNotEmpty() }.mapValues { it.value.size }

    /**
     * The next packet (radio-airtime decision 53, AE4): the hub's own acknowledgements first; then short packets
     * (receipts and most texts; the hub cannot read private mail, so size is the only sign) before full ones (picture
     * pieces); among senders, whoever had the least radio time in the last hour, ties in turn. Phones' packets only
     * while their [share] allows; acknowledgements only need the legal [duty].
     */
    private fun nextFair(now: Long): Pair<String, Outgoing>? {
        val senders = queues.keys.toList()
        if (senders.isEmpty()) return null
        val ready = senders.indices.mapNotNull { i ->
            val sender = senders[(turn + i) % senders.size]
            val packets = queues.getValue(sender).filter { it !in uncertain && it !in completed && now >= it.retryAt }
                .filter { sender == HUB || share.allows(now, airtime.millis(it.bytes.size)) }
            if (packets.isEmpty()) null else Triple(i, sender, packets)
        }
        if (ready.isEmpty()) return null
        val short = ready.mapNotNull { (i, s, p) -> p.firstOrNull { it.bytes.size < SHORT_BYTES }?.let { Triple(i, s, it) } }
        val pool = short.ifEmpty { ready.map { (i, s, p) -> Triple(i, s, p.first()) } }
        val pick = pool.minWith(compareBy({ if (it.second == HUB) 0 else 1 }, { usedBy[it.second]?.used(now) ?: 0.0 }, { it.first }))
        turn = (turn + pick.first + 1) % senders.size
        return pick.second to pick.third
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
        const val FAILURE_BACKOFF_MILLIS = 5_000L
        /** Per packet, across phones and restarts; below the phone's first 2-minute retry so honest retries get through. */
        const val RETRY_COOLDOWN_MILLIS = 60_000L
        /** Phones' share of each hour (AE4): 5 % is 180 s, half the EU 868 / Togo 10 % ceiling. */
        const val APP_SHARE_PERCENT = 5
        /** Packets below this many bytes go first: receipts, acknowledgements and most texts. */
        const val SHORT_BYTES = 128
        /** How long a packet for a phone on the Wi-Fi waits for it before the radio (AE7). */
        const val LOCAL_GRACE_MILLIS = 120_000L
        /** A phone counts as on the Wi-Fi for this long after it last asked for mail (it asks every 30 s or less). */
        const val LISTEN_FRESH_MILLIS = 90_000L
        const val MAX_LISTENING = 4_000
    }
}

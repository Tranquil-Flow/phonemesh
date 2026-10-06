package org.phonemesh.ble

/** Facts extracted from one ROUTING_APP packet, already normalized to unsigned node numbers. */
internal data class RoutingOutcome(
    val from: Long,
    val to: Long,
    val requestId: Long,
    val error: String?,
)

/**
 * Bounded, worker-confined correlation of outgoing packets against mesh ACK/error traffic.
 *
 * Invariants, all covered by DeliveryTrackingTest:
 * - only the *destination* radio acknowledging our own packet id can produce ACKNOWLEDGED;
 * - a local (connected radio) ACK, an intermediate hop, a foreign request id or a broadcast destination
 *   can never produce ACKNOWLEDGED;
 * - pending state is bounded in size and in time; expiry and session loss are UNCONFIRMED, not FAILED;
 * - nothing here stores or replays message text.
 */
internal class DeliveryTracker(
    private val now: () -> Long,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    internal class Pending(
        val packetId: Long,
        val destination: Long,
        val local: Long,
        val createdAt: Long,
    )

    data class Emission(val packetId: Long, val state: DeliveryState, val detail: String)

    private val pending = LinkedHashMap<Long, Pending>()

    val size: Int get() = pending.size

    fun nextExpiryDelay(): Long? = pending.values.minOfOrNull { (it.createdAt + timeoutMs - now()).coerceAtLeast(1L) }

    /** Used before track(): a known non-submission must still emit an observable terminal state. */
    fun rejectUnsubmitted(packetId: Long, reason: String): List<Emission> =
        listOf(Emission(packetId, DeliveryState.FAILED, "Packet not submitted: $reason"))

    /** Returns false when the bounded map is full; caller must report UNCONFIRMED rather than drop silently. */
    fun track(packetId: Long, destination: Long, local: Long): Boolean {
        if (packetId !in 1..MeshtasticProtocol.BROADCAST_NUM || pending.containsKey(packetId)) return false
        if (pending.size >= maxPending) return false
        pending[packetId] = Pending(packetId, destination, local, now())
        return true
    }

    /** Radio queue acceptance. Never a recipient ACK. */
    fun onQueued(packetId: Long): List<Emission> {
        val expired = expire()
        // Late/foreign queue reports for untracked ids are ignored: no pending work, nothing to report.
        if (!pending.containsKey(packetId)) return expired
        return expired + Emission(packetId, DeliveryState.QUEUED, "Radio accepted packet for queueing; still not a recipient ACK")
    }

    /** The connected radio itself rejected the packet before transmitting it. */
    fun onQueueRejected(packetId: Long, reason: String): List<Emission> {
        val expired = expire()
        if (!pending.containsKey(packetId)) return expired
        pending.remove(packetId)
        return expired + Emission(packetId, DeliveryState.FAILED, "Radio rejected packet before transmission: $reason")
    }

    fun onRouting(outcome: RoutingOutcome, localNode: Long): List<Emission> {
        val expired = expire()
        val entry = pending[outcome.requestId] ?: return expired
        if (entry.local != localNode) return expired // session replaced; the map was not adopted
        if (outcome.to != localNode) return expired // routing traffic for some other conversation
        val error = outcome.error
        if (error != null) {
            if (outcome.from != entry.local && outcome.from != entry.destination) return expired
            pending.remove(outcome.requestId)
            return expired + listOf(
                Emission(
                    entry.packetId,
                    DeliveryState.FAILED,
                    "Routing reported $error for packet ${hex(entry.packetId)} from ${hex(outcome.from)}",
                ),
            )
        }
        if (outcome.from != entry.destination || outcome.from == entry.local) return expired // intermediate/local ACK
        if (entry.destination == MeshtasticProtocol.BROADCAST_NUM) return expired // broadcast has no recipient ACK
        pending.remove(outcome.requestId)
        return expired + listOf(
            Emission(
                entry.packetId,
                DeliveryState.ACKNOWLEDGED,
                "Destination radio ${hex(entry.destination)} acknowledged packet ${hex(entry.packetId)}",
            ),
        )
    }

    fun expire(): List<Emission> {
        if (pending.isEmpty()) return emptyList()
        val limit = now() - timeoutMs
        val expired = pending.values.filter { it.createdAt <= limit }
        expired.forEach { pending.remove(it.packetId) }
        return expired.map {
            Emission(it.packetId, DeliveryState.UNCONFIRMED, "No confirmation within ${timeoutMs / 1000}s; delivery unknown, not a failure")
        }
    }

    /** On disconnect or GATT replacement: nothing may claim delivery and nothing is retried. */
    fun failPending(reason: String): List<Emission> {
        if (pending.isEmpty()) return emptyList()
        val entries = pending.values.toList()
        pending.clear()
        return entries.map { Emission(it.packetId, DeliveryState.UNCONFIRMED, "Unconfirmed: $reason") }
    }

    private fun hex(value: Long): String = "!%08x".format(value and MeshtasticProtocol.BROADCAST_NUM)

    companion object {
        const val TIMEOUT_MS = 90_000L
        const val DEFAULT_MAX_PENDING = 32
    }
}

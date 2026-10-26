package org.phonemesh.app.demo

import org.phonemesh.core.LoraAirtime
import org.phonemesh.core.LoraShape
import kotlin.math.ceil

/**
 * The demo's LoRa mesh, inside the app: every radio in the demo (the phone's demo radio, the demo people's radios and the
 * demo hub's radio) is a node. A packet takes its LongFast airtime to arrive, one radio airs one packet at a time, and
 * links can be cut (someone out of range) or a node switched off. Every arrival runs on the [clock]'s thread.
 */
class DemoMesh(private val clock: DemoClock, private val shape: LoraShape = LoraAirtime.preset("LONG_FAST")!!) {
    /** [to] is null for a broadcast, else the node the packet was addressed to (always the hearer). */
    fun interface Ear { fun heard(from: String, port: Int, payload: ByteArray, to: String?) }

    private class Node(val ear: Ear) { var on = true; var freeAt = 0L }

    private val nodes = LinkedHashMap<String, Node>()
    private val cuts = HashSet<Set<String>>()

    @Synchronized fun join(node: String, ear: Ear) {
        val old = nodes[node]
        nodes[node] = Node(ear).also { if (old != null) it.on = old.on }
    }

    @Synchronized fun setOn(node: String, on: Boolean) { nodes[node]?.on = on }
    @Synchronized fun isOn(node: String): Boolean = nodes[node]?.on == true
    @Synchronized fun cut(a: String, b: String) { cuts += setOf(a, b) }
    @Synchronized fun restore(a: String, b: String) { cuts -= setOf(a, b) }
    @Synchronized fun linked(a: String, b: String): Boolean = setOf(a, b) !in cuts

    fun airtimeMillis(bytes: Int): Long = ceil(LoraAirtime.seconds(bytes + HEADER_BYTES, shape) * 1000).toLong()

    /** One packet to every node in reach. Nothing happens when [from] is off. */
    fun broadcast(from: String, port: Int, payload: ByteArray) {
        val bytes = payload.copyOf()
        val at = slot(from, bytes.size) ?: return
        clock.after(at - clock.now()) {
            for ((_, ear) in hearers(from, null)) ear.heard(from, port, bytes, null)
        }
    }

    /** One packet to [to], with an acknowledgement: [arrived] says whether it got there (after Meshtastic's retries). */
    fun send(from: String, to: String, port: Int, payload: ByteArray, arrived: (Boolean) -> Unit) {
        val bytes = payload.copyOf()
        val at = slot(from, bytes.size) ?: run { clock.after(GIVE_UP_MS) { arrived(false) }; return }
        clock.after(at - clock.now()) {
            val ear = hearers(from, to).firstOrNull()?.second
            if (ear == null) clock.after(GIVE_UP_MS) { arrived(false) }
            else {
                ear.heard(from, port, bytes, to)
                clock.after(airtimeMillis(ACK_BYTES)) { arrived(true) }
            }
        }
    }

    /** When [from]'s next packet has finished airing, or null when it is off or unknown. */
    @Synchronized private fun slot(from: String, size: Int): Long? {
        val node = nodes[from]?.takeIf { it.on } ?: return null
        val end = maxOf(clock.now(), node.freeAt) + airtimeMillis(size)
        node.freeAt = end
        return end
    }

    @Synchronized private fun hearers(from: String, only: String?): List<Pair<String, Ear>> =
        nodes.filter { (id, node) -> id != from && node.on && (only == null || id == only) && setOf(from, id) !in cuts }
            .map { (id, node) -> id to node.ear }

    companion object {
        /** Meshtastic's packet header and encryption overhead on the air, roughly. */
        const val HEADER_BYTES = 16
        const val ACK_BYTES = 8
        /** Meshtastic gives up on an unacknowledged addressed packet after its retries. */
        const val GIVE_UP_MS = 20_000L
    }
}

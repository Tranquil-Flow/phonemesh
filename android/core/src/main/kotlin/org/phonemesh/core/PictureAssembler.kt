package org.phonemesh.core

/**
 * Rebuilds pictures from [PictureWire] packets, per sender. Bounded in count and in time; never throws on radio input.
 * Pieces may arrive before their HEAD; a picture is [Event.Done] only when every part arrived and the CRC-32 matches.
 * Not thread-safe: one caller (the main thread).
 */
class PictureAssembler(private val now: () -> Long) {
    sealed class Event {
        abstract val sender: String
        abstract val id: Int
        data class Started(override val sender: String, val head: PictureWire.Head, val have: Int) : Event() { override val id get() = head.id }
        data class Progress(override val sender: String, override val id: Int, val have: Int, val total: Int) : Event()
        data class Done(override val sender: String, val head: PictureWire.Head, val bytes: ByteArray) : Event() {
            override val id get() = head.id
            override fun equals(other: Any?) = other is Done && sender == other.sender && head == other.head && bytes.contentEquals(other.bytes)
            override fun hashCode() = head.hashCode()
        }
        /** Every part arrived but the picture does not check out (size or CRC-32). */
        data class Broken(override val sender: String, override val id: Int) : Event()
        /** Nothing more arrived for [QUIET_MS]. Only pictures whose HEAD was seen are reported. */
        data class Expired(override val sender: String, override val id: Int) : Event()
    }

    private class Pending(var head: PictureWire.Head?, val parts: MutableMap<Int, ByteArray>, var touched: Long)

    private val pendingByKey = LinkedHashMap<Pair<String, Int>, Pending>()
    private val finished = LinkedHashSet<Pair<String, Int>>()

    val pending: Int get() = pendingByKey.size

    fun accept(sender: String, packet: PictureWire.Packet): List<Event> {
        val key = sender to packet.id
        if (key in finished) return emptyList()
        val entry = pendingByKey[key] ?: Pending(null, HashMap(), now()).also { makeRoom(sender); pendingByKey[key] = it }
        entry.touched = now()
        return when (packet) {
            is PictureWire.Head -> onHead(key, entry, packet)
            is PictureWire.Part -> onPart(key, entry, packet)
        }
    }

    private fun onHead(key: Pair<String, Int>, entry: Pending, head: PictureWire.Head): List<Event> {
        if (entry.head != null) return emptyList() // a resent HEAD
        entry.head = head
        entry.parts.keys.removeAll { it >= head.parts }
        return listOf(Event.Started(key.first, head, entry.parts.size)) + finishIfComplete(key, entry)
    }

    private fun onPart(key: Pair<String, Int>, entry: Pending, part: PictureWire.Part): List<Event> {
        val head = entry.head
        if (part.index in entry.parts || (head != null && part.index >= head.parts)) return emptyList()
        entry.parts[part.index] = part.data
        if (head == null) return emptyList()
        val done = finishIfComplete(key, entry)
        return done.ifEmpty { listOf(Event.Progress(key.first, key.second, entry.parts.size, head.parts)) }
    }

    private fun finishIfComplete(key: Pair<String, Int>, entry: Pending): List<Event> {
        val head = entry.head ?: return emptyList()
        if (entry.parts.size < head.parts) return emptyList()
        pendingByKey.remove(key)
        remember(key)
        val bytes = (0 until head.parts).fold(ByteArray(0)) { acc, i -> acc + entry.parts.getValue(i) }
        val partsFit = (0 until head.parts - 1).all { entry.parts.getValue(it).size == PictureWire.PART_BYTES }
        return if (partsFit && bytes.size == head.totalBytes && PictureWire.crc(bytes) == head.crc) listOf(Event.Done(key.first, head, bytes))
        else listOf(Event.Broken(key.first, key.second))
    }

    /** Ends pictures that heard nothing for [QUIET_MS]. Call on a timer. */
    fun expire(): List<Event> {
        val limit = now() - QUIET_MS
        val stale = pendingByKey.filterValues { it.touched < limit }
        stale.keys.forEach { pendingByKey.remove(it); remember(it) }
        return stale.filterValues { it.head != null }.keys.map { Event.Expired(it.first, it.second) }
    }

    private fun makeRoom(sender: String) {
        val mine = pendingByKey.filterKeys { it.first == sender }
        if (mine.size >= MAX_PER_SENDER) pendingByKey.remove(mine.minBy { it.value.touched }.key)
        if (pendingByKey.size >= MAX_TOTAL) pendingByKey.remove(pendingByKey.minBy { it.value.touched }.key)
    }

    private fun remember(key: Pair<String, Int>) {
        finished.remove(key)
        finished.add(key)
        if (finished.size > REMEMBERED) finished.remove(finished.first())
    }

    companion object {
        const val MAX_PER_SENDER = 4
        const val MAX_TOTAL = 8
        const val QUIET_MS = 10 * 60 * 1000L
        private const val REMEMBERED = 64
    }
}

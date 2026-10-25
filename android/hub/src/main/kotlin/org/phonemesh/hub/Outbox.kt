package org.phonemesh.hub

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.wire.WireV2

/** Single-Hub-owned phone-origin forwarding intents, not a mailbox rebroadcast policy. Initial forwards, and retries
 * only when a phone explicitly resubmits identical bytes (bounded by [lastAired] cooldown and [MAX_AGE] from first storage).
 * Atomic replacement + file sync covers process restart, NOT sudden power loss (directory is not fsynced).
 * A pending intent at restart may have reached serial: at most one new attempt per crash, charged again.
 * Null [file] preserves the existing in-memory constructor used by callers/tests.
 */
class Outbox(private val file: File? = null) {
    data class Entry(val phone: String, val port: Int, val bytes: ByteArray, val at: Long, val retryAt: Long = 0) {
        val id: String get() = key(port, bytes)
    }
    data class Reservation(val at: Long, val millis: Double)
    private data class State(
        val entries: List<Entry> = emptyList(), val reservations: List<Reservation> = emptyList(), val freeAt: Long = 0,
        /** Packet key -> last time a forward of it was charged to the radio. Digest and time only, no sender address. */
        val aired: Map<String, Long> = emptyMap(),
    )
    private var state = read()
    // Fault injection is before atomic rename, after the actual temporary file has been written/synced/closed.
    internal var beforeCommit: () -> Unit = {}
    val entries: List<Entry> get() = state.entries
    val reservations: List<Reservation> get() = state.reservations
    val freeAt: Long get() = state.freeAt
    fun lastAired(id: String): Long? = state.aired[id]

    fun add(entry: Entry) {
        check(state.entries.size < MAX_PENDING) { "outbox full" }
        check(state.entries.none { it.id == entry.id }) { "duplicate intent" }
        commit(state.copy(entries = state.entries + entry.copy(bytes = entry.bytes.copyOf())))
    }
    fun remove(id: String) = commit(state.copy(entries = state.entries.filterNot { it.id == id }))
    fun delay(at: Long) = commit(state.copy(freeAt = maxOf(state.freeAt, at)))
    fun retry(id: String, at: Long) = commit(state.copy(
        freeAt = maxOf(state.freeAt, at),
        entries = state.entries.map { if (it.id == id) it.copy(retryAt = at) else it }))
    /** Charges airtime; [id] (a phone packet) also records its forwarding time for the retry cooldown, in the same commit. */
    fun reserve(now: Long, millis: Double, freeAt: Long, window: Long, id: String? = null) = commit(state.copy(
        reservations = state.reservations.filter { it.at > now - window } + Reservation(now, millis), freeAt = freeAt,
        aired = if (id == null) state.aired else pruned(state.aired.filterValues { it > now - MAX_AGE } + (id to now))))

    private fun pruned(aired: Map<String, Long>): Map<String, Long> =
        if (aired.size <= MAX_AIRED) aired else aired.entries.sortedBy { it.value }.drop(aired.size - MAX_AIRED).associate { it.toPair() }

    private fun commit(next: State) {
        if (file != null) {
            file.absoluteFile.parentFile.mkdirs()
            val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { raw ->
                val out = DataOutputStream(raw)
                out.writeInt(VERSION); out.writeLong(next.freeAt); out.writeInt(next.entries.size)
                for (e in next.entries) {
                    out.writeUTF(e.phone); out.writeInt(e.port); out.writeLong(e.at); out.writeLong(e.retryAt)
                    out.writeInt(e.bytes.size); out.write(e.bytes)
                }
                out.writeInt(next.reservations.size)
                for (r in next.reservations) { out.writeLong(r.at); out.writeDouble(r.millis) }
                out.writeInt(next.aired.size)
                for ((id, at) in next.aired) { out.writeUTF(id); out.writeLong(at) }
                out.flush(); raw.fd.sync()
            }
            beforeCommit()
            // Fail closed if this filesystem does not support same-directory atomic replacement.
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else beforeCommit()
        state = next
    }

    private fun read(): State {
        if (file == null || !file.exists()) return State()
        return DataInputStream(file.inputStream().buffered()).use { input ->
            val version = input.readInt()
            check(version == 1 || version == VERSION) { "unsupported outbox" }
            val freeAt = input.readLong()
            val count = input.readInt().also { check(it in 0..MAX_PENDING) }
            val entries = List(count) {
                val phone = input.readUTF(); val port = input.readInt(); val at = input.readLong(); val retryAt = input.readLong()
                check(port == 301 || port == 303)
                val size = input.readInt().also { check(it in 1..WireV2.MAX_PACKET) }
                Entry(phone, port, ByteArray(size).also { input.readFully(it) }, at, retryAt)
            }
            check(entries.map { it.id }.toSet().size == entries.size)
            val n = input.readInt().also { check(it in 0..MAX_RESERVATIONS) }
            val reservations = List(n) {
                Reservation(input.readLong(), input.readDouble().also { check(it.isFinite() && it > 0) })
            }
            // Version 1 (rc4 and earlier) had no forwarding history: such mail gets one demanded retry, then a cooldown.
            val aired = if (version == 1) emptyMap() else {
                val m = input.readInt().also { check(it in 0..MAX_AIRED) }
                LinkedHashMap<String, Long>().apply {
                    repeat(m) { val id = input.readUTF(); check(id.startsWith("301:") || id.startsWith("303:")); put(id, input.readLong()) }
                }
            }
            check(input.read() == -1) { "trailing outbox data" }
            State(entries, reservations, freeAt, aired)
        }
    }

    companion object {
        private const val VERSION = 2
        /** About three days of a busy hub's forwards; the oldest history is dropped first beyond this. */
        const val MAX_AIRED = 20_000
        const val MAX_PENDING = 2000
        private const val MAX_RESERVATIONS = 100_000
        // Match the sender's existing three-day retry horizon; never extend to mailbox's 30 days.
        const val MAX_AGE = 3 * Mailbox.DAY
        fun key(port: Int, bytes: ByteArray): String = "$port:" + Mailbox.hex(Bytes.sha256(bytes))
    }
}

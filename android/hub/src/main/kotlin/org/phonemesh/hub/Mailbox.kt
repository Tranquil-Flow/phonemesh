package org.phonemesh.hub

import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.SharedChatWire
import org.phonemesh.core.wire.WireV2
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The hub's blind mailbox (accounts spec, "Stage 2: hub"): every accounts-port and shared-chat packet it hears or is
 * given, kept [days] days in one append-only file per UTC day, and served by recipient code. It never decrypts: a
 * message is found by the 8-byte code a phone asks for; hellos and shared-chat posts go to anyone who asks for them.
 */
class Mailbox(private val dir: File, private val days: Int) {
    enum class Kind { CODE, HELLO, SHARED }

    class Record(val seq: Long, val at: Long, val port: Int, bytes: ByteArray, val kind: Kind, val code: String?) {
        val bytes: ByteArray = bytes.copyOf()
    }

    private val records = ArrayList<Record>()
    private val seen = HashSet<String>()
    private val lock = Object()
    private var nextSeq = 1L

    init {
        require(days >= 1) { "keep mail at least a day" }
        dir.mkdirs()
        for (file in dayFiles().sortedBy { it.name }) load(file)
        nextSeq = (records.maxOfOrNull { it.seq } ?: 0) + 1
    }

    /** What kind of mail [bytes] on [port] is, with its code; null when it is not mail (wrong port or shape). */
    fun classify(port: Int, bytes: ByteArray): Pair<Kind, String?>? = when {
        bytes.isEmpty() || bytes.size > WireV2.MAX_PACKET -> null
        port == WireV2.PORT && Start.isHello(bytes) -> Kind.HELLO to null
        port == WireV2.PORT -> WireV2.parse(bytes)?.let { Kind.CODE to hex(it.header.code) }
        port == SharedChatWire.PORT -> if (SharedChatWire.decode(bytes) != null) Kind.SHARED to null else null
        else -> null
    }

    /** Stores [bytes]; the new record, or null when it is not mail or was stored already (a relay's second copy). */
    fun put(port: Int, bytes: ByteArray, now: Long): Record? {
        val (kind, code) = classify(port, bytes) ?: return null
        synchronized(lock) {
            if (!seen.add(key(port, bytes))) return null
            val record = Record(nextSeq++, now, port, bytes, kind, code)
            records += record
            append(record)
            lock.notifyAll()
            return record
        }
    }

    /** Mail after [since] (a seq) for [codes], hellos and shared-chat posts if asked; at most [limit], oldest first. */
    fun after(since: Long, codes: Set<String>, hellos: Boolean, shared: Boolean, limit: Int = 200): List<Record> = synchronized(lock) {
        var at = records.binarySearchBy(since + 1) { it.seq }.let { if (it < 0) -it - 1 else it }
        val out = ArrayList<Record>()
        while (at < records.size && out.size < limit) {
            val r = records[at++]
            val wanted = when (r.kind) {
                Kind.CODE -> r.code in codes
                Kind.HELLO -> hellos
                Kind.SHARED -> shared
            }
            if (wanted) out += r
        }
        out
    }

    /** Like [after], but waits up to [waitMillis] for mail to arrive when there is none yet (the long poll). */
    fun await(since: Long, codes: Set<String>, hellos: Boolean, shared: Boolean, waitMillis: Long): List<Record> {
        val until = System.currentTimeMillis() + waitMillis
        synchronized(lock) {
            while (true) {
                val found = after(since, codes, hellos, shared)
                val left = until - System.currentTimeMillis()
                if (found.isNotEmpty() || left <= 0) return found
                lock.wait(left)
            }
        }
    }

    /** The newest seq: a phone's starting point. */
    fun latest(): Long = synchronized(lock) { nextSeq - 1 }

    fun size(): Int = synchronized(lock) { records.size }

    /** Drops mail older than [days] days, and the day files that held only that. */
    fun expire(now: Long) = synchronized(lock) {
        val cutoff = now - days * DAY
        val gone = records.filter { it.at < cutoff }
        if (gone.isNotEmpty()) {
            records.removeAll(gone.toSet())
            gone.forEach { seen.remove(key(it.port, it.bytes)) }
        }
        val keepFrom = day(cutoff)
        for (file in dayFiles()) if (file.nameWithoutExtension < keepFrom) file.delete()
    }

    private fun append(r: Record) {
        DataOutputStream(FileOutputStream(File(dir, day(r.at) + SUFFIX), true).buffered()).use {
            it.writeLong(r.seq); it.writeLong(r.at); it.writeShort(r.port); it.writeShort(r.bytes.size); it.write(r.bytes)
        }
    }

    private fun load(file: File) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            while (true) {
                try {
                    val seq = input.readLong(); val at = input.readLong(); val port = input.readUnsignedShort()
                    val bytes = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                    val (kind, code) = classify(port, bytes) ?: continue
                    if (seen.add(key(port, bytes))) records += Record(seq, at, port, bytes, kind, code)
                } catch (_: EOFException) {
                    break // a record cut short by a crash: the rest of the file is lost, the file stays usable
                }
            }
        }
        records.sortBy { it.seq }
    }

    private fun dayFiles(): List<File> = dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.toList().orEmpty()

    private fun key(port: Int, bytes: ByteArray) = "$port:" + hex(Bytes.sha256(bytes))

    companion object {
        const val DAY = 86_400_000L
        private const val SUFFIX = ".mail"
        private val DAY_NAME = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        fun day(millis: Long): String = DAY_NAME.format(Instant.ofEpochMilli(millis))
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

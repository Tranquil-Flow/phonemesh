package org.phonemesh.core.backup

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Hkdf
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * The streaming form of [BackupFile] (encrypted chat backup spec 5.4 and 5.8): the same `PMBK0001` bytes, written and
 * read one 64 KiB chunk at a time, so full chat backups up to [BackupFile.MAX_PLAIN] need one chunk of memory, not the
 * whole file. The plaintext size goes in the header, so the writer needs it first (the spec's two-pass export or a
 * private staging file).
 */
object BackupStream {
    private val MAGIC = "PMBK0001".toByteArray(Charsets.US_ASCII)
    private val WRAP_INFO = "Phonemesh backup wrap v1".toByteArray(Charsets.US_ASCII)

    /** Seals exactly [size] bytes from [input] into [out]; the same bytes [BackupFile.seal] makes from the same random. */
    fun seal(
        key: RecoveryKey, purpose: BackupFile.Purpose, input: InputStream, size: Long, out: OutputStream,
        random: SecureRandom = SecureRandom(),
    ) {
        require(size in 1..BackupFile.MAX_PLAIN) { "plaintext of $size bytes" }
        val archiveId = ByteArray(16).also(random::nextBytes)
        val salt = ByteArray(16).also(random::nextBytes)
        val data = ByteArray(32).also(random::nextBytes)
        val wrapNonce = ByteArray(12).also(random::nextBytes)
        val chunks = ((size + BackupFile.CHUNK - 1) / BackupFile.CHUNK).toInt()
        val h0 = ByteBuffer.allocate(72).put(MAGIC).put(BackupFile.SUITE.toByte()).put(purpose.code.toByte()).putShort(0)
            .put(archiveId).put(salt).putInt(BackupFile.CHUNK).putLong(size).putInt(chunks).put(wrapNonce).array()
        val root = key.bytes()
        val wrapKey = Hkdf.derive(salt, root, WRAP_INFO + archiveId, 32)
        root.fill(0)
        val header = h0 + Aead.seal(wrapKey, wrapNonce, h0, data)
        wrapKey.fill(0)
        try {
            out.write(header)
            val buffer = ByteArray(BackupFile.CHUNK)
            for (i in 0 until chunks) {
                val length = if (i < chunks - 1) BackupFile.CHUNK else (size - i.toLong() * BackupFile.CHUNK).toInt()
                readFully(input, buffer, length)
                val sealed = Aead.seal(data, nonce(i), aad(header, i, length), buffer.copyOf(length))
                out.write(ByteBuffer.allocate(4).putInt(length).array())
                out.write(sealed)
            }
            buffer.fill(0)
            require(input.read() == -1) { "the input is longer than $size bytes" }
        } finally {
            data.fill(0)
        }
    }

    sealed class Opened {
        class Ok(val purpose: BackupFile.Purpose, val archiveId: ByteArray, val size: Long) : Opened()
        class Failed(val problem: BackupFile.Problem) : Opened()
    }

    /** The header read, the wrapped data key opened: what is needed to read the chunks. [data] is wiped by [Unwrapped.close]. */
    internal class Unwrapped(
        val header: ByteArray, val purpose: BackupFile.Purpose, val archiveId: ByteArray, val plainSize: Long, val chunks: Int,
        val data: ByteArray,
    ) {
        private val sealed = ByteArray(BackupFile.CHUNK + Aead.TAG)
        private val lengthBytes = ByteArray(4)

        /** Chunk [i], authenticated; a problem (not null) when it is cut short, the wrong length or does not authenticate. */
        fun chunk(i: Int, input: InputStream): Pair<ByteArray?, BackupFile.Problem?> {
            try { readFully(input, lengthBytes, 4) } catch (_: EOFException) { return null to BackupFile.Problem.NOT_A_BACKUP }
            val length = ByteBuffer.wrap(lengthBytes).int
            val expected = if (i < chunks - 1) BackupFile.CHUNK else (plainSize - i.toLong() * BackupFile.CHUNK).toInt()
            if (length != expected) return null to BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH
            try { readFully(input, sealed, length + Aead.TAG) } catch (_: EOFException) { return null to BackupFile.Problem.NOT_A_BACKUP }
            val part = Aead.open(data, nonce(i), aad(header, i, length), sealed.copyOf(length + Aead.TAG))
                ?: return null to BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH
            return part to null
        }

        fun close() { data.fill(0); sealed.fill(0) }
    }

    /** The public header of a copy: its purpose and archive id, read before any key work. Null when it is not a copy. */
    class Head(val purpose: BackupFile.Purpose, val archiveId: ByteArray)

    fun head(bytes: ByteArray): Head? {
        if (bytes.size < 28 || !bytes.copyOfRange(0, 8).contentEquals(MAGIC)) return null
        val purpose = BackupFile.Purpose.values().firstOrNull { it.code == (bytes[9].toInt() and 0xFF) } ?: return null
        return Head(purpose, bytes.copyOfRange(12, 28))
    }

    private fun unwrap(key: RecoveryKey, input: InputStream, maxSize: Long): Any {
        val header = ByteArray(BackupFile.HEADER)
        try { readFully(input, header, header.size) } catch (_: EOFException) { return BackupFile.Problem.NOT_A_BACKUP }
        if (!header.copyOfRange(0, 8).contentEquals(MAGIC)) return BackupFile.Problem.NOT_A_BACKUP
        val b = ByteBuffer.wrap(header)
        b.position(8)
        val suite = b.get().toInt() and 0xFF
        val purposeCode = b.get().toInt() and 0xFF
        val purpose = BackupFile.Purpose.values().firstOrNull { it.code == purposeCode }
        val flags = b.short.toInt()
        if (suite != BackupFile.SUITE || purpose == null || flags != 0) return BackupFile.Problem.UNSUPPORTED
        val archiveId = ByteArray(16).also { b.get(it) }
        val salt = ByteArray(16).also { b.get(it) }
        val chunkSize = b.int
        val plainSize = b.long
        val chunks = b.int.toLong() and 0xFFFFFFFFL
        if (chunkSize != BackupFile.CHUNK) return BackupFile.Problem.UNSUPPORTED
        if (plainSize < 1 || plainSize > BackupFile.MAX_PLAIN || chunks < 1 || chunks > BackupFile.MAX_CHUNKS) return BackupFile.Problem.NOT_A_BACKUP
        if (chunks != (plainSize + BackupFile.CHUNK - 1) / BackupFile.CHUNK) return BackupFile.Problem.NOT_A_BACKUP
        if (plainSize > maxSize) return BackupFile.Problem.TOO_BIG
        val wrapNonce = ByteArray(12).also { b.get(it) }
        val wrapped = ByteArray(48).also { b.get(it) }
        val root = key.bytes()
        val wrapKey = Hkdf.derive(salt, root, WRAP_INFO + archiveId, 32)
        root.fill(0)
        val data = Aead.open(wrapKey, wrapNonce, header.copyOfRange(0, 72), wrapped)
        wrapKey.fill(0)
        if (data == null || data.size != 32) return BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH
        return Unwrapped(header, purpose, archiveId, plainSize, chunks.toInt(), data)
    }

    /**
     * Opens a copy from [input], giving each authenticated chunk to [sink] in order. Only [Opened.Ok] at the end means
     * the whole file was right: on [Opened.Failed] the caller throws away whatever [sink] received (spec 5.7: staging,
     * then one commit). [maxSize] caps what a caller accepts before any key work.
     */
    fun open(key: RecoveryKey, input: InputStream, maxSize: Long = BackupFile.MAX_PLAIN, sink: (ByteArray) -> Unit): Opened {
        val u = when (val r = unwrap(key, input, maxSize)) { is BackupFile.Problem -> return fail(r); else -> r as Unwrapped }
        try {
            for (i in 0 until u.chunks) {
                val (part, problem) = u.chunk(i, input)
                if (part == null) return fail(problem!!)
                sink(part)
            }
        } finally {
            u.close()
        }
        if (input.read() != -1) return fail(BackupFile.Problem.NOT_A_BACKUP)
        return Opened.Ok(u.purpose, u.archiveId, u.plainSize)
    }

    /**
     * What went wrong inside a [Plain] stream, thrown from its reads (a RuntimeException: [HistoryBackup.read] answers
     * null). Once thrown, every later read throws the same one: a broken stream never looks like a clean end (B4-15).
     */
    class Broken(val problem: BackupFile.Problem) : RuntimeException(problem.name)

    /**
     * The plaintext of a copy as a stream, one authenticated chunk at a time, so a parser reads it without threads or a
     * pipe. Any problem, at the end too (a cut file, bytes after the last chunk), is thrown as [Broken] from the read
     * that meets it: a parser that reads to the end has seen the whole file checked.
     */
    class Plain internal constructor(private val u: Unwrapped, private val input: InputStream) : java.io.InputStream() {
        val purpose: BackupFile.Purpose get() = u.purpose
        val archiveId: ByteArray get() = u.archiveId.copyOf()
        val size: Long get() = u.plainSize
        private var next = 0
        private var chunk: ByteArray = ByteArray(0)
        private var at = 0
        private var finished = false
        private var broken: Broken? = null

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(into: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at >= chunk.size) {
                broken?.let { throw it }
                if (finished) return -1
                if (next == u.chunks) {
                    finished = true
                    u.close()
                    if (input.read() != -1) throw Broken(BackupFile.Problem.NOT_A_BACKUP).also { broken = it }
                    return -1
                }
                val (part, problem) = u.chunk(next, input)
                if (part == null) { finished = true; u.close(); throw Broken(problem!!).also { broken = it } }
                chunk = part; at = 0; next++
            }
            val n = minOf(len, chunk.size - at)
            chunk.copyInto(into, off, at, at + n)
            at += n
            return n
        }

        override fun close() { finished = true; u.close() }
    }

    sealed class Streamed {
        class Ok(val stream: Plain) : Streamed()
        class Failed(val problem: BackupFile.Problem) : Streamed()
    }

    /** Opens [input] as a [Plain] stream; every header problem and a key that does not match are answered here. */
    fun openStream(key: RecoveryKey, input: InputStream, maxSize: Long = BackupFile.MAX_PLAIN): Streamed =
        when (val r = unwrap(key, input, maxSize)) { is BackupFile.Problem -> Streamed.Failed(r); else -> Streamed.Ok(Plain(r as Unwrapped, input)) }

    private fun fail(p: BackupFile.Problem) = Opened.Failed(p)

    private fun readFully(input: InputStream, into: ByteArray, length: Int) {
        var at = 0
        while (at < length) {
            val n = input.read(into, at, length - at)
            if (n < 0) throw EOFException()
            at += n
        }
    }

    private fun nonce(i: Int): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(i.toLong()).array()

    private fun aad(header: ByteArray, i: Int, length: Int): ByteArray =
        ByteBuffer.allocate(BackupFile.HEADER + 8).put(header).putInt(i).putInt(length).array()
}

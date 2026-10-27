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

    /**
     * Opens a copy from [input], giving each authenticated chunk to [sink] in order. Only [Opened.Ok] at the end means
     * the whole file was right: on [Opened.Failed] the caller throws away whatever [sink] received (spec 5.7: staging,
     * then one commit). [maxSize] caps what a caller accepts before any key work.
     */
    fun open(key: RecoveryKey, input: InputStream, maxSize: Long = BackupFile.MAX_PLAIN, sink: (ByteArray) -> Unit): Opened {
        val header = ByteArray(BackupFile.HEADER)
        try { readFully(input, header, header.size) } catch (_: EOFException) { return fail(BackupFile.Problem.NOT_A_BACKUP) }
        if (!header.copyOfRange(0, 8).contentEquals(MAGIC)) return fail(BackupFile.Problem.NOT_A_BACKUP)
        val b = ByteBuffer.wrap(header)
        b.position(8)
        val suite = b.get().toInt() and 0xFF
        val purposeCode = b.get().toInt() and 0xFF
        val purpose = BackupFile.Purpose.values().firstOrNull { it.code == purposeCode }
        val flags = b.short.toInt()
        if (suite != BackupFile.SUITE || purpose == null || flags != 0) return fail(BackupFile.Problem.UNSUPPORTED)
        val archiveId = ByteArray(16).also { b.get(it) }
        val salt = ByteArray(16).also { b.get(it) }
        val chunkSize = b.int
        val plainSize = b.long
        val chunks = b.int.toLong() and 0xFFFFFFFFL
        if (chunkSize != BackupFile.CHUNK) return fail(BackupFile.Problem.UNSUPPORTED)
        if (plainSize < 1 || plainSize > BackupFile.MAX_PLAIN || chunks < 1 || chunks > BackupFile.MAX_CHUNKS) return fail(BackupFile.Problem.NOT_A_BACKUP)
        if (chunks != (plainSize + BackupFile.CHUNK - 1) / BackupFile.CHUNK) return fail(BackupFile.Problem.NOT_A_BACKUP)
        if (plainSize > maxSize) return fail(BackupFile.Problem.TOO_BIG)
        val wrapNonce = ByteArray(12).also { b.get(it) }
        val wrapped = ByteArray(48).also { b.get(it) }
        val root = key.bytes()
        val wrapKey = Hkdf.derive(salt, root, WRAP_INFO + archiveId, 32)
        root.fill(0)
        val data = Aead.open(wrapKey, wrapNonce, header.copyOfRange(0, 72), wrapped)
        wrapKey.fill(0)
        if (data == null || data.size != 32) return fail(BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH)
        val sealed = ByteArray(BackupFile.CHUNK + Aead.TAG)
        val lengthBytes = ByteArray(4)
        try {
            for (i in 0 until chunks.toInt()) {
                try { readFully(input, lengthBytes, 4) } catch (_: EOFException) { return fail(BackupFile.Problem.NOT_A_BACKUP) }
                val length = ByteBuffer.wrap(lengthBytes).int
                val expected = if (i < chunks - 1) BackupFile.CHUNK else (plainSize - i.toLong() * BackupFile.CHUNK).toInt()
                if (length != expected) return fail(BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH)
                try { readFully(input, sealed, length + Aead.TAG) } catch (_: EOFException) { return fail(BackupFile.Problem.NOT_A_BACKUP) }
                val part = Aead.open(data, nonce(i), aad(header, i, length), sealed.copyOf(length + Aead.TAG))
                    ?: return fail(BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH)
                sink(part)
            }
        } finally {
            data.fill(0)
        }
        if (input.read() != -1) return fail(BackupFile.Problem.NOT_A_BACKUP)
        return Opened.Ok(purpose, archiveId, plainSize)
    }

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

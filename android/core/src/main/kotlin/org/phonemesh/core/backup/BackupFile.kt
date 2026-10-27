package org.phonemesh.core.backup

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Hkdf
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * A saved copy's sealed container, `PMBK0001` (encrypted chat backup spec 5.3-5.4, BK5 A). A 120-byte header with a
 * fresh random archive id, HKDF salt and data key per file; the data key wrapped under HKDF(recovery key); the
 * plaintext in 64 KiB chunks, each AES-256-GCM sealed with the whole header and its index as associated data. File
 * size is exactly `120 + P + 20 * N`. Never sent over radio.
 *
 * This version works in memory and caps files at [MAX_IN_MEMORY]: a contacts-only copy is tens of kilobytes. Full
 * chat backups use [BackupStream], the same bytes one chunk at a time, up to the 512 MiB format limit.
 */
object BackupFile {
    const val HEADER = 120
    const val CHUNK = 65_536
    const val SUITE = 1
    const val MAX_PLAIN = 536_870_912L
    const val MAX_CHUNKS = 8_192
    const val MAX_IN_MEMORY = 16 * 1024 * 1024
    private val MAGIC = "PMBK0001".toByteArray(Charsets.US_ASCII)
    private val WRAP_INFO = "Phonemesh backup wrap v1".toByteArray(Charsets.US_ASCII)

    enum class Purpose(val code: Int) { CONTACTS(0), HISTORY(1) }

    /** Why a file did not open. A wrong key and changed bytes are one answer: they cannot be told apart. */
    enum class Problem { NOT_A_BACKUP, UNSUPPORTED, TOO_BIG, KEY_OR_FILE_DOES_NOT_MATCH }

    sealed class Opened {
        class Ok(val purpose: Purpose, val archiveId: ByteArray, val plain: ByteArray) : Opened()
        class Failed(val problem: Problem) : Opened()
    }

    /** Seals [plain] for [purpose] under [key]: a new id, salt, data key and nonce every call, never reused. */
    fun seal(key: RecoveryKey, purpose: Purpose, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(plain.isNotEmpty() && plain.size <= MAX_IN_MEMORY) { "plaintext of ${plain.size} bytes" }
        val archiveId = ByteArray(16).also(random::nextBytes)
        val salt = ByteArray(16).also(random::nextBytes)
        val data = ByteArray(32).also(random::nextBytes)
        val wrapNonce = ByteArray(12).also(random::nextBytes)
        val chunks = (plain.size + CHUNK - 1) / CHUNK
        val h0 = ByteBuffer.allocate(72).put(MAGIC).put(SUITE.toByte()).put(purpose.code.toByte()).putShort(0)
            .put(archiveId).put(salt).putInt(CHUNK).putLong(plain.size.toLong()).putInt(chunks).put(wrapNonce).array()
        val root = key.bytes()
        val wrapKey = Hkdf.derive(salt, root, WRAP_INFO + archiveId, 32)
        root.fill(0)
        val header = h0 + Aead.seal(wrapKey, wrapNonce, h0, data)
        wrapKey.fill(0)
        val out = java.io.ByteArrayOutputStream(HEADER + plain.size + 20 * chunks)
        out.write(header)
        for (i in 0 until chunks) {
            val from = i * CHUNK
            val part = plain.copyOfRange(from, minOf(plain.size, from + CHUNK))
            val sealed = Aead.seal(data, nonce(i), aad(header, i, part.size), part)
            out.write(ByteBuffer.allocate(4).putInt(part.size).array())
            out.write(sealed)
        }
        data.fill(0)
        return out.toByteArray()
    }

    /** Opens [file] with [key]: every check before any plaintext is returned, and nothing partial. */
    fun open(key: RecoveryKey, file: ByteArray): Opened {
        if (file.size < HEADER || !file.copyOfRange(0, 8).contentEquals(MAGIC)) return Opened.Failed(Problem.NOT_A_BACKUP)
        val b = ByteBuffer.wrap(file)
        b.position(8)
        val suite = b.get().toInt() and 0xFF
        val purposeCode = b.get().toInt() and 0xFF
        val purpose = Purpose.values().firstOrNull { it.code == purposeCode }
        val flags = b.short.toInt()
        if (suite != SUITE || purpose == null || flags != 0) return Opened.Failed(Problem.UNSUPPORTED)
        val archiveId = ByteArray(16).also { b.get(it) }
        val salt = ByteArray(16).also { b.get(it) }
        val chunkSize = b.int
        val plainSize = b.long
        val chunks = b.int.toLong() and 0xFFFFFFFFL
        if (chunkSize != CHUNK) return Opened.Failed(Problem.UNSUPPORTED)
        if (plainSize < 1 || plainSize > MAX_PLAIN || chunks < 1 || chunks > MAX_CHUNKS) return Opened.Failed(Problem.NOT_A_BACKUP)
        if (chunks != (plainSize + CHUNK - 1) / CHUNK) return Opened.Failed(Problem.NOT_A_BACKUP)
        if (file.size.toLong() != HEADER + plainSize + 20 * chunks) return Opened.Failed(Problem.NOT_A_BACKUP)
        if (plainSize > MAX_IN_MEMORY) return Opened.Failed(Problem.TOO_BIG)
        val wrapNonce = ByteArray(12).also { b.get(it) }
        val wrapped = ByteArray(48).also { b.get(it) }
        val header = file.copyOfRange(0, HEADER)
        val root = key.bytes()
        val wrapKey = Hkdf.derive(salt, root, WRAP_INFO + archiveId, 32)
        root.fill(0)
        val data = Aead.open(wrapKey, wrapNonce, header.copyOfRange(0, 72), wrapped)
        wrapKey.fill(0)
        if (data == null || data.size != 32) return Opened.Failed(Problem.KEY_OR_FILE_DOES_NOT_MATCH)
        val plain = ByteArray(plainSize.toInt())
        var at = 0
        try {
            for (i in 0 until chunks.toInt()) {
                val length = b.int
                val expected = if (i < chunks - 1) CHUNK else (plainSize - i.toLong() * CHUNK).toInt()
                if (length != expected) return Opened.Failed(Problem.KEY_OR_FILE_DOES_NOT_MATCH)
                val sealed = ByteArray(length + Aead.TAG).also { b.get(it) }
                val part = Aead.open(data, nonce(i), aad(header, i, length), sealed)
                    ?: return Opened.Failed(Problem.KEY_OR_FILE_DOES_NOT_MATCH)
                part.copyInto(plain, at)
                at += length
            }
        } finally {
            data.fill(0)
        }
        if (b.hasRemaining() || at.toLong() != plainSize) return Opened.Failed(Problem.NOT_A_BACKUP)
        return Opened.Ok(purpose, archiveId, plain)
    }

    private fun nonce(i: Int): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(i.toLong()).array()

    private fun aad(header: ByteArray, i: Int, length: Int): ByteArray =
        ByteBuffer.allocate(HEADER + 8).put(header).putInt(i).putInt(length).array()
}

package org.phonemesh.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** The streaming container: same bytes as the in-memory one and the independent vectors, one chunk of memory. */
class BackupStreamTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/backup/backup-vectors.txt")!!.bufferedReader(Charsets.UTF_8)
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val root = RecoveryKey.of(hex(v.getValue("root")))

    private class Fixed(vararg chunks: ByteArray) : SecureRandom() {
        private val queue = ArrayDeque(chunks.toList())
        override fun nextBytes(bytes: ByteArray) { queue.removeFirst().also { require(it.size == bytes.size) }.copyInto(bytes) }
    }
    private fun fixed() = Fixed(ByteArray(16) { 0xA1.toByte() }, ByteArray(16) { 0x5A }, ByteArray(32) { it.toByte() }, ByteArray(12) { 0x77 })

    private fun sealStream(purpose: BackupFile.Purpose, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArrayOutputStream().also { BackupStream.seal(root, purpose, ByteArrayInputStream(plain), plain.size.toLong(), it, random) }.toByteArray()

    private fun openStream(file: ByteArray, key: RecoveryKey = root, max: Long = BackupFile.MAX_PLAIN): Pair<BackupStream.Opened, ByteArray> {
        val got = ByteArrayOutputStream()
        return BackupStream.open(key, ByteArrayInputStream(file), max) { got.write(it) } to got.toByteArray()
    }

    @Test fun streamingWritesTheIndependentFilesByteForByte() {
        assertArrayEquals(hex(v.getValue("small_file")), sealStream(BackupFile.Purpose.CONTACTS, hex(v.getValue("small_plain")), fixed()))
        val big = ByteArray(65_536 + 1000) { ((it * 7 + 3) and 0xFF).toByte() }
        val file = sealStream(BackupFile.Purpose.HISTORY, big, fixed())
        assertEquals(v.getValue("big_sha256"), MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) })
        val (opened, plain) = openStream(file)
        assertTrue(opened is BackupStream.Opened.Ok)
        assertArrayEquals(big, plain)
    }

    @Test fun eachFormOpensTheOther() {
        val plain = ByteArray(200_000) { (it % 251).toByte() }
        val inMemory = BackupFile.seal(root, BackupFile.Purpose.HISTORY, plain)
        assertArrayEquals(plain, openStream(inMemory).second)
        val streamed = sealStream(BackupFile.Purpose.HISTORY, plain)
        assertArrayEquals(plain, (BackupFile.open(root, streamed) as BackupFile.Opened.Ok).plain)
    }

    /** 40 MiB through one 64 KiB chunk at a time: past the in-memory cap, never held whole. */
    @Test fun aCopyPastTheInMemoryCapStreamsBothWays() {
        val size = 40L * 1024 * 1024 + 123
        val pattern = { i: Long -> ((i * 31 + 7) and 0xFF).toInt() }
        val source = object : InputStream() {
            var at = 0L
            override fun read(): Int = if (at >= size) -1 else pattern(at++)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= size) return -1
                val n = minOf(len.toLong(), size - at).toInt()
                for (k in 0 until n) b[off + k] = pattern(at + k).toByte()
                at += n; return n
            }
        }
        val file = java.io.File.createTempFile("pmbk", ".bin").apply { deleteOnExit() }
        file.outputStream().buffered().use { BackupStream.seal(root, BackupFile.Purpose.HISTORY, source, size, it) }
        assertEquals(120 + size + 20 * ((size + 65_535) / 65_536), file.length())
        var seen = 0L
        var mismatch = false
        val opened = file.inputStream().buffered().use { input ->
            BackupStream.open(root, input) { part ->
                for (k in part.indices) if (part[k] != pattern(seen + k).toByte()) mismatch = true
                seen += part.size
            }
        }
        assertTrue(opened is BackupStream.Opened.Ok)
        assertEquals(size, seen)
        assertTrue(!mismatch)
        assertEquals("the in-memory reader says too big", BackupFile.Problem.TOO_BIG,
            (BackupFile.open(root, file.readBytes()) as BackupFile.Opened.Failed).problem)
        assertEquals("a caller's own cap comes before any key work", BackupFile.Problem.TOO_BIG,
            (file.inputStream().use { BackupStream.open(root, it, maxSize = 1024) {} } as BackupStream.Opened.Failed).problem)
        file.delete()
    }

    @Test fun everyFlippedBitCutAndExtraByteFails() {
        val file = hex(v.getValue("small_file"))
        for (i in file.indices) for (bit in 0..7) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertTrue("byte $i bit $bit", openStream(bad).first is BackupStream.Opened.Failed)
        }
        for (n in 0 until file.size) assertTrue("cut at $n", openStream(file.copyOf(n)).first is BackupStream.Opened.Failed)
        assertEquals(BackupFile.Problem.NOT_A_BACKUP, (openStream(file + 0).first as BackupStream.Opened.Failed).problem)
        val other = RecoveryKey.of(ByteArray(16) { 9 })
        assertEquals(BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH, (openStream(file, other).first as BackupStream.Opened.Failed).problem)
    }

    @Test fun anInputOfTheWrongLengthIsRefused() {
        val out = OutputStream.nullOutputStream()
        assertTrue(runCatching { BackupStream.seal(root, BackupFile.Purpose.CONTACTS, ByteArrayInputStream(ByteArray(10)), 11, out) }.isFailure)
        assertTrue(runCatching { BackupStream.seal(root, BackupFile.Purpose.CONTACTS, ByteArrayInputStream(ByteArray(12)), 11, out) }.isFailure)
        assertTrue(runCatching { BackupStream.seal(root, BackupFile.Purpose.CONTACTS, ByteArrayInputStream(ByteArray(0)), 0, out) }.isFailure)
    }

    // ---- The plaintext as a pull stream: the parser reads it, a problem anywhere is thrown from a read.

    private fun plainOf(file: ByteArray, key: RecoveryKey = root): Result<ByteArray> {
        val opened = BackupStream.openStream(key, ByteArrayInputStream(file))
        if (opened !is BackupStream.Streamed.Ok) return Result.failure(IllegalStateException((opened as BackupStream.Streamed.Failed).problem.name))
        return runCatching { opened.stream.readBytes() }
    }

    @Test fun aPlainStreamGivesTheSameBytesAsTheCallbackForm() {
        val big = ByteArray(65_536 * 2 + 5) { ((it * 11 + 1) and 0xFF).toByte() }
        val file = sealStream(BackupFile.Purpose.HISTORY, big)
        assertArrayEquals(big, plainOf(file).getOrThrow())
        val one = (BackupStream.openStream(root, ByteArrayInputStream(file)) as BackupStream.Streamed.Ok).stream
        assertEquals(big[0].toInt() and 0xFF, one.read())
        assertEquals(BackupFile.Purpose.HISTORY, one.purpose)
        assertEquals(big.size.toLong(), one.size)
    }

    @Test fun aPlainStreamRefusesEveryBreakAndTheEndOfTheFileTooAsAFileAndAKey() {
        val plain = ByteArray(65_536 + 40) { (it % 97).toByte() }
        val file = sealStream(BackupFile.Purpose.HISTORY, plain)
        for (i in 0 until BackupFile.HEADER + 8) for (bit in 0..7) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertTrue("header byte $i bit $bit", plainOf(bad).isFailure)
        }
        for (i in listOf(BackupFile.HEADER, BackupFile.HEADER + 3, BackupFile.HEADER + 100, 120 + 4 + 65_536 + 16 + 2, file.size - 1, file.size - 20)) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertTrue("byte $i", plainOf(bad).isFailure)
        }
        for (n in listOf(0, 10, 119, 120, 121, 130, 200, 65_536 + 130, file.size - 17, file.size - 1)) assertTrue("cut $n", plainOf(file.copyOf(n)).isFailure)
        assertTrue("a byte after the last chunk", plainOf(file + 0).isFailure)
        assertTrue("another key", plainOf(file, RecoveryKey.generate()).isFailure)
        assertArrayEquals(plain, plainOf(file).getOrThrow())
    }

    @Test fun aPlainStreamNamesTheProblemAsTheCallbackFormDoes() {
        val file = sealStream(BackupFile.Purpose.CONTACTS, ByteArray(100) { 1 })
        val wrong = BackupStream.openStream(RecoveryKey.generate(), ByteArrayInputStream(file)) as BackupStream.Streamed.Failed
        assertEquals(BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH, wrong.problem)
        assertEquals(BackupFile.Problem.NOT_A_BACKUP, (BackupStream.openStream(root, ByteArrayInputStream("hello".toByteArray())) as BackupStream.Streamed.Failed).problem)
        assertEquals(BackupFile.Problem.TOO_BIG, (BackupStream.openStream(root, ByteArrayInputStream(file), maxSize = 50) as BackupStream.Streamed.Failed).problem)
        val stream = (BackupStream.openStream(root, ByteArrayInputStream(file + 0)) as BackupStream.Streamed.Ok).stream
        val e = runCatching { stream.readBytes() }.exceptionOrNull() as BackupStream.Broken
        assertEquals(BackupFile.Problem.NOT_A_BACKUP, e.problem)
    }

    @Test fun theHeadShowsThePurposeAndIdBeforeAnyKeyWork() {
        val file = sealStream(BackupFile.Purpose.HISTORY, ByteArray(10) { 2 })
        val head = BackupStream.head(file.copyOf(120))!!
        assertEquals(BackupFile.Purpose.HISTORY, head.purpose)
        assertArrayEquals(file.copyOfRange(12, 28), head.archiveId)
        assertEquals(null, BackupStream.head("hello, this is not a saved copy at all".toByteArray()))
        assertEquals(null, BackupStream.head(file.copyOf(27)))
    }
}

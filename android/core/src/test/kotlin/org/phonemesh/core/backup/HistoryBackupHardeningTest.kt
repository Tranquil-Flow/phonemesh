package org.phonemesh.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Review B4: the reader refuses what spec 5.5 forbids (B4-10), tells a storage failure from a bad file (B4-1) and a newer copy from a wrong key (B4-14). */
class HistoryBackupHardeningTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/backup/history-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun manifest(flags: Int, chats: Int, messages: Int, reactions: Int = 0, pictures: Int = 0, scopeStart: Long = 0, scopeEnd: Long = 0) =
        HistoryBackup.Manifest(hex("me"), 1, 1, "Awa", 0, scopeStart, scopeEnd, flags, 1, chats, messages, reactions, pictures, 0)

    private fun contact() = HistoryBackup.Contact(ContactsBackup.Contact(1, hex("kofi"), hex("kofi"), "Kofi", 0, false, null, 1))
    private val chat = HistoryBackup.Chat(1, HistoryBackup.PERSON, 1, "Kofi", false)

    private fun bytes(m: HistoryBackup.Manifest, rs: List<HistoryBackup.Record>): ByteArray =
        ByteArrayOutputStream().also { out -> HistoryBackup.Writer(out, m).apply { rs.forEach(::write); finish() } }.toByteArray()

    /** Accepted by the writer and by the reader; the writer's refusal and the reader's must agree (one rule set). */
    private fun accepted(m: HistoryBackup.Manifest, rs: List<HistoryBackup.Record>): Boolean {
        val written = runCatching { bytes(m, rs) }.getOrNull() ?: return false
        return HistoryBackup.read(ByteArrayInputStream(written)) != null
    }

    private fun msg(id: Long, out: Boolean, status: Int, planned: Long = 0, time: Long = 5, label: String? = null, picture: Long = 0, text: String = "hi") =
        HistoryBackup.Message(id, 1, out, 0, label, text, null, time, status, 0, false, planned, picture)

    @Test fun plannedAtGoesOnlyWithAnUnsentMessage() {
        assertTrue(accepted(manifest(4, 1, 1), listOf(contact(), chat, msg(1, true, 5, planned = 99))))
        assertTrue(accepted(manifest(4, 1, 1), listOf(contact(), chat, msg(1, true, 5, planned = 0))))
        assertTrue("received", !accepted(manifest(0, 1, 1), listOf(contact(), chat, msg(1, false, 0, planned = 99))))
        assertTrue("sent", !accepted(manifest(0, 1, 1), listOf(contact(), chat, msg(1, true, 1, planned = 99))))
        assertTrue("delivered", !accepted(manifest(0, 1, 1), listOf(contact(), chat, msg(1, true, 3, planned = 99))))
        assertTrue("unconfirmed", !accepted(manifest(4, 1, 1), listOf(contact(), chat, msg(1, true, 6, planned = 99))))
    }

    @Test fun aReactionWasUnsentNeedsTheUnsentScope() {
        val r = HistoryBackup.Reaction(1, 1, 0, null, "👍", 0, true)
        assertTrue(!accepted(manifest(0, 1, 1, 1), listOf(contact(), chat, msg(1, false, 0), r)))
        assertTrue(accepted(manifest(4, 1, 1, 1), listOf(contact(), chat, msg(1, false, 0), r)))
    }

    @Test fun timesStayInsideTheScopeWhenOneIsSet() {
        val scoped = manifest(0, 1, 1, scopeStart = 100, scopeEnd = 200)
        assertTrue(accepted(scoped, listOf(contact(), chat, msg(1, false, 0, time = 100))))
        assertTrue(accepted(scoped, listOf(contact(), chat, msg(1, false, 0, time = 200))))
        assertTrue("before", !accepted(scoped, listOf(contact(), chat, msg(1, false, 0, time = 99))))
        assertTrue("after", !accepted(scoped, listOf(contact(), chat, msg(1, false, 0, time = 201))))
        assertTrue("far after", !accepted(scoped, listOf(contact(), chat, msg(1, false, 0, time = Long.MAX_VALUE))))
        val withReaction = manifest(0, 1, 1, 1, scopeStart = 100, scopeEnd = 200)
        assertTrue(!accepted(withReaction, listOf(contact(), chat, msg(1, false, 0, time = 150), HistoryBackup.Reaction(1, 1, 0, null, "👍", 300, false))))
        assertTrue(accepted(withReaction, listOf(contact(), chat, msg(1, false, 0, time = 150), HistoryBackup.Reaction(1, 1, 0, null, "👍", 160, false))))
        assertTrue("all history has no range", accepted(manifest(0, 1, 1), listOf(contact(), chat, msg(1, false, 0, time = Long.MAX_VALUE))))
    }

    @Test fun theSameReactionTwiceIsRefused() {
        val m = manifest(0, 1, 1, 2)
        val a = HistoryBackup.Reaction(1, 1, 0, null, "👍", 0, false)
        assertTrue(!accepted(m, listOf(contact(), chat, msg(1, false, 0), a, HistoryBackup.Reaction(2, 1, 0, null, "👍", 0, false))))
        assertTrue("another emoji", accepted(m, listOf(contact(), chat, msg(1, false, 0), a, HistoryBackup.Reaction(2, 1, 0, null, "❤️", 0, false))))
        assertTrue("another author", accepted(m, listOf(contact(), chat, msg(1, false, 0), a, HistoryBackup.Reaction(2, 1, 1, null, "👍", 0, false))))
        assertTrue("another label", accepted(m, listOf(contact(), chat, msg(1, false, 0), a, HistoryBackup.Reaction(2, 1, 0, "Yaw", "👍", 0, false))))
    }

    @Test fun oneSharedChatAndAGroupBitNeedsAGroup() {
        val shared = { id: Long -> HistoryBackup.Chat(id, HistoryBackup.SHARED_CHAT, 0, "a", false) }
        assertTrue(accepted(manifest(1, 1, 0), listOf(contact(), shared(1))))
        assertTrue(!accepted(manifest(1, 2, 0), listOf(contact(), shared(1), shared(2))))
        assertTrue(!accepted(manifest(16, 0, 0), listOf(contact())))
        assertTrue(accepted(manifest(16, 1, 0), listOf(contact(), HistoryBackup.Chat(1, HistoryBackup.GROUP_CHAT, 0, "Famille", false))))
    }

    @Test fun titlesAndLabelsHaveNoHiddenOrReorderingCharacters() {
        val m = manifest(0, 1, 1)
        for (bad in listOf("Ali\u202Eecivres", "a\u200Bb", "a\u0085b", "a\u0080b", "a\u2028b", "a\u0007b", "a\uE000b")) {
            assertTrue("label ${bad.map { it.code }}", !accepted(m, listOf(contact(), chat, msg(1, false, 0, label = bad))))
            assertTrue("title ${bad.map { it.code }}", !accepted(m, listOf(contact(), HistoryBackup.Chat(1, HistoryBackup.PERSON, 1, bad, false), msg(1, false, 0))))
            assertTrue("reaction label ${bad.map { it.code }}", !accepted(manifest(0, 1, 1, 1), listOf(contact(), chat, msg(1, false, 0), HistoryBackup.Reaction(1, 1, 0, bad, "👍", 0, false))))
        }
        assertTrue("an ordinary label, an empty one and an RTL name", listOf("Yaw", "", "مريم").all { ok ->
            accepted(m, listOf(contact(), chat, msg(1, false, 0, label = ok)))
        })
        assertTrue("an empty title is allowed for a person chat", accepted(m, listOf(contact(), HistoryBackup.Chat(1, HistoryBackup.PERSON, 1, "", false), msg(1, false, 0))))
        assertTrue("C1 in message text", !accepted(m, listOf(contact(), chat, msg(1, false, 0, text = "a\u0085b"))))
    }

    private fun pictureCopy(data: ByteArray, w: Int = 64, h: Int = 48): Boolean {
        val m = manifest(8, 1, 1, 0, 1)
        return accepted(m, listOf(contact(), chat, msg(1, false, 0, picture = 1, text = ""), HistoryBackup.Picture(1, 1, true, 1, w, h, ByteArray(4) { 1 }, data)))
    }

    @Test fun aPictureMustBeAWellFormedWebpOfItsStatedSize() {
        assertTrue(pictureCopy(hex("picture")))
        assertTrue("lossy", pictureCopy(hex("webp_lossy_40x30"), 40, 30))
        assertTrue("extended", pictureCopy(hex("webp_extended_16x9"), 16, 9))
        assertTrue("a bare 12-byte header", !pictureCopy("RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBP".toByteArray(), 512, 512))
        assertTrue("size differs from the record", !pictureCopy(hex("picture"), 65, 48))
        assertTrue("size differs from the record (other way)", !pictureCopy(hex("webp_lossy_40x30"), 40, 31))
        assertTrue("the record says 512 but the header says 64 x 48", !pictureCopy(hex("picture"), 512, 512))
    }

    @Test fun webpStructureIsCheckedChunkByChunk() {
        val ok = hex("picture")
        assertEquals(64 to 48, WebpCheck.dimensions(ok))
        assertEquals(40 to 30, WebpCheck.dimensions(hex("webp_lossy_40x30")))
        assertEquals(16 to 9, WebpCheck.dimensions(hex("webp_extended_16x9")))
        // RIFF length that does not match, a cut file, bytes after the last chunk, a wrong chunk tag or an impossible chunk size.
        assertNull(WebpCheck.dimensions(ok + 0))
        assertNull("a RIFF length that is not the file's", WebpCheck.dimensions(ok.copyOf().also { it[4] = (it[4] - 2).toByte() }))
        for (n in 0 until ok.size) assertNull("cut at $n", WebpCheck.dimensions(ok.copyOf(n)))
        assertNull(WebpCheck.dimensions(ok.copyOf().also { it[12] = 'X'.code.toByte() }))
        assertNull(WebpCheck.dimensions(ok.copyOf().also { it[16] = 0x7F }))
        // The lossless signature, the version bits and the lossy start code.
        assertNull(WebpCheck.dimensions(ok.copyOf().also { it[20] = 0x2E }))
        assertNull(WebpCheck.dimensions(ok.copyOf().also { it[24] = (it[24].toInt() or 0x20).toByte() }))
        val lossy = hex("webp_lossy_40x30")
        assertNull(WebpCheck.dimensions(lossy.copyOf().also { it[23] = 0 }))
        assertNull("not a key frame", WebpCheck.dimensions(lossy.copyOf().also { it[20] = (it[20].toInt() or 1).toByte() }))
        // An extended file whose canvas differs from its image, and an animated one.
        val extended = hex("webp_extended_16x9")
        assertNull(WebpCheck.dimensions(extended.copyOf().also { it[24] = (it[24] + 1).toByte() }))
        assertNull(WebpCheck.dimensions(extended.copyOf().also { it[20] = 0x02 }))
    }

    @Test fun aSinkOrIndexFailureIsNotAVerdictOnTheFile() {
        val stream = hex("stream")
        class Disk : RuntimeException("disk full")
        val thrown = assertThrows(Disk::class.java) { HistoryBackup.read(ByteArrayInputStream(stream)) { throw Disk() } }
        assertEquals("disk full", thrown.message)
        val failing = object : HistoryBackup.Index by HistoryBackup.MemoryIndex() {
            override fun addChat(id: Long, contactId: Long): Boolean = throw Disk()
        }
        assertThrows(Disk::class.java) { HistoryBackup.read(ByteArrayInputStream(stream), failing) }
        assertNotNull("without a failure the same copy reads", HistoryBackup.read(ByteArrayInputStream(stream)))
        assertNull("a bad file is still null", HistoryBackup.read(ByteArrayInputStream(stream.copyOf(stream.size - 1)), failing))
    }

    @Test fun aCancellationFromTheSinkPassesThroughUnchanged() {
        val cancel = java.util.concurrent.CancellationException("cancelled")
        val got = assertThrows(java.util.concurrent.CancellationException::class.java) { HistoryBackup.read(ByteArrayInputStream(hex("stream"))) { throw cancel } }
        assertSame(cancel, got)
    }

    /** Recomputes END after [patch] changed the bytes before it, so a rule (not the digest) refuses the copy. */
    private fun reseal(stream: ByteArray, patch: (ByteArray) -> Unit): ByteArray {
        val before = stream.copyOf(stream.size - 54).also(patch)
        val records = java.nio.ByteBuffer.wrap(stream, stream.size - 48, 8).long
        val end = java.nio.ByteBuffer.allocate(54).put(255.toByte()).put(0).putInt(48).putLong(records).putLong(before.size.toLong())
            .put(java.security.MessageDigest.getInstance("SHA-256").digest(before)).array()
        return before + end
    }

    @Test fun anAuthenticatedCopyFromANewerAppIsUnsupportedNotAWrongKey() {
        val plain = hex("stream")
        assertThrows(HistoryBackup.Unsupported::class.java) { HistoryBackup.read(ByteArrayInputStream(reseal(plain) { it[7] = 3 })) }
        assertThrows("a schema far ahead", HistoryBackup.Unsupported::class.java) { HistoryBackup.read(ByteArrayInputStream(reseal(plain) { it[6] = 1 })) }
        // Scope bit 32 (unknown) in a schema 1 manifest: the scope flags sit at a fixed place after the strings.
        val flagsAt = 6 + 2 + 33 + 8 + 8 + (2 + 3) + 3 + 8 + 8
        assertThrows(HistoryBackup.Unsupported::class.java) { HistoryBackup.read(ByteArrayInputStream(reseal(plain) { it[flagsAt + 1] = (it[flagsAt + 1].toInt() or 0x20).toByte() })) }
        assertNull("schema 0 is nothing any app wrote", HistoryBackup.read(ByteArrayInputStream(reseal(plain) { it[7] = 0 })))
        assertNotNull(HistoryBackup.read(ByteArrayInputStream(reseal(plain) { })))
    }
}

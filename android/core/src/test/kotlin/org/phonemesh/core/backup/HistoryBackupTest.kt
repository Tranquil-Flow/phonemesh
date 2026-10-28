package org.phonemesh.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Full chat copy records (spec 5.5). Vectors from `.local/crypto-vectors/history_vectors.py`, written from the spec. */
class HistoryBackupTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/backup/history-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun manifest(flags: Int = 0b1011, messages: Int = 4, pictures: Int = 2, missing: Int = 1) = HistoryBackup.Manifest(
        hex("me"), 1_790_000_000_000, 4, "Awa", 0x2E7D32, 0, 0, flags, 1, 2, messages, 1, pictures, missing)

    private fun records(): List<HistoryBackup.Record> = listOf(
        HistoryBackup.Contact(ContactsBackup.Contact(1, hex("kofi"), hex("kofi"), "Kofi", 0x1E88E5, true, null, 1_789_000_000_000)),
        HistoryBackup.Chat(1, HistoryBackup.PERSON, 1, "Kofi", true),
        HistoryBackup.Chat(2, HistoryBackup.SHARED_CHAT, 0, "Tout le monde ici", false),
        HistoryBackup.Message(1, 1, false, 1, null, "Tu as la clé ?", "fr", 1_789_500_000_000, 0, 0, false, 0, 0),
        HistoryBackup.Message(2, 1, true, 0, null, "Oui,\nà midi.", "fr", 1_789_500_060_000, 3, 1, false, 0, 0),
        HistoryBackup.Message(3, 1, true, 0, null, "", null, 1_789_500_120_000, 1, 0, false, 0, 1),
        HistoryBackup.Message(4, 2, false, 0, "Yaw", "Bonjour", "fr", 1_789_600_000_000, 0, 0, true, 0, 2),
        HistoryBackup.Reaction(1, 2, 1, null, "👍", 1_789_500_070_000, false),
        HistoryBackup.Picture(1, 3, true, 1, 64, 48, hex("sketch"), hex("picture")),
        HistoryBackup.Picture(2, 4, false, 1, 40, 30, hex("sketch"), ByteArray(0)),
    )

    private fun write(m: HistoryBackup.Manifest = manifest(), rs: List<HistoryBackup.Record> = records()): ByteArray =
        ByteArrayOutputStream().also { out -> HistoryBackup.Writer(out, m).apply { rs.forEach(::write); finish() } }.toByteArray()

    private fun read(bytes: ByteArray, got: MutableList<HistoryBackup.Record> = ArrayList()) =
        HistoryBackup.read(ByteArrayInputStream(bytes)) { got += it }

    @Test fun writingMatchesTheIndependentStreamByteForByte() {
        assertArrayEquals(hex("stream"), write())
    }

    @Test fun readingGivesBackEveryRecord() {
        val got = ArrayList<HistoryBackup.Record>()
        val m = assertNotNull(read(hex("stream"), got)).let { read(hex("stream"))!! }
        assertEquals(4, m.messages)
        assertEquals(10, got.size)
        val reply = got.filterIsInstance<HistoryBackup.Message>().single { it.id == 2L }
        assertEquals("Oui,\nà midi.", reply.text); assertEquals(1L, reply.replyId); assertEquals(3, reply.status)
        val picture = got.filterIsInstance<HistoryBackup.Picture>().first()
        assertArrayEquals(hex("picture"), picture.data)
        assertArrayEquals("a copy read back writes the same bytes", hex("stream"), write(m, got))
    }

    @Test fun everyFlippedBitCutAndExtraByteIsRefused() {
        val s = hex("stream")
        for (i in s.indices) for (bit in 0..7) {
            val bad = s.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertNull("byte $i bit $bit", read(bad))
        }
        for (n in 0 until s.size) assertNull("cut at $n", read(s.copyOf(n)))
        assertNull(read(s + 0))
    }

    private fun refused(m: HistoryBackup.Manifest = manifest(), change: (MutableList<HistoryBackup.Record>) -> Unit) {
        val rs = records().toMutableList().also(change)
        assertTrue(runCatching { write(m, rs) }.isFailure)
    }

    private fun msg(id: Long, chat: Long = 1, out: Boolean = true, status: Int = 1, text: String = "x", reply: Long = 0, picture: Long = 0) =
        HistoryBackup.Message(id, chat, out, 0, null, text, null, 0, status, reply, false, 0, picture)

    /** Review A-03: a family past its declared count is refused at once, before the index or the reader sees it. */
    @Test fun aFamilyPastItsDeclaredCountStopsAtOnce() {
        val s = hex("stream")
        val messagesAt = 6 + 2 + 33 + 8 + 8 + (2 + 3) + 3 + 8 + 8 + 2 + 4 + 4
        assertEquals(4, java.nio.ByteBuffer.wrap(s, messagesAt, 4).int)
        val hostile = s.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(messagesAt, 1) }
        val got = ArrayList<HistoryBackup.Record>()
        assertNull(read(hostile, got))
        assertEquals("only the declared message reached the reader", 1, got.count { it is HistoryBackup.Message })
        // Moving on before a family is complete is refused too: say 2 contacts while there is 1.
        val contactsAt = messagesAt - 8
        val short = s.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(contactsAt, 2) }
        val seen = ArrayList<HistoryBackup.Record>()
        assertNull(read(short, seen))
        assertTrue("no chat reached the reader", seen.none { it is HistoryBackup.Chat })
        assertTrue("the writer refuses an extra record as it comes", runCatching { write(manifest(messages = 3)) }.isFailure)
    }

    @Test fun theRulesRefuseWhatTheSpecForbids() {
        refused { it[3] = msg(1, chat = 9, out = false, status = 0) }                      // unknown chat
        refused { it[3] = msg(1, out = false, status = 3) }                                 // incoming must be received
        refused { it[3] = msg(1, out = true, status = 0) }                                  // outgoing cannot be received
        refused { it[3] = msg(1, status = 5) }                                              // unsent text needs its scope bit
        refused { it[4] = msg(2, reply = 4) }                                               // a reply names an earlier message
        refused { it[6] = msg(4, chat = 2, out = false, status = 0, reply = 1, picture = 2) } // reply from another chat
        refused { it[3] = msg(1, text = "") }                                               // a text message has text
        refused { it[3] = msg(1, text = "a\u0007b") }                                       // no control characters
        refused { it[7] = HistoryBackup.Reaction(1, 9, 0, null, "👍", 0, false) }          // reaction to nothing
        refused { it[7] = HistoryBackup.Reaction(1, 2, 0, null, "ab", 0, false) }          // not an emoji
        refused { it[8] = HistoryBackup.Picture(1, 4, true, 1, 64, 48, hex("sketch"), hex("picture")) } // wrong message
        refused { it[8] = HistoryBackup.Picture(1, 3, true, 1, 64, 48, hex("sketch"), ByteArray(300) { 1 }) } // not WEBP
        refused { it[8] = HistoryBackup.Picture(1, 3, true, 2, 64, 48, hex("sketch"), hex("picture")) } // a sketch has no bytes
        refused { it[8] = HistoryBackup.Picture(1, 3, true, 1, 640, 48, hex("sketch"), hex("picture")) } // too wide
        refused { it[2] = HistoryBackup.Chat(2, HistoryBackup.PERSON, 1, "Kofi again", false) } // one chat per person
        refused { it.add(1, it[1]) }                                                        // ids ascend
        refused { val c = it.removeAt(1); it.add(4, c) }                                    // families in order
        refused(manifest(flags = 0b1010)) { }                                               // shared chats not in scope
        refused(manifest(flags = 0b0011)) { }                                               // picture bytes not in scope
        refused(manifest(messages = 5)) { }                                                 // counts must match
        refused(manifest(missing = 0)) { }
        refused(manifest(pictures = 1, missing = 0)) { it.removeAt(9) }                     // a picture message without its picture
    }

    @Test fun anEmptyHistoryIsAValidCopy() {
        val m = HistoryBackup.Manifest(hex("me"), 1, 1, "Awa", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val bytes = ByteArrayOutputStream().also { HistoryBackup.Writer(it, m).finish() }.toByteArray()
        assertNotNull(read(bytes))
    }

    @Test fun aLargeHistoryStreamsWithoutHoldingItAll() {
        val n = 50_000
        val m = HistoryBackup.Manifest(hex("me"), 1, 1, "Awa", 0, 0, 0, 0, 1, 1, n, 0, 0, 0)
        val out = ByteArrayOutputStream()
        HistoryBackup.Writer(out, m).apply {
            write(records()[0]); write(records()[1])
            for (i in 1..n) write(msg(i.toLong(), text = "message $i", reply = if (i > 1) i - 1L else 0))
            finish()
        }
        var count = 0
        assertNotNull(HistoryBackup.read(ByteArrayInputStream(out.toByteArray())) { if (it is HistoryBackup.Message) count++ })
        assertEquals(n, count)
    }

    @Test fun aWholeCopySealsAndOpensThroughTheStream() {
        val plain = hex("stream")
        val root = RecoveryKey.of(ByteArray(16) { 3 })
        val sealed = ByteArrayOutputStream().also { BackupStream.seal(root, BackupFile.Purpose.HISTORY, ByteArrayInputStream(plain), plain.size.toLong(), it) }
        val pipe = java.io.PipedInputStream(); val feed = java.io.PipedOutputStream(pipe)
        val reader = Thread { feed.use { f -> BackupStream.open(root, ByteArrayInputStream(sealed.toByteArray())) { f.write(it) } } }.apply { start() }
        val got = ArrayList<HistoryBackup.Record>()
        assertNotNull(HistoryBackup.read(pipe) { got += it })
        reader.join()
        assertEquals(10, got.size)
    }

    // ---- Schema 2: a private group's history (decision 75), read only, never a group key or state.

    private fun groupManifest(flags: Int = 0b11000, messages: Int = 4, reactions: Int = 1, pictures: Int = 1, chats: Int = 2) = HistoryBackup.Manifest(
        hex("me"), 1_790_000_000_000, 5, "Awa", 0x2E7D32, 1_780_000_000_000, 1_790_000_000_000, flags, 1, chats, messages, reactions, pictures, 0)

    private fun groupRecords(): List<HistoryBackup.Record> = listOf(
        HistoryBackup.Contact(ContactsBackup.Contact(1, hex("kofi"), hex("kofi"), "Kofi", 0x1E88E5, true, null, 1_789_000_000_000)),
        HistoryBackup.Chat(1, HistoryBackup.PERSON, 1, "Kofi", false),
        HistoryBackup.Chat(2, HistoryBackup.GROUP_CHAT, 0, "Famille", true),
        HistoryBackup.Message(1, 2, false, 1, "Kofi", "On se retrouve ?", "fr", 1_789_700_000_000, 0, 0, false, 0, 0),
        HistoryBackup.Message(2, 2, false, 0, "Yaw", "Oui !", null, 1_789_700_030_000, 0, 1, false, 0, 0),
        HistoryBackup.Message(3, 2, true, 0, null, "Moi aussi", "fr", 1_789_700_060_000, 1, 2, false, 0, 0),
        HistoryBackup.Message(4, 2, false, 1, "Kofi", "", null, 1_789_700_090_000, 0, 0, false, 0, 1),
        HistoryBackup.Reaction(1, 3, 0, "Yaw", "👍", 1_789_700_070_000, false),
        HistoryBackup.Picture(1, 4, true, 1, 64, 48, hex("sketch"), hex("picture")),
    )

    private fun writeGroups(m: HistoryBackup.Manifest = groupManifest(), rs: List<HistoryBackup.Record> = groupRecords()) = write(m, rs)

    @Test fun aGroupHistoryMatchesTheIndependentStreamByteForByte() {
        assertArrayEquals(hex("groups_stream"), writeGroups())
    }

    @Test fun aGroupHistoryReadsBackAndWritesTheSameBytes() {
        val got = ArrayList<HistoryBackup.Record>()
        val m = read(hex("groups_stream"), got)!!
        assertEquals(9, got.size)
        val chat = got.filterIsInstance<HistoryBackup.Chat>().single { it.kind == HistoryBackup.GROUP_CHAT }
        assertEquals("Famille", chat.title); assertEquals(0L, chat.contactId); assertTrue(chat.pinned)
        val yaw = got.filterIsInstance<HistoryBackup.Message>().single { it.id == 2L }
        assertEquals("Yaw", yaw.authorLabel); assertEquals(0L, yaw.authorContactId)
        assertArrayEquals(hex("groups_stream"), write(m, got))
    }

    @Test fun aCopyWithoutGroupsStaysSchemaOneAndAGroupCopyIsSchemaTwo() {
        fun schema(b: ByteArray) = java.nio.ByteBuffer.wrap(b, 6, 2).short.toInt()
        assertEquals(1, schema(write()))
        assertEquals(2, schema(writeGroups()))
        assertEquals("schema 1 with groups is not a copy", 1, schema(hex("stream")))
    }

    /** Recomputes END after [patch] changed the bytes before it, so a rule (not the digest) has to refuse the copy. */
    private fun reseal(stream: ByteArray, patch: (ByteArray) -> Unit): ByteArray {
        val before = stream.copyOf(stream.size - 54).also(patch)
        val records = java.nio.ByteBuffer.wrap(stream, stream.size - 48, 8).long
        val end = java.nio.ByteBuffer.allocate(54).put(255.toByte()).put(0).putInt(48).putLong(records).putLong(before.size.toLong())
            .put(java.security.MessageDigest.getInstance("SHA-256").digest(before)).array()
        return before + end
    }

    @Test fun theSchemaMustAgreeWithTheScopeBits() {
        val g = hex("groups_stream")
        assertNotNull(read(reseal(g) { }))
        assertNull("schema 1 cannot carry the group bit", read(reseal(g) { it[7] = 1 }))
        assertNull("schema 3 does not exist", read(reseal(g) { it[7] = 3 }))
        val plain = hex("stream")
        assertNotNull(read(reseal(plain) { }))
        assertNull("schema 2 needs the group bit", read(reseal(plain) { it[7] = 2 }))
    }

    @Test fun everyFlippedBitCutAndExtraByteOfAGroupHistoryIsRefused() {
        val s = hex("groups_stream")
        for (i in s.indices) for (bit in 0..7) {
            val bad = s.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertNull("byte $i bit $bit", read(bad))
        }
        for (n in 0 until s.size) assertNull("cut at $n", read(s.copyOf(n)))
        assertNull(read(s + 0))
    }

    @Test fun theRulesRefuseWhatAGroupHistoryMustNotHold() {
        val g = groupRecords()
        assertTrue("a group chat needs the group bit", runCatching { writeGroups(groupManifest(flags = 0b01000), g) }.isFailure)
        assertTrue("an unknown scope bit", runCatching { writeGroups(groupManifest(flags = 0b111000), g) }.isFailure)
        assertTrue("a group is not one person's chat", runCatching {
            // The person's own chat is left out, so contact 1 is free: only the group rule can refuse this.
            writeGroups(groupManifest(chats = 1), g.toMutableList().also { it.removeAt(1); it[1] = HistoryBackup.Chat(2, HistoryBackup.GROUP_CHAT, 1, "Famille", true) })
        }.isFailure)
        assertNotNull("the same without the contact is a good copy", read(writeGroups(groupManifest(chats = 1), g.toMutableList().also { it.removeAt(1); it[1] = HistoryBackup.Chat(2, HistoryBackup.GROUP_CHAT, 0, "Famille", true) })))
        assertTrue("a group has a name", runCatching {
            writeGroups(rs = g.toMutableList().also { it[2] = HistoryBackup.Chat(2, HistoryBackup.GROUP_CHAT, 0, "", true) })
        }.isFailure)
        assertTrue("a group message names an author who is a contact or none", runCatching {
            writeGroups(rs = g.toMutableList().also { it[4] = HistoryBackup.Message(2, 2, false, 7, "Yaw", "Oui !", null, 0, 0, 0, false, 0, 0) })
        }.isFailure)
        assertTrue("kind 4 is not a chat", runCatching {
            writeGroups(rs = g.toMutableList().also { it[2] = HistoryBackup.Chat(2, 4, 0, "Famille", true) })
        }.isFailure)
    }
}

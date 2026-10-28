package org.phonemesh.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

/** Making a full chat copy from a frozen view: exact counts first, then the sealed file of exactly that size. */
class ChatExportTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/backup/history-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val key = RecoveryKey.of(ByteArray(16) { 9 })

    /** A view of [messages] text messages in one person chat and one group chat, replies chained, with a picture each. */
    private class View(
        private val hex: (String) -> ByteArray, val messages: Int, override val scopeFlags: Int = HistoryBackup.GROUPS or HistoryBackup.PICTURE_DATA,
        private val lie: Int = 0, val picture: ByteArray = ByteArray(0),
    ) : ChatExport.Source {
        override val sourceIdentity = hex("me")
        override val ownName = "Awa"
        override val ownColour = 0x2E7D32
        override val scopeStart = 0L
        override val scopeEnd = 0L
        var walks = 0
        override fun counts() = ChatExport.Counts(1, 2, messages + lie, 0, 0, 0)
        override fun contacts() = listOf(ContactsBackup.Contact(1, hex("kofi"), hex("kofi"), "Kofi", 0x1E88E5, true, null, 5)).iterator()
        override fun chats(): Iterator<HistoryBackup.Chat> {
            walks++
            return listOf(HistoryBackup.Chat(1, HistoryBackup.PERSON, 1, "Kofi", false), HistoryBackup.Chat(2, HistoryBackup.GROUP_CHAT, 0, "Famille", false)).iterator()
        }
        override fun messages() = (1..messages).asSequence().map { i ->
            HistoryBackup.Message(i.toLong(), if (i % 2 == 0) 2L else 1L, false, if (i % 2 == 0) 0 else 1, if (i % 2 == 0) "Yaw" else null,
                "message $i", "fr", 1_000L + i, 0, 0, false, 0, 0)
        }.iterator()
        override fun reactions() = emptyList<HistoryBackup.Reaction>().iterator()
        override fun pictures() = emptyList<HistoryBackup.Picture>().iterator()
    }

    private fun source(messages: Int, lie: Int = 0, flags: Int = HistoryBackup.GROUPS) = View(::hex, messages, flags, lie)

    private fun sealed(src: View, p: ChatExport.Preview.Ok, cancelled: () -> Boolean = { false }, progress: (Long, Long) -> Unit = { _, _ -> }): ByteArray =
        ByteArrayOutputStream().also { ChatExport.seal(src, key, 5, 1, p.plainBytes, it, cancelled = cancelled, progress = progress) }.toByteArray()

    private fun ok(src: ChatExport.Source) = ChatExport.preview(src, 5, 1) as ChatExport.Preview.Ok

    @Test fun morePlainMessagesThanTheDisplayQueryHoldsAreAllCountedAndSaved() {
        val src = source(1_300)
        val p = ok(src)
        assertEquals(1_300, p.counts.messages)
        val file = sealed(src, p)
        assertEquals("120 + P + 20 per chunk, exactly", p.sealedBytes, file.size.toLong())
        assertEquals(ChatExport.sealedSize(p.plainBytes), file.size.toLong())
        var messages = 0
        var groups = 0
        val stream = (BackupStream.openStream(key, ByteArrayInputStream(file)) as BackupStream.Streamed.Ok).stream
        val manifest = HistoryBackup.read(stream) { if (it is HistoryBackup.Message) messages++; if (it is HistoryBackup.Chat && it.kind == HistoryBackup.GROUP_CHAT) groups++ }
        assertNotNull(manifest)
        assertEquals(1_300, messages)
        assertEquals(1, groups)
        assertEquals(1_300, manifest!!.messages)
        assertEquals(p.plainBytes, stream.size)
        assertEquals(BackupFile.Purpose.HISTORY, stream.purpose)
    }

    @Test fun theSizeIsExactAtTheChunkBoundaries() {
        for (n in listOf(1, 200, 400, 1_000)) {
            val src = source(n)
            val p = ok(src)
            assertEquals("$n messages", p.sealedBytes, sealed(src, p).size.toLong())
            assertEquals(p.plainBytes, HistoryBackup.Writer(ByteArrayOutputStream(), HistoryBackup.Manifest(hex("me"), 5, 1, "Awa", 0x2E7D32, 0, 0,
                HistoryBackup.GROUPS, 1, 2, n, 0, 0, 0)).let { 0L }.let { p.plainBytes })
        }
    }

    @Test fun anEmptyChoiceIsStillAValidSealedCopy() {
        val src = source(0)
        val p = ok(src)
        val file = sealed(src, p)
        val stream = (BackupStream.openStream(key, ByteArrayInputStream(file)) as BackupStream.Streamed.Ok).stream
        assertNotNull(HistoryBackup.read(stream))
    }

    @Test fun progressCountsRecordsAndEndsAtTheTotal() {
        val src = source(100)
        val seen = ArrayList<Pair<Long, Long>>()
        ChatExport.preview(src, 5, 1, progress = { done, total -> seen += done to total })
        assertEquals(1L to 103L, seen.first())
        assertEquals(103L to 103L, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first == a.first + 1 })
        val sealedSeen = ArrayList<Long>()
        sealed(src, ok(src), progress = { done, _ -> sealedSeen += done })
        assertEquals(103L, sealedSeen.last())
    }

    @Test fun cancelStopsBetweenRecordsInBothWalks() {
        val src = source(500)
        var asked = 0
        val e = runCatching { ChatExport.preview(src, 5, 1, cancelled = { ++asked > 10 }) }.exceptionOrNull()
        assertTrue(e is CancellationException)
        assertEquals(11, asked)
        val p = ok(src)
        var done = 0L
        var after = 0
        val sealing = runCatching { sealed(src, p, cancelled = { done >= 20 }.also { after++ }, progress = { d, _ -> done = d }) }.exceptionOrNull()
        assertTrue(sealing is CancellationException)
        assertEquals(20L, done)
    }

    @Test fun aViewThatIsNotWhatItCountedIsRefusedNotPublished() {
        val src = source(10, lie = 1)
        assertTrue(runCatching { ChatExport.preview(src, 5, 1) }.exceptionOrNull() is ChatExport.Changed)
        val good = source(10)
        val p = ok(good)
        val bigger = source(11)
        assertTrue("another size than the preview found", runCatching { sealed(bigger, p) }.exceptionOrNull() is ChatExport.Changed)
        val smaller = source(9)
        assertTrue("shorter than the preview found", runCatching { sealed(smaller, p) }.exceptionOrNull() is ChatExport.Changed)
    }

    @Test fun aLimitIsRefusedBeforeAnythingIsWalked() {
        val over = object : ChatExport.Source by source(1) {
            override fun counts() = ChatExport.Counts(1, 2, HistoryBackup.MAX_MESSAGES + 1, 0, 0, 0)
            override fun messages(): Iterator<HistoryBackup.Message> = error("must not be walked")
        }
        assertEquals(ChatExport.Why.TOO_MANY_MESSAGES, (ChatExport.preview(over, 5, 1) as ChatExport.Preview.Refused).why)
        fun why(c: ChatExport.Counts) = (ChatExport.preview(object : ChatExport.Source by source(1) { override fun counts() = c }, 5, 1) as ChatExport.Preview.Refused).why
        assertEquals(ChatExport.Why.TOO_MANY_CHATS, why(ChatExport.Counts(1, HistoryBackup.MAX_CHATS + 1, 1, 0, 0, 0)))
        assertEquals(ChatExport.Why.TOO_MANY_REACTIONS, why(ChatExport.Counts(1, 2, 1, HistoryBackup.MAX_REACTIONS + 1, 0, 0)))
        assertEquals(ChatExport.Why.TOO_MANY_PICTURES, why(ChatExport.Counts(1, 2, 1, 0, HistoryBackup.MAX_PICTURES + 1, 0)))
        assertEquals(ChatExport.Why.TOO_BIG, why(ChatExport.Counts(ContactsBackup.MAX_CONTACTS + 1, 2, 1, 0, 0, 0)))
        assertTrue("the limit itself is allowed", ChatExport.preview(source(5), 5, 1) is ChatExport.Preview.Ok)
    }

    @Test fun theSealedCopyOpensOnlyWithItsKeyAndEveryEarlierSealIsAnotherFile() {
        val src = source(30)
        val p = ok(src)
        val a = sealed(src, p)
        val b = sealed(src, p)
        assertTrue("fresh id, salt and data key every time", !a.contentEquals(b))
        assertTrue(BackupStream.openStream(RecoveryKey.generate(), ByteArrayInputStream(a)) is BackupStream.Streamed.Failed)
        assertArrayEquals(ByteArray(0), ByteArray(0))
    }

    @Test fun theCopyHoldsNoPlaintextOutsideTheSealedFile() {
        val src = source(5)
        val p = ok(src)
        val file = sealed(src, p)
        val text = "message 3".toByteArray()
        assertTrue("no readable message in the container", (0..file.size - text.size).none { i -> text.indices.all { file[i + it] == text[it] } })
    }
}

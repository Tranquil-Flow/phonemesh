package org.phonemesh.core.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.DraftFit
import org.phonemesh.core.DraftRules
import org.phonemesh.core.account.AccountId
import kotlin.random.Random

/** NOT behavioral RED: it needs SharedPost and DraftRules.fitWithin, which only the implementation patch adds. */
class SharedPostTest {
    private val id = AccountId.fromBytes(ByteArray(8) { (0xa0 + it).toByte() })
    private val other = AccountId.fromBytes(ByteArray(8) { (0x10 + it).toByte() })
    private val ref = byteArrayOf(1, 2, 3, 4)
    private val direction = String(Character.toChars(0x202E))
    private val combining = String(Character.toChars(0x301))
    private val bell = String(Character.toChars(7))
    private val at = 1_800_000_000L
    private val text = SharedPost.Kind.TEXT
    private val reply = SharedPost.Kind.REPLY
    private val reaction = SharedPost.Kind.REACTION

    private fun post(
        kind: SharedPost.Kind = text, name: String = "Awa", language: String = "fr", ref: ByteArray? = null, words: String = "salut",
    ) = SharedPost.Post(kind, id, at, name, language, ref, words)

    private fun frame(
        version: Int = 2, kind: Int = 1, sender: ByteArray = ByteArray(8) { 7 }, time: ByteArray = byteArrayOf(0, 0, 0, 5),
        name: ByteArray = "Awa".toByteArray(), nameLength: Int = name.size, language: ByteArray = "fr".toByteArray(),
        languageLength: Int = language.size, ref: ByteArray = ByteArray(0), body: ByteArray = "salut".toByteArray(),
    ): ByteArray = byteArrayOf(version.toByte(), kind.toByte()) + sender + time + byteArrayOf(nameLength.toByte()) + name +
        byteArrayOf(languageLength.toByte()) + language + ref + body

    @Test fun theLayoutIsExactlyTheDocumentedOne() {
        val time = byteArrayOf(0x6b, 0x49, 0xd2.toByte(), 0)
        val head = id.toBytes() + time + byteArrayOf(3) + "Awa".toByteArray()
        assertArrayEquals(byteArrayOf(2, 1) + head + byteArrayOf(2) + "fr".toByteArray() + "salut".toByteArray(), SharedPost.encode(post()))
        assertArrayEquals(
            byteArrayOf(2, 2) + head + byteArrayOf(2) + "fr".toByteArray() + ref + "oui".toByteArray(),
            SharedPost.encode(post(reply, ref = ref, words = "oui")),
        )
        assertArrayEquals(
            byteArrayOf(2, 3) + head + byteArrayOf(0) + ref + "👍".toByteArray(),
            SharedPost.encode(post(reaction, language = "", ref = ref, words = "👍")),
        )
    }

    @Test fun theRoomIsWhatIsLeftOf228BytesAfterTheEnvelope() {
        assertEquals(207, SharedPost.room(text, 3, "fr"))
        assertEquals(203, SharedPost.room(reply, 3, "fr"))
        assertEquals(186, SharedPost.room(text, 24, "fr"))
        assertEquals(182, SharedPost.room(reply, 24, "fr"))
        assertEquals(183, SharedPost.room(text, 24, "pt-BR"))
    }

    @Test fun everyKindFillsExactlyOneUnfragmentedPacketAndRoundTrips() {
        for (kind in listOf(text, reply)) for ((name, language) in listOf("é".repeat(12) to "pt-BR", "A" to "fr", "Moussa" to "en-US")) {
            val answers = if (kind == reply) ref else null
            val room = SharedPost.room(kind, name.toByteArray().size, language)
            val words = "é".repeat(room / 2) + "x".repeat(room % 2)
            val bytes = SharedPost.encode(post(kind, name, language, answers, words))
            assertEquals("$kind $name/$language", 228, bytes.size)
            val back = SharedPost.decode(bytes)!!
            assertEquals(kind, back.kind); assertEquals(id, back.sender); assertEquals(at, back.sentAtSeconds)
            assertEquals(name, back.name); assertEquals(language, back.language); assertEquals(words, back.text)
            if (answers == null) assertNull(back.ref) else assertArrayEquals(answers, back.ref)
            assertTrue(runCatching { SharedPost.encode(post(kind, name, language, answers, words + "x")) }.isFailure)
            assertTrue("a letter straddling the limit", runCatching { SharedPost.encode(post(kind, name, language, answers, "x".repeat(room - 1) + "é")) }.isFailure)
            assertNull("one byte over is refused on arrival too", SharedPost.decode(bytes + byteArrayOf(0x78)))
        }
        val emoji = "👍👍"
        val back = SharedPost.decode(SharedPost.encode(post(reaction, language = "", ref = ref, words = emoji)))!!
        assertEquals(emoji, back.text); assertEquals("", back.language); assertArrayEquals(ref, back.ref)
    }

    @Test fun everyPhoneComputesTheSameReferenceFromWhatThePostCarries() {
        val sent = post()
        val heard = SharedPost.decode(SharedPost.encode(sent))!!
        assertEquals(4, sent.reference().size)
        assertArrayEquals(sent.reference(), heard.reference())
        assertArrayEquals("the codec's own formula", SharedChatWire.Message(id, "Awa", "fr", "salut").ref(at), sent.reference())
        assertArrayEquals("the name does not change it", sent.reference(), post(name = "Mallory").reference())
        assertFalse(sent.reference().contentEquals(post(words = "salut!").reference()))
        assertFalse(sent.reference().contentEquals(SharedPost.Post(text, id, at + 1, "Awa", "fr", null, "salut").reference()))
        assertFalse(sent.reference().contentEquals(SharedPost.Post(text, other, at, "Awa", "fr", null, "salut").reference()))
        val answer = SharedPost.decode(SharedPost.encode(post(reply, ref = sent.reference(), words = "oui")))!!
        assertArrayEquals(sent.reference(), answer.ref)
    }

    @Test fun versionOneIsStillReadAndNeverMistakenForVersionTwo() {
        val old = SharedChatWire.encode(SharedChatWire.Message(id, "Awa", "fr", "salut"))
        val read = SharedPost.read(old) as SharedPost.Read.V1
        assertEquals(id, read.message.sender); assertEquals("Awa", read.message.name); assertEquals("salut", read.message.text)
        assertNull(SharedPost.decode(old))
        assertNull(SharedChatWire.decode(SharedPost.encode(post())))
        assertTrue(SharedPost.read(SharedPost.encode(post())) is SharedPost.Read.V2)
        assertNull(SharedPost.read(ByteArray(0)))
        assertNull(SharedPost.read(byteArrayOf(3, 1, 2, 3)))
    }

    @Test fun encodingRefusesWhatItCouldNotSendWhole() {
        fun refused(p: SharedPost.Post) = assertTrue(runCatching { SharedPost.encode(p) }.isFailure)
        refused(post(name = "é".repeat(12) + "x")); refused(post(name = "")); refused(post(name = "A$direction"))
        refused(post(words = "a${bell}b")); refused(post(words = "   ")); refused(post(language = "FR")); refused(post(language = "f1"))
        refused(post(ref = ref))
        refused(post(reply))
        refused(post(reply, ref = byteArrayOf(1, 2, 3), words = "x"))
        refused(post(reaction, language = "fr", ref = ref, words = "👍"))
        refused(post(reaction, language = "", ref = ref, words = "👍".repeat(9))) // 36 bytes: over the 32-byte reaction limit
        refused(SharedPost.Post(text, id, -1, "Awa", "fr", null, "x"))
        refused(SharedPost.Post(text, id, 1L shl 32, "Awa", "fr", null, "x"))
    }

    @Test fun malformedFramesAreRefusedNotThrown() {
        val bad = linkedMapOf(
            "empty" to ByteArray(0),
            "too short" to ByteArray(17) { 2 },
            "version 3" to frame(version = 3),
            "kind 0" to frame(kind = 0),
            "kind 4" to frame(kind = 4),
            "name length 0" to frame(name = ByteArray(0)),
            "name length runs past the end" to frame(nameLength = 40),
            "name 25 bytes" to frame(name = ("é".repeat(12) + "x").toByteArray()),
            "name not UTF-8" to frame(name = byteArrayOf(0xff.toByte(), 0xfe.toByte())),
            "name with a direction mark" to frame(name = ("A" + direction + "b").toByteArray()),
            "name not NFC" to frame(name = ("e" + combining).toByteArray()),
            "name with a control character" to frame(name = ("A" + bell).toByteArray()),
            "language length 0" to frame(language = ByteArray(0)),
            "language length 1" to frame(language = "f".toByteArray()),
            "language length 7" to frame(language = "pt-BRxx".toByteArray()),
            "language not canonical" to frame(language = "FR".toByteArray()),
            "language not a language" to frame(language = "f1".toByteArray()),
            "no text" to frame(body = ByteArray(0)),
            "text not UTF-8" to frame(body = byteArrayOf(0xc3.toByte(), 0x28)),
            "text with a control character" to frame(body = "a${bell}b".toByteArray()),
            "blank text" to frame(body = "   ".toByteArray()),
            "229 bytes" to frame(body = ByteArray(229) { 0x78 }),
            "reply with a reference and no text" to frame(kind = 2, ref = ref, body = ByteArray(0)),
            "reaction naming a language" to frame(kind = 3, ref = ref, body = "👍".toByteArray()),
            "reaction with no emoji" to frame(kind = 3, language = ByteArray(0), ref = ref, body = ByteArray(0)),
            "reaction of 34 bytes" to frame(kind = 3, language = ByteArray(0), ref = ref, body = "é".repeat(17).toByteArray()),
            "reaction not UTF-8" to frame(kind = 3, language = ByteArray(0), ref = ref, body = byteArrayOf(0xff.toByte())),
        )
        for ((why, bytes) in bad) assertNull(why, SharedPost.decode(bytes))
        assertNotNull("control: the table's own base frame is good", SharedPost.decode(frame()))
    }

    @Test fun randomBytesNeverThrow() {
        val random = Random(7)
        repeat(20_000) { i ->
            val bytes = random.nextBytes(random.nextInt(0, 240))
            if (bytes.isNotEmpty() && i % 2 == 0) bytes[0] = 2
            if (bytes.size > 1 && i % 4 == 0) bytes[1] = (1 + random.nextInt(3)).toByte()
            SharedPost.read(bytes)
        }
    }

    @Test fun theDraftBudgetFollowsTheEnvelopeNotAFixedNumber() {
        val name = "Awa"
        assertEquals(DraftFit.FITS, DraftRules.fitWithin("a".repeat(SharedPost.room(text, name.length, "fr")), SharedPost.room(text, name.length, "fr")))
        assertEquals(DraftFit.TOO_LONG, DraftRules.fitWithin("a".repeat(208), 207))
        assertEquals(DraftFit.EMPTY, DraftRules.fitWithin("  ", 207))
        assertEquals(DraftFit.INVALID, DraftRules.fitWithin("a${bell}b", 207))
        assertEquals("é".repeat(3), DraftRules.trimWithin("é".repeat(10), 7))
        assertEquals("mot mot", DraftRules.trimWithin("mot mot mot", 8))
        assertEquals("never inside an emoji", "😀".repeat(1), DraftRules.trimWithin("😀😀", 7))
    }
}

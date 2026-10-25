package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.DeliveryState
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.DraftFit
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.crypto.Bytes

/**
 * BEHAVIORAL RED. This class uses only APIs that exist in the input snapshot (AppCore, TestCore, FakeRadio.broadcasts, History,
 * AppMailStore.refNumber, Bytes, AccountId) and parses the wire with plain JDK code, so it COMPILES against the unchanged input
 * and fails there on assertions about what the phone does: nothing goes out on port 303, no reference is kept, and so on.
 *
 * The version-2 layout under test (port 303): version 2 | kind 1 text, 2 reply, 3 reaction | sender account id (8) |
 * sender's time in whole seconds (4, big-endian) | name length | name | language length | language (length 0 for a reaction) |
 * reference (4; kinds 2 and 3 only) | text or emoji.  A post's reference is SHA-256(sender id, time, text)[0..4].
 */
@RunWith(AndroidJUnit4::class)
class SharedChatBehaviorRedTest {
    private val core get() = TestCore.core
    private val radio get() = TestCore.radio
    private fun <T> main(block: () -> T): T = TestCore.onMain(block)

    @After fun tearDown() = TestCore.uninstall()

    private fun group() = main { core.groupChatId() }
    private fun say(chatId: Long, text: String): Boolean = main { core.setDraft(chatId, text); core.send(chatId) }
    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun ownId(): ByteArray = AccountId.of(main { checkNotNull(core.history.accountRow()).identity }).toBytes()
    private fun reference(sender: ByteArray, seconds: Long, text: String): ByteArray =
        Bytes.sha256(sender, u32(seconds), text.toByteArray(Charsets.UTF_8)).copyOf(4)
    private fun number(ref: ByteArray) = AppMailStore.refNumber(ref)
    private fun hear(bytes: ByteArray) = main { core.onBroadcastData("!0000cafe", 303, bytes, 0, 9L, "") }

    /** A version-2 frame built by hand from the layout above. */
    private fun frame(kind: Int, sender: ByteArray, seconds: Long, name: String, language: String, ref: ByteArray?, text: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val l = language.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(2, kind.toByte()) + sender + u32(seconds) + byteArrayOf(n.size.toByte()) + n +
            byteArrayOf(l.size.toByte()) + l + (ref ?: ByteArray(0)) + text.toByteArray(Charsets.UTF_8)
    }

    /** The fields of a frame the phone sent, read back by hand. */
    private class Raw(val b: ByteArray) {
        val version = b[0].toInt()
        val kind = b[1].toInt()
        val sender = b.copyOfRange(2, 10)
        val time = b.copyOfRange(10, 14)
        private val nameLength = b[14].toInt() and 0xff
        val name = String(b, 15, nameLength, Charsets.UTF_8)
        private val languageAt = 15 + nameLength
        private val languageLength = b[languageAt].toInt() and 0xff
        val language = String(b, languageAt + 1, languageLength, Charsets.US_ASCII)
        private val refAt = languageAt + 1 + languageLength
        val ref: ByteArray? = if (kind == 1) null else b.copyOfRange(refAt, refAt + 4)
        private val textAt = refAt + (if (kind == 1) 0 else 4)
        val text = String(b, textAt, b.size - textAt, Charsets.UTF_8)
    }

    private val stranger = ByteArray(8) { (0x40 + it).toByte() }
    private val other = ByteArray(8) { (0x50 + it).toByte() }

    @Test fun aNamedPhoneSendsItsGroupMessageAsOneRawPort303Packet() {
        TestCore.install()
        val group = group()
        assertTrue(say(group, "Bonjour à tous"))
        assertEquals("one packet, on port 303", listOf(303), radio.broadcasts.map { it.port })
        val out = radio.broadcasts.single()
        assertEquals("^all", out.destinationId)
        assertEquals(0, out.channel)
        assertTrue(out.payload.size <= 228)
        val raw = Raw(out.payload)
        val message = main { core.history.lastMessage(group)!! }
        assertEquals(2, raw.version)
        assertEquals(1, raw.kind)
        assertArrayEquals(ownId(), raw.sender)
        assertArrayEquals(u32(message.time / 1000), raw.time)
        assertEquals("Awa", raw.name)
        assertEquals("fr", raw.language)
        assertEquals("Bonjour à tous", raw.text)
    }

    @Test fun anUnnamedPhoneIsRefusedWithTheNamePromptAndSendsNothing() {
        TestCore.install(named = false)
        val group = group()
        assertFalse(say(group, "Salut"))
        assertEquals(UiText(R.string.add_name_first), core.lastNote)
        assertEquals("the draft stays", "Salut", main { core.draft(group) })
        assertTrue(main { core.history.messages(group) }.isEmpty())
        assertTrue(radio.sent.isEmpty())
        assertTrue(radio.broadcasts.isEmpty())
    }

    @Test fun aSavedNameThatCannotBePostedIsRefusedNotReplacedByOldText() {
        TestCore.install()
        val group = group()
        main { core.history.setAccountName("x".repeat(30), 3) }
        assertFalse(say(group, "Salut"))
        assertEquals(UiText(R.string.add_name_first), core.lastNote)
        assertTrue(radio.sent.isEmpty())
        assertTrue(radio.broadcasts.isEmpty())
        assertTrue(main { core.history.messages(group) }.isEmpty())
    }

    @Test fun theDraftLimitIsThePostsRoomForTheNameAndLanguageNotAFixedNumber() {
        TestCore.install()
        val group = group()
        main { core.setDraft(group, "a".repeat(207)) }
        assertEquals("228 - 14 fixed - 1 - 3 (Awa) - 1 - 2 (fr)", DraftFit.FITS, core.draftFit(group))
        main { core.setDraft(group, "a".repeat(208)) }
        assertEquals(DraftFit.TOO_LONG, core.draftFit(group))
        main { core.saveAccount("é".repeat(12), 3) }
        main { core.setDraft(group, "é".repeat(93)) }
        assertEquals("a 24-byte name leaves 186", DraftFit.FITS, core.draftFit(group))
        main { core.setDraft(group, "é".repeat(93) + "a") }
        assertEquals(DraftFit.TOO_LONG, core.draftFit(group))
    }

    @Test fun theLongestNameWithTheLongestDraftFillsExactlyOneUnfragmentedPacket() {
        TestCore.install()
        main { core.saveAccount("é".repeat(12), 3) }
        val group = group()
        val text = "é".repeat(93)
        assertTrue(say(group, text))
        assertEquals(1, radio.broadcasts.size)
        assertEquals(228, radio.broadcasts.single().payload.size)
        assertEquals(text, Raw(radio.broadcasts.single().payload).text)
        assertFalse("one byte more is not offered", say(group, text + "a"))
        assertEquals(1, radio.broadcasts.size)
    }

    @Test fun theReplyReferenceIsTheHashOfSenderTimeAndTextAndIsNotTheRadioAcceptanceId() {
        TestCore.install()
        val group = group()
        assertTrue(say(group, "Bonjour"))
        assertEquals(1, radio.broadcasts.size)
        val radioId = radio.broadcasts.single().packetId
        val sent = main { core.history.lastMessage(group)!! }
        val expected = number(reference(ownId(), sent.time / 1000, "Bonjour"))
        assertEquals("the message keeps the shared reference", expected, sent.packetId)
        assertNotEquals("not the radio's id", radioId, sent.packetId)
        main { radio.deliver(radioId, DeliveryState.QUEUED) }
        assertEquals("delivery still follows the radio's id", DeliveryStatus.SENT, main { core.history.message(sent.id)!!.status })
        main { radio.deliver(expected, DeliveryState.FAILED) }
        assertEquals("a reference is not a packet id", DeliveryStatus.SENT, main { core.history.message(sent.id)!!.status })
    }

    @Test fun aResendAndARestartKeepTheReferenceAndTheSameBytes() {
        TestCore.install()
        val group = group()
        assertTrue(say(group, "Bonjour"))
        assertEquals(1, radio.broadcasts.size)
        val first = radio.broadcasts.single()
        val sent = main { core.history.lastMessage(group)!! }
        val expected = number(reference(ownId(), sent.time / 1000, "Bonjour"))
        main { radio.deliver(first.packetId, DeliveryState.FAILED) }
        assertTrue(main { core.resend(sent.id) })
        assertEquals(2, radio.broadcasts.size)
        val second = radio.broadcasts.last()
        assertNotEquals("every packet gets its own radio id", first.packetId, second.packetId)
        assertArrayEquals("a resend is the same post", first.payload, second.payload)
        assertEquals(expected, main { core.history.message(sent.id)!!.packetId })
        main { radio.deliver(first.packetId, DeliveryState.QUEUED) }
        assertEquals("the old packet's late report changes nothing", DeliveryStatus.WAITING, main { core.history.message(sent.id)!!.status })
        main { radio.deliver(second.packetId, DeliveryState.FAILED) }
        TestCore.restart()
        main { radio.connected(true) }
        assertEquals(expected, main { core.history.message(sent.id)!!.packetId })
        assertTrue(main { core.resend(sent.id) })
        assertEquals(1, radio.broadcasts.size)
        assertArrayEquals("after a restart too", first.payload, radio.broadcasts.single().payload)
    }

    @Test fun aReplyAndAReactionAreRawPort303FramesNamingTheSameReferenceEveryPhoneComputes() {
        TestCore.install()
        val group = group()
        hear(frame(1, stranger, 1_800_000_000L, "Mallory", "fr", null, "On se voit ?"))
        val asked = main { core.history.lastMessage(group) }
        assertNotNull("a version-2 post is kept", asked)
        val ref = reference(stranger, 1_800_000_000L, "On se voit ?")
        assertEquals(number(ref), asked!!.packetId)
        main { core.setReplyTarget(group, asked.id) }
        assertTrue(say(group, "Oui"))
        assertEquals(listOf(303), radio.broadcasts.map { it.port })
        val reply = Raw(radio.broadcasts.single().payload)
        assertEquals(2, reply.kind)
        assertArrayEquals(ref, reply.ref)
        assertEquals("Oui", reply.text)
        assertTrue(main { core.react(asked.id, "👍") })
        assertEquals(listOf(303, 303), radio.broadcasts.map { it.port })
        val reaction = Raw(radio.broadcasts.last().payload)
        assertEquals(3, reaction.kind)
        assertEquals("", reaction.language)
        assertArrayEquals(ref, reaction.ref)
        assertEquals("👍", reaction.text)
        // Another phone answers and reacts with the reference it computed for the same post.
        hear(frame(2, other, 1_800_000_050L, "Ama", "fr", ref, "Moi aussi"))
        val answer = main { core.history.lastMessage(group)!! }
        assertEquals(number(ref), answer.replyTo)
        assertEquals("On se voit ?", main { core.quoted(answer)!!.text })
        hear(frame(3, other, 1_800_000_060L, "Ama", "", ref, "❤️"))
        val reactions = main { core.history.reactions(group) }
        assertEquals(listOf("👍", "❤️"), reactions.map { it.emoji })
        assertTrue(reactions.all { it.target == number(ref) })
    }

    @Test fun aResentPostIsKeptOnceButTheSameWordsAtAnotherSecondAreAnotherPost() {
        TestCore.install()
        val group = group()
        val post = frame(1, stranger, 1_800_000_000L, "Mallory", "fr", null, "Salut")
        hear(post)
        hear(post)
        assertEquals(1, main { core.history.messages(group) }.size)
        hear(frame(1, stranger, 1_800_000_001L, "Mallory", "fr", null, "Salut"))
        assertEquals(2, main { core.history.messages(group) }.size)
    }
}

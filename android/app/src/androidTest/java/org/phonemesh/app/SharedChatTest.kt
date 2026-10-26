package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RadioPeer
import org.phonemesh.ble.TextMark
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.DraftFit
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.wire.ClaimedSender
import org.phonemesh.core.wire.SharedChatWire
import org.phonemesh.core.wire.SharedPost

/**
 * NOT behavioral RED: this class names SharedPost, ClaimedSender, AppCore.peopleHeard and FakeRadio's projection tags, so it
 * does not compile until the implementation and the FakeRadio hunks are applied. The behavioral RED is
 * SharedChatBehaviorRedTest. These tests pin the finished composition: AppCore, the fake radio's raw bytes, History, screens.
 */
@RunWith(AndroidJUnit4::class)
class SharedChatTest {
    private val core get() = TestCore.core
    private val radio get() = TestCore.radio
    private fun <T> main(block: () -> T): T = TestCore.onMain(block)
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    @After fun tearDown() {
        Views.finish(Views.resumed())
        TestCore.uninstall()
    }

    private val stranger = AccountId.fromBytes(ByteArray(8) { (0x40 + it).toByte() })
    private val ama = AccountId.fromBytes(ByteArray(8) { (0x50 + it).toByte() })
    private val bell = String(Character.toChars(7))

    private fun ownId() = AccountId.of(main { checkNotNull(core.history.accountRow()).identity })
    private fun group() = main { core.groupChatId() }
    private fun messages(chatId: Long) = main { core.history.messages(chatId) }
    private fun say(chatId: Long, text: String): Boolean = main { core.setDraft(chatId, text); core.send(chatId) }
    private fun post(
        kind: SharedPost.Kind = SharedPost.Kind.TEXT, sender: AccountId = stranger, at: Long = 1_800_000_000L, name: String = "Mallory",
        ref: ByteArray? = null, text: String = "Salut",
    ) = SharedPost.encode(SharedPost.Post(kind, sender, at, name, if (kind == SharedPost.Kind.REACTION) "" else "fr", ref, text))
    private fun hear(payload: ByteArray, packetId: Long = 0, channel: Int = 0, port: Int = MeshRadio.SHARED_CHAT_PORT) =
        main { core.onBroadcastData("!0000cafe", port, payload, channel, packetId, "") }
    private fun kinds() = radio.broadcasts.map { SharedPost.decode(it.payload)!!.kind }

    private class Bea {
        val qr = QrCode(ContactCard.create(SoftKeys.generate(), org.phonemesh.core.account.Profile("Bea", 0x8E24AA)), KeyPairBytes.generate().public)
    }

    /** Awa (the app) and Bea scan each other: a saved contact in purple and a person chat. */
    private fun meetBea(): Pair<AccountId, Long> {
        val bea = Bea()
        main { core.accountsFlow.showCode() }
        val added = main { core.accountsFlow.scanned(AddCode.of(bea.qr)) } as Accounts.Added.Contact
        return bea.qr.card.accountId to added.chatId
    }

    @Test fun plainReplyAndReactionAllLeaveOnPort303AndNeverAsOldText() {
        TestCore.install()
        val group = group()
        hear(post(text = "On se voit ?"), packetId = 9)
        val asked = main { core.history.lastMessage(group)!! }
        main { core.setReplyTarget(group, asked.id) }
        assertTrue(say(group, "Oui"))
        assertTrue(main { core.react(asked.id, "👍") })
        assertTrue(say(group, "Merci"))
        assertEquals(listOf(303, 303, 303), radio.broadcasts.map { it.port })
        assertEquals(listOf(SharedPost.Kind.REPLY, SharedPost.Kind.REACTION, SharedPost.Kind.TEXT), kinds())
        assertEquals("only the fake's tagged projections are in sent: no old text went out", 3, radio.sent.size)
        assertTrue(radio.sent.all { it.projected && it.port == 303 })
        for (out in radio.broadcasts) {
            val sent = SharedPost.decode(out.payload)!!
            assertEquals(ownId(), sent.sender)
            assertEquals("Awa", sent.name)
        }
    }

    @Test fun answeringAnOldMessageKeepsTheOldTextPortAndItsOldLimit() {
        TestCore.install()
        val group = group()
        main { radio.receive("!0000aaaa", "[pm1:fr] On se voit ?", false, TextMark(packetId = 500)) }
        main { core.setReplyTarget(group, core.history.lastMessage(group)!!.id) }
        main { core.setDraft(group, "a".repeat(191)) }
        assertEquals(DraftFit.FITS, core.draftFit(group))
        main { core.setDraft(group, "a".repeat(192)) }
        assertEquals(DraftFit.TOO_LONG, core.draftFit(group))
        assertTrue(say(group, "Oui"))
        assertTrue(radio.broadcasts.isEmpty())
        val sent = radio.sent.single()
        assertFalse(sent.projected)
        assertNull(sent.port)
        assertEquals(500L, sent.replyTo)
        main { radio.receive("!0000aaaa", "[pm1:fr] Le marché", false, TextMark(packetId = 700)) }
        assertTrue(main { core.react(core.history.lastMessage(group)!!.id, "👍") })
        val reaction = radio.sent.last()
        assertTrue(reaction.reaction)
        assertFalse(reaction.projected)
        assertEquals(700L, reaction.replyTo)
        assertTrue(radio.broadcasts.isEmpty())
    }

    @Test fun aReplyHasFourBytesLessRoomThanAPlainPost() {
        TestCore.install()
        val group = group()
        hear(post())
        val asked = main { core.history.lastMessage(group)!! }
        main { core.setDraft(group, "a".repeat(207)) }
        assertEquals(DraftFit.FITS, core.draftFit(group))
        main { core.setReplyTarget(group, asked.id) }
        assertEquals(DraftFit.TOO_LONG, core.draftFit(group))
        main { core.setDraft(group, "a".repeat(203)) }
        assertEquals(DraftFit.FITS, core.draftFit(group))
        assertTrue(main { core.send(group) })
        assertEquals(228, radio.broadcasts.single().payload.size)
    }

    @Test fun aClaimedNameIsShownWithoutAnyColourOrContact() {
        TestCore.install()
        val group = group()
        val unreadBefore = main { core.history.chat(group)!!.unread }
        hear(post(), packetId = 77)
        val heard = main { core.history.lastMessage(group)!! }
        assertEquals(Direction.IN, heard.direction)
        assertEquals(ClaimedSender.key(stranger, "Mallory"), heard.senderId)
        assertEquals("Salut", heard.text)
        assertEquals("fr", heard.language)
        assertEquals("Mallory", main { core.senderName(heard.senderId!!) })
        assertEquals(unreadBefore + 1, main { core.history.chat(group)!!.unread })
        val row = main { core.chatRows() }.single { it.chat.id == group }
        assertNull(row.colour)
        assertNull(row.met)
        assertNull(main { core.chatColour(core.history.chat(group)!!) })
    }

    @Test fun aPostClaimingASavedContactsIdOrNameBorrowsNothingFromTheContact() {
        TestCore.install()
        val (bea, personChat) = meetBea()
        val group = group()
        val before = main { core.history.contactRow(bea.toString())!! }
        hear(post(sender = bea, name = "Mallory", text = "Envoie ton code"))
        hear(post(sender = stranger, name = "Bea", text = "C'est moi"))
        hear(post(sender = bea, name = "Bea", text = "Vraiment moi"))
        hear(post(kind = SharedPost.Kind.REACTION, sender = bea, name = "Bea", ref = byteArrayOf(1, 2, 3, 4), text = "👍"))
        val heard = messages(group)
        assertEquals(listOf(ClaimedSender.key(bea, "Mallory"), ClaimedSender.key(stranger, "Bea"), ClaimedSender.key(bea, "Bea")), heard.map { it.senderId })
        assertEquals(listOf("Mallory", "Bea", "Bea"), heard.map { m -> main { core.senderName(m.senderId!!) } })
        assertTrue("nothing reaches the private chat", messages(personChat).isEmpty())
        val after = main { core.history.contactRow(bea.toString())!! }
        assertEquals(before.name, after.name)
        assertEquals(before.colour, after.colour)
        assertEquals(before.met, after.met)
        val rows = main { core.chatRows() }
        assertNull("even the contact's own id and name stay uncoloured", rows.single { it.chat.id == group }.colour)
        assertEquals("the colour stays on the contact's own chat", 0x8E24AA, rows.single { it.chat.id == personChat }.colour)
    }

    @Test fun theConversationScreenNamesTheClaimAndTheOldSenderApart() {
        TestCore.install()
        val (bea, _) = meetBea()
        val group = group()
        main { radio.setPeers(listOf(RadioPeer("!0000aaaa", "Kofi"))) }
        hear(post(sender = bea, name = "Mallory", text = "Salut"))
        main { radio.receive("!0000aaaa", "[pm1:fr] Bonjour", false, TextMark(packetId = 2)) }
        val a = Views.launch<ConversationActivity>(ConversationActivity.intent(inst.targetContext, group))
        val said = messages(group).map { m ->
            main { Views.descendants(a.window.decorView).first { it.tag == "bubble:${m.id}" }.contentDescription.toString() }
        }
        assertEquals(listOf("Mallory, Salut", "Kofi, Bonjour"), said)
        assertNotNull(Views.withText(a, "Mallory"))
        assertNull("the saved contact's name is never borrowed", Views.withText(a, "Bea"))
    }

    @Test fun malformedOrUnsafeFramesAreDroppedAndVersionOneIsStillRead() {
        TestCore.install()
        val group = group()
        val good = post()
        val frames = linkedMapOf(
            "empty" to ByteArray(0),
            "cut short" to good.copyOf(12),
            "version 3" to good.copyOf().also { it[0] = 3 },
            "kind 9" to good.copyOf().also { it[1] = 9 },
            "name length 0" to good.copyOf().also { it[14] = 0 },
            "name length 25" to good.copyOf().also { it[14] = 25 },
            "garbage" to ByteArray(228) { 0xff.toByte() },
            "over 228 bytes" to good + ByteArray(228),
            "control character in the text" to good.copyOf().also { it[it.size - 1] = 7 },
            "direction override in the text" to good + byteArrayOf(0xe2.toByte(), 0x80.toByte(), 0xae.toByte()),
        )
        for ((why, bytes) in frames) {
            hear(bytes)
            assertTrue(why, messages(group).isEmpty())
        }
        hear(good, channel = 1)
        assertTrue("only the primary channel", messages(group).isEmpty())
        hear(post(sender = ownId(), name = "Awa", text = "écho"))
        assertTrue("a copy of this phone's own post is not a new message", messages(group).isEmpty())
        hear(good)
        assertEquals(listOf("Salut"), messages(group).map { it.text })
        hear(SharedChatWire.encode(SharedChatWire.Message(stranger, "Mallory", "fr", "Ancien")), packetId = 33)
        val old = main { core.history.lastMessage(group)!! }
        assertEquals("Ancien", old.text)
        assertEquals("a version-1 post keeps the radio id it came with", 33L, old.packetId)
        assertEquals(ClaimedSender.key(stranger, "Mallory"), old.senderId)
    }

    @Test fun aNameThatCannotBePostedFailsTheMessageVisiblyAndSendsNothing() {
        TestCore.install()
        val group = group()
        val kept = main {
            val m = core.history.addOutgoing(group, "Bonjour", "fr", System.currentTimeMillis())
            core.history.setStatus(m.id, DeliveryStatus.FAILED)
            m
        }
        main { core.history.setAccountProfile(org.phonemesh.core.account.Profile("x".repeat(30), 0x43A047)) }
        assertTrue(main { core.resend(kept.id) })
        assertEquals(DeliveryStatus.FAILED, main { core.history.message(kept.id)!!.status })
        assertNull("it never went to a radio", main { core.history.message(kept.id)!!.packetId })
        assertTrue(radio.sent.isEmpty())
        assertTrue(radio.broadcasts.isEmpty())
        assertEquals(UiText(R.string.add_name_first), core.lastNote)
    }

    @Test fun anUnnamedPhoneCannotReactAndALongEmojiIsRefused() {
        TestCore.install(named = false)
        val group = group()
        hear(post())
        val asked = main { core.history.lastMessage(group)!! }
        assertFalse(main { core.react(asked.id, "👍") })
        assertEquals(UiText(R.string.add_name_first), core.lastNote)
        main { core.saveAccount("Awa", 3) }
        assertFalse(main { core.react(asked.id, "👍👍👍") })
        assertTrue(radio.broadcasts.isEmpty())
        assertTrue(main { core.history.reactions(group) }.isEmpty())
    }

    @Test fun waitingMessagesAndReactionsGoOutOnPort303InOrderAndDrainByTheRadioId() {
        TestCore.install()
        val group = group()
        hear(post())
        val asked = main { core.history.lastMessage(group)!! }
        main { radio.connected(false) }
        assertTrue(say(group, "Un"))
        assertTrue(say(group, "Deux"))
        assertTrue(main { core.react(asked.id, "👍") })
        assertTrue(radio.broadcasts.isEmpty())
        main { radio.connected(true) }
        assertEquals("one at a time", 1, radio.broadcasts.size)
        main { radio.deliver(radio.broadcasts[0].packetId, DeliveryState.QUEUED) }
        inst.waitForIdleSync()
        assertEquals(2, radio.broadcasts.size)
        main { radio.deliver(radio.broadcasts[1].packetId, DeliveryState.QUEUED) }
        inst.waitForIdleSync()
        assertEquals(listOf(SharedPost.Kind.TEXT, SharedPost.Kind.TEXT, SharedPost.Kind.REACTION), kinds())
        assertEquals(listOf("Un", "Deux"), radio.broadcasts.take(2).map { SharedPost.decode(it.payload)!!.text })
        assertTrue(radio.sent.all { it.projected })
    }

    @Test fun oldAndNewSendersCountAndNameApart() {
        TestCore.install()
        val group = group()
        main { radio.receive("!0000aaaa", "[pm1:fr] Ancien", false, TextMark(packetId = 3)) }
        assertEquals(main { core.text(AppCore.unnamedRadio("!0000aaaa")) }, main { core.senderName("!0000aaaa") })
        main { radio.setPeers(listOf(RadioPeer("!0000aaaa", "Kofi"))) }
        assertEquals("Kofi", main { core.senderName("!0000aaaa") })
        hear(post(name = "Mallory", text = "Nouveau"))
        assertEquals("one old radio, one claimed account, and me", 3, main { core.peopleHeard(group) })
        hear(post(name = "Mallory B", at = 1_800_000_001L, text = "Encore"))
        hear(post(sender = ama, kind = SharedPost.Kind.REACTION, name = "Ama", ref = byteArrayOf(1, 2, 3, 4), text = "❤️"))
        assertEquals("a renamed claim is one person; a reaction counts its account", 4, main { core.peopleHeard(group) })
        assertEquals(4, main { core.chatRows() }.single { it.chat.id == group }.peopleHeard)
    }
}

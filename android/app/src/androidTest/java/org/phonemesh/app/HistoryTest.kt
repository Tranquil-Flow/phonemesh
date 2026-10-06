package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.DeliveryStatus

@RunWith(AndroidJUnit4::class)
class HistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val history = History(context, null)

    @After fun close() {
        history.close()
        context.deleteDatabase(FILE)
    }

    @Test fun theGroupIsPinnedAndOtherChatsAreNewestFirst() {
        val group = history.groupChat()
        val kofi = history.directChat("!0000aaaa", "Kofi")
        val ama = history.directChat("!0000bbbb", null)
        history.addIncoming(kofi.id, "!0000aaaa", "Salut", "fr", 1_000, true)
        history.addIncoming(ama.id, "!0000bbbb", "Bonjour", "fr", 2_000, true)
        assertEquals(listOf(group.id, ama.id, kofi.id), history.chats().map { it.id })
    }

    @Test fun incomingCountsUnreadUntilCleared() {
        val group = history.groupChat()
        history.addIncoming(group.id, "!0000aaaa", "Un", "fr", 1, true)
        history.addIncoming(group.id, "!0000aaaa", "Deux", "fr", 2, true)
        history.addIncoming(group.id, "!0000aaaa", "Vu", "fr", 3, false)
        assertEquals(2, history.chat(group.id)!!.unread)
        history.clearUnread(group.id)
        assertEquals(0, history.chat(group.id)!!.unread)
    }

    @Test fun aPrivateChatIsReusedAndRenamed() {
        val first = history.directChat("!0000aaaa", null)
        assertEquals("", first.name)
        assertEquals(first.id, history.directChat("!0000aaaa", null).id)
        assertEquals(1, history.renameAll(mapOf("!0000aaaa" to "Kofi", "!0000cccc" to "Nobody")))
        assertEquals("Kofi", history.chat(first.id)!!.name)
        assertEquals(listOf("!0000aaaa"), history.chats().map { it.address })
    }

    @Test fun deliveryFindsTheMessageByItsLatestPacket() {
        val chat = history.directChat("!0000aaaa", "Kofi")
        val sent = history.addOutgoing(chat.id, "Salut", "fr", 10)
        assertEquals(DeliveryStatus.WAITING, sent.status)
        assertNull(sent.senderId)
        history.attachPacket(sent.id, 501)
        history.setStatus(sent.id, DeliveryStatus.FAILED)
        history.attachPacket(sent.id, 502) // resend: same message, new packet
        assertNull(history.byPacket(501))
        assertEquals(sent.id, history.byPacket(502)!!.id)
        assertEquals(DeliveryStatus.FAILED, history.message(sent.id)!!.status)
    }

    @Test fun reopenTurnsClocksIntoWarningsAndKeepsSent() {
        val group = history.groupChat()
        val waiting = history.addOutgoing(group.id, "Un", "fr", 1)
        val sent = history.addOutgoing(group.id, "Deux", "fr", 2)
        history.setStatus(sent.id, DeliveryStatus.SENT)
        assertEquals(1, history.expireWaiting())
        assertEquals(DeliveryStatus.UNCONFIRMED, history.message(waiting.id)!!.status)
        assertEquals(DeliveryStatus.SENT, history.message(sent.id)!!.status)
    }

    @Test fun sendersHeardIgnoresMyOwnMessagesAndAMissingLanguageStaysNull() {
        val group = history.groupChat()
        history.addIncoming(group.id, "!0000aaaa", "Un", null, 1, true)
        history.addIncoming(group.id, "!0000bbbb", "Deux", "fr", 2, true)
        history.addIncoming(group.id, "!0000aaaa", "Trois", "fr", 3, true)
        history.addOutgoing(group.id, "Moi", "fr", 4)
        assertEquals(setOf("!0000aaaa", "!0000bbbb"), history.sendersHeard(group.id))
        assertNull(history.messages(group.id).first().language)
        assertEquals(listOf("Un", "Deux", "Trois", "Moi"), history.messages(group.id).map { it.text })
    }

    @Test fun historySurvivesClosingTheDatabase() {
        context.deleteDatabase(FILE)
        val first = History(context, FILE)
        try {
            first.addIncoming(first.groupChat().id, "!0000aaaa", "Toujours là", "fr", 5, true)
        } finally { first.close() }
        val again = History(context, FILE)
        try {
            assertEquals("Toujours là", again.lastMessage(again.groupChat().id)!!.text)
        } finally { again.close() }
    }

    private companion object { const val FILE = "history-restart-test.db" }
}

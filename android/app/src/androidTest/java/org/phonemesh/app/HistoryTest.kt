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

    @Test fun pinnedChatsComeRightAfterTheGroup() {
        val group = history.groupChat()
        val kofi = history.directChat("!0000aaaa", "Kofi")
        val ama = history.directChat("!0000bbbb", "Ama")
        history.addIncoming(kofi.id, "!0000aaaa", "Vieux", "fr", 1_000, false)
        history.addIncoming(ama.id, "!0000bbbb", "Neuf", "fr", 2_000, false)
        assertEquals(listOf(group.id, ama.id, kofi.id), history.chats().map { it.id })
        history.setPinned(kofi.id, true)
        assertEquals(listOf(group.id, kofi.id, ama.id), history.chats().map { it.id })
        assertTrue(history.chat(kofi.id)!!.pinned)
        history.setPinned(kofi.id, false)
        assertEquals(listOf(group.id, ama.id, kofi.id), history.chats().map { it.id })
    }

    @Test fun aVersionOneHistoryKeepsItsChatsAfterTheUpgrade() {
        context.deleteDatabase(OLD)
        context.openOrCreateDatabase(OLD, 0, null).use { db ->
            db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, address TEXT NOT NULL, " +
                "name TEXT NOT NULL DEFAULT '', last_activity INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0, UNIQUE (kind, address))")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL REFERENCES chats(id), " +
                "direction TEXT NOT NULL, sender_id TEXT, text TEXT NOT NULL, language TEXT, time INTEGER NOT NULL, packet_id INTEGER, delivery TEXT)")
            db.execSQL("INSERT INTO chats (kind, address, name) VALUES ('DIRECT', '!0000aaaa', 'Kofi')")
            db.execSQL("INSERT INTO messages (chat_id, direction, sender_id, text, time) VALUES (1, 'IN', '!0000aaaa', 'Salut', 5)")
            db.version = 1
        }
        val upgraded = History(context, OLD)
        try {
            val kofi = upgraded.chats().single()
            assertEquals("Kofi", kofi.name)
            assertFalse(kofi.pinned)
            assertEquals("Salut", upgraded.lastMessage(kofi.id)!!.text)
            upgraded.setPinned(kofi.id, true)
            assertTrue(upgraded.chat(kofi.id)!!.pinned)
        } finally {
            upgraded.close()
            context.deleteDatabase(OLD)
        }
    }

    @Test fun deletingAPrivateChatRemovesItButTheGroupOnlyEmpties() {
        val group = history.groupChat()
        val kofi = history.directChat("!0000aaaa", "Kofi")
        history.addIncoming(kofi.id, "!0000aaaa", "Un", "fr", 1, true)
        history.addIncoming(group.id, "!0000bbbb", "Tous", "fr", 2, true)
        assertTrue(history.deleteChat(kofi.id))
        assertNull(history.chat(kofi.id))
        assertTrue(history.messages(kofi.id).isEmpty())
        assertTrue(history.deleteChat(group.id))
        assertEquals(0, history.chat(group.id)!!.unread)
        assertTrue(history.messages(group.id).isEmpty())
        assertFalse(history.deleteChat(group.id))
        assertFalse(history.deleteChat(kofi.id))
    }

    @Test fun deletingAMessageKeepsItsChatAndTheOthers() {
        val chat = history.directChat("!0000aaaa", "Kofi")
        val first = history.addIncoming(chat.id, "!0000aaaa", "Un", "fr", 1, false)
        val second = history.addOutgoing(chat.id, "Deux", "fr", 2)
        history.attachPacket(second.id, 77)
        assertTrue(history.deleteMessage(second.id))
        assertFalse("already gone", history.deleteMessage(second.id))
        assertEquals(listOf(first.id), history.messages(chat.id).map { it.id })
        assertEquals(first.id, history.lastMessage(chat.id)!!.id)
        assertNull("a late receipt finds nothing", history.byPacket(77))
        assertNotNull(history.chat(chat.id))
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

    private companion object {
        const val FILE = "history-restart-test.db"
        const val OLD = "history-version-one-test.db"
    }
}

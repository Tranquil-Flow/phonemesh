package org.phonemesh.app

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.RadioPeer
import org.phonemesh.core.DeliveryStatus

/** Decision 39: explicit legacy text is separate from account-private traffic. Fake radio/speech only. */
@RunWith(AndroidJUnit4::class)
class LegacyRepliesTest {
    private val core get() = TestCore.core

    @After fun tearDown() {
        Views.finish(Views.resumed())
        TestCore.uninstall()
    }

    @Test fun explicitReplyUsesPlainStockTextAndTheAddressedRadioOnly() {
        TestCore.install()
        TestCore.onMain {
            val chat = core.startDirectChat(RadioPeer("!0000aaaa", "Kofi"))
            val heard = core.history.addIncoming(chat, "!0000aaaa", "hello", "fr", 1L, false, packetId = 77L)
            core.setReplyTarget(chat, heard.id)
            core.setDraft(chat, "Bonjour Kofi")
            assertTrue("decision 39 permits explicit legacy text", core.send(chat))
            val sent = TestCore.radio.sent.single()
            assertEquals("stock apps see plain words, not a Phonemesh language header", "Bonjour Kofi", sent.text)
            assertEquals("!0000aaaa", sent.destinationId)
            assertEquals(0, sent.channel)
            assertEquals(77L, sent.replyTo)
            assertFalse(sent.reaction)
            assertFalse(sent.projected)
            assertNull(core.draft(chat))
            assertEquals(sent.packetId, core.history.lastMessage(chat)!!.packetId)
            assertTrue(TestCore.radio.broadcasts.isEmpty())
            assertTrue(TestCore.radio.sentData.isEmpty())
        }
    }

    @Test fun newLegacyTextWaitsAcrossRestartThenUsesOnlyItsOriginalRadioAddress() {
        TestCore.install(connected = false)
        val chat = TestCore.onMain {
            core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")).also {
                core.setDraft(it, "kept for Kofi")
                assertTrue(core.send(it))
                assertEquals(DeliveryStatus.QUEUED, core.history.lastMessage(it)!!.status)
            }
        }
        TestCore.restart()
        TestCore.onMain { TestCore.radio.connected(true) }
        Views.waitUntil { TestCore.radio.sent.isNotEmpty() }
        TestCore.onMain {
            assertEquals("kept for Kofi", TestCore.radio.sent.single().text)
            assertEquals("!0000aaaa", TestCore.radio.sent.single().destinationId)
            assertEquals(1, core.history.messages(chat).size)
            assertTrue(TestCore.radio.broadcasts.isEmpty())
        }
    }

    @Test fun explicitRetrySendsLegacyTextButNeverAQueuedPicture() {
        TestCore.install()
        TestCore.onMain {
            val chat = core.startDirectChat(RadioPeer("!0000aaaa", "Kofi"))
            val failed = core.history.addOutgoing(chat, "try again", "fr", 1L)
            core.history.setStatus(failed.id, DeliveryStatus.FAILED)
            assertTrue(core.resend(failed.id))
            assertEquals("try again", TestCore.radio.sent.single().text)
            assertFalse(core.canSendPictures(chat))
            assertTrue(TestCore.radio.sentData.isEmpty())
        }
    }

    @Test fun aRadioAcknowledgementDoesNotClaimDeliveryToTheStockApp() {
        TestCore.install()
        TestCore.onMain {
            val chat = core.startDirectChat(RadioPeer("!0000aaaa", "Kofi"))
            val row = core.history.addOutgoing(chat, "hello", "fr", 1L)
            core.history.attachPacket(row.id, 100L)
            core.onDelivery(100L, org.phonemesh.ble.DeliveryState.ACKNOWLEDGED, "fake radio ack")
            assertEquals("a radio ACK is not an app receipt", DeliveryStatus.SENT, core.history.message(row.id)!!.status)
        }
    }

    @Test fun legacyTextCannotTargetAnAccountOrTheBroadcastNode() {
        TestCore.install()
        TestCore.onMain {
            for (address in listOf("account-not-a-radio-node", "!ffffffff", "!FFFFFFFF")) {
                val chat = core.startDirectChat(RadioPeer(address, "Not a unicast node"))
                core.setDraft(chat, "keep this draft")
                assertFalse("legacy replies must not become broadcasts: $address", core.send(chat))
                assertEquals("keep this draft", core.draft(chat))
                assertTrue(core.history.messages(chat).isEmpty())
            }
            assertTrue(TestCore.radio.sent.isEmpty())
            assertTrue(TestCore.radio.broadcasts.isEmpty())
        }
    }

    @Test fun aPersonMessageNeverFallsBackToLegacyText() {
        TestCore.install()
        val (_, person) = PersonFixture.meet("Bea")
        TestCore.onMain {
            core.setDraft(person, "private words")
            assertTrue(core.send(person))
            assertTrue("no plaintext radio-addressed send", TestCore.radio.sent.none { !it.projected })
            assertTrue(TestCore.radio.broadcasts.any { it.port == 301 })
        }
    }

    @Test fun migrationDoesNotAutoSendOldLegacyQueueEntries() {
        TestCore.install(connected = false)
        val ids = TestCore.onMain {
            val legacy = core.startDirectChat(RadioPeer("!0000aaaa", "Kofi"))
            val old = core.history.addOutgoing(legacy, "old unsent words", "fr", 1L)
            core.history.setStatus(old.id, DeliveryStatus.QUEUED)
            val shared = core.history.addOutgoing(core.groupChatId(), "shared queue", "fr", 2L)
            core.history.setStatus(shared.id, DeliveryStatus.QUEUED)
            // Schema 22 has the same tables. The new transition must explicitly retire its legacy queue only.
            core.history.writableDatabase.version = 22
            old.id to shared.id
        }
        TestCore.restart()
        TestCore.onMain {
            assertEquals("old read-only drafts are not newly authorized sends", DeliveryStatus.FAILED, core.history.message(ids.first)!!.status)
            assertEquals(DeliveryStatus.QUEUED, core.history.message(ids.second)!!.status)
        }
    }

    @Test fun legacyScreenOffersWritingAndKeepsItsPrivacyWarningVisible() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val chat = TestCore.onMain { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, chat))
        try {
            Views.waitUntil { screen.window.decorView.findViewWithTag<View>("keyboard")?.isShown == true }
            TestCore.onMain {
                val warning = screen.window.decorView.findViewWithTag<TextView>("legacy_privacy")
                assertNotNull("a toast is not a persistent privacy boundary", warning)
                assertTrue(warning!!.isShown)
                assertEquals("Old radio chat. Not a private Phonemesh chat.\nAnyone with the channel key can read messages.", warning.text.toString())
                core.setDraft(chat, "hello")
                core.setReplyTarget(chat, null) // Notify the real screen observer after setting its draft.
                assertTrue(screen.window.decorView.findViewWithTag<View>("draft_bar").isShown)
                assertTrue(warning.isShown)
            }
        } finally { Views.finish(screen) }
    }
}

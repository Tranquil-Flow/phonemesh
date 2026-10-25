package org.phonemesh.app

import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.RadioPeer

/** UI state injection only, through the fake radio: no scan, connection, recording, playback or send. */
@RunWith(AndroidJUnit4::class)
class HardeningUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun tearDown() = TestCore.uninstall()

    @Test fun deliveryStatusIsPerMessageAndNeverSaysReadOrPlayed() {
        TestCore.install()
        val core = TestCore.core
        val radio = TestCore.radio
        // The one-time tips have been seen: a tip's large picture would leave room for fewer than these three messages.
        TestCore.onMain { core.settings.tipsShown = org.phonemesh.core.Tip.entries.map { it.key }.toSet() }
        val (bea, chat) = PersonFixture.meet("Kofi")
        TestCore.onMain { listOf("Un", "Deux", "Trois").forEach { core.setDraft(chat, it); core.send(chat) } }
        val (one, two, three) = core.history.messages(chat)
        Views.waitUntil { radio.broadcasts.size == 3 }
        // One reached the other phone and was receipted; Two gave up (every resend failed); Three is unconfirmed.
        PersonFixture.acknowledge(bea, radio.broadcasts[0].payload)
        TestCore.onMain {
            core.history.outbox(bea.account).filter { it.messageId == two.id }.forEach { core.history.setOutboxState(it.id, "FAILED") }
            core.history.setStatus(two.id, org.phonemesh.core.DeliveryStatus.FAILED)
            core.history.setStatus(three.id, org.phonemesh.core.DeliveryStatus.UNCONFIRMED)
        }
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, chat))
        try {
            assertEquals("Arrivé", Views.tagged(screen, "status:${one.id}")!!.contentDescription)
            assertNull(Views.tagged(screen, "resend:${one.id}"))
            assertEquals("Échec", Views.textOf(Views.tagged(screen, "status_label:${two.id}")!!))
            assertNotNull(Views.tagged(screen, "resend:${two.id}"))
            assertEquals("Pas confirmé", Views.textOf(Views.tagged(screen, "status_label:${three.id}")!!))
            assertNotNull(Views.tagged(screen, "resend:${three.id}"))
            val words = Views.all(screen).filterIsInstance<TextView>().filter { !Ui.isContent(it) }
                .map { TestCore.onMain { it.text.toString().lowercase() } }
            val claims = Regex("\\b(lu|écouté|read|played|heard)\\b")
            words.forEach { assertFalse("claims reading or playing: $it", claims.containsMatchIn(it)) }
        } finally { Views.finish(screen) }
    }

    @Test fun radioStatusSurvivesDiagnosticsAndBadMessages() {
        TestCore.install(connected = false)
        val core = TestCore.core
        val chats = Views.start(ChatsActivity::class.java)
        try {
            assertEquals("Radio non connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
            assertNotNull(Views.tagged(chats, "card_radio"))
            TestCore.onMain { TestCore.radio.connected(true) }
            TestCore.onMain { core.onState(true, "Draining fromRadio (coalesced trigger)") }
            assertEquals("Radio connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
            assertTrue(Views.all(chats).filterIsInstance<TextView>().none { Views.textOf(it).contains("Draining") })
            TestCore.onMain { TestCore.radio.receive("!12345678", "\u0000", false) }
            assertNull(core.history.lastMessage(core.groupChatId()))
            assertEquals("Radio connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
            TestCore.onMain { TestCore.radio.connected(false) }
            assertEquals("Radio non connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
            assertNotNull(Views.tagged(chats, "card_radio"))
        } finally { Views.finish(chats) }
    }
}

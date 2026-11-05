package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.wire.Body

/**
 * v0.1.3 N2: fresh incoming text becomes one generic, coalesced alert, only when the person asked for alerts and Android
 * allows them, never for the conversation on screen, and withdrawn once read or deleted. Real app core, real sessions
 * (PersonFixture), fake alert host: nothing is posted to Android here (SystemAlertsTest covers the real one). Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class MessageAlertsTest {
    private val host get() = TestCore.alerts
    private var conversation: ConversationActivity? = null

    @After fun clean() {
        Views.finish(conversation)
        TestCore.uninstall()
    }

    private fun wantAlerts() = TestCore.onMain { TestCore.core.setNewMessageAlerts(true) }

    private fun unread(chatId: Long) = TestCore.onMain { checkNotNull(TestCore.core.history.chat(chatId)).unread }

    @Test fun nothingIsRaisedUntilThePersonAsksForAlerts() {
        val core = TestCore.install()
        val (bea, chatId) = PersonFixture.meet("Bea")
        assertFalse("off by default", TestCore.onMain { core.settings.newMessageAlerts })
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "bonjour Awa")))
        assertEquals(1, unread(chatId))
        assertTrue(host.shown.isEmpty())
    }

    @Test fun aFreshTextRaisesOneAlertForItsChatAndTheSamePacketAgainNone() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet("Bea")
        wantAlerts()
        val packet = PersonFixture.send(bea, Body.Text("fr", "bonjour Awa"))
        PersonFixture.deliver(packet)
        PersonFixture.deliver(packet)
        assertEquals(listOf(Alert(chatId, 1, fresh = true)), host.shown.toList())
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "encore"), 2_000))
        assertEquals(Alert(chatId, 2, fresh = true), host.shown.last())
    }

    @Test fun blockedAlertsKeepTheMessageUnreadAndRaiseNothingLater() {
        val core = TestCore.install()
        val (bea, chatId) = PersonFixture.meet("Bea")
        wantAlerts()
        host.allow = false
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "bloqué")))
        assertTrue(host.shown.isEmpty())
        assertEquals("kept and unread", 1, unread(chatId))
        assertTrue(TestCore.onMain { core.history.messages(chatId).any { it.text == "bloqué" } })
        assertTrue(TestCore.onMain { core.alertsBlocked() })
        // Allowed again: a later text alerts; the blocked one was not saved up for it.
        host.allow = true
        assertFalse(TestCore.onMain { core.alertsBlocked() })
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "permis"), 2_000))
        assertEquals(listOf(Alert(chatId, 1, fresh = true)), host.shown.toList())
    }

    @Test fun theConversationOnScreenRaisesNoAlertButAnotherChatDoes() {
        TestCore.install()
        val (bea, beaChat) = PersonFixture.meet("Bea")
        val (cey, ceyChat) = PersonFixture.meet("Cey", 0x00838F)
        wantAlerts()
        conversation = Views.launch(ConversationActivity.intent(InstrumentationRegistry.getInstrumentation().targetContext, beaChat))
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "je te vois")))
        assertTrue("the open chat shows it", host.shown.isEmpty())
        PersonFixture.deliver(PersonFixture.send(cey, Body.Text("fr", "et moi")))
        assertEquals(listOf(Alert(ceyChat, 1, fresh = true)), host.shown.toList())
    }

    @Test fun twoChatsShareOneAlertThatNarrowsAsEachIsRead() {
        val core = TestCore.install()
        val (bea, beaChat) = PersonFixture.meet("Bea")
        val (cey, ceyChat) = PersonFixture.meet("Cey", 0x00838F)
        wantAlerts()
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "un")))
        PersonFixture.deliver(PersonFixture.send(cey, Body.Text("fr", "deux")))
        assertEquals("one alert for both, opening the chat list", Alert(null, 2, fresh = true), host.shown.last())
        TestCore.onMain { core.openChat(beaChat); core.closeChat(beaChat) }
        assertEquals("narrowed quietly to Cey", Alert(ceyChat, 1, fresh = false), host.shown.last())
        assertEquals(0, host.cleared)
        assertEquals("Cey's unread untouched", 1, unread(ceyChat))
        TestCore.onMain { core.openChat(ceyChat); core.closeChat(ceyChat) }
        assertEquals(1, host.cleared)
    }

    @Test fun aDeletedChatsAlertIsWithdrawn() {
        val core = TestCore.install()
        val (bea, chatId) = PersonFixture.meet("Bea")
        wantAlerts()
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "à effacer")))
        assertEquals(1, host.shown.size)
        assertTrue(TestCore.onMain { core.deleteChat(chatId) })
        assertEquals(1, host.cleared)
    }

    @Test fun anAlertFromBeforeARestartIsWithdrawnOnceItsChatIsRead() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet("Bea")
        wantAlerts()
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "avant")))
        assertEquals(1, host.shown.size)
        // Android kept the alert; the app's process did not.
        val core = TestCore.restart()
        TestCore.onMain { core.openChat(chatId); core.closeChat(chatId) }
        assertEquals("the earlier process's alert goes once read", 1, host.cleared)
    }

    @Test fun turningAlertsOffWithdrawsTheAlertAndRaisesNoMore() {
        val core = TestCore.install()
        val (bea, chatId) = PersonFixture.meet("Bea")
        wantAlerts()
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "un")))
        TestCore.onMain { core.setNewMessageAlerts(false) }
        assertEquals(1, host.cleared)
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "deux"), 2_000))
        assertEquals(1, host.shown.size)
        assertEquals("both kept unread", 2, unread(chatId))
    }

    @Test fun ownSendsAndReceiptsRaiseNoAlert() {
        val core = TestCore.install()
        val (bea, chatId) = PersonFixture.meet("Bea")
        wantAlerts()
        TestCore.onMain { core.setDraft(chatId, "de moi"); assertTrue(core.send(chatId)) }
        PersonFixture.acknowledge(bea, TestCore.radio.broadcasts.single().payload)
        assertTrue(TestCore.onMain { core.history.lastMessage(chatId)!!.direction == Direction.OUT })
        assertTrue(host.shown.isEmpty())
    }

    @Test fun turningAlertsOnStartsNoBackgroundUse() {
        val core = TestCore.install()
        wantAlerts()
        assertTrue(TestCore.onMain { core.settings.newMessageAlerts })
        assertFalse("roaming stays the person's own choice", TestCore.onMain { core.settings.hubRoaming })
        assertFalse(TestCore.onMain { core.roamingActive })
        assertEquals("the alert channel is prepared", 1, host.prepared)
    }
}

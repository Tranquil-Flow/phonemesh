package org.phonemesh.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.Profile
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.wire.Body
import java.security.KeyStore

/**
 * v0.1.3 N1: a fresh incoming person text or reply is an event raised once, only after the transaction that stores it
 * commits. Real SQLite (rollback, reopen, migration) and real sessions; the last test goes through the whole app core.
 * Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class IncomingAlertsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val opened = ArrayList<History>()
    private var now = 1_791_000_000_000L
    private var installed = false

    private inner class Phone(val file: String, name: String) {
        val history = History(context, file).also { opened += it }
        val keys = AccountStore(history, Vault("test-incoming-alerts-vault"), true, { now }, keyPrefix = "test-incoming-alerts-$file")
            .also { it.setName(name, 1) }
        val flow = Accounts(history, keys, { true }, { now })
        val mail = Postman(keys, AppMailStore(history, keys))
        val alerts = IncomingAlerts(history)
    }

    private fun meet(): Pair<Phone, Phone> {
        val a = Phone(A, "Awa")
        val b = Phone(B, "Bea")
        val qa = a.flow.showCode()
        val qb = b.flow.showCode()
        assertTrue(a.flow.scanned(qb) is Accounts.Added.Contact)
        assertTrue(b.flow.scanned(qa) is Accounts.Added.Contact)
        return a to b
    }

    private fun Phone.chatWith(other: Phone): Chat = history.personChat(other.keys.accountId.toString(), "Bea")

    private fun text(from: Phone, to: Phone, words: String, at: Long = now): ByteArray =
        checkNotNull(from.mail.send(to.keys.accountId, Body.Text("fr", words), null, at)).packet

    private fun fresh(phone: Phone, words: String): IncomingAlerts.Fresh {
        val m = phone.history.chats().flatMap { phone.history.messages(it.id) }.single { it.text == words }
        return IncomingAlerts.Fresh(m.id, m.chatId, checkNotNull(phone.history.chat(m.chatId)).kind)
    }

    private fun count(history: History, table: String): Long =
        history.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    private fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }

    @After fun clean() {
        opened.forEach { it.close() }
        listOf(A, B).forEach { context.deleteDatabase(it) }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("test-incoming-alerts-") }.forEach { keys.deleteEntry(it) }
        if (installed) TestCore.uninstall()
    }

    @Test fun aFreshTextAndAReplyAreEachHandedOutOnceAfterCommit() {
        val (a, b) = meet()
        assertNotNull(a.mail.receive(text(b, a, "bonjour"), now + 1))
        assertEquals(listOf(fresh(a, "bonjour")), a.alerts.take())
        assertTrue("handed out once", a.alerts.take().isEmpty())
        val reply = checkNotNull(b.mail.send(a.keys.accountId, Body.Reply(ByteArray(4) { 1 }, "fr", "oui"), null, now + 2)).packet
        assertNotNull(a.mail.receive(reply, now + 3))
        assertEquals(listOf(fresh(a, "oui")), a.alerts.take())
        assertEquals(PERSON_KIND, fresh(a, "oui").kind)
    }

    @Test fun aDuplicateOrReplayedPacketIsNoSecondAlertEvenAfterReopen() {
        val (a, b) = meet()
        val packet = text(b, a, "une fois")
        assertNotNull(a.mail.receive(packet, now + 1))
        assertEquals(1, a.alerts.take().size)
        assertNull(a.mail.receive(packet, now + 2))
        assertTrue(a.alerts.take().isEmpty())
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertNull(reopened.mail.receive(packet, now + 3))
        assertTrue("a replay after restart raises nothing", reopened.alerts.take().isEmpty())
        assertEquals(1L, count(reopened.history, "messages"))
    }

    @Test fun aRolledBackReceiveLeavesNoAlertAndItsRetryAlertsOnce() {
        val (a, b) = meet()
        val packet = text(b, a, "après l'échec")
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_incoming BEFORE INSERT ON messages WHEN NEW.direction='IN' BEGIN SELECT RAISE(ABORT, 'injected incoming failure'); END")
        val failure = runCatching { a.mail.receive(packet, now + 1) }.exceptionOrNull()
        assertTrue(failure.toString().contains("injected incoming failure"))
        assertTrue(a.alerts.take().isEmpty())
        a.history.writableDatabase.execSQL("DROP TRIGGER reject_incoming")
        assertNotNull(a.mail.receive(packet, now + 2))
        assertEquals(listOf(fresh(a, "après l'échec")), a.alerts.take())
    }

    @Test fun anAlertThatCannotBeKeptRollsItsMessageAndReceivingKeyBackToo() {
        val (a, b) = meet()
        val packet = text(b, a, "ensemble")
        val before = checkNotNull(a.history.sessionRow(b.keys.accountId.toString()))
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_alert BEFORE INSERT ON incoming_alerts BEGIN SELECT RAISE(ABORT, 'injected alert failure'); END")
        val failure = runCatching { a.mail.receive(packet, now + 1) }.exceptionOrNull()
        assertTrue(failure.toString().contains("injected alert failure"))
        assertEquals(0L, count(a.history, "messages"))
        assertArrayEquals("the receiving key is not consumed", before.second, checkNotNull(a.history.sessionRow(b.keys.accountId.toString())).second)
        a.history.writableDatabase.execSQL("DROP TRIGGER reject_alert")
        assertNotNull(a.mail.receive(packet, now + 2))
        assertEquals(listOf(fresh(a, "ensemble")), a.alerts.take())
    }

    @Test fun receiptsReactionsProfilesAndOwnSendsAreNoAlertsButALaterTextIs() {
        val (a, b) = meet()
        // Awa's own message (her bubble and its packet) is no alert of hers.
        val chat = a.chatWith(b)
        a.history.addOutgoing(chat.id, "salut", "fr", now)
        val out = checkNotNull(a.mail.send(b.keys.accountId, Body.Text("fr", "salut"), null, now)).packet
        assertNotNull(b.mail.receive(out, now + 1))
        assertEquals("Bea's phone has one fresh text", 1, b.alerts.take().size)
        // Bea's receipt, her reaction and her newer profile all reach Awa and are kept.
        val receipts = b.mail.due(now + 60_000)
        assertTrue(receipts.isNotEmpty())
        receipts.forEach { a.mail.receive(it.packet, now + 60_001) }
        val reaction = checkNotNull(b.mail.send(a.keys.accountId, Body.Reaction(ByteArray(4) { 1 }, "\uD83D\uDC4D"), null, now + 60_002)).packet
        assertNotNull(a.mail.receive(reaction, now + 60_003))
        assertEquals(1, a.history.reactions(chat.id).size)
        val profile = checkNotNull(b.mail.send(a.keys.accountId, Body.ProfileUpdate(Profile("Bea", 0x8E24AA, revision = 2)), null, now + 60_004)).packet
        assertNotNull(a.mail.receive(profile, now + 60_005))
        assertEquals(2L, Profile.decode(checkNotNull(a.history.contactRow(b.keys.accountId.toString())?.profile))?.revision)
        assertTrue("none of those is a fresh text", a.alerts.take().isEmpty())
        assertNotNull(a.mail.receive(text(b, a, "et voilà", now + 60_006), now + 60_007))
        assertEquals(listOf(fresh(a, "et voilà")), a.alerts.take())
    }

    @Test fun nothingIsHandedOutWhileATransactionCouldStillRollBack() {
        val (a, b) = meet()
        // A hub letter is received inside the transaction that also moves the hub cursor.
        val rolled = text(b, a, "défait")
        runCatching { a.history.transaction { a.mail.receive(rolled, now + 1); error("the cursor could not move") } }
        assertEquals(0L, count(a.history, "messages"))
        assertTrue(a.alerts.take().isEmpty())
        assertNotNull(a.mail.receive(rolled, now + 2))
        a.history.transaction { assertTrue("not inside a transaction", a.alerts.take().isEmpty()) }
        assertEquals(listOf(fresh(a, "défait")), a.alerts.take())
    }

    @Test fun aDeletedChatsPendingAlertIsDropped() {
        val (a, b) = meet()
        assertNotNull(a.mail.receive(text(b, a, "effacé"), now + 1))
        assertTrue(a.history.deleteChat(a.chatWith(b).id))
        assertNotNull(a.mail.receive(text(b, a, "nouveau", now + 2), now + 3))
        assertEquals(listOf(fresh(a, "nouveau")), a.alerts.take())
    }

    @Test fun aPendingAlertSurvivesARestartAndIsHandedOutOnce() {
        val (a, b) = meet()
        assertNotNull(a.mail.receive(text(b, a, "avant la coupure"), now + 1))
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertEquals(listOf(fresh(reopened, "avant la coupure")), reopened.alerts.take())
        reopened.history.close()
        val again = Phone(A, "Awa")
        assertTrue(again.alerts.take().isEmpty())
    }

    @Test fun recordingOutsideTheStoringTransactionIsRefused() {
        val history = History(context, A).also { opened += it }
        val chat = history.personChat("0011223344556677", "Old")
        val message = history.addIncoming(chat.id, "0011223344556677", "seul", "fr", now, true)
        assertThrows(IllegalStateException::class.java) { IncomingAlerts(history).record(message) }
    }

    @Test fun pendingAlertsStayBoundedToTheNewest() {
        val history = History(context, A).also { opened += it }
        val alerts = IncomingAlerts(history)
        val chat = history.personChat("0011223344556677", "Many")
        val ids = (0 until IncomingAlerts.MAX_PENDING + 5).map { index ->
            history.transaction { history.addIncoming(chat.id, "0011223344556677", "n$index", "fr", now + index, true).also { alerts.record(it) }.id }
        }
        assertEquals(ids.takeLast(IncomingAlerts.MAX_PENDING), alerts.take().map { it.messageId })
    }

    @Test fun alertRowsHoldIdsAndATimeButNoWords() {
        val (a, b) = meet()
        assertNotNull(a.mail.receive(text(b, a, "secret du marché"), now + 1))
        val db = a.history.readableDatabase
        assertEquals(listOf("message_id", "chat_id", "committed"), columns(db, "incoming_alerts"))
        assertEquals(1L, count(a.history, "incoming_alerts"))
    }

    @Test fun aVersion23HistoryGainsAnEmptyAlertTableAndOldUnreadIsNoAlert() {
        val history = History(context, A).also { opened += it }
        val chat = history.personChat("0011223344556677", "Old")
        history.addIncoming(chat.id, "0011223344556677", "déjà là", "fr", now, true)
        history.writableDatabase.execSQL("DROP TABLE IF EXISTS incoming_alerts")
        history.writableDatabase.version = 23
        history.close()
        val reopened = History(context, A).also { opened += it }
        assertEquals(24, reopened.readableDatabase.version)
        assertEquals(listOf("message_id", "chat_id", "committed"), columns(reopened.readableDatabase, "incoming_alerts"))
        assertEquals(1, checkNotNull(reopened.chat(chat.id)).unread)
        assertTrue(IncomingAlerts(reopened).take().isEmpty())
    }

    @Test fun theAppHandsAFreshPersonTextToItsAlertSinkOnceAfterItIsKept() {
        TestCore.install()
        installed = true
        val core = TestCore.core
        val (bea, chatId) = PersonFixture.meet("Bea")
        val got = ArrayList<IncomingAlerts.Fresh>()
        TestCore.onMain { core.incomingAlertSink = { got += it } }
        val packet = PersonFixture.send(bea, Body.Text("fr", "bonjour Awa"))
        PersonFixture.deliver(packet)
        PersonFixture.deliver(packet)
        val m = TestCore.onMain { core.history.messages(chatId).single { it.text == "bonjour Awa" } }
        assertEquals(listOf(IncomingAlerts.Fresh(m.id, chatId, PERSON_KIND)), TestCore.onMain { got.toList() })
        // With no sink (no alerts wanted) a fresh text is dropped, not kept for later.
        TestCore.onMain { core.incomingAlertSink = null }
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "sans alerte"), 2_000))
        TestCore.onMain { core.incomingAlertSink = { got += it } }
        PersonFixture.deliver(PersonFixture.send(bea, Body.Text("fr", "encore"), 3_000))
        val last = TestCore.onMain { core.history.messages(chatId).single { it.text == "encore" } }
        assertEquals(listOf(m.id, last.id), TestCore.onMain { got.map { it.messageId } })
    }

    private companion object {
        const val A = "test-incoming-alerts-a.db"
        const val B = "test-incoming-alerts-b.db"
        val PERSON_KIND = ChatKind.PERSON
    }
}

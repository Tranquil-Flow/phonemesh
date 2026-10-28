package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.wire.Body
import java.security.KeyStore

/** Real SQLite rollback and reopen at the session/outbox/held ownership boundary. Emulator only. */
@RunWith(AndroidJUnit4::class)
class MailPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val opened = ArrayList<History>()
    private var now = 1_791_000_000_000L

    private inner class Phone(val file: String, name: String) {
        val history = History(context, file).also { opened += it }
        val keys = AccountStore(history, Vault("test-mail-persistence-vault"), true, { now }, keyPrefix = "test-mail-persistence-$file")
            .also { it.setName(name, 1) }
        val flow = Accounts(history, keys, { true }, { now })
        val mail = Postman(keys, AppMailStore(history, keys))
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

    private fun linked(): Pair<Phone, Phone> {
        val a = Phone(A, "Awa"); val b = Phone(B, "Bea")
        assertTrue(a.flow.openLink(b.keys.card()) is Accounts.Added.Contact)
        val hello = a.history.outbox(b.keys.accountId.toString()).single().packet
        b.flow.accept(checkNotNull(b.flow.onPacket(hello)))
        return a to b
    }

    @Test fun incomingWithoutItsRatchetKeySurvivesRestartAndOpensWhenTheKeyArrives() {
        val (a, b) = linked()
        val long = "é".repeat(90)
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", long), null, now)!!.packet
        val before = a.history.sessionRow(b.keys.accountId.toString())!!.second
        assertNull(a.mail.receive(packet, now))
        assertArrayEquals("unauthenticated held bytes cannot advance keys", before, a.history.sessionRow(b.keys.accountId.toString())!!.second)
        assertEquals(0L, count(a.history, "messages"))
        a.history.close()
        val reopened = Phone(A, "Awa")
        val keyed = b.mail.send(a.keys.accountId, Body.Text("fr", "key arrives"), null, now + 1)!!.packet
        reopened.mail.receive(keyed, now + 2)
        val chat = reopened.history.personChat(b.keys.accountId.toString(), "Bea")
        assertEquals(setOf(long, "key arrives"), reopened.history.messages(chat.id).map { it.text }.toSet())
        assertNull(reopened.mail.receive(packet, now + 3))
        assertEquals(2L, count(reopened.history, "messages"))
    }

    @Test fun incomingWithoutKeysKeepsOnlyTenAndExpiresWithoutBeingAcknowledged() {
        val (a, b) = linked()
        val packets = (0..10).map { index -> b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(89) + index), null, now)!!.packet }
        packets.forEach { assertNull(a.mail.receive(it, now)) }
        assertEquals(0L, count(a.history, "messages"))
        assertEquals(0L, count(a.history, "received_mail"))
        assertEquals("per-contact unauthenticated queue cap", 10L, count(a.history, "incoming_held"))
        // A duplicate cannot extend its original deadline.
        assertNull(a.mail.receive(packets.first(), now + 86_399_999L))
        a.history.close()
        val reopened = Phone(A, "Awa")
        val keyed = b.mail.send(a.keys.accountId, Body.Text("fr", "too late"), null, now + 86_400_000L)!!.packet
        reopened.mail.receive(keyed, now + 86_400_000L)
        assertEquals(0L, count(reopened.history, "incoming_held"))
        assertEquals(1L, count(reopened.history, "messages"))
    }

    @Test fun anIdenticalKeylessPacketCannotRestartItsLifetimeAtExpiry() {
        expiredDuplicate(false)
    }

    @Test fun anIdenticalKeylessPacketCannotRestartItsLifetimeAfterSweepAndReopen() {
        expiredDuplicate(true)
    }

    private fun expiredDuplicate(sweep: Boolean) {
        val (a, b) = linked()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        assertNull(a.mail.receive(packet, now))
        assertEquals(1L, count(a.history, "incoming_held"))
        val expiry = now + org.phonemesh.core.mail.MailStore.INCOMING_LIFETIME
        if (sweep) {
            assertTrue(a.mail.recoverIncoming(expiry).isEmpty())
            assertEquals(0L, count(a.history, "incoming_held"))
        }
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertNull(reopened.mail.receive(packet, expiry))
        assertEquals("expired ciphertext must not obtain a new retention deadline", 0L, count(reopened.history, "incoming_held"))
        assertEquals(0L, count(reopened.history, "messages"))
        assertEquals(0L, count(reopened.history, "received_mail"))
        val keyed = b.mail.send(a.keys.accountId, Body.Text("fr", "key arrived later"), null, expiry + 1)!!.packet
        assertNotNull(reopened.mail.receive(keyed, expiry + 1))
        assertEquals("the expired stored payload must not resurrect", 1L, count(reopened.history, "messages"))
        // A newly received packet that can now authenticate is still deliverable; the rule is retention, not a ban.
        assertNotNull(reopened.mail.receive(packet, expiry + 2))
        assertEquals(2L, count(reopened.history, "messages"))
    }

    @Test fun versionTenUpgradePreservesTheOriginalHeldDeadline() {
        val (a, b) = linked()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        a.mail.receive(packet, now)
        val before = a.history.sessionRow(b.keys.accountId.toString())!!.second
        a.history.writableDatabase.apply { execSQL("DROP TABLE IF EXISTS incoming_deadlines"); execSQL("DROP TABLE picture_pieces"); execSQL("DROP TABLE pending_requests"); execSQL("DROP TABLE picture_ids"); execSQL("DROP TABLE hub_custody"); execSQL("DROP TABLE hub_cursor"); version = 10 }
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertEquals(21, reopened.history.readableDatabase.version)
        assertArrayEquals(before, reopened.history.sessionRow(b.keys.accountId.toString())!!.second)
        assertEquals(1L, count(reopened.history, "incoming_held"))
        assertNull(reopened.mail.receive(packet, now + org.phonemesh.core.mail.MailStore.INCOMING_LIFETIME))
        assertEquals(0L, count(reopened.history, "incoming_held"))
        assertEquals(0L, count(reopened.history, "messages"))
    }

    @Test fun alteredCiphertextCannotRenewTheSameCodeAndBookkeepingStaysBounded() {
        val (a, b) = linked()
        val packets = (0 until Ratchet.WINDOW).map {
            b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        }
        packets.forEach { a.mail.receive(it, now) }
        assertEquals(10L, count(a.history, "incoming_held"))
        val deadline = now + org.phonemesh.core.mail.MailStore.INCOMING_LIFETIME
        // Only the first ten codes were admitted. Rejected codes must not have their window burned.
        // Different digests for an admitted code must not grow a permanent digest tombstone log.
        repeat(100) { variant ->
            val altered = packets[variant % 10].copyOf().also { it[it.lastIndex] = variant.toByte() }
            assertNull(a.mail.receive(altered, deadline))
        }
        assertEquals(0L, count(a.history, "incoming_held"))
        assertEquals(10L, count(a.history, "incoming_deadlines"))
        assertEquals(0L, count(a.history, "received_mail"))
        val qa = a.flow.showCode(); val qb = b.flow.showCode()
        assertTrue(a.flow.scanned(qb) is Accounts.Added.Contact)
        assertTrue(b.flow.scanned(qa) is Accounts.Added.Contact)
        assertEquals(0L, count(a.history, "incoming_deadlines"))
        assertNull(a.mail.receive(packets.first(), deadline + 1))
        assertEquals(0L, count(a.history, "incoming_deadlines"))
    }

    @Test fun aFullQueueDoesNotBurnTheDeadlineOfARejectedCode() {
        val (a, b) = linked()
        val packets = (0..10).map { b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet }
        packets.forEach { a.mail.receive(it, now) }
        assertEquals(10L, count(a.history, "incoming_held"))
        assertEquals("only admitted codes may acquire retention deadlines", 10L, count(a.history, "incoming_deadlines"))
        val expiry = now + org.phonemesh.core.mail.MailStore.INCOMING_LIFETIME
        assertNull(a.mail.receive(packets.last(), expiry))
        assertEquals("the formerly rejected packet gets its first admission", 1L, count(a.history, "incoming_held"))
        assertNull(a.mail.receive(packets.first(), expiry))
        assertEquals("an admitted old code cannot renew", 1L, count(a.history, "incoming_held"))
        assertEquals(0L, count(a.history, "received_mail"))
    }

    @Test fun versionTenVariantsAreClampedToTheirEarliestDeadline() { migrateVariants(false) }
    @Test fun versionTenVariantsCannotRecoverAfterAnAlreadyExpiredMinimum() { migrateVariants(true) }

    private fun migrateVariants(expired: Boolean) {
        val (a, b) = linked()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        val forged = packet.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        a.mail.receive(forged, now)
        a.mail.receive(packet, now + 1)
        assertEquals(2L, count(a.history, "incoming_held"))
        val earliest = if (expired) now - 1 else now + 1_000
        val before = a.history.sessionRow(b.keys.accountId.toString())!!.second
        a.history.writableDatabase.apply {
            execSQL("UPDATE incoming_held SET expires=?", arrayOf(now + 50_000))
            execSQL("UPDATE incoming_held SET expires=? WHERE packet=?", arrayOf(earliest, forged))
            execSQL("DROP TABLE incoming_deadlines")
            execSQL("DROP TABLE picture_pieces"); execSQL("DROP TABLE pending_requests"); execSQL("DROP TABLE picture_ids"); execSQL("DROP TABLE hub_custody"); execSQL("DROP TABLE hub_cursor")
            version = 10
        }
        a.history.close()
        val reopened = Phone(A, "Awa")
        val latest = reopened.history.readableDatabase.rawQuery("SELECT max(expires) FROM incoming_held", null)
            .use { it.moveToFirst(); it.getLong(0) }
        assertEquals("all variants inherit the earliest deadline at migration", earliest, latest)
        assertArrayEquals(before, reopened.history.sessionRow(b.keys.accountId.toString())!!.second)
        val arrival = maxOf(now, earliest) + 1
        val keyed = b.mail.send(a.keys.accountId, Body.Text("fr", "key arrived"), null, arrival)!!.packet
        assertNotNull(reopened.mail.receive(keyed, arrival))
        assertEquals(0L, count(reopened.history, "incoming_held"))
        assertEquals("no migration resurrection", 1L, count(reopened.history, "messages"))
    }

    @Test fun aForgedFirstVariantDoesNotPreventAuthenticRecovery() {
        val (a, b) = linked()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        val forged = packet.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        a.mail.receive(forged, now)
        a.mail.receive(packet, now + 1)
        assertEquals(2L, count(a.history, "incoming_held"))
        assertEquals(1L, count(a.history, "incoming_deadlines"))
        assertEquals(0L, count(a.history, "received_mail"))
        val keyed = b.mail.send(a.keys.accountId, Body.Text("fr", "key arrived"), null, now + 2)!!.packet
        a.mail.receive(keyed, now + 2)
        assertEquals(2L, count(a.history, "messages"))
        assertEquals("used recognition codes leave no metadata", 0L, count(a.history, "incoming_deadlines"))
        assertEquals("forged bytes remain unauthenticated until expiry", 1L, count(a.history, "incoming_held"))
    }

    @Test fun aLateQrResetFailurePreservesHeldCiphertextAndDeadlines() {
        val (a, b) = linked()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        a.mail.receive(packet, now)
        val before = a.history.sessionRow(b.keys.accountId.toString())!!.second
        a.flow.showCode(); val qb = b.flow.showCode()
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_deadline_delete BEFORE DELETE ON incoming_deadlines BEGIN SELECT RAISE(ABORT, 'injected deadline delete failure'); END")
        assertTrue(a.flow.scanned(qb) is Accounts.Added.NotSaved)
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertArrayEquals(before, reopened.history.sessionRow(b.keys.accountId.toString())!!.second)
        assertEquals(1L, count(reopened.history, "incoming_held"))
        assertEquals(1L, count(reopened.history, "incoming_deadlines"))
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_deadline_delete")
        reopened.flow.showCode()
        assertTrue(reopened.flow.scanned(qb) is Accounts.Added.Contact)
        assertEquals(0L, count(reopened.history, "incoming_held"))
        assertEquals(0L, count(reopened.history, "incoming_deadlines"))
    }

    @Test fun repeatingTheSameQrPreservesHeldDeadlinesUntilTheKeyArrives() {
        val (a, b) = meet()
        val (sender, receiver) = if (a.keys.session(b.keys.accountId)!!.mayStep) a to b else b to a
        val peerCode = sender.flow.showCode()
        val first = receiver.mail.send(sender.keys.accountId, Body.Text("fr", "your turn"), null, now)!!.packet
        assertNotNull(sender.mail.receive(first, now))
        val keyed = sender.mail.send(receiver.keys.accountId, Body.Text("fr", "new key"), null, now + 1)!!.packet
        val held = sender.mail.send(receiver.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now + 2)!!.packet
        assertNull(receiver.mail.receive(held, now + 2))
        assertEquals(1L, count(receiver.history, "incoming_held"))
        assertEquals(1L, count(receiver.history, "incoming_deadlines"))
        val before = receiver.history.sessionRow(sender.keys.accountId.toString())!!.second
        assertTrue(receiver.flow.scanned(peerCode) is Accounts.Added.Contact)
        assertArrayEquals(before, receiver.history.sessionRow(sender.keys.accountId.toString())!!.second)
        assertEquals(1L, count(receiver.history, "incoming_held"))
        assertEquals(1L, count(receiver.history, "incoming_deadlines"))
        assertNotNull(receiver.mail.receive(keyed, now + 3))
        assertEquals(2L, count(receiver.history, "messages"))
        assertEquals(0L, count(receiver.history, "incoming_held"))
        assertEquals(0L, count(receiver.history, "incoming_deadlines"))
    }

    @Test fun failedHeldInsertRollsBackItsFirstSeenDeadline() {
        val (a, b) = linked()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_held_insert BEFORE INSERT ON incoming_held BEGIN SELECT RAISE(ABORT, 'injected held insert failure'); END")
        assertNotNull(runCatching { a.mail.receive(packet, now) }.exceptionOrNull())
        assertEquals(0L, count(a.history, "incoming_deadlines"))
        a.history.close()
        val reopened = Phone(A, "Awa")
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_held_insert")
        assertNull(reopened.mail.receive(packet, now + 1))
        assertEquals(1L, count(reopened.history, "incoming_held"))
        assertEquals(1L, count(reopened.history, "incoming_deadlines"))
    }

    @Test fun failedHeldConsumptionRollsBackAndCanRecoverAfterRestart() {
        val (a, b) = linked()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        a.mail.receive(packet, now)
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_held_delete BEFORE DELETE ON incoming_held BEGIN SELECT RAISE(ABORT, 'injected held delete failure'); END")
        val keyed = b.mail.send(a.keys.accountId, Body.Text("fr", "key arrives"), null, now + 1)!!.packet
        val failure = runCatching { a.mail.receive(keyed, now + 2) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.toString().contains("injected held delete failure"))
        assertEquals("key-bearing packet committed, held projection rolled back", 1L, count(a.history, "messages"))
        assertEquals(1L, count(a.history, "received_mail"))
        assertEquals(1L, count(a.history, "incoming_held"))
        a.history.close()
        val reopened = Phone(A, "Awa")
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_held_delete")
        assertEquals(1, reopened.mail.recoverIncoming(now + 3).size)
        assertEquals(2L, count(reopened.history, "messages"))
        assertEquals(2L, count(reopened.history, "received_mail"))
        assertEquals(0L, count(reopened.history, "incoming_held"))
        assertTrue(reopened.mail.recoverIncoming(now + 4).isEmpty())
    }

    @Test fun unauthenticatedHeldBytesNeverBecomeContentOrReceipts() {
        val (a, b) = linked()
        val original = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        val unknown = original.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
        assertNull(a.mail.receive(unknown, now))
        assertEquals(0L, count(a.history, "incoming_held"))
        val forged = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(a.mail.receive(forged, now))
        assertEquals(1L, count(a.history, "incoming_held"))
        val keyed = b.mail.send(a.keys.accountId, Body.Text("fr", "authentic"), null, now + 1)!!.packet
        a.mail.receive(keyed, now + 2)
        assertEquals(1L, count(a.history, "messages"))
        assertEquals(1L, count(a.history, "received_mail"))
        assertEquals(1L, count(a.history, "incoming_held"))
        assertTrue(a.mail.recoverIncoming(now + 86_400_000L).isEmpty())
        assertEquals(0L, count(a.history, "incoming_held"))
    }

    @Test fun freshQrRetiresUnauthenticatedHeldPackets() {
        val (a, b) = linked()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "é".repeat(90)), null, now)!!.packet
        a.mail.receive(packet, now)
        assertEquals(1L, count(a.history, "incoming_held"))
        val qa = a.flow.showCode(); val qb = b.flow.showCode()
        assertTrue(a.flow.scanned(qb) is Accounts.Added.Contact)
        assertTrue(b.flow.scanned(qa) is Accounts.Added.Contact)
        assertEquals(0L, count(a.history, "incoming_held"))
        assertNull(a.mail.receive(packet, now + 1))
        assertEquals(0L, count(a.history, "incoming_held"))
    }

    @Test fun versionNineUpgradeKeepsSessionAndQueuedCiphertext() {
        val (a, b) = meet()
        val contact = b.keys.accountId
        val sent = a.mail.send(contact, Body.Text("fr", "kept through upgrade"), null, now)!!
        val state = a.history.sessionRow(contact.toString())!!.second
        a.history.writableDatabase.apply { execSQL("DROP TABLE incoming_held"); execSQL("DROP TABLE incoming_deadlines"); execSQL("DROP TABLE picture_pieces"); execSQL("DROP TABLE pending_requests"); execSQL("DROP TABLE picture_ids"); execSQL("DROP TABLE hub_custody"); execSQL("DROP TABLE hub_cursor"); version = 9 }
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertEquals(21, reopened.history.readableDatabase.version)
        assertEquals(0L, count(reopened.history, "incoming_held"))
        assertArrayEquals(state, reopened.history.sessionRow(contact.toString())!!.second)
        assertArrayEquals(sent.packet, reopened.mail.unsent().single().packet)
    }

    @After fun clean() {
        opened.forEach { it.close() }
        listOf(A, B).forEach { context.deleteDatabase(it) }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("test-mail-persistence-") }.forEach { keys.deleteEntry(it) }
    }

    @Test fun aHeldBodySurvivesOutboxFailureThenReopensAndCommitsOnce() {
        val (a, b) = meet()
        val them = b.keys.accountId
        repeat(Ratchet.WINDOW + 1) { index ->
            a.mail.send(them, Body.Text("fr", "message $index"), null, now)?.let { b.mail.receive(it.packet, now) }
        }
        assertEquals(1, a.history.held(them.toString()).size)
        a.mail.receive(b.mail.due(now + 60_000).single().packet, now + 60_001)
        val before = a.history.sessionRow(them.toString())!!
        val held = a.history.held(them.toString()).single()
        val outboxBefore = a.history.outbox(them.toString()).size
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected outbox failure'); END")
        val failure = runCatching { a.mail.due(now + 60_002) }.exceptionOrNull()
        assertNotNull("the real SQLite trigger must fire", failure)
        assertTrue(failure.toString().contains("injected outbox failure"))
        a.history.close()
        val reopened = Phone(A, "Awa")
        val after = reopened.history.sessionRow(them.toString())!!
        assertEquals(before.first, after.first)
        assertTrue("failed transaction preserves sealed session bytes", before.second.contentEquals(after.second))
        assertEquals(outboxBefore, reopened.history.outbox(them.toString()).size)
        assertEquals("a failed replacement must not delete the only held payload", 1, reopened.history.held(them.toString()).size)
        assertArrayEquals(held.body, reopened.history.held(them.toString()).single().body)
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_outbox")
        val sent = reopened.mail.due(now + 60_003).single()
        assertTrue(reopened.history.held(them.toString()).isEmpty())
        assertEquals(outboxBefore + 1, reopened.history.outbox(them.toString()).size)
        assertEquals("message ${Ratchet.WINDOW}", (b.mail.receive(sent.packet, now + 60_004)!!.body as Body.Text).text)
        assertTrue("no duplicate replacement", reopened.mail.due(now + 60_005).isEmpty())
    }

    @Test fun aLateHeldDeleteFailureRollsBackSessionAndReplacementPacket() {
        val (a, b) = meet()
        val them = b.keys.accountId
        repeat(Ratchet.WINDOW + 1) { index ->
            a.mail.send(them, Body.Text("fr", "message $index"), null, now)?.let { b.mail.receive(it.packet, now) }
        }
        a.mail.receive(b.mail.due(now + 60_000).single().packet, now + 60_001)
        val before = a.history.sessionRow(them.toString())!!
        val count = a.history.outbox(them.toString()).size
        // This must fire AFTER the replacement insert, demonstrating rollback of already-executed mutations.
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_held_delete BEFORE DELETE ON held WHEN (SELECT count(*) FROM outbox) > $count BEGIN SELECT RAISE(ABORT, 'injected late delete failure'); END")
        val failure = runCatching { a.mail.due(now + 60_002) }.exceptionOrNull()
        assertNotNull("held consumption must follow replacement insertion inside its transaction", failure)
        assertTrue(failure.toString().contains("injected late delete failure"))
        a.history.close()
        val reopened = Phone(A, "Awa")
        val after = reopened.history.sessionRow(them.toString())!!
        assertEquals(before.first, after.first)
        assertTrue(before.second.contentEquals(after.second))
        assertEquals(count, reopened.history.outbox(them.toString()).size)
        assertEquals(1, reopened.history.held(them.toString()).size)
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_held_delete")
        assertEquals(1, reopened.mail.due(now + 60_003).size)
        assertTrue(reopened.history.held(them.toString()).isEmpty())
    }

    @Test fun aMessageInsertFailureDoesNotConsumeItsReceivingKey() {
        val (a, b) = meet()
        val them = b.keys.accountId
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "keep before acknowledging"), null, now)!!.packet
        val before = a.history.sessionRow(them.toString())!!
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_incoming BEFORE INSERT ON messages WHEN NEW.direction='IN' BEGIN SELECT RAISE(ABORT, 'injected incoming failure'); END")
        val failure = runCatching { a.mail.receive(packet, now + 1) }.exceptionOrNull()
        assertNotNull("Postman must durably project content before returning success", failure)
        assertTrue(failure.toString().contains("injected incoming failure"))
        a.history.close()
        val reopened = Phone(A, "Awa")
        val after = reopened.history.sessionRow(them.toString())!!
        assertEquals(before.first, after.first)
        assertArrayEquals(before.second, after.second)
        assertEquals(0L, count(reopened.history, "messages"))
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_incoming")
        assertNotNull(reopened.mail.receive(packet, now + 2))
        val chat = reopened.history.personChat(them.toString(), "Bea")
        assertEquals("keep before acknowledging", reopened.history.messages(chat.id).single().text)
        assertNull(reopened.mail.receive(packet, now + 3))
        assertEquals(1L, count(reopened.history, "messages"))
    }

    @Test fun committedIncomingContentExistsBeforeAnyUiNotification() {
        val (a, b) = meet()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "survives notification gap"), null, now)!!.packet
        assertNotNull(a.mail.receive(packet, now + 1))
        // Deliberately omit every AppCore/view notification, then reconstruct the entire recipient.
        a.history.close()
        val reopened = Phone(A, "Awa")
        val chat = reopened.history.personChat(b.keys.accountId.toString(), "Bea")
        assertEquals("survives notification gap", reopened.history.messages(chat.id).single().text)
        assertEquals(1, reopened.history.chat(chat.id)!!.unread)
        assertNull(reopened.mail.receive(packet, now + 2))
        assertEquals(1L, count(reopened.history, "messages"))
    }

    private fun count(history: History, table: String): Long =
        history.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    @Test fun receiptDeadlineAndUnsentBytesSurviveTwoReopens() {
        val (a, b) = meet()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "offline receipt"), null, now)!!.packet
        a.mail.receive(packet, now + 1)
        val due = a.mail.nextDue()!!
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertEquals("the quiet deadline is durable", due, reopened.mail.nextDue())
        val receipt = reopened.mail.due(due).single()
        assertTrue(reopened.mail.due(due + 1).isEmpty())
        reopened.history.close()
        val again = Phone(A, "Awa")
        val recovered = again.mail.unsent().single()
        assertArrayEquals("reconnect resends bytes, never reseals", receipt.packet, recovered.packet)
        assertNotNull("receipts have durable transport acceptance IDs", recovered.outboxId)
        again.mail.transmitted(recovered.outboxId!!)
        assertTrue(again.mail.unsent().isEmpty())
        assertNull("accepted receipts do not request acknowledgements", again.mail.nextDue())
    }

    @Test fun aLostReceiptIsRecoveredFromAnExactDuplicateAfterRestart() {
        val (a, b) = meet()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "lost receipt"), null, now)!!.packet
        a.mail.receive(packet, now + 1)
        val lost = a.mail.due(now + 61_000).single()
        lost.outboxId?.let { a.mail.transmitted(it) }
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertNull(reopened.mail.receive(packet, now + 62_000))
        val retryAt = reopened.mail.nextDue()
        assertNotNull("exact authenticated duplicates recover lost receipts after restart", retryAt)
        val replacement = reopened.mail.due(retryAt!!).single()
        b.mail.receive(replacement.packet, retryAt + 1)
        assertEquals("DELIVERED", b.history.outbox(a.keys.accountId.toString()).single().state)
        assertEquals(1L, count(reopened.history, "messages"))
    }

    @Test fun receiptReplaysNeverCauseAnAcknowledgementLoop() {
        val (a, b) = meet()
        val packet = a.mail.send(b.keys.accountId, Body.Text("fr", "no loop"), null, now)!!.packet
        b.mail.receive(packet, now + 1)
        val receipt = b.mail.due(now + 61_000).single()
        assertNull(a.mail.receive(receipt.packet, now + 61_001))
        assertNull(a.mail.nextDue())
        assertNull(a.mail.receive(receipt.packet, now + 62_000))
        assertNull("a duplicate receipt is not data", a.mail.nextDue())
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertNull(reopened.mail.receive(receipt.packet, now + 63_000))
        assertNull(reopened.mail.nextDue())
    }

    @Test fun modifiedCiphertextWithAKnownCodeDoesNotCreateAReceipt() {
        val (a, b) = meet()
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "authentic copy only"), null, now)!!.packet
        a.mail.receive(packet, now + 1)
        val sent = a.mail.due(now + 61_000).single()
        sent.outboxId?.let { a.mail.transmitted(it) }
        val altered = packet.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(a.mail.receive(altered, now + 62_000))
        assertNull("recipient code alone does not authenticate a replay", a.mail.nextDue())
    }

    @Test fun aLateDeliveryFailureRollsBackContentAckAndReceivingStateTogether() {
        val (a, b) = meet()
        val them = b.keys.accountId
        val chat = a.history.personChat(them.toString(), "Bea")
        val original = a.history.addOutgoing(chat.id, "original", "fr", now)
        val packet = a.mail.send(them, Body.Text("fr", "original"), original.id, now)!!
        b.mail.receive(packet.packet, now + 1)
        val reply = b.mail.send(a.keys.accountId, Body.Text("fr", "reply"), null, now + 2)!!
        val before = a.history.sessionRow(them.toString())!!
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_delivery BEFORE UPDATE OF delivery ON messages WHEN NEW.delivery='DELIVERED' BEGIN SELECT RAISE(ABORT, 'injected delivery failure'); END")
        val failure = runCatching { a.mail.receive(reply.packet, now + 3) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.toString().contains("injected delivery failure"))
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertArrayEquals(before.second, reopened.history.sessionRow(them.toString())!!.second)
        assertNull(reopened.history.lastAck(them.toString()))
        assertEquals("WAITING", reopened.history.outbox(them.toString()).single().state)
        assertEquals(org.phonemesh.core.DeliveryStatus.WAITING, reopened.history.message(original.id)!!.status)
        assertEquals(1L, count(reopened.history, "messages"))
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_delivery")
        assertNotNull(reopened.mail.receive(reply.packet, now + 4))
        assertEquals(2L, count(reopened.history, "messages"))
        assertEquals(org.phonemesh.core.DeliveryStatus.DELIVERED, reopened.history.message(original.id)!!.status)
    }

    @Test fun aLateOldChainPacketAndItsReplayAreAcknowledgedAfterReopen() {
        val (a, b) = meet()
        val late = b.mail.send(a.keys.accountId, Body.Text("fr", "late old chain"), null, now)!!
        a.mail.receive(b.mail.send(a.keys.accountId, Body.Text("fr", "later"), null, now)!!.packet, now)
        repeat(3) {
            b.mail.receive(a.mail.send(b.keys.accountId, Body.Text("fr", "reply"), null, now)!!.packet, now)
            a.mail.receive(b.mail.send(a.keys.accountId, Body.Text("fr", "next turn"), null, now)!!.packet, now)
        }
        a.history.close()
        val reopened = Phone(A, "Awa")
        assertEquals("late old chain", (reopened.mail.receive(late.packet, now + 1)!!.body as Body.Text).text)
        val receipts = reopened.mail.due(now + 61_000)
        assertTrue(receipts.any { it.isReceipt })
        receipts.forEach { reopened.mail.transmitted(it.outboxId); b.mail.receive(it.packet, now + 61_001) }
        assertEquals("DELIVERED", b.history.outboxRow(late.outboxId)!!.state)
        val countBefore = count(reopened.history, "messages")
        reopened.history.close()
        val again = Phone(A, "Awa")
        assertNull(again.mail.receive(late.packet, now + 62_000))
        val replacement = again.mail.due(now + 123_000).filter { it.isReceipt }.single()
        b.mail.receive(replacement.packet, now + 123_001)
        assertEquals(countBefore, count(again.history, "messages"))
        assertEquals("DELIVERED", b.history.outboxRow(late.outboxId)!!.state)
    }

    @Test fun aFullReplayLedgerRefusesWithoutConsumingContentOrKeys() {
        val (a, b) = meet()
        val db = a.history.writableDatabase
        db.beginTransaction()
        try {
            repeat(org.phonemesh.core.mail.MailStore.MAX_REPLAYS) { index ->
                db.execSQL("INSERT INTO received_mail(digest,account_id,expires) VALUES(?,?,?)",
                    arrayOf(java.lang.String.format(java.util.Locale.ROOT, "%064x", index), b.keys.accountId.toString(), now + 500_000))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val before = a.history.sessionRow(b.keys.accountId.toString())!!
        val packet = b.mail.send(a.keys.accountId, Body.Text("fr", "bounded"), null, now)!!.packet
        assertTrue(runCatching { a.mail.receive(packet, now) }.exceptionOrNull() is org.phonemesh.core.mail.MailStore.Refused)
        assertArrayEquals(before.second, a.history.sessionRow(b.keys.accountId.toString())!!.second)
        assertEquals(0L, count(a.history, "messages"))
        assertEquals(org.phonemesh.core.mail.MailStore.MAX_REPLAYS.toLong(), count(a.history, "received_mail"))
        assertNotNull(a.mail.receive(packet, now + 500_001))
        assertEquals(1L, count(a.history, "received_mail"))
    }

    @Test fun versionSevenKeepsAccountsMessagesAndExactReceiptBytes() {
        val (a, b) = meet()
        val chat = a.history.personChat(b.keys.accountId.toString(), "Bea")
        a.history.addOutgoing(chat.id, "kept bubble", "fr", now)
        a.mail.receive(b.mail.send(a.keys.accountId, Body.Text("fr", "legacy receipt"), null, now)!!.packet, now)
        val receipt = a.mail.due(now + 61_000).single()
        val identity = a.keys.identity.copyOf()
        // Isolated test DB only: recreate the exact v7 outbox shape, preserving all original rows.
        val db = a.history.writableDatabase
        db.execSQL("ALTER TABLE outbox RENAME TO outbox_v8")
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL, message_id INTEGER, " +
            "port INTEGER NOT NULL, packet BLOB NOT NULL, chain_tag BLOB, counter INTEGER, created INTEGER NOT NULL, " +
            "tries INTEGER NOT NULL DEFAULT 0, next_try INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'WAITING', code BLOB)")
        val columns = "id,account_id,message_id,port,packet,chain_tag,counter,created,tries,next_try,state,code"
        db.execSQL("INSERT INTO outbox ($columns) SELECT $columns FROM outbox_v8")
        db.execSQL("UPDATE outbox SET state='RECEIPT' WHERE id=?", arrayOf(receipt.outboxId))
        db.execSQL("DROP TABLE outbox_v8")
        db.execSQL("CREATE INDEX outbox_by_contact ON outbox(account_id,id)")
        db.execSQL("DROP TABLE received_mail")
        db.execSQL("DROP TABLE qr_sessions")
        db.execSQL("DROP TABLE incoming_held")
        db.execSQL("DROP TABLE incoming_deadlines")
        db.execSQL("DROP TABLE picture_pieces"); db.execSQL("DROP TABLE pending_requests"); db.execSQL("DROP TABLE picture_ids")
        db.execSQL("DROP TABLE hub_custody"); db.execSQL("DROP TABLE hub_cursor")
        db.version = 7
        a.history.close()
        val upgraded = Phone(A, "Awa")
        assertEquals(21, upgraded.history.readableDatabase.version)
        assertArrayEquals(identity, upgraded.keys.identity)
        assertEquals(listOf("kept bubble", "legacy receipt"), upgraded.history.messages(chat.id).map { it.text })
        val recovered = upgraded.mail.unsent().single()
        assertTrue(recovered.isReceipt)
        assertEquals(receipt.outboxId, recovered.outboxId)
        assertArrayEquals(receipt.packet, recovered.packet)
        upgraded.mail.transmitted(recovered.outboxId)
        assertTrue(upgraded.mail.unsent().isEmpty())
    }

    private companion object {
        const val A = "test-mail-persistence-a.db"
        const val B = "test-mail-persistence-b.db"
    }
}

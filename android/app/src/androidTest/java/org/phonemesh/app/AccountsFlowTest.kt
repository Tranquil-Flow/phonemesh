package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** Accounts plan step 11 without screens: two phones add each other by QR and by link, hellos become requests. */
@RunWith(AndroidJUnit4::class)
class AccountsFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = AccountTestResources(context)
    private val opened = fixture.opened
    private var now = 1_791_000_000_000L

    private inner class Phone(file: String, name: String, colour: Int, var radioTakes: Boolean = true) {
        val history = fixture.history(file)
        val store = AccountStore(history, fixture.vault, true, { now }, keyPrefix = fixture.keyPrefix(file)).also { it.setName(name, colour) }
        val sent = ArrayList<ByteArray>()
        val flow = Accounts(history, store, { sent += it; radioTakes }, { now })
    }

    @After fun clean() = fixture.clean()

    @Test fun simultaneousLinkOpenersConvergeOnTheSmallerAccount() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        a.flow.scanned(b.flow.link()); b.flow.scanned(a.flow.link())
        val aHello = a.sent.single(); val bHello = b.sent.single()
        a.flow.onPacket(bHello); b.flow.onPacket(aHello)
        val small = if (a.store.accountId < b.store.accountId) a else b
        val large = if (small === a) b else a
        assertTrue(small.store.session(large.store.accountId)!!.iAmL)
        assertFalse("the larger opener must adopt the smaller opener's start", large.store.session(small.store.accountId)!!.iAmL)
        assertEquals("SUPERSEDED", large.history.outbox(small.store.accountId.toString()).single().state)
        assertTrue(a.flow.requests().isEmpty()); assertTrue(b.flow.requests().isEmpty())
        val am = org.phonemesh.core.mail.Postman(a.store, AppMailStore(a.history, a.store))
        val bm = org.phonemesh.core.mail.Postman(b.store, AppMailStore(b.history, b.store))
        val first = am.send(b.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "A to B"), null, now)!!
        assertNotNull(bm.receive(first.packet, now))
        val second = bm.send(a.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "B to A"), null, now)!!
        assertNotNull(am.receive(second.packet, now))
        val saved = large.history.sessionRow(small.store.accountId.toString())!!.second
        large.flow.onPacket(if (small === a) aHello else bHello)
        assertArrayEquals("a repeated old hello cannot rewind a converged session", saved, large.history.sessionRow(small.store.accountId.toString())!!.second)
    }

    @Test fun simultaneousLinkReplacementIsAtomicAndRetryableAfterFailure() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        a.flow.scanned(b.flow.link()); b.flow.scanned(a.flow.link())
        val small = if (a.store.accountId < b.store.accountId) a else b
        val large = if (small === a) b else a
        val packet = small.sent.single()
        val before = large.history.sessionRow(small.store.accountId.toString())!!.second
        val db = large.history.writableDatabase
        db.execSQL("CREATE TRIGGER reject_link_resolution BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT, 'injected link conflict failure'); END")
        runCatching { large.flow.onPacket(packet) }
        assertArrayEquals(before, large.history.sessionRow(small.store.accountId.toString())!!.second)
        assertEquals("SENT", large.history.outbox(small.store.accountId.toString()).single().state)
        db.execSQL("DROP TRIGGER reject_link_resolution")
        large.flow.onPacket(packet)
        assertFalse(large.store.session(small.store.accountId)!!.iAmL)
        assertEquals("SUPERSEDED", large.history.outbox(small.store.accountId.toString()).single().state)
    }

    @Test fun aPendingRequestSurvivesDatabaseReopenAndIsStillAcceptable() {
        val awa = Phone(A, "Awa", 3); val bea = Phone(B, "Bea", 5)
        bea.flow.scanned(awa.flow.link())
        val hello = bea.sent.single()
        val request = awa.flow.onPacket(hello)!!
        awa.history.close()
        val reopened = Phone(A, "Awa", 3)
        val pending = reopened.flow.requests().single()
        assertArrayEquals(request.id, pending.id)
        assertEquals(bea.store.accountId, pending.account)
        assertEquals("Bea", pending.name)
        assertEquals(5, pending.colour)
        assertNull("replayed ciphertext cannot duplicate the durable request", reopened.flow.onPacket(hello))
        reopened.flow.accept(pending)
        assertNotNull(reopened.store.session(bea.store.accountId))
        reopened.history.close()
        val accepted = Phone(A, "Awa", 3)
        assertTrue(accepted.flow.requests().isEmpty())
        assertNotNull(accepted.store.session(bea.store.accountId))
        assertNull(accepted.flow.onPacket(hello))
    }

    @Test fun ignoringARequestSurvivesReopenAndRetiresItsOldUiHandle() {
        val awa = Phone(A, "Awa", 3); val bea = Phone(B, "Bea", 5)
        bea.flow.scanned(awa.flow.link())
        val hello = bea.sent.single()
        val request = awa.flow.onPacket(hello)!!
        awa.flow.ignore(request)
        // Refusal may be represented by a domain result or exception; neither may mutate state.
        runCatching { awa.flow.accept(request) }
        assertNull("a stale ignored handle must not add a contact", awa.history.contactRow(bea.store.accountId.toString()))
        assertNull(awa.store.session(bea.store.accountId))
        awa.history.close()
        val reopened = Phone(A, "Awa", 3)
        assertTrue(reopened.flow.requests().isEmpty())
        assertNull(reopened.flow.onPacket(hello))
        assertNull(reopened.history.contactRow(bea.store.accountId.toString()))
    }

    @Test fun acceptingARetiredRequestCannotOverwriteANewerQrContact() {
        val awa = Phone(A, "Awa", 3); val bea = Phone(B, "Bea", 5)
        bea.flow.scanned(awa.flow.link())
        val request = awa.flow.onPacket(bea.sent.single())!!
        bea.store.setName("New Bea", 2)
        val qa = awa.flow.showCode(); val qb = bea.flow.showCode()
        awa.flow.scanned(qb); bea.flow.scanned(qa)
        val before = awa.history.sessionRow(bea.store.accountId.toString())!!.second
        runCatching { awa.flow.accept(request) }
        val contact = awa.history.contactRow(bea.store.accountId.toString())!!
        assertEquals("New Bea", contact.name)
        assertEquals(2, contact.colour)
        assertTrue(contact.met)
        assertArrayEquals(before, awa.history.sessionRow(bea.store.accountId.toString())!!.second)
        assertTrue("a QR contact retires its pending hello", awa.flow.requests().isEmpty())
    }

    @Test fun failedAcceptKeepsTheRequestAndRollsBackContactAcrossReopen() {
        val awa = Phone(A, "Awa", 3); val bea = Phone(B, "Bea", 5)
        bea.flow.scanned(awa.flow.link())
        val request = awa.flow.onPacket(bea.sent.single())!!
        awa.history.writableDatabase.execSQL("CREATE TRIGGER reject_request_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT, 'injected request acceptance failure'); END")
        runCatching { awa.flow.accept(request) }
        assertNull("contact must roll back with session failure", awa.history.contactRow(bea.store.accountId.toString()))
        assertNull(awa.store.session(bea.store.accountId))
        awa.history.close()
        val reopened = Phone(A, "Awa", 3)
        assertArrayEquals(request.id, reopened.flow.requests().single().id)
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_request_session")
        reopened.flow.accept(reopened.flow.requests().single())
        assertNotNull(reopened.store.session(bea.store.accountId))
        assertTrue(reopened.flow.requests().isEmpty())
    }


    @Test fun aFailedLinkCommitSendsNothingAndLeavesNoHalfContact() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        b.history.writableDatabase.execSQL("CREATE TRIGGER reject_hello_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected hello failure'); END")
        runCatching { b.flow.scanned(a.flow.link()) }
        assertTrue("hello cannot leave before its keys and retry bytes commit", b.sent.isEmpty())
        b.history.close()
        val reopened = Phone(B, "Bea", 5)
        assertNull(reopened.history.contactRow(a.store.accountId.toString()))
        assertNull(reopened.store.session(a.store.accountId))
        assertTrue(reopened.history.outbox(a.store.accountId.toString()).isEmpty())
    }

    @Test fun aHelloIsDurableBeforeSubmissionAndRetriesTheExactBytesAfterReopen() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        var offered: ByteArray? = null
        val flow = Accounts(b.history, b.store, { bytes ->
            val row = b.history.outbox(a.store.accountId.toString()).single()
            assertEquals("WAITING", row.state)
            assertArrayEquals(bytes, row.packet)
            assertNotNull(b.store.session(a.store.accountId))
            offered = bytes
            false
        }, { now })
        assertTrue(flow.scanned(a.flow.link()) is Accounts.Added.Contact)
        val packet = offered!!
        b.history.close()
        val again = Phone(B, "Bea", 5)
        val postman = org.phonemesh.core.mail.Postman(again.store, AppMailStore(again.history, again.store))
        val unsent = postman.unsent().single()
        assertArrayEquals(packet, unsent.packet)
        postman.transmitted(unsent.outboxId)
        assertTrue(postman.due(now + 119_999).isEmpty())
        assertArrayEquals(packet, postman.due(now + 120_000).single().packet)
        assertTrue(again.sent.isEmpty())
    }


    @Test fun freshQrReplacesBothSessionsAndRejectsAnOldPreparedWrite() {
        val a = Phone(A, "Awa", 3)
        val b = Phone(B, "Bea", 5)
        val qa = a.flow.showCode(); val qb = b.flow.showCode()
        a.flow.scanned(qb); b.flow.scanned(qa)
        val contact = b.store.accountId
        val mailA = org.phonemesh.core.mail.Postman(a.store, AppMailStore(a.history, a.store))
        val mailB = org.phonemesh.core.mail.Postman(b.store, AppMailStore(b.history, b.store))
        val old = mailA.send(contact, org.phonemesh.core.wire.Body.Text("fr", "old conversation"), null, now)!!
        mailB.receive(old.packet, now)
        val oldState = a.store.session(contact)!!
        val stale = org.phonemesh.core.session.Ratchet.seal(oldState, org.phonemesh.core.wire.Body.encode(org.phonemesh.core.wire.Body.Text("fr", "stale"))).state
        now += Accounts.SHOWN_MS + 1
        val freshA = a.flow.showCode(); val freshB = b.flow.showCode()
        assertTrue(a.flow.scanned(freshB) is Accounts.Added.Contact)
        assertTrue(b.flow.scanned(freshA) is Accounts.Added.Contact)
        assertEquals("explicit QR replacement starts a new session", 0L, a.store.session(contact)!!.serial)
        assertFalse("an old prepared update cannot resurrect the old session", a.store.storeSession(contact, stale, expected = oldState))
        a.history.close(); b.history.close()
        val againA = Phone(A, "Awa", 3); val againB = Phone(B, "Bea", 5)
        val sender = org.phonemesh.core.mail.Postman(againA.store, AppMailStore(againA.history, againA.store))
        val receiver = org.phonemesh.core.mail.Postman(againB.store, AppMailStore(againB.history, againB.store))
        assertTrue("old ciphertext is never auto-drained into the new session", sender.unsent().isEmpty())
        val packet = sender.send(contact, org.phonemesh.core.wire.Body.Text("fr", "fresh conversation"), null, now)!!
        assertEquals("fresh conversation", (receiver.receive(packet.packet, now)!!.body as org.phonemesh.core.wire.Body.Text).text)
    }

    @Test fun changingThePeersShowingRequiresAFreshLocalCodeAndCannotRestoreOldKeys() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        val qa = a.flow.showCode(); val qb = b.flow.showCode()
        a.flow.scanned(qb); b.flow.scanned(qa)
        val contact = b.store.accountId
        val mail = org.phonemesh.core.mail.Postman(a.store, AppMailStore(a.history, a.store))
        val original = mail.send(contact, org.phonemesh.core.wire.Body.Text("fr", "first"), null, now)!!
        val before = a.history.sessionRow(contact.toString())!!.second
        b.history.close()
        val restartedB = Phone(B, "Bea", 5)
        val qb2 = restartedB.flow.showCode()
        assertTrue("a different peer showing must not reuse this local one-time key", a.flow.scanned(qb2) is Accounts.Added.ShowYoursFirst)
        assertTrue("the retired local showing cannot be used with the old peer code either", a.flow.scanned(qb) is Accounts.Added.ShowYoursFirst)
        assertArrayEquals(before, a.history.sessionRow(contact.toString())!!.second)
        val qa2 = a.flow.showCode()
        assertFalse(qa == qa2)
        assertTrue(a.flow.scanned(qb2) is Accounts.Added.Contact)
        assertTrue(restartedB.flow.scanned(qa2) is Accounts.Added.Contact)
        val fresh = mail.send(contact, org.phonemesh.core.wire.Body.Text("fr", "second"), null, now)!!
        assertFalse("fresh ciphertext must not restore the previous recipient code", original.packet.copyOfRange(1, 9).contentEquals(fresh.packet.copyOfRange(1, 9)))
        val receiver = org.phonemesh.core.mail.Postman(restartedB.store, AppMailStore(restartedB.history, restartedB.store))
        assertEquals("second", (receiver.receive(fresh.packet, now)!!.body as org.phonemesh.core.wire.Body.Text).text)
    }

    @Test fun repeatedScanOfTheSameShowingDoesNotRewindAnActiveSession() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        val qa = a.flow.showCode(); val qb = b.flow.showCode()
        a.flow.scanned(qb); b.flow.scanned(qa)
        val mail = org.phonemesh.core.mail.Postman(a.store, AppMailStore(a.history, a.store))
        mail.send(b.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "keep progress"), null, now)
        val before = a.history.sessionRow(b.store.accountId.toString())!!
        assertTrue(a.flow.scanned(qb) is Accounts.Added.Contact)
        assertArrayEquals(before.second, a.history.sessionRow(b.store.accountId.toString())!!.second)
        assertEquals(1, a.history.outbox(b.store.accountId.toString()).size)
    }

    @Test fun failedQrReplacementRollsBackContactSessionAndRetryWorkAcrossReopen() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        val qa = a.flow.showCode(); val qb = b.flow.showCode()
        a.flow.scanned(qb); b.flow.scanned(qa)
        val contact = b.store.accountId
        val chat = a.history.personChat(contact.toString(), "Bea")
        val message = a.history.addOutgoing(chat.id, "keep my bubble", "fr", now)
        val mail = org.phonemesh.core.mail.Postman(a.store, AppMailStore(a.history, a.store))
        val packet = mail.send(contact, org.phonemesh.core.wire.Body.Text("fr", "keep my bubble"), message.id, now)!!
        a.history.hold(contact.toString(), null, org.phonemesh.core.wire.Body.encode(org.phonemesh.core.wire.Body.Text("fr", "held")), now)
        val ack = org.phonemesh.core.wire.Ack(byteArrayOf(1, 2), 0, 0).encode()
        a.history.saveAck(contact.toString(), ack)
        val before = a.history.sessionRow(contact.toString())!!.second
        b.store.setName("New Bea", 2)
        now += Accounts.SHOWN_MS + 1
        a.flow.showCode(); val freshB = b.flow.showCode()
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_qr BEFORE INSERT ON qr_sessions BEGIN SELECT RAISE(ABORT, 'injected QR commit failure'); END")
        assertTrue(a.flow.scanned(freshB) is Accounts.Added.NotSaved)
        a.history.close()
        val reopened = Phone(A, "Awa", 3)
        assertArrayEquals(before, reopened.history.sessionRow(contact.toString())!!.second)
        assertEquals("Bea", reopened.history.contactRow(contact.toString())!!.name)
        assertArrayEquals(ack, reopened.history.lastAck(contact.toString()))
        assertEquals(org.phonemesh.core.DeliveryStatus.WAITING, reopened.history.message(message.id)!!.status)
        assertEquals(1, reopened.history.held(contact.toString()).size)
        val queued = org.phonemesh.core.mail.Postman(reopened.store, AppMailStore(reopened.history, reopened.store)).unsent().single()
        assertArrayEquals(packet.packet, queued.packet)
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_qr")
    }

    @Test fun twoPhonesScanEachOtherAndAreMetInPerson() {
        val awa = Phone(A, "Awa", 3)
        val bea = Phone(B, "Bea", 5)
        val awaCode = awa.flow.showCode()
        val beaCode = bea.flow.showCode()
        val atAwa = awa.flow.scanned(beaCode) as Accounts.Added.Contact
        val atBea = bea.flow.scanned(awaCode) as Accounts.Added.Contact
        assertTrue(atAwa.met && atBea.met)
        assertEquals(bea.store.accountId, atAwa.account)
        assertNotNull(awa.store.session(bea.store.accountId))
        assertNotNull(bea.store.session(awa.store.accountId))
        assertEquals("Bea", awa.history.contactRow(bea.store.accountId.toString())!!.name)
        assertTrue("a QR start sends nothing over the air", awa.sent.isEmpty() && bea.sent.isEmpty())
        assertArrayEquals(awa.flow.safetyPicture(bea.store.accountId), bea.flow.safetyPicture(awa.store.accountId))
    }

    @Test fun aCodeIsTheSameForTenMinutesThenFresh() {
        val awa = Phone(A, "Awa", 3)
        val first = awa.flow.showCode()
        now += 60_000
        assertEquals(first, awa.flow.showCode())
        now += Accounts.SHOWN_MS
        assertFalse(first == awa.flow.showCode())
    }

    @Test fun scanningBeforeShowingYourOwnOrYourOwnOrJunkIsRefused() {
        val awa = Phone(A, "Awa", 3)
        val bea = Phone(B, "Bea", 5)
        val beaCode = bea.flow.showCode()
        assertTrue(awa.flow.scanned(beaCode) is Accounts.Added.ShowYoursFirst)
        assertTrue(awa.flow.scanned(awa.flow.showCode()) is Accounts.Added.Own)
        assertTrue(awa.flow.scanned("https://example.org/") is Accounts.Added.Bad)
        assertNull(awa.history.contactRow(bea.store.accountId.toString()))
    }

    @Test fun openingALinkSendsOneHelloThatBecomesARequestOnce() {
        val awa = Phone(A, "Awa", 3)
        val bea = Phone(B, "Bea", 5)
        val added = bea.flow.scanned(awa.flow.link()) as Accounts.Added.Contact
        assertFalse("a link contact is not met yet", added.met)
        assertEquals(1, bea.sent.size)
        assertTrue("the hello fits one packet", bea.sent[0].size <= 228)
        val request = awa.flow.onPacket(bea.sent[0])!!
        assertEquals("Bea", request.name)
        assertEquals(5, request.colour)
        assertNull("a replay is dropped", awa.flow.onPacket(bea.sent[0]))
        assertEquals(1, awa.flow.requests().size)
        val accepted = awa.flow.accept(request)
        assertEquals(bea.store.accountId, accepted.account)
        assertFalse(accepted.met)
        assertTrue(awa.flow.requests().isEmpty())
        assertNotNull(awa.store.session(bea.store.accountId))
        assertArrayEquals(awa.flow.safetyPicture(bea.store.accountId), bea.flow.safetyPicture(awa.store.accountId))
        // Opening the same link again adds nothing and sends nothing more.
        bea.flow.scanned(awa.flow.link())
        assertEquals(1, bea.sent.size)
    }

    @Test fun anIgnoredRequestIsGoneAndAnotherPhonesHelloIsNotOurs() {
        val awa = Phone(A, "Awa", 3)
        val bea = Phone(B, "Bea", 5)
        bea.flow.scanned(awa.flow.link())
        assertNull("a hello for Awa means nothing to Bea", bea.flow.onPacket(bea.sent[0]))
        val request = awa.flow.onPacket(bea.sent[0])!!
        awa.flow.ignore(request)
        assertTrue(awa.flow.requests().isEmpty())
        assertNull(awa.history.contactRow(bea.store.accountId.toString()))
    }

    @Test fun theSafetyPictureButtonsSetAndClearMet() {
        val awa = Phone(A, "Awa", 3)
        val bea = Phone(B, "Bea", 5)
        bea.flow.scanned(awa.flow.link())
        awa.flow.accept(awa.flow.onPacket(bea.sent[0])!!)
        val id = bea.store.accountId
        assertFalse(awa.flow.isMet(id))
        awa.flow.setMet(id, true)
        assertTrue(awa.flow.isMet(id))
        awa.flow.setMet(id, false)
        assertFalse(awa.flow.isMet(id))
    }

    @Test fun changingMetPreservesThePersistedDeliveryAck() {
        val awa = Phone(A, "Awa", 3)
        val bea = Phone(B, "Bea", 5)
        bea.flow.scanned(awa.flow.link())
        awa.flow.accept(awa.flow.onPacket(bea.sent[0])!!)
        val id = bea.store.accountId
        val ack = org.phonemesh.core.wire.Ack(byteArrayOf(1, 2), 25, 0).encode()
        awa.history.saveAck(id.toString(), ack)
        awa.flow.setMet(id, true)
        awa.flow.setMet(id, false)
        val reopened = fixture.history(A)
        assertArrayEquals("contact metadata must not replace transport state", ack, reopened.lastAck(id.toString()))
    }

    @Test fun aHelloTheRadioCannotTakeWaitsInTheOutbox() {
        val awa = Phone(A, "Awa", 3)
        val bea = Phone(B, "Bea", 5, radioTakes = false)
        assertTrue(bea.flow.scanned(awa.flow.link()) is Accounts.Added.Contact)
        assertEquals("tried once", 1, bea.sent.size)
        assertNotNull(bea.store.session(awa.store.accountId))
    }

    @Test fun cleanupPreservesUnrelatedFixtureResources() {
        val token = java.util.UUID.randomUUID().toString()
        val sentinelAlias = "test-sentinel-$token"
        val sentinelFile = "test-sentinel-$token.db"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keys.containsAlias(sentinelAlias))
        val path = context.getDatabasePath(sentinelFile)
        path.parentFile!!.mkdirs()
        check(path.createNewFile()) { "sentinel file already exists" }
        try {
            path.writeText("unrelated fixture")
            val sentinel = Vault(sentinelAlias)
            val sealed = sentinel.seal(byteArrayOf(1, 2, 3), "sentinel")
            Phone(A, "Awa", 3)
            clean() // Exercise the class's actual teardown, not a second cleanup implementation.
            assertEquals("unrelated fixture", path.readText())
            assertTrue("unrelated key must survive", keys.containsAlias(sentinelAlias))
            assertArrayEquals(byteArrayOf(1, 2, 3), sentinel.open(sealed, "sentinel"))
        } finally {
            keys.deleteEntry(sentinelAlias)
            context.deleteDatabase(sentinelFile)
        }
    }

    private fun fixtureNames() = listOf(A)

    @Test fun fixtureInstancesNeverShareDatabaseNames() {
        val other = AccountsFlowTest()
        try {
            assertTrue("each instance must have its own databases", fixtureNames().zip(other.fixtureNames()).all { (a, b) -> a != b })
        } finally { other.clean() }
    }

    private val A = fixture.database("a")
    private val B = fixture.database("b")
    @Test fun pendingRequestsRemainUsableAfterTheirOriginalPrekeyIsGone() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        val prekey = a.store.prekey.copyOf()
        b.flow.scanned(a.flow.link())
        a.flow.onPacket(b.sent.single())!!
        repeat(3) { now += 40L * 86_400_000L; a.store.rotateIfDue() }
        assertTrue(a.store.prekeys.none { it.contentEquals(prekey) })
        a.history.close()
        val reopened = Phone(A, "Awa", 3)
        reopened.flow.accept(reopened.flow.requests().single())
        val fromB = org.phonemesh.core.mail.Postman(b.store, AppMailStore(b.history, b.store))
            .send(reopened.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "still here"), null, now)!!
        val received = org.phonemesh.core.mail.Postman(reopened.store, AppMailStore(reopened.history, reopened.store))
            .receive(fromB.packet, now)!!
        assertEquals("still here", (received.body as org.phonemesh.core.wire.Body.Text).text)
    }

    @Test fun failedRequestInsertDoesNotBurnItsReplayMarker() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        b.flow.scanned(a.flow.link())
        val packet = b.sent.single()
        a.history.writableDatabase.execSQL("CREATE TRIGGER reject_pending BEFORE INSERT ON pending_requests BEGIN SELECT RAISE(ABORT, 'injected pending failure'); END")
        runCatching { a.flow.onPacket(packet) }
        a.history.close()
        val reopened = Phone(A, "Awa", 3)
        assertTrue(reopened.flow.requests().isEmpty())
        reopened.history.writableDatabase.execSQL("DROP TRIGGER reject_pending")
        assertNotNull("the exact failed request remains retryable", reopened.flow.onPacket(packet))
        assertEquals(1, reopened.flow.requests().size)
    }

    @Test fun swappingOrDamagingSealedRequestsCannotCreateContacts() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        b.flow.scanned(a.flow.link())
        val request = a.flow.onPacket(b.sent.single())!!
        val db = a.history.writableDatabase
        val sealed = db.rawQuery("SELECT sealed FROM pending_requests", null).use { it.moveToFirst(); it.getBlob(0) }
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        db.execSQL("UPDATE pending_requests SET sealed=?", arrayOf(sealed))
        assertTrue(a.flow.requests().isEmpty())
        runCatching { a.flow.accept(request) }
        assertNull(a.history.contactRow(b.store.accountId.toString()))
        assertNull(a.store.session(b.store.accountId))
    }

    @Test fun thePendingCapDoesNotBurnAnUnadmittedHello() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        b.flow.scanned(a.flow.link())
        val db = a.history.writableDatabase
        repeat(128) { i ->
            val account = java.lang.String.format(java.util.Locale.ROOT, "%016x", i)
            val id = ByteArray(16).also { it[0] = i.toByte() }
            db.execSQL("INSERT INTO pending_requests(account_id,hello_id,sealed,at) VALUES(?,?,?,?)", arrayOf(account, id, byteArrayOf(0), now))
        }
        assertNull(a.flow.onPacket(b.sent.single()))
        db.execSQL("DELETE FROM pending_requests")
        assertNotNull(a.flow.onPacket(b.sent.single()))
    }

    @Test fun upgradingVersionElevenPreservesContactsAndExactHelloBytes() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        b.flow.scanned(a.flow.link())
        val packet = b.sent.single().copyOf()
        val state = b.history.sessionRow(a.store.accountId.toString())!!.second
        b.history.writableDatabase.apply { execSQL("DROP TABLE picture_pieces"); execSQL("DROP TABLE pending_requests"); execSQL("DROP TABLE picture_ids"); execSQL("DROP TABLE hub_custody"); execSQL("DROP TABLE hub_cursor"); version = 11 }
        b.history.close()
        val reopened = Phone(B, "Bea", 5)
        assertEquals(18, reopened.history.readableDatabase.version)
        assertTrue(reopened.flow.requests().isEmpty())
        assertEquals("Awa", reopened.history.contactRow(a.store.accountId.toString())!!.name)
        assertArrayEquals(packet, reopened.history.outbox(a.store.accountId.toString()).single().packet)
        assertArrayEquals(state, reopened.history.sessionRow(a.store.accountId.toString())!!.second)
    }

    @Test fun aFreshHelloCannotResetAnEstablishedLinkSession() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        val small = if (a.store.accountId < b.store.accountId) a else b
        val large = if (small === a) b else a
        large.flow.scanned(small.flow.link())
        small.flow.accept(small.flow.onPacket(large.sent.single())!!)
        val sm = org.phonemesh.core.mail.Postman(small.store, AppMailStore(small.history, small.store))
        val lm = org.phonemesh.core.mail.Postman(large.store, AppMailStore(large.history, large.store))
        val response = sm.send(large.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "established"), null, now)!!
        assertNotNull(lm.receive(response.packet, now))
        assertTrue(large.store.session(small.store.accountId)!!.heard)
        val before = large.history.sessionRow(small.store.accountId.toString())!!.second
        val next = org.phonemesh.core.session.Start.link(small.store, org.phonemesh.core.account.Profile("new claim", 0xFDD835), large.store.card(),
            org.phonemesh.core.session.KeyPairBytes.generate())!!.hello
        assertNull(large.flow.onPacket(next))
        assertArrayEquals("F13 is only for crossed, unanswered starts", before,
            large.history.sessionRow(small.store.accountId.toString())!!.second)
        val followup = sm.send(large.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "unchanged"), null, now)!!
        assertNotNull(lm.receive(followup.packet, now))
    }

    @Test fun swappingSealedRowsCannotAdmitEitherRequest() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        b.flow.scanned(a.flow.link())
        val first = a.flow.onPacket(b.sent.single())!!
        val c = org.phonemesh.core.session.SoftKeys.generate()
        val hello = org.phonemesh.core.session.Start.link(c, org.phonemesh.core.account.Profile("Cy", 0xFDD835), a.store.card(),
            org.phonemesh.core.session.KeyPairBytes.generate())!!.hello
        val second = a.flow.onPacket(hello)!!
        val db = a.history.writableDatabase
        fun sealed(account: String) = db.rawQuery("SELECT sealed FROM pending_requests WHERE account_id=?", arrayOf(account))
            .use { it.moveToFirst(); it.getBlob(0) }
        val one = sealed(first.account.toString()); val two = sealed(second.account.toString())
        db.execSQL("UPDATE pending_requests SET sealed=? WHERE account_id=?", arrayOf(two, first.account.toString()))
        db.execSQL("UPDATE pending_requests SET sealed=? WHERE account_id=?", arrayOf(one, second.account.toString()))
        assertTrue(a.flow.requests().isEmpty())
        runCatching { a.flow.accept(first) }; runCatching { a.flow.accept(second) }
        assertNull(a.history.contactRow(first.account.toString()))
        assertNull(a.history.contactRow(second.account.toString()))
        assertNull(a.store.session(first.account)); assertNull(a.store.session(second.account))
    }

    @Test fun openingTheSmallerPendingPeersLinkAdoptsTheirDurableStart() = pendingLinkIsAdopted(true)

    @Test fun openingTheLargerPendingPeersLinkAdoptsTheirDurableStart() = pendingLinkIsAdopted(false)

    private fun pendingLinkIsAdopted(openerSmaller: Boolean) {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        val smaller = if (a.store.accountId < b.store.accountId) a else b
        val larger = if (smaller === a) b else a
        val opener = if (openerSmaller) smaller else larger
        val owner = if (openerSmaller) larger else smaller
        opener.flow.scanned(owner.flow.link())
        val hello = opener.sent.single()
        assertNotNull(owner.flow.onPacket(hello))
        assertEquals(1, owner.flow.requests().size)
        val added = owner.flow.scanned(opener.flow.link()) as Accounts.Added.Contact
        assertEquals(opener.store.accountId, added.account)
        assertTrue("opening their link consents to the already-durable request", owner.flow.requests().isEmpty())
        assertTrue("no competing hello is sent", owner.sent.isEmpty())
        assertFalse(owner.store.session(opener.store.accountId)!!.iAmL)
        val before = owner.history.sessionRow(opener.store.accountId.toString())!!.second
        assertNull(owner.flow.onPacket(hello))
        assertArrayEquals(before, owner.history.sessionRow(opener.store.accountId.toString())!!.second)
        val om = org.phonemesh.core.mail.Postman(opener.store, AppMailStore(opener.history, opener.store))
        val rm = org.phonemesh.core.mail.Postman(owner.store, AppMailStore(owner.history, owner.store))
        val first = om.send(owner.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "one"), null, now)!!
        assertNotNull(rm.receive(first.packet, now))
        val second = rm.send(opener.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "two"), null, now)!!
        assertNotNull(om.receive(second.packet, now))
    }

    /** Requires the new durable-request schema; not an existing-API behavioral RED on schema 11. */
    @Test fun openingALinkCannotDiscardAnUnreadablePendingRequest() {
        val a = Phone(A, "Awa", 3); val b = Phone(B, "Bea", 5)
        b.flow.scanned(a.flow.link())
        val request = a.flow.onPacket(b.sent.single())!!
        val account = b.store.accountId.toString()
        val db = a.history.writableDatabase
        val sealed = db.rawQuery("SELECT sealed FROM pending_requests WHERE account_id=?", arrayOf(account))
            .use { it.moveToFirst(); it.getBlob(0) }
        db.execSQL("UPDATE pending_requests SET sealed=? WHERE account_id=?", arrayOf(byteArrayOf(0), account))
        assertTrue(a.flow.scanned(b.flow.link()) is Accounts.Added.NotSaved)
        assertTrue(a.sent.isEmpty())
        assertNull(a.history.contactRow(account))
        assertNull(a.store.session(b.store.accountId))
        assertTrue(a.history.outbox(account).isEmpty())
        assertEquals(1, db.rawQuery("SELECT count(*) FROM pending_requests WHERE account_id=?", arrayOf(account))
            .use { it.moveToFirst(); it.getInt(0) })
        // Restoring readability models recovery without deleting the owner's durable request.
        db.execSQL("UPDATE pending_requests SET sealed=? WHERE account_id=?", arrayOf(sealed, account))
        val added = a.flow.scanned(b.flow.link()) as Accounts.Added.Contact
        assertEquals(request.account, added.account)
        assertTrue(a.sent.isEmpty())
        assertTrue(a.flow.requests().isEmpty())
        val am = org.phonemesh.core.mail.Postman(a.store, AppMailStore(a.history, a.store))
        val bm = org.phonemesh.core.mail.Postman(b.store, AppMailStore(b.history, b.store))
        val packet = bm.send(a.store.accountId, org.phonemesh.core.wire.Body.Text("fr", "recovered"), null, now)!!
        assertNotNull(am.receive(packet.packet, now))
    }

}

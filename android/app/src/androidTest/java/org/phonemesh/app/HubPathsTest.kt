package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubWire
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Body
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Accounts plan step 16, in the app: the real AppCore, History and Postman with a fake [HubLink] and a scripted hub
 * wire, and Bea's phone played by a JVM Postman (as PersonChatTest does). Unexecuted: written without a device.
 * Entry points are the ordinary ones: send, resend paths, onBroadcastData, activityStarted, useHub, restart.
 */
@RunWith(AndroidJUnit4::class)
class HubPathsTest {
    private val core get() = TestCore.core
    private val pin = HubCard.pinOf(ByteArray(91) { it.toByte() })
    private val card = HubCard("Village", "password1", 8443, pin)

    @After fun tearDown() = TestCore.uninstall()

    private enum class Mode { UP, DOWN, BUSY, REFUSE }

    /** The hub as the phone sees it. Its sends run on the app's hub thread, so state is concurrent. */
    private class ScriptedWire : HubWire {
        @Volatile var mode = Mode.UP
        @Volatile var gate: CountDownLatch? = null
        @Volatile var mailGate: CountDownLatch? = null
        @Volatile var failAfterGate = false
        @Volatile var afterFirst: CountDownLatch? = null
        @Volatile var filterCodes = false
        @Volatile var newest = 0L
        val sent = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
        val letters = CopyOnWriteArrayList<HubClient.Mail>()
        val polls = CopyOnWriteArrayList<Long>()
        val helloFlags = CopyOnWriteArrayList<Boolean>()

        override fun send(port: Int, packet: ByteArray): HubClient.Sent {
            sent += port to packet.copyOf()
            gate?.await(10, TimeUnit.SECONDS)
            if (sent.size > 1) afterFirst?.await(10, TimeUnit.SECONDS)
            if (failAfterGate) throw IOException("lost after waiting")
            return when (mode) {
                Mode.UP -> HubClient.Sent.STORED
                Mode.DOWN -> throw IOException("no hub")
                Mode.BUSY -> HubClient.Sent.BUSY
                Mode.REFUSE -> HubClient.Sent.REFUSED
            }
        }

        override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<HubClient.Mail> {
            polls += since
            helloFlags += hellos
            mailGate?.await(10, TimeUnit.SECONDS)
            return letters.filter { it.seq > since && (!filterCodes || (Start.isHello(it.packet) && hellos) ||
                org.phonemesh.core.session.SessionState.hexOf(it.packet.copyOfRange(1, 9)) in codes) }
        }

        override fun latest(): Long = newest
    }

    /** Like WifiHubLink from the app's side: leave() drops the wire; join() only remembers who to tell. */
    private class FakeHubLink : HubLink {
        @Volatile var wire: HubWire? = null
        var joins = 0
        var leaves = 0
        private var changed: (() -> Unit)? = null
        override fun wire(): HubWire? = wire
        override fun join(card: HubCard, changed: () -> Unit) { joins++; this.changed = changed }
        override fun leave() { leaves++; wire = null }
        fun up(w: HubWire) { wire = w; changed?.invoke() }
        fun down() { wire = null; changed?.invoke() }
    }

    private class Rig(val bea: PersonChatTest.Bea, val chatId: Long, val link: FakeHubLink, val wire: ScriptedWire)

    /** Awa (the app) and Bea scanned each other; a screen is open (hubs are allowed); the hub's Wi-Fi is joined. */
    private fun rig(hubUp: Boolean = true): Rig {
        TestCore.install()
        TestCore.radio.acceptBroadcasts = true
        val bea = PersonChatTest.Bea()
        val awaText = TestCore.onMain { core.accountsFlow.showCode() }
        val awaQr = (AddCode.parse(awaText) as AddCode.Qr).code
        val added = TestCore.onMain { core.accountsFlow.scanned(AddCode.of(bea.qr)) } as Accounts.Added.Contact
        bea.awa = awaQr.card.accountId
        bea.store.sessions[bea.awa] = Start.qr(bea.keys, bea.qr, bea.pair, awaQr)!!
        val link = FakeHubLink()
        val wire = ScriptedWire()
        TestCore.onMain {
            core.settings.hubCard = card.encode()
            core.hub = link
            core.activityStarted()
        }
        if (hubUp) link.up(wire)
        return Rig(bea, added.chatId, link, wire)
    }

    private fun send(rig: Rig, text: String) = TestCore.onMain { core.setDraft(rig.chatId, text); assertTrue(core.send(rig.chatId)) }

    /** Views.waitUntil runs its predicate on the main thread, where TestCore.onMain (runOnMainSync) must not be called. */
    private fun <T> here(block: () -> T): T =
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else TestCore.onMain(block)

    private fun rows(rig: Rig) = here { core.history.outbox(rig.bea.qr.card.accountId.toString()) }

    private fun lastStatus(rig: Rig) = here { core.history.lastMessage(rig.chatId)!!.status }

    private fun fromBea(rig: Rig, packet: ByteArray) =
        TestCore.onMain { core.onBroadcastData("!0000bea0", org.phonemesh.ble.MeshRadio.ACCOUNT_PORT, packet, 0, 1L, "") }

    private fun cursor() = here { HubCursor(core.history).get(pin) }

    @Test fun pinnedCustodyAndVisibleHubStatusRollBackTogetherOnSqlFailure() {
        val rig = rig(hubUp = false)
        send(rig, "preuve persistante")
        Views.waitUntil { lastStatus(rig) == DeliveryStatus.SENT }
        val row = rows(rig).single()
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER refuse_custody BEFORE INSERT ON hub_custody BEGIN SELECT RAISE(ABORT, 'owned custody refusal'); END")
            try {
                AppMailStore(core.history, core.accounts).markHubReached(row.id, pin)
                throw AssertionError("storage refusal must propagate")
            } catch (_: android.database.sqlite.SQLiteException) {
                assertFalse(HubCustody(core.history).has(row.id, pin))
                assertEquals("SENT", core.history.outboxRow(row.id)!!.state)
                assertEquals(DeliveryStatus.SENT, core.history.lastMessage(rig.chatId)!!.status)
            } finally {
                core.history.writableDatabase.execSQL("DROP TRIGGER refuse_custody")
            }
            AppMailStore(core.history, core.accounts).markHubReached(row.id, pin)
            assertTrue(HubCustody(core.history).has(row.id, pin))
        }
        assertEquals(DeliveryStatus.HUB, lastStatus(rig))
    }

    @Test fun schema14HubRowsUpgradeWithoutInventingPinnedCustody() {
        val rig = rig()
        send(rig, "gardé avant la mise à jour")
        Views.waitUntil { lastStatus(rig) == DeliveryStatus.HUB }
        val row = rows(rig).single()
        TestCore.onMain {
            core.history.writableDatabase.execSQL("DROP TABLE hub_custody")
            core.history.writableDatabase.version = 14
        }
        TestCore.restart()
        TestCore.onMain {
            assertEquals(16, core.history.readableDatabase.version)
            assertTrue(row.packet.contentEquals(core.history.outboxRow(row.id)!!.packet))
            assertEquals("HUB", core.history.outboxRow(row.id)!!.state)
            assertFalse("legacy HUB is not proof of a particular pinned hub", HubCustody(core.history).has(row.id, pin))
            assertEquals(DeliveryStatus.HUB, core.history.lastMessage(rig.chatId)!!.status)
        }
    }

    @Test fun anAcceptingHubIsOfferedTheSameCiphertextAgainAtTheRetryDeadline() {
        // Owner-approved recovery (2026-10-25): a hub airs a packet once, so a missed airing or a lost receipt must not
        // strand the sender at "reached the hub". The scheduled retry goes to the same pinned hub with the exact bytes.
        val rig = rig()
        send(rig, "en attente de Bea")
        Views.waitUntil { lastStatus(rig) == DeliveryStatus.HUB }
        val original = rows(rig).single()
        TestCore.onMain {
            core.history.updateOutbox(original.id, original.tries, System.currentTimeMillis() - 1, original.state)
            core.runPostman()
        }
        Views.waitUntil { TestCore.radio.broadcasts.isNotEmpty() || rig.wire.sent.size > 1 }
        assertEquals("the retry goes back to the accepting hub", 2, rig.wire.sent.size)
        assertTrue("identical stored ciphertext, never resealed", original.packet.contentEquals(rig.wire.sent[1].second))
        assertTrue("the hub took it, so the radio stays quiet", TestCore.radio.broadcasts.isEmpty())
        assertEquals("a hub retry is still only reached the hub", DeliveryStatus.HUB, lastStatus(rig))
        assertEquals("en attente de Bea", (rig.bea.postman.receive(rig.wire.sent[1].second, 1_000)!!.body as Body.Text).text)
        fromBea(rig, rig.bea.postman.due(60_000).single().packet)
        assertEquals("only the recipient's authenticated receipt is Delivered", DeliveryStatus.DELIVERED, lastStatus(rig))
    }

    @Test fun acceptingHubCustodySurvivesPhoneRestartButDoesNotBelongToAnotherHub() {
        val rig = rig()
        send(rig, "gardé au relais")
        Views.waitUntil { lastStatus(rig) == DeliveryStatus.HUB }
        val original = rows(rig).single()
        TestCore.restart()
        val sameLink = FakeHubLink()
        val sameWire = ScriptedWire()
        TestCore.onMain {
            core.hub = sameLink
            // restart installs a fresh fake radio; preserve the fixture's explicit queue-acceptance policy.
            TestCore.radio.acceptBroadcasts = true
            TestCore.radio.connected(true)
            core.activityStarted()
            core.history.updateOutbox(original.id, original.tries, System.currentTimeMillis() - 1, original.state)
            sameLink.up(sameWire)
        }
        Views.waitUntil { TestCore.radio.broadcasts.isNotEmpty() || sameWire.sent.isNotEmpty() }
        assertTrue("custody row survives SQLite reopen", here { HubCustody(core.history).has(original.id, pin) })
        assertTrue("after restart the retry still reaches the same hub", original.packet.contentEquals(sameWire.sent.single().second))
        assertTrue(TestCore.radio.broadcasts.isEmpty())
        val otherCard = HubCard("Other village", "password1", 8443, HubCard.pinOf(ByteArray(91) { (it + 1).toByte() }))
        val otherWire = ScriptedWire()
        TestCore.onMain {
            core.history.updateOutbox(original.id, original.tries, System.currentTimeMillis() - 1, original.state)
            core.useHub(otherCard)
            sameLink.up(otherWire)
        }
        Views.waitUntil { otherWire.sent.isNotEmpty() }
        assertTrue("another hub gets the same durable ciphertext", original.packet.contentEquals(otherWire.sent.single().second))
        assertFalse(lastStatus(rig) == DeliveryStatus.DELIVERED)
    }

    @Test fun anUnsignedRadioHubAckDoesNotClaimCustodyForTheCurrentlySavedPinnedHub() {
        val rig = rig(hubUp = false)
        send(rig, "pas une preuve du relais")
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        val row = rows(rig).single()
        TestCore.onMain {
            core.onBroadcastData("!0000bea0", org.phonemesh.ble.MeshRadio.HUB_ACK_PORT,
                org.phonemesh.core.wire.HubAck.encode(listOf(checkNotNull(row.code))), 0, 2L, "")
            core.history.updateOutbox(row.id, row.tries, System.currentTimeMillis() - 1, "HUB")
            rig.link.up(rig.wire)
        }
        Views.waitUntil { rig.wire.sent.isNotEmpty() }
        assertEquals(1, rig.wire.sent.size)
        assertFalse(lastStatus(rig) == DeliveryStatus.DELIVERED)
    }

    @Test fun aReachedHubMarksHubNotDeliveredAndTheRadioStaysQuiet() {
        val rig = rig()
        send(rig, "par le relais")
        Views.waitUntil { rows(rig).singleOrNull()?.state == "HUB" }
        assertTrue("the hub took it, so the radio did not", TestCore.radio.broadcasts.isEmpty())
        val row = rows(rig).single()
        assertEquals(1, rig.wire.sent.size)
        assertEquals(301, rig.wire.sent[0].first)
        assertTrue("the hub got the exact stored bytes", row.packet.contentEquals(rig.wire.sent[0].second))
        assertEquals(DeliveryStatus.HUB, lastStatus(rig))
        assertEquals("par le relais", (rig.bea.postman.receive(rig.wire.sent[0].second, 1_000)!!.body as Body.Text).text)
        assertEquals("Bea having it from the hub is not a receipt yet", DeliveryStatus.HUB, lastStatus(rig))
        fromBea(rig, rig.bea.postman.due(60_000).single().packet)
        assertEquals(DeliveryStatus.DELIVERED, lastStatus(rig))
    }

    private fun fallsBackToTheRadio(mode: Mode) {
        val rig = rig()
        rig.wire.mode = mode
        send(rig, "si le relais manque")
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        val tried = rig.wire.sent.single().second
        val stored = rows(rig).single().packet
        assertTrue("the radio sends exactly what the hub was offered", tried.contentEquals(TestCore.radio.broadcasts[0].payload))
        assertTrue("and exactly what is stored", stored.contentEquals(tried))
        Views.waitUntil { lastStatus(rig) == DeliveryStatus.SENT }
        assertEquals("SENT", rows(rig).single().state)
        assertFalse(lastStatus(rig) == DeliveryStatus.DELIVERED)
        send(rig, "pendant que le relais se repose")
        Views.waitUntil { TestCore.radio.broadcasts.size == 2 }
        assertEquals("a failed hub rests: it is not asked again at once", 1, rig.wire.sent.size)
        assertEquals("si le relais manque", (rig.bea.postman.receive(TestCore.radio.broadcasts[0].payload, 1_000)!!.body as Body.Text).text)
    }

    @Test fun aDeadHubFallsBackToTheRadioWithTheExactBytes() = fallsBackToTheRadio(Mode.DOWN)

    @Test fun aBusyHubFallsBackToTheRadioWithTheExactBytes() = fallsBackToTheRadio(Mode.BUSY)

    @Test fun aRefusingHubFallsBackToTheRadioWithTheExactBytes() = fallsBackToTheRadio(Mode.REFUSE)

    @Test fun switchingBetweenRadioAndHubSendsEachMessageOnceByTheBestWayThen() {
        val rig = rig(hubUp = false)
        send(rig, "un")
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        assertTrue(rig.wire.sent.isEmpty())
        rig.link.up(rig.wire)
        send(rig, "deux")
        Views.waitUntil { rows(rig).count { it.state == "HUB" } == 1 }
        assertEquals("the radio did not repeat it", 1, TestCore.radio.broadcasts.size)
        rig.link.down()
        send(rig, "trois")
        Views.waitUntil { TestCore.radio.broadcasts.size == 2 }
        assertEquals(1, rig.wire.sent.size)
        Views.waitUntil { rows(rig).map { it.state } == listOf("SENT", "HUB", "SENT") }
        assertTrue(rows(rig).none { it.state == "DELIVERED" })
    }

    @Test fun aLateStoredAnswerAfterTheHubLeftStillMeansOnlyReachedHub() {
        val rig = rig()
        rig.wire.gate = CountDownLatch(1)
        send(rig, "en vol")
        Views.waitUntil { rig.wire.sent.size == 1 }
        rig.link.down()
        TestCore.onMain { core.activityStopped() }
        rig.wire.gate!!.countDown()
        Views.waitUntil { rows(rig).singleOrNull()?.state == "HUB" }
        assertTrue(TestCore.radio.broadcasts.isEmpty())
        assertEquals(DeliveryStatus.HUB, lastStatus(rig))
    }

    @Test fun aLateFailureAfterTheHubLeftGoesToTheRadioWithTheExactBytes() {
        val rig = rig()
        rig.wire.gate = CountDownLatch(1)
        rig.wire.failAfterGate = true
        send(rig, "en vol perdu")
        Views.waitUntil { rig.wire.sent.size == 1 }
        rig.link.down()
        rig.wire.gate!!.countDown()
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        assertTrue(rig.wire.sent[0].second.contentEquals(TestCore.radio.broadcasts[0].payload))
        assertTrue(rows(rig).single().packet.contentEquals(TestCore.radio.broadcasts[0].payload))
        assertFalse(lastStatus(rig) == DeliveryStatus.DELIVERED)
    }

    @Test fun aPollThatFinishesAfterTheHubWasReplacedIsThrownAway() {
        val rig = rig(hubUp = false)
        val packet = rig.bea.postman.send(rig.bea.awa, Body.Text("fr", "trop tard"), 1, 0)!!.packet
        rig.wire.letters += HubClient.Mail(7, 301, packet)
        rig.wire.mailGate = CountDownLatch(1)
        rig.link.up(rig.wire)
        Views.waitUntil { rig.wire.polls.isNotEmpty() }
        TestCore.onMain { core.useHub(HubCard("Autre", "password2", 8443, HubCard.pinOf(ByteArray(91) { (it + 1).toByte() }))) }
        rig.wire.mailGate!!.countDown()
        Thread.sleep(700)
        TestCore.onMain { }
        assertTrue(TestCore.onMain { core.history.messages(rig.chatId).isEmpty() })
        assertEquals(0L, cursor())
    }

    @Test fun aLetterIsOpenedOnceAndTheCursorMovesOnlyWithItsCommit() {
        val rig = rig()
        val packet = rig.bea.postman.send(rig.bea.awa, Body.Text("fr", "bonjour par le relais"), 1, 0)!!.packet
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER fail_incoming BEFORE INSERT ON messages WHEN NEW.direction='IN' BEGIN SELECT RAISE(ABORT, 'injected incoming failure'); END")
        }
        rig.wire.letters += HubClient.Mail(7, 301, packet)
        Views.waitUntil(8_000) { core.lastNote != null }
        TestCore.onMain {
            assertTrue(core.history.messages(rig.chatId).isEmpty())
            assertEquals("a letter that did not commit does not move the cursor", 0L, HubCursor(core.history).get(pin))
            core.history.writableDatabase.execSQL("DROP TRIGGER fail_incoming")
        }
        Views.waitUntil(30_000) { core.history.messages(rig.chatId).size == 1 }
        assertEquals("bonjour par le relais", TestCore.onMain { core.history.messages(rig.chatId).single().text })
        assertEquals(7L, cursor())
        TestCore.restart()
        assertEquals("the cursor is durable, per hub pin", 7L, cursor())
        assertEquals(0L, TestCore.onMain { HubCursor(core.history).get(HubCard.pinOf(ByteArray(91) { (it + 1).toByte() })) })
    }

    @Test fun aHubThatLostItsMailMakesTheCursorStartAgain() {
        val rig = rig()
        TestCore.onMain { HubCursor(core.history).advance(pin, 5) }
        rig.link.down()
        rig.wire.newest = 2
        rig.link.up(rig.wire)
        Views.waitUntil(8_000) { HubCursor(core.history).get(pin) == 0L }
    }
    @Test fun anAccountWithoutContactsCollectsAndPersistsItsFirstHubHello() {
        TestCore.install()
        val owner = TestCore.onMain { AddCode.parse(core.accountsFlow.link())!!.card }
        val bea = PersonChatTest.Bea()
        val hello = Start.link(bea.keys, org.phonemesh.core.account.Profile("Bea", 0x43A047), owner, bea.pair)!!.hello
        val link = FakeHubLink()
        val wire = ScriptedWire()
        wire.letters += HubClient.Mail(7, 301, hello)
        TestCore.onMain {
            core.settings.hubCard = card.encode()
            core.hub = link
            core.activityStarted()
        }
        link.up(wire)
        Views.waitUntil(8_000) { core.accountsFlow.requests().size == 1 }
        assertTrue("unpaired accounts must explicitly request hellos", wire.helloFlags.isNotEmpty() && wire.helloFlags.all { it })
        assertEquals(7L, cursor())
        TestCore.restart()
        assertEquals("Bea", TestCore.onMain { core.accountsFlow.requests().single().name })
        assertEquals(7L, cursor())
    }

    @Test fun aReplacedWireFailureDoesNotPutTheNewWireToSleep() {
        val rig = rig()
        val gate = CountDownLatch(1)
        rig.wire.gate = gate
        rig.wire.failAfterGate = true
        val replacement = ScriptedWire()
        try {
            send(rig, "new wire")
            Views.waitUntil { rig.wire.sent.size == 1 }
            rig.link.up(replacement)
            gate.countDown()
            Views.waitUntil(5_000) { replacement.sent.size == 1 }
            assertTrue("same-pin replacement must not fall back to radio", TestCore.radio.broadcasts.isEmpty())
            assertEquals(null, TestCore.onMain { core.hubStatus().failure })
        } finally { gate.countDown() }
    }

    @Test fun aFailedWireIsNotRetriedForEveryAlreadyQueuedRow() {
        val rig = rig()
        val gate = CountDownLatch(1)
        rig.wire.gate = gate
        rig.wire.mode = Mode.DOWN
        try {
            repeat(4) { send(rig, "queued $it") }
            Views.waitUntil { rig.wire.sent.isNotEmpty() }
            gate.countDown()
            Views.waitUntil { TestCore.radio.broadcasts.size == 4 }
            assertEquals("one failed attempt determines fallback for the queued batch", 1, rig.wire.sent.size)
            assertTrue(rows(rig).zip(TestCore.radio.broadcasts).all { (row, air) -> row.packet.contentEquals(air.payload) })
        } finally { gate.countDown() }
    }

    private fun picture(rig: Rig) {
        // Synthetic payload tests transport status, not image decoding.
        val preview = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val picture = PreparedPicture(org.phonemesh.core.PictureCodec.WEBP, 2, 2,
            ByteArray(400) { it.toByte() }, byteArrayOf(1, 2, 3), preview, 70)
        try { TestCore.onMain { assertTrue(core.sendPicture(rig.chatId, picture)) } }
        finally { preview.recycle() }
    }

    @Test fun oneHubPicturePartCannotAdvanceTheWholeBubble() {
        val rig = rig()
        val gate = CountDownLatch(1)
        rig.wire.afterFirst = gate
        try {
            picture(rig)
            Views.waitUntil { rows(rig).count { it.state == "HUB" } == 1 }
            assertTrue(rows(rig).any { it.state == "WAITING" })
            assertEquals(DeliveryStatus.WAITING, lastStatus(rig))
        } finally { gate.countDown() }
    }

    @Test fun aHubPartCannotOverwriteAFailedPictureBubble() {
        val rig = rig()
        val gate = CountDownLatch(1)
        val remaining = CountDownLatch(1)
        rig.wire.gate = gate
        rig.wire.afterFirst = remaining
        try {
            picture(rig)
            Views.waitUntil { rig.wire.sent.size == 1 }
            TestCore.onMain {
                val rows = core.history.outbox(rig.bea.qr.card.accountId.toString())
                core.history.setOutboxState(rows[1].id, "FAILED")
                core.history.setStatus(rows[0].messageId!!, DeliveryStatus.FAILED)
            }
            gate.countDown()
            Views.waitUntil { rows(rig).first().state == "HUB" }
            assertEquals(DeliveryStatus.FAILED, lastStatus(rig))
        } finally { gate.countDown(); remaining.countDown() }
    }

    @Test fun aNewSessionCollectsOlderMailBelowTheExistingHubCursor() {
        val rig = rig(hubUp = false)
        val later = PersonChatTest.Bea()
        val own = TestCore.onMain { (AddCode.parse(core.accountsFlow.showCode()) as AddCode.Qr).code }
        later.awa = own.card.accountId
        later.store.sessions[later.awa] = Start.qr(later.keys, later.qr, later.pair, own)!!
        val early = later.postman.send(later.awa, Body.Text("fr", "before adding"), 1, 0)!!.packet
        val current = rig.bea.postman.send(rig.bea.awa, Body.Text("fr", "known contact"), 1, 0)!!.packet
        rig.wire.filterCodes = true
        rig.wire.letters += HubClient.Mail(5, 301, early)
        rig.wire.letters += HubClient.Mail(9, 301, current)
        rig.link.up(rig.wire)
        Views.waitUntil { HubCursor(core.history).get(pin) == 9L }
        val added = TestCore.onMain { core.accountsFlow.scanned(AddCode.of(later.qr)) as Accounts.Added.Contact }
        Views.waitUntil(8_000) { core.history.messages(added.chatId).any { it.text == "before adding" } }
        assertEquals("replay does not duplicate already opened mail", 1, TestCore.onMain { core.history.messages(rig.chatId).size })
    }

}

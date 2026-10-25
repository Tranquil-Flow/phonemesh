package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.MeshRadio
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.AddCode
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.mail.MailStore
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.Body

/** Plan steps 12-13 on the phone: a person chat with Bea, whose phone is played by a JVM Postman. */
@RunWith(AndroidJUnit4::class)
class PersonChatTest {
    private val core get() = TestCore.core

    @After fun tearDown() {
        Views.finish(Views.resumed())
        TestCore.uninstall()
    }

    /** Bea's phone: keys, one session with Awa, and her own Postman over memory. */
    internal class Bea {
        val keys = SoftKeys.generate()
        val pair = KeyPairBytes.generate()
        val qr = QrCode(ContactCard.create(keys, "Bea", 5), pair.public)
        val store = Memory()
        val postman = Postman(keys, store, quietDelay = { 20_000L })
        lateinit var awa: AccountId
    }

    internal class Memory : MailStore {
        val sessions = LinkedHashMap<AccountId, SessionState>()
        val rows = LinkedHashMap<Long, Postman.Outgoing>()
        private var next = 1L
        private val replays = LinkedHashMap<String, MailStore.Replay>()
        override fun pendingReceipts() = replays.values.filter { it.due != null }
        override fun replay(digest: String, now: Long, due: Long): Boolean {
            val row = replays[digest] ?: return false
            if (row.expires < now && row.due == null) return false
            if (row.responseAck != null && row.due == null && rows.values.none {
                it.contact == row.contact && it.status == Postman.Status.WAITING && it.carriedAck?.let { ack -> MailStore.covers(ack, row.responseAck!!) } == true
            }) replays[digest] = row.copy(due = due)
            return true
        }
        private val incomingHeld = LinkedHashMap<Pair<AccountId, String>, MailStore.HeldIncoming>()
        override fun holdIncoming(contact: AccountId, digest: String, packet: ByteArray, now: Long) {
            pendingIncoming(now)
            val key = contact to digest
            if (key !in incomingHeld && incomingHeld.values.count { it.contact == contact } < MailStore.MAX_INCOMING_HELD)
                incomingHeld[key] = MailStore.HeldIncoming(contact, digest, packet.copyOf(), now + MailStore.INCOMING_LIFETIME)
        }
        override fun pendingIncoming(now: Long): List<MailStore.HeldIncoming> {
            incomingHeld.entries.removeAll { it.value.expires <= now }
            return incomingHeld.values.toList()
        }
        override fun incomingExpiry(): Long? = incomingHeld.values.minOfOrNull { it.expires }
        override fun contacts() = sessions.keys.toList()
        override fun session(contact: AccountId) = sessions[contact]
        override fun keep(contact: AccountId, state: SessionState, outgoing: Postman.Outgoing?,
            consumeHeldId: Long?, incoming: MailStore.Incoming?, expected: SessionState): MailStore.Committed? {
            sessions[contact] = state
            incoming?.let { item ->
                if (item.due != null) pendingReceipts().filter { it.contact == contact }.forEach { replays[it.digest] = it.copy(due = item.due) }
                replays[item.digest] = MailStore.Replay(item.digest, contact, item.responseAck, item.due, item.at + MailStore.REPLAY_LIFETIME)
                incomingHeld.remove(contact to item.digest)
            }
            outgoing?.carriedAck?.let { ack ->
                pendingReceipts().filter { it.contact == contact && it.responseAck?.let { saved -> MailStore.covers(ack, saved) } == true }
                    .forEach { replays[it.digest] = it.copy(due = null) }
            }
            incoming?.peerAck?.let { markDelivered(contact, it) }
            return MailStore.Committed(outgoing?.let { val id = next++; rows[id] = it.copy(id = id); id })
        }
        override fun unconfirmed() = rows.values.filter { it.status == Postman.Status.WAITING || it.status == Postman.Status.SENT }
        override fun update(row: Postman.Outgoing) { rows[row.id] = row }
        override fun markSent(id: Long) { rows[id]?.let { rows[id] = it.copy(status = Postman.Status.SENT) } }
        override fun markHub(codes: Set<String>) {}
        override fun markDelivered(contact: AccountId, ack: Ack) {
            rows.values.toList().forEach { if (Bytes.equal(it.chainTag, ack.tag) && ack.covers(it.counter)) rows[it.id] = it.copy(status = Postman.Status.DELIVERED) }
        }
        override fun lastAck(contact: AccountId): Ack? = null
        override fun saveAck(contact: AccountId, ack: Ack) {}
        override fun hold(contact: AccountId, messageId: Long?, body: ByteArray, now: Long) {}
        override fun held(contact: AccountId) = emptyList<Postman.Held>()
        override fun dropHeld(id: Long) {}
    }

    /** Awa (the app) and Bea scan each other: a met contact and a person chat. */
    private fun meet(): Pair<Bea, Long> {
        TestCore.install()
        TestCore.radio.acceptBroadcasts = true
        val bea = Bea()
        val awaText = TestCore.onMain { core.accountsFlow.showCode() }
        val awaQr = (AddCode.parse(awaText) as AddCode.Qr).code
        val added = TestCore.onMain { core.accountsFlow.scanned(AddCode.of(bea.qr)) } as Accounts.Added.Contact
        bea.awa = awaQr.card.accountId
        bea.store.sessions[bea.awa] = Start.qr(bea.keys, bea.qr, bea.pair, awaQr)!!
        return bea to added.chatId
    }

    /** Link opener Awa receives a key-bearing first turn from owner Bea deterministically. */
    private fun linked(): Pair<Bea, Long> {
        TestCore.install(connected = false)
        val bea = Bea()
        val added = TestCore.onMain { core.accountsFlow.openLink(bea.qr.card) } as Accounts.Added.Contact
        val hello = TestCore.onMain { core.history.outbox(bea.qr.card.accountId.toString()).single().packet }
        val received = checkNotNull(Start.hello(bea.keys, hello))
        bea.awa = AccountId.of(received.identity)
        bea.store.sessions[bea.awa] = received.state
        return bea to added.chatId
    }

    private fun deliver(packet: ByteArray) = TestCore.onMain { core.onBroadcastData("!0000bea0", MeshRadio.ACCOUNT_PORT, packet, 0, 1L, "") }

    @Test fun aHubAckIsVisibleAndDurableButNeverClaimsPhoneDelivery() {
        val (bea, chatId) = meet()
        TestCore.onMain { core.setDraft(chatId, "through the hub"); assertTrue(core.send(chatId)) }
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        val packet = TestCore.radio.broadcasts.single().payload
        val ack = org.phonemesh.core.wire.HubAck.encode(listOf(packet.copyOfRange(1, 9)))
        val observed = ArrayList<String?>()
        val observer = AppCore.Observer { change ->
            if (change == AppCore.Change.DELIVERY) observed += core.history.message(message.id)!!.status?.name
        }
        TestCore.onMain { core.observe(observer) }
        try {
            TestCore.onMain { core.onBroadcastData("!0000aaaa", MeshRadio.HUB_ACK_PORT, ack, 0, 1L, "") }
            assertEquals(listOf("HUB"), observed)
            val context = android.view.ContextThemeWrapper(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_Phonemesh)
            TestCore.onMain {
                val look = Looks.status(core.history.message(message.id)!!.status!!, Palette(context))
                assertTrue(look.showLabel)
                assertTrue(look.label != R.string.status_sent && look.label != R.string.status_delivered)
                assertTrue(look.icon != R.drawable.ic_check)
            }
        } finally { TestCore.onMain { core.unobserve(observer) } }
        TestCore.restart()
        assertEquals("HUB", TestCore.onMain { core.history.message(message.id)!!.status!!.name })
        bea.postman.receive(packet, 1_000)!!
        deliver(bea.postman.due(60_000).single().packet)
        assertEquals(DeliveryStatus.DELIVERED, TestCore.onMain { core.history.message(message.id)!!.status })
        TestCore.onMain { core.onBroadcastData("!0000aaaa", MeshRadio.HUB_ACK_PORT, ack, 0, 2L, "") }
        assertEquals(DeliveryStatus.DELIVERED, TestCore.onMain { core.history.message(message.id)!!.status })
    }

    @Test fun failedHubStatusCommitRollsBackItsOutboxAndBubble() {
        val (bea, chatId) = meet()
        TestCore.onMain { core.setDraft(chatId, "keep both statuses"); assertTrue(core.send(chatId)) }
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        val packet = TestCore.radio.broadcasts.single().payload
        val ack = org.phonemesh.core.wire.HubAck.encode(listOf(packet.copyOfRange(1, 9)))
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER reject_hub_bubble BEFORE UPDATE ON messages BEGIN SELECT RAISE(ABORT, 'injected hub status failure'); END")
            runCatching { core.onBroadcastData("!0000aaaa", MeshRadio.HUB_ACK_PORT, ack, 0, 1L, "") }
        }
        TestCore.restart()
        TestCore.onMain {
            assertEquals("SENT", core.history.outbox(bea.qr.card.accountId.toString()).single().state)
            assertEquals(DeliveryStatus.SENT, core.history.message(message.id)!!.status)
            core.history.writableDatabase.execSQL("DROP TRIGGER reject_hub_bubble")
            core.onBroadcastData("!0000aaaa", MeshRadio.HUB_ACK_PORT, ack, 0, 2L, "")
            assertEquals("HUB", core.history.message(message.id)!!.status!!.name)
        }
    }

    @Test fun linkHellosUseTheSameSingleFlightAcceptanceQueueAsMessages() {
        TestCore.install()
        val first = Bea(); val second = Bea()
        TestCore.onMain {
            TestCore.radio.acceptBroadcasts = false
            core.accountsFlow.openLink(first.qr.card)
            core.accountsFlow.openLink(second.qr.card)
            assertEquals("two link opens must not issue concurrent writes", 1, TestCore.radio.broadcasts.size)
            assertEquals(listOf("WAITING", "WAITING"), core.history.outboxIn(setOf("WAITING", "SENT")).map { it.state })
            TestCore.radio.deliver(TestCore.radio.broadcasts.single().packetId, org.phonemesh.ble.DeliveryState.QUEUED)
        }
        Views.waitUntil { TestCore.radio.broadcasts.size == 2 }
        TestCore.onMain {
            assertEquals("SENT", core.history.outbox(first.qr.card.accountId.toString()).single().state)
            assertEquals("WAITING", core.history.outbox(second.qr.card.accountId.toString()).single().state)
            TestCore.radio.deliver(TestCore.radio.broadcasts.last().packetId, org.phonemesh.ble.DeliveryState.FAILED)
        }
        val retry = TestCore.radio.broadcasts.last().payload.copyOf()
        TestCore.restart()
        TestCore.onMain { TestCore.radio.connected(true) }
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        assertTrue(retry.contentEquals(TestCore.radio.broadcasts.single().payload))
        TestCore.onMain {
            TestCore.radio.deliver(TestCore.radio.broadcasts.single().packetId, org.phonemesh.ble.DeliveryState.QUEUED)
            assertEquals("SENT", core.history.outbox(second.qr.card.accountId.toString()).single().state)
        }
    }

    @Test fun onlyAnAuthenticatedPeerPacketRetiresTheDurableHelloRetry() {
        val (bea, _) = linked()
        val address = bea.qr.card.accountId.toString()
        val packet = bea.postman.send(bea.awa, Body.Text("fr", "accepted"), null, 1_000)!!.packet
        val bad = packet.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        deliver(bad)
        assertEquals("WAITING", TestCore.onMain { core.history.outbox(address).single().state })
        deliver(packet)
        assertEquals("DELIVERED", TestCore.onMain { core.history.outbox(address).single().state })
        TestCore.restart()
        TestCore.onMain { assertTrue(core.postman.unsent().none { Start.isHello(it.packet) }) }
    }


    @Test fun aKeyArrivalRecoversHeldContentAndNotifiesTheScreenWithoutATransmitRadio() {
        val (bea, chatId) = linked()
        val long = "é".repeat(90)
        val held = bea.postman.send(bea.awa, Body.Text("fr", long), null, 1_000)!!.packet
        deliver(held)
        assertTrue(TestCore.onMain { core.history.messages(chatId).isEmpty() })
        val observed = ArrayList<Set<String>>()
        val observer = AppCore.Observer { change ->
            if (change == AppCore.Change.MESSAGES) observed += core.history.messages(chatId).map { it.text }.toSet()
        }
        TestCore.onMain { core.observe(observer) }
        try {
            val keyed = bea.postman.send(bea.awa, Body.Text("fr", "key arrives"), null, 1_001)!!.packet
            deliver(keyed)
            assertEquals(setOf(long, "key arrives"), observed.last())
            assertTrue("receiving must not require or start a radio write", TestCore.radio.broadcasts.isEmpty())
        } finally { TestCore.onMain { core.unobserve(observer) } }
    }

    @Test fun restartingWithoutARadioRecoversDurableHeldContentWithoutAnotherPacket() {
        val (bea, chatId) = linked()
        val long = "é".repeat(90)
        deliver(bea.postman.send(bea.awa, Body.Text("fr", long), null, 1_000)!!.packet)
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER fail_held_consumption BEFORE DELETE ON incoming_held BEGIN SELECT RAISE(ABORT, 'injected held consumption failure'); END")
        }
        deliver(bea.postman.send(bea.awa, Body.Text("fr", "key arrives"), null, 1_001)!!.packet)
        TestCore.onMain {
            assertEquals(listOf("key arrives"), core.history.messages(chatId).map { it.text })
            assertNotNull(core.lastNote)
            core.history.writableDatabase.execSQL("DROP TRIGGER fail_held_consumption")
            // Restart before the deadline: waiting until expiry would discard decryptable content.
            core.history.writableDatabase.execSQL("UPDATE incoming_held SET expires=?", arrayOf(System.currentTimeMillis() + 30_000L))
        }
        TestCore.restart()
        // Only the constructor's posted recovery may do this: no runPostman(), radio or packet event here.
        Views.waitUntil(8_000) { core.history.messages(chatId).size == 2 }
        assertEquals(setOf(long, "key arrives"), TestCore.onMain { core.history.messages(chatId).map { it.text }.toSet() })
        assertTrue(TestCore.radio.broadcasts.isEmpty())
    }

    @Test fun restartingWithoutARadioExpiresUnauthenticatedHeldBytes() {
        val (bea, chatId) = linked()
        deliver(bea.postman.send(bea.awa, Body.Text("fr", "é".repeat(90)), null, 1_000)!!.packet)
        TestCore.onMain {
            core.history.writableDatabase.execSQL("UPDATE incoming_held SET expires=0")
        }
        TestCore.restart()
        Views.waitUntil(8_000) {
            core.history.readableDatabase.rawQuery("SELECT count(*) FROM incoming_held", null).use { it.moveToFirst(); it.getLong(0) == 0L }
        }
        TestCore.onMain {
            assertTrue(core.history.messages(chatId).isEmpty())
            core.history.readableDatabase.rawQuery("SELECT count(*) FROM received_mail", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
            }
        }
        assertTrue(TestCore.radio.broadcasts.isEmpty())
    }

    @Test fun aFailedRadioWriteDoesNotCancelIncomingExpiry() = expiryAfterRadioResult(org.phonemesh.ble.DeliveryState.FAILED)

    @Test fun anUnconfirmedRadioWriteDoesNotCancelIncomingExpiry() = expiryAfterRadioResult(org.phonemesh.ble.DeliveryState.UNCONFIRMED)

    @Test fun failedAcceptanceStorageDoesNotCancelIncomingExpiry() = expiryAfterRadioResult(org.phonemesh.ble.DeliveryState.QUEUED, storageFailure = true)

    private fun expiryAfterRadioResult(state: org.phonemesh.ble.DeliveryState, storageFailure: Boolean = false) {
        val (bea, chatId) = linked()
        deliver(bea.postman.send(bea.awa, Body.Text("fr", "é".repeat(90)), null, 1_000)!!.packet)
        TestCore.onMain {
            assertEquals(1L, heldIncomingCount())
            TestCore.radio.acceptBroadcasts = false
            TestCore.radio.connected(true)
        }
        // Reconnect submits the durable outgoing hello, unrelated to the held incoming packet.
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        TestCore.onMain {
            core.history.writableDatabase.execSQL("UPDATE incoming_held SET expires=?", arrayOf(System.currentTimeMillis() + 2_000L))
            core.runPostman() // Arm maintenance before the failure; no manual tick is permitted afterwards.
            if (storageFailure) core.history.writableDatabase.execSQL(
                "CREATE TRIGGER reject_expiry_test_acceptance BEFORE UPDATE ON outbox WHEN NEW.state='SENT' BEGIN SELECT RAISE(ABORT, 'injected acceptance failure'); END")
            TestCore.radio.deliver(TestCore.radio.broadcasts.single().packetId, state)
            assertNotNull(core.lastNote)
            assertEquals(1L, heldIncomingCount())
        }
        Views.waitUntil(8_000) { heldIncomingCount() == 0L }
        TestCore.onMain {
            assertTrue(core.history.messages(chatId).isEmpty())
            core.history.readableDatabase.rawQuery("SELECT count(*) FROM received_mail", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
            }
            assertEquals("maintenance must not retry the blocked radio", 1, TestCore.radio.broadcasts.size)
        }
    }

    /** Main-thread only, including Views.waitUntil's predicate. */
    private fun heldIncomingCount(): Long = core.history.readableDatabase.rawQuery("SELECT count(*) FROM incoming_held", null)
        .use { it.moveToFirst(); it.getLong(0) }

    @Test fun accountPacketsWaitForActualQueueAcceptanceAndDrainOneAtATime() {
        val (_, chatId) = meet()
        TestCore.onMain {
            TestCore.radio.acceptBroadcasts = false
            repeat(4) { core.setDraft(chatId, "paced $it"); assertTrue(core.send(chatId)) }
            core.runPostman()
            assertEquals("one pending radio write, not four", 1, TestCore.radio.broadcasts.size)
            assertTrue(core.history.messages(chatId).all { it.status == DeliveryStatus.WAITING })
            TestCore.radio.deliver(TestCore.radio.broadcasts.single().packetId, org.phonemesh.ble.DeliveryState.SUBMITTING)
            assertEquals(1, TestCore.radio.broadcasts.size)
        }
        repeat(4) { index ->
            val packet = TestCore.radio.broadcasts[index]
            TestCore.onMain { TestCore.radio.deliver(packet.packetId, org.phonemesh.ble.DeliveryState.QUEUED) }
            if (index < 3) Views.waitUntil { TestCore.radio.broadcasts.size == index + 2 }
        }
        TestCore.onMain { assertTrue(core.history.messages(chatId).all { it.status == DeliveryStatus.SENT }) }
        assertEquals(4, TestCore.radio.broadcasts.size)
    }

    @Test fun aRejectedAccountWriteSurvivesRestartAndIgnoresTheOldCallback() {
        val (bea, chatId) = meet()
        TestCore.onMain {
            TestCore.radio.acceptBroadcasts = false
            core.setDraft(chatId, "rejected then retried"); assertTrue(core.send(chatId))
        }
        val rejected = TestCore.radio.broadcasts.single()
        TestCore.onMain {
            TestCore.radio.deliver(rejected.packetId, org.phonemesh.ble.DeliveryState.FAILED)
            assertEquals(DeliveryStatus.WAITING, core.history.lastMessage(chatId)!!.status)
            TestCore.radio.deliver(rejected.packetId, org.phonemesh.ble.DeliveryState.QUEUED)
            assertEquals(DeliveryStatus.WAITING, core.history.lastMessage(chatId)!!.status)
        }
        TestCore.restart()
        TestCore.onMain { TestCore.radio.connected(true) }
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        val retried = TestCore.radio.broadcasts.single()
        assertTrue(rejected.payload.contentEquals(retried.payload))
        TestCore.onMain { TestCore.radio.deliver(retried.packetId, org.phonemesh.ble.DeliveryState.QUEUED) }
        assertEquals(DeliveryStatus.SENT, TestCore.onMain { core.history.lastMessage(chatId)!!.status })
        assertEquals("rejected then retried", (bea.postman.receive(retried.payload, 1_000)!!.body as Body.Text).text)
    }

    @Test fun reconnectDrainsBothAnInterruptedWriteAndNewOfflineWork() {
        val (_, chatId) = meet()
        TestCore.onMain {
            TestCore.radio.acceptBroadcasts = false
            core.setDraft(chatId, "interrupted"); assertTrue(core.send(chatId))
            TestCore.radio.connected(false)
            core.setDraft(chatId, "while offline"); assertTrue(core.send(chatId))
            TestCore.radio.connected(true)
        }
        Views.waitUntil { TestCore.radio.broadcasts.size == 2 }
        assertTrue("oldest unaccepted ciphertext goes first", TestCore.radio.broadcasts[0].payload.contentEquals(TestCore.radio.broadcasts[1].payload))
        TestCore.onMain { TestCore.radio.deliver(TestCore.radio.broadcasts[1].packetId, org.phonemesh.ble.DeliveryState.QUEUED) }
        Views.waitUntil { TestCore.radio.broadcasts.size == 3 }
        TestCore.onMain {
            TestCore.radio.deliver(TestCore.radio.broadcasts[2].packetId, org.phonemesh.ble.DeliveryState.QUEUED)
            assertTrue(core.history.messages(chatId).all { it.status == DeliveryStatus.SENT })
        }
    }

    @Test fun acceptanceStorageFailurePreservesExactPacketAndDoesNotDrainMore() = acceptanceFailure(false)

    @Test fun lateAcceptanceStorageFailureRollsBackOutboxAndBubbleTogether() = acceptanceFailure(true)

    private fun acceptanceFailure(late: Boolean) {
        val (bea, chatId) = meet()
        TestCore.onMain {
            TestCore.radio.acceptBroadcasts = false
            repeat(2) { core.setDraft(chatId, "durable $it"); assertTrue(core.send(chatId)) }
            val table = if (late) "messages" else "outbox"
            val field = if (late) "delivery" else "state"
            core.history.writableDatabase.execSQL("CREATE TRIGGER reject_sent BEFORE UPDATE ON $table WHEN NEW.$field='SENT' BEGIN SELECT RAISE(ABORT, 'injected acceptance failure'); END")
            TestCore.radio.deliver(TestCore.radio.broadcasts.single().packetId, org.phonemesh.ble.DeliveryState.QUEUED)
            assertTrue(core.history.messages(chatId).all { it.status == DeliveryStatus.WAITING })
            assertEquals(1, TestCore.radio.broadcasts.size)
            assertNotNull(core.lastNote)
        }
        val original = TestCore.radio.broadcasts.single().payload.copyOf()
        TestCore.restart()
        TestCore.onMain {
            core.history.writableDatabase.execSQL("DROP TRIGGER reject_sent")
            val unsent = core.history.outbox(bea.qr.card.accountId.toString())
            assertTrue(unsent.all { it.state == "WAITING" })
            assertTrue(original.contentEquals(unsent.first().packet))
            TestCore.radio.connected(true)
        }
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        assertTrue(original.contentEquals(TestCore.radio.broadcasts.single().payload))
    }

    @Test fun manualRetryUsesStoredCiphertextWithoutAdvancingTheSession() {
        val (bea, chatId) = meet()
        TestCore.onMain { core.setDraft(chatId, "retry exactly"); assertTrue(core.send(chatId)) }
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        val original = TestCore.radio.broadcasts.single().payload.copyOf()
        val before = TestCore.onMain { core.history.sessionRow(bea.qr.card.accountId.toString())!! }
        TestCore.onMain {
            val row = core.history.outbox(bea.qr.card.accountId.toString()).single()
            core.history.setOutboxState(row.id, "FAILED")
            core.history.setStatus(message.id, DeliveryStatus.FAILED)
            assertTrue(core.resend(message.id))
        }
        Views.waitUntil { TestCore.radio.broadcasts.size == 2 }
        assertEquals(2, TestCore.radio.broadcasts.size)
        assertTrue(original.contentEquals(TestCore.radio.broadcasts.last().payload))
        assertTrue(TestCore.radio.sent.isEmpty())
        TestCore.onMain {
            assertEquals(1, core.history.outbox(bea.qr.card.accountId.toString()).size)
            assertTrue(before.second.contentEquals(core.history.sessionRow(bea.qr.card.accountId.toString())!!.second))
        }
        assertEquals("retry exactly", (bea.postman.receive(original, 1_000)!!.body as Body.Text).text)
        deliver(bea.postman.due(60_000).single().packet)
        assertEquals(DeliveryStatus.DELIVERED, TestCore.onMain { core.history.message(message.id)!!.status })
    }

    @Test fun personPlanSurvivesRestartAndUsesTheAccountsPort() {
        val (bea, chatId) = meet()
        TestCore.onMain {
            TestCore.radio.connected(false)
            core.setDraft(chatId, "planned privately")
            assertTrue(core.plan(chatId, System.currentTimeMillis() - 1_000))
            core.sendDue()
            assertTrue(core.hasDuePlanned())
        }
        TestCore.restart()
        TestCore.onMain { TestCore.radio.connected(true); core.sendDue() }
        assertEquals(1, TestCore.radio.broadcasts.size)
        assertTrue(TestCore.radio.sent.isEmpty())
        assertEquals("planned privately", (bea.postman.receive(TestCore.radio.broadcasts.single().payload, 1_000)!!.body as Body.Text).text)
        TestCore.onMain {
            assertTrue(!core.hasDuePlanned())
            assertEquals(1, core.history.messages(chatId).size)
        }
    }

    @Test fun queuedPersonStatusAndBytesSurviveRestartBeforeReconnect() {
        val (bea, chatId) = meet()
        TestCore.onMain {
            TestCore.radio.connected(false)
            core.setDraft(chatId, "still waiting")
            assertTrue(core.send(chatId))
        }
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        val packet = TestCore.onMain { core.history.outbox(bea.qr.card.accountId.toString()).single().packet.copyOf() }
        TestCore.restart()
        assertEquals(DeliveryStatus.WAITING, TestCore.onMain { core.history.message(message.id)!!.status })
        TestCore.onMain { TestCore.radio.connected(true) }
        // Reconnect posts the durable drain; wait for that callback, not another user/radio event.
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        assertEquals(1, TestCore.radio.broadcasts.size)
        assertTrue(packet.contentEquals(TestCore.radio.broadcasts.single().payload))
        assertEquals("still waiting", (bea.postman.receive(packet, 1_000)!!.body as Body.Text).text)
    }

    @Test fun failedPersonPlanCommitKeepsThePlanAndReceivingStateAcrossRestart() {
        val (bea, chatId) = meet()
        val before = TestCore.onMain { core.history.sessionRow(bea.qr.card.accountId.toString())!! }
        TestCore.onMain {
            TestCore.radio.connected(false)
            core.setDraft(chatId, "keep my plan")
            assertTrue(core.plan(chatId, System.currentTimeMillis() - 1_000))
            core.history.writableDatabase.execSQL("CREATE TRIGGER reject_planned_outbox BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected planned failure'); END")
            TestCore.radio.connected(true)
            assertTrue(core.hasDuePlanned())
            assertTrue(core.history.messages(chatId).isEmpty())
        }
        assertTrue(TestCore.radio.broadcasts.isEmpty())
        TestCore.restart()
        TestCore.onMain {
            assertTrue(core.hasDuePlanned())
            assertTrue(before.second.contentEquals(core.history.sessionRow(bea.qr.card.accountId.toString())!!.second))
            core.history.writableDatabase.execSQL("DROP TRIGGER reject_planned_outbox")
            TestCore.radio.connected(true)
        }
        Views.waitUntil { TestCore.radio.broadcasts.size == 1 }
        assertEquals("keep my plan", (bea.postman.receive(TestCore.radio.broadcasts.single().payload, 1_000)!!.body as Body.Text).text)
        TestCore.onMain { assertTrue(!core.hasDuePlanned()); assertEquals(1, core.history.messages(chatId).size) }
    }

    @Test fun aPersonChatIsListedWithItsColourAndSendsOverTheAccountsPort() {
        val (bea, chatId) = meet()
        val row = TestCore.onMain { core.chatRows() }.single { it.chat.id == chatId }
        assertEquals(ChatKind.PERSON, row.chat.kind)
        assertEquals("Bea", row.title)
        assertEquals(5, row.colour)
        assertEquals(true, row.met)
        TestCore.onMain { core.setDraft(chatId, "Bonjour Bea") }
        assertTrue(TestCore.onMain { core.send(chatId) })
        val out = TestCore.radio.broadcasts.single()
        assertEquals(MeshRadio.ACCOUNT_PORT, out.port)
        assertTrue("nothing went through the radio-address path", TestCore.radio.sent.isEmpty())
        val got = bea.postman.receive(out.payload, 1_000)!!
        assertEquals("Bonjour Bea", (got.body as Body.Text).text)
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        assertEquals(DeliveryStatus.SENT, message.status)
        // Bea's receipt after the quiet: delivered.
        deliver(bea.postman.due(60_000).single().packet)
        assertEquals(DeliveryStatus.DELIVERED, TestCore.onMain { core.history.message(message.id)!!.status })
    }

    @Test fun aReceiptNotifiesObserversAfterUpdatingDelivery() {
        val (bea, chatId) = meet()
        TestCore.onMain { core.setDraft(chatId, "Bonjour Bea"); core.send(chatId) }
        val message = TestCore.onMain { core.history.lastMessage(chatId)!! }
        bea.postman.receive(TestCore.radio.broadcasts.single().payload, 1_000)!!
        val observed = ArrayList<DeliveryStatus?>()
        val observer = AppCore.Observer { change ->
            if (change == AppCore.Change.DELIVERY) observed += core.history.message(message.id)!!.status
        }
        TestCore.onMain { core.observe(observer) }
        try {
            deliver(bea.postman.due(60_000).single().packet)
            assertEquals("the screen sees the saved delivered state immediately", listOf(DeliveryStatus.DELIVERED), observed)
        } finally {
            TestCore.onMain { core.unobserve(observer) }
        }
    }

    @Test fun beasMessagesReplyAndReactionShowInThePersonChat() {
        val (bea, chatId) = meet()
        val hello = bea.postman.send(bea.awa, Body.Text("fr", "Salut Awa"), 1, 0)!!
        deliver(hello.packet)
        val shown = TestCore.onMain { core.history.lastMessage(chatId)!! }
        assertEquals("Salut Awa", shown.text)
        assertNotNull("a reference for replies", shown.packetId)
        // Awa replies to it: Bea sees a reply naming her message.
        TestCore.onMain { core.setReplyTarget(chatId, shown.id); core.setDraft(chatId, "Oui ?") }
        assertTrue(TestCore.onMain { core.send(chatId) })
        val reply = bea.postman.receive(TestCore.radio.broadcasts.last().payload, 2_000)!!.body as Body.Reply
        assertEquals("Oui ?", reply.text)
        assertTrue(reply.ref.contentEquals(org.phonemesh.core.session.Ratchet.reference(hello.packet.copyOfRange(1, 9))))
        // Bea reacts to Awa's reply.
        val awaReply = TestCore.onMain { core.history.lastMessage(chatId)!! }
        val reaction = bea.postman.send(bea.awa, Body.Reaction(AppMailStore.refBytes(awaReply.packetId!!), "👍"), null, 3_000)!!
        deliver(reaction.packet)
        assertEquals("👍", TestCore.onMain { core.history.reactions(chatId) }.single().emoji)
    }

    @Test fun aMaximumDraftAndReplyAfterReceivingStillFitTheAccountsPacket() {
        val (bea, chatId) = meet()
        val incoming = bea.postman.send(bea.awa, Body.Text("fr", "Bonjour"), 1, 0)!!
        deliver(incoming.packet)
        val target = TestCore.onMain { core.history.lastMessage(chatId)!! }
        for (reply in listOf(false, true)) {
            TestCore.onMain {
                if (reply) core.setReplyTarget(chatId, target.id)
                core.setDraft(chatId, "x".repeat(191))
                assertTrue(core.send(chatId))
            }
            val packet = TestCore.radio.broadcasts.last().payload
            assertTrue(packet.size <= 228)
            val body = bea.postman.receive(packet, 2_000)!!.body
            if (reply) assertEquals("x".repeat(191), (body as Body.Reply).text)
            else assertEquals("x".repeat(191), (body as Body.Text).text)
        }
    }

    @Test fun aReceiptWakesHeldMessagesAndGivesThemReplyReferences() {
        val (bea, chatId) = meet()
        TestCore.onMain {
            repeat(org.phonemesh.core.session.Ratchet.WINDOW + 1) {
                core.setDraft(chatId, "Message $it")
                assertTrue(core.send(chatId))
            }
        }
        val held = TestCore.onMain { core.history.lastMessage(chatId)!! }
        Views.waitUntil { TestCore.radio.broadcasts.size == org.phonemesh.core.session.Ratchet.WINDOW }
        assertEquals(org.phonemesh.core.session.Ratchet.WINDOW, TestCore.radio.broadcasts.size)
        TestCore.radio.broadcasts.toList().forEach { bea.postman.receive(it.payload, 1_000) }
        deliver(bea.postman.due(61_000).single().packet)
        Views.waitUntil(8_000) { TestCore.radio.broadcasts.size == org.phonemesh.core.session.Ratchet.WINDOW + 1 }
        val got = bea.postman.receive(TestCore.radio.broadcasts.last().payload, 62_000)!!
        assertEquals("Message ${org.phonemesh.core.session.Ratchet.WINDOW}", (got.body as Body.Text).text)
        assertEquals(AppMailStore.refNumber(got.ref), TestCore.onMain { core.history.message(held.id)!!.packetId })
    }

    @Test fun failedIncomingStorageDoesNotCrashOrNotifyDeliveryAndCanRetry() {
        val (bea, chatId) = meet()
        val packet = bea.postman.send(bea.awa, Body.Text("fr", "retry after storage failure"), 1, 0)!!.packet
        TestCore.onMain { core.history.writableDatabase.execSQL("CREATE TRIGGER fail_incoming BEFORE INSERT ON messages WHEN NEW.direction='IN' BEGIN SELECT RAISE(ABORT, 'injected incoming failure'); END") }
        val changes = ArrayList<AppCore.Change>()
        val observer = AppCore.Observer { changes += it }
        TestCore.onMain { core.observe(observer) }
        try {
            assertTrue("a recoverable storage failure must not crash the UI", runCatching { deliver(packet) }.isSuccess)
            assertTrue(TestCore.onMain { core.history.messages(chatId).isEmpty() })
            assertNotNull(TestCore.onMain { core.lastNote })
            assertTrue("failure is not a delivery notification", AppCore.Change.DELIVERY !in changes)
            TestCore.onMain { core.history.writableDatabase.execSQL("DROP TRIGGER fail_incoming") }
            deliver(packet)
            assertEquals("retry after storage failure", TestCore.onMain { core.history.messages(chatId).single().text })
        } finally { TestCore.onMain { core.unobserve(observer) } }
    }

    @Test fun failedOutgoingStorageLeavesAVisibleFailedBubbleAndSendsNothing() {
        val (_, chatId) = meet()
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER fail_outgoing BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected outgoing failure'); END")
            core.setDraft(chatId, "still on this phone")
        }
        assertTrue("a recoverable storage failure must not crash the UI", runCatching { TestCore.onMain { core.send(chatId) } }.isSuccess)
        assertTrue(TestCore.radio.broadcasts.isEmpty())
        val kept = TestCore.onMain { core.history.messages(chatId).single() }
        assertEquals("still on this phone", kept.text)
        assertEquals(DeliveryStatus.FAILED, kept.status)
        assertNotNull(TestCore.onMain { core.lastNote })
    }

    @Test fun withNoRadioTheMessageWaitsAndGoesWhenOneIsReady() {
        val (bea, chatId) = meet()
        TestCore.onMain { TestCore.radio.ready = false; TestCore.radio.connected(false) }
        TestCore.onMain { core.setDraft(chatId, "Plus tard") }
        assertTrue(TestCore.onMain { core.send(chatId) })
        assertTrue(TestCore.radio.broadcasts.isEmpty())
        assertEquals(DeliveryStatus.WAITING, TestCore.onMain { core.history.lastMessage(chatId)!!.status })
        TestCore.onMain { TestCore.radio.connected(true) }
        Views.waitUntil { TestCore.radio.broadcasts.isNotEmpty() }
        assertEquals("Plus tard", (bea.postman.receive(TestCore.radio.broadcasts.single().payload, 1_000)!!.body as Body.Text).text)
    }
    @Test fun anUnreadableSessionKeepsAFailedTextInsteadOfCrashing() {
        val (bea, chatId) = meet()
        TestCore.onMain {
            core.history.writableDatabase.execSQL("UPDATE sessions SET state=? WHERE account_id=?",
                arrayOf(byteArrayOf(0), bea.qr.card.accountId.toString()))
            core.setDraft(chatId, "still on my phone")
            assertTrue(core.send(chatId))
            val message = core.history.lastMessage(chatId)!!
            assertEquals("still on my phone", message.text)
            assertEquals(DeliveryStatus.FAILED, message.status)
            assertTrue(TestCore.radio.broadcasts.isEmpty())
            assertTrue(core.history.outbox(bea.qr.card.accountId.toString()).isEmpty())
            assertEquals(R.string.mail_save_failed, core.lastNote!!.id)
        }
    }

    @Test fun anUnreadableSessionRefusesAReactionWithoutCrashingOrPublishing() {
        val (bea, chatId) = meet()
        val incoming = bea.postman.send(bea.awa, Body.Text("fr", "answer me"), null, 1_000)!!
        deliver(incoming.packet)
        TestCore.onMain {
            val message = core.history.lastMessage(chatId)!!
            core.history.writableDatabase.execSQL("UPDATE sessions SET state=? WHERE account_id=?",
                arrayOf(byteArrayOf(0), bea.qr.card.accountId.toString()))
            assertEquals(false, core.react(message.id, "👍"))
            assertTrue(core.history.reactions(chatId).isEmpty())
            assertTrue(TestCore.radio.broadcasts.isEmpty())
            assertTrue(core.history.outbox(bea.qr.card.accountId.toString()).isEmpty())
            assertEquals(R.string.mail_save_failed, core.lastNote!!.id)
        }
    }

    @Test fun aLateReactionInsertFailureRollsBackStateAndCiphertextBeforeSending() {
        val (bea, chatId) = meet()
        val incoming = bea.postman.send(bea.awa, Body.Text("fr", "answer me"), null, 1_000)!!
        deliver(incoming.packet)
        TestCore.onMain {
            val message = core.history.lastMessage(chatId)!!
            val before = core.history.sessionRow(bea.qr.card.accountId.toString())!!.second
            core.history.writableDatabase.execSQL("CREATE TRIGGER reject_reaction BEFORE INSERT ON messages WHEN NEW.reaction=1 BEGIN SELECT RAISE(ABORT, 'injected reaction failure'); END")
            assertEquals(false, core.react(message.id, "👍"))
            assertTrue(before.contentEquals(core.history.sessionRow(bea.qr.card.accountId.toString())!!.second))
            assertTrue(core.history.reactions(chatId).isEmpty())
            assertTrue(TestCore.radio.broadcasts.isEmpty())
            assertTrue(core.history.outbox(bea.qr.card.accountId.toString()).isEmpty())
            core.history.writableDatabase.execSQL("DROP TRIGGER reject_reaction")
            assertTrue(core.react(message.id, "👍"))
            assertEquals("👍", core.history.reactions(chatId).single().emoji)
        }
    }

}

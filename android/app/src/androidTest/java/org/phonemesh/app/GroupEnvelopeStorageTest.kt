package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.Avatar
import org.phonemesh.core.backup.BackupStream
import org.phonemesh.core.backup.ChatExport
import org.phonemesh.core.backup.RecoveryKey
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.group.GroupKey
import org.phonemesh.core.group.GroupMember
import org.phonemesh.core.group.GroupPost
import org.phonemesh.core.group.GroupRoster
import org.phonemesh.core.group.GroupStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/** G3h: real Vault/SQLite atomicity, outbox routing, and exclusion of own private halves from exported chat copies. */
@RunWith(AndroidJUnit4::class)
class GroupEnvelopeStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val core get() = TestCore.core
    private val work get() = File(context.noBackupFilesDir, "group-box-export-test")
    private class Fixture(val group: ByteArray, val post: GroupPost, val esi: GroupMember, val now: Long)

    @After fun tearDown() { work.deleteRecursively(); TestCore.uninstall() }

    /** Seed a checked three-member epoch; subsequent changes run through the production GroupPost and AppGroupStore. */
    private fun prepare(): Fixture {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val (bea, _) = PersonFixture.meet("Bea")
        return TestCore.onMain {
            val now = System.currentTimeMillis()
            val post = GroupPost(core.accounts.identity, core.groupStore, profileDelay = { 0L })
            val group = post.create("Market", 0x2E7D32, Avatar.Letter, false, listOf(AppMailStore.idOf(bea.account)), now)!!
            val member = post.member(group)!!
            val signing = P256.generate()
            val esiId = P256.compress(P256.generate().public as ECPublicKey)
            val esiSigner = P256.compress(signing.public as ECPublicKey)
            val esi = GroupMember(esiId, esiSigner, { P256.sign(signing.private, it) }, group, core.accounts.identity)
            val otherSigner = P256.compress(P256.generate().public as ECPublicKey)
            val roster = GroupRoster("Market", 0x2E7D32, listOf(
                GroupRoster.Member(core.accounts.identity, true, member.mySigner),
                GroupRoster.Member(bea.keys.identity, false, otherSigner),
                GroupRoster.Member(esiId, false, esiSigner),
            ))
            val out = member.newEpoch(roster, now)
            assertTrue(esi.receiveKey(core.accounts.identity, GroupKey.decode(out.keys.getValue(esiId.toList()))!!, now))
            out.rosterPackets.forEach { esi.receive(it, now) }
            assertTrue(core.groupStore.commit(GroupPost.Change(group,
                saved = GroupStore.Saved(group, member.encode(), member.mySigner, profileDue = now))))
            val announcement = esi.makeAndAnnounceBoxKey(now).second
            assertTrue(post.receive(announcement.packet, now))
            Fixture(group, post, esi, now)
        }
    }

    private fun failOutbox() = core.history.writableDatabase.execSQL(
        "CREATE TRIGGER fail_box_out BEFORE INSERT ON group_outbox BEGIN SELECT RAISE(ABORT, 'late group failure'); END")
    private fun allowOutbox() = core.history.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_box_out")

    @Test fun anAnnouncementAndItsPrivateHalfCommitTogetherOrNeitherDoes() {
        val f = prepare()
        TestCore.onMain {
            val store = core.groupStore
            val before = store.saved(f.group)!!.state
            val queued = store.rows.outWaiting().size
            failOutbox()
            try { assertFalse(f.post.tick(f.now)) } finally { allowOutbox() }
            assertArrayEquals("late SQL failure rolls back the scalar and chain position", before, store.saved(f.group)!!.state)
            assertEquals(queued, store.rows.outWaiting().size)
            assertTrue(f.post.tick(f.now))
            val reopened = AppGroupStore(core.history, core.accounts, Vault())
            val again = GroupPost(core.accounts.identity, reopened).member(f.group)!!
            assertEquals(1, again.ownBoxKeys(f.now).size)
            assertFalse(again.needsBoxKey)
            val packets = reopened.rows.outWaiting().drop(queued)
            assertTrue(packets.isNotEmpty())
            assertTrue(packets.all { !it.hubOnly })
            packets.forEach { f.esi.receive(it.packet, f.now) }
            assertArrayEquals(again.ownBoxKeys(f.now).single().public, f.esi.boxKeyOf(core.accounts.identity))
        }
    }

    @Test fun anEnvelopeEpochRollsBackItsStatePrivateKeysAndOutboxTogether() {
        val f = prepare()
        TestCore.onMain {
            val store = core.groupStore
            val before = store.saved(f.group)!!.state
            val privateCount = store.rows.privates().size
            val queued = store.rows.outWaiting().size
            failOutbox()
            try { assertFalse(f.post.restyle(f.group, "New market", 0x336699, Avatar.Letter, false, f.now)) }
            finally { allowOutbox() }
            assertArrayEquals(before, store.saved(f.group)!!.state)
            assertEquals(privateCount, store.rows.privates().size)
            assertEquals(queued, store.rows.outWaiting().size)
            assertTrue(f.post.restyle(f.group, "New market", 0x336699, Avatar.Letter, false, f.now))
            val reopened = AppGroupStore(core.history, core.accounts, Vault())
            val packets = reopened.rows.outWaiting().drop(queued)
            assertFalse("first durable packet is a radio envelope", packets.first().hubOnly)
            assertEquals(1L, packets.first().epoch)
            assertTrue(packets.drop(1).all { it.epoch == 2L })
            packets.forEach { f.esi.receive(it.packet, f.now) }
            assertEquals("the reopened outbox reaches a non-contact", 2L, f.esi.epoch)
            assertEquals(2L, GroupPost(core.accounts.identity, reopened).member(f.group)!!.epoch)
        }
    }

    @Test fun aRealChatCopyContainsNeitherOwnPrivateHalfNorLiveGroupState() {
        val f = prepare()
        val retained = TestCore.onMain { originalEnvelope(f).packet }
        val secrets = TestCore.onMain {
            assertTrue(f.post.tick(f.now))
            val member = f.post.member(f.group)!!
            val box = member.ownBoxKeys(f.now).single()
            val scalar = Bytes.fixed((box.private as ECPrivateKey).s, 32)
            val row = core.groupStore.rows.group(AppGroupStore.hex(f.group))!!
            assertFalse("database state is Vault ciphertext", contains(row.sealed, scalar))
            core.history.addOutgoing(row.chatId, "Market tomorrow", "en-US", f.now).also {
                core.history.setStatus(it.id, org.phonemesh.core.DeliveryStatus.SENT)
            }
            Triple(row.chatId, member.encode(), scalar)
        }
        val key = RecoveryKey.generate()
        val sink = ByteArrayOutputStream()
        try {
            ChatSnapshot.build(core.history, core.picturesDir, work, ChatScope(setOf(secrets.first), null), f.now, core::exportLabel)!!.use { snap ->
                val preview = ChatExport.preview(snap, 5, 1) as ChatExport.Preview.Ok
                assertTrue(ChatBackup(work).save(snap, preview, key, 5, 1, { sink },
                    { ByteArrayInputStream(sink.toByteArray()) }) is ChatBackup.Saved.Ok)
            }
            val opened = BackupStream.openStream(key, ByteArrayInputStream(sink.toByteArray())) as BackupStream.Streamed.Ok
            val plain = opened.stream.use { it.readBytes() }
            assertTrue("positive control: the chosen message is exported", contains(plain, "Market tomorrow".toByteArray()))
            assertFalse("the private half is excluded", contains(plain, secrets.third))
            assertFalse("live state is excluded", contains(plain, secrets.second))
            assertFalse("retained envelope bytes are excluded", contains(plain, retained))
            assertFalse("live group id is excluded", contains(plain, f.group))
            assertNotNull(TestCore.onMain { f.post.member(f.group) })
        } finally { secrets.third.fill(0); secrets.second.fill(0) }
    }

    /** Exact stored identities, including radio/hub distinction; no UI projection. */
    private fun radioRows(f: Fixture): List<GroupRows.OutRow> =
        core.groupStore.rows.outAt(AppGroupStore.hex(f.group), 1)

    private fun originalEnvelope(f: Fixture): GroupRows.OutRow {
        assertTrue(f.post.restyle(f.group, "Retry market", 0x336699, Avatar.Letter, false, f.now))
        return radioRows(f).single()
    }

    @Test fun aScheduledRetryRearmsTheSameSentEnvelopeAndKeepsRosterPartsDistinct() {
        val f = prepare()
        TestCore.onMain {
            val rows = core.groupStore.rows
            val original = originalEnvelope(f)
            core.groupStore.aired(original.id)
            val before = rows.outWaiting().filter { it.epoch == 2L && it.seq == -1L }
            assertTrue("multipart roster positive control", before.size > 1)
            assertTrue(f.post.tick(f.now + 600_000))
            assertEquals("one matching radio envelope row, never a duplicate", 1, radioRows(f).size)
            val again = radioRows(f).single()
            assertEquals(original.id, again.id)
            assertEquals(original.created, again.created)
            assertEquals(original.audience, again.audience)
            assertEquals(original.messageId, again.messageId)
            assertEquals(1, again.airings)
            assertEquals(AppGroupStore.STATE_WAITING, again.state)
            assertArrayEquals(original.packet, again.packet)
            val allParts = rows.outWaiting().filter { it.epoch == 2L && it.seq == -1L }
            assertEquals("every part retained in each list copy", before.size * 2, allParts.size)
            assertEquals("distinct parts were not merged by sentinel position", before.size, allParts.map { it.packet.toList() }.distinct().size)
        }
    }

    @Test fun aCollidingHubChainRowCannotBeRearmedAsTheRadioEnvelope() {
        val f = prepare()
        TestCore.onMain {
            val rows = core.groupStore.rows
            // The hub writer starts at zero too. Insert the collision before the genuine radio row.
            val held = f.post.member(f.group)!!
            val seq = held.roster!!.parts().size.toLong()
            val hub = rows.addOut(AppGroupStore.hex(f.group), byteArrayOf(2, 3, 4), true, null, 1, seq, 7, f.now - 1)
            rows.setOutState(hub, AppGroupStore.STATE_HUB, 2)
            val envelope = originalEnvelope(f)
            assertEquals("fixture pins the colliding chain position", seq, envelope.seq)
            core.groupStore.aired(envelope.id)
            assertTrue(f.post.tick(f.now + 600_000))
            assertEquals("collision still leaves one radio identity", 1, radioRows(f).size)
            assertEquals(envelope.id, radioRows(f).single().id)
            assertEquals(AppGroupStore.STATE_WAITING, rows.out(envelope.id)!!.state)
            assertEquals(AppGroupStore.STATE_HUB, rows.out(hub)!!.state)
            assertEquals(2, rows.out(hub)!!.airings)
            assertArrayEquals(byteArrayOf(2, 3, 4), rows.out(hub)!!.packet)
        }
    }

    @Test fun queuedRetriesCoalesceWithoutResettingTheSharedAcceptanceBudget() {
        val f = prepare()
        TestCore.onMain {
            val original = originalEnvelope(f)
            assertTrue(f.post.tick(f.now + 600_000))
            assertEquals("queued original and retry coalesce", 1, radioRows(f).size)
            assertEquals(0, radioRows(f).single().airings)
            core.groupStore.aired(original.id)
            assertEquals(1, radioRows(f).single().airings)
            // Receipt repair spends the SAME row's budget before the second scheduled retry.
            val receipt = org.phonemesh.core.group.GroupReceipt.Entry(0, original.seq + 1, 1)
            assertTrue(core.groupStore.commit(GroupPost.Change(f.group, events = listOf(
                GroupPost.Event.Delivered(f.group, org.phonemesh.core.account.AccountId.of(f.esi.roster!!.members[1].identity), 1, receipt)))))
            assertEquals(AppGroupStore.STATE_WAITING, radioRows(f).single().state)
            core.groupStore.aired(original.id)
            assertEquals(2, radioRows(f).single().airings)
            assertTrue(f.post.tick(f.now + 3_600_000))
            assertEquals("second core intent reuses the repaired row", original.id, radioRows(f).single().id)
            core.groupStore.aired(original.id)
            assertEquals(AppGroupStore.MAX_AIRINGS, radioRows(f).single().airings)
            assertEquals(1, radioRows(f).size)
        }
    }

    @Test fun capExhaustionStillKeepsTheUsefulListForAPendingKey() {
        val f = prepare()
        TestCore.onMain {
            val rows = core.groupStore.rows
            val original = originalEnvelope(f)
            f.esi.receive(original.packet, f.now)
            assertEquals("the member has only a pending key", 1L, f.esi.epoch)
            assertNotNull(f.esi.waitingOn())
            rows.setOutState(original.id, AppGroupStore.STATE_SENT, AppGroupStore.MAX_AIRINGS)
            val parts = rows.outWaiting().filter { it.epoch == 2L && it.seq == -1L }
            // Original list was lost. Only a newly queued copy will be delivered.
            parts.forEach { core.groupStore.aired(it.id) }
            assertTrue(f.post.tick(f.now + 600_000))
            assertEquals("the cap never manufactures another identity", 1, radioRows(f).size)
            assertEquals(AppGroupStore.STATE_SENT, rows.out(original.id)!!.state)
            assertEquals(AppGroupStore.MAX_AIRINGS, rows.out(original.id)!!.airings)
            val repeatedList = rows.outWaiting().filter { it.epoch == 2L && it.seq == -1L }
            assertEquals(parts.size, repeatedList.size)
            repeatedList.forEach { f.esi.receive(it.packet, f.now + 600_000) }
            assertEquals("list alone completes a key the member already holds", 2L, f.esi.epoch)
        }
    }

    @Test fun aLateSqlRefusalRollsBackRearmCounterAndListThenReopenRetries() {
        val f = prepare()
        TestCore.onMain {
            val original = originalEnvelope(f)
            core.groupStore.reachedHub(original.id)
            val saved = core.groupStore.saved(f.group)!!.state
            val count = core.groupStore.rows.outWaiting().size
            failOutbox()
            try { assertFalse(f.post.tick(f.now + 600_000)) } finally { allowOutbox() }
            assertArrayEquals(saved, core.groupStore.saved(f.group)!!.state)
            assertEquals(AppGroupStore.STATE_HUB, core.groupStore.rows.out(original.id)!!.state)
            assertEquals(1, core.groupStore.rows.out(original.id)!!.airings)
            assertEquals(count, core.groupStore.rows.outWaiting().size)
            val reopened = AppGroupStore(core.history, core.accounts, Vault())
            val post = GroupPost(core.accounts.identity, reopened)
            assertTrue(post.tick(f.now + 600_000))
            assertEquals("one row after refused transaction and reopen", 1, reopened.rows.outAt(AppGroupStore.hex(f.group), 1).size)
            assertEquals(original.id, reopened.rows.outAt(AppGroupStore.hex(f.group), 1).single().id)
            assertEquals(AppGroupStore.STATE_WAITING, reopened.rows.out(original.id)!!.state)
        }
    }

    @Test fun acceptedRadioRetriesReallyBroadcastTheOriginalBytes() {
        val f = prepare()
        val original = TestCore.onMain { originalEnvelope(f) }
        TestCore.onMain { TestCore.radio.acceptBroadcasts = true; TestCore.radio.connected(true); core.runPostman() }
        Views.waitUntil(15_000) { core.groupStore.rows.out(original.id)!!.state == AppGroupStore.STATE_SENT }
        TestCore.onMain {
            assertEquals(1, TestCore.radio.broadcasts.count { it.payload.contentEquals(original.packet) })
            assertTrue(f.post.tick(f.now + 600_000)); core.runPostman()
        }
        Views.waitUntil(15_000) { TestCore.radio.broadcasts.count { it.payload.contentEquals(original.packet) } == 2 }
        TestCore.onMain {
            assertEquals("accepted emissions share the original row budget", 2, core.groupStore.rows.out(original.id)!!.airings)
            val air = TestCore.radio.broadcasts.filter { it.payload.contentEquals(original.packet) }
            assertEquals("two accepted fake-radio offers, not a projected UI count", 2, air.size)
            assertTrue(air.all { it.port == org.phonemesh.ble.MeshRadio.ACCOUNT_PORT && it.channel == AppCore.PRIMARY_CHANNEL })
            assertTrue(TestCore.radio.sent.all { it.projected })
            air.forEach { assertArrayEquals(original.packet, it.payload) }
            assertEquals(1, radioRows(f).size)
        }
    }

    @Test fun anInFlightEnvelopeCoalescesAndALateCallbackChargesItsOriginalRowOnce() {
        val f = prepare()
        val original = TestCore.onMain { originalEnvelope(f) }
        // Accept earlier unrelated fixture mail automatically, then hold the target's acceptance.
        TestCore.onMain { TestCore.radio.acceptBroadcasts = true; TestCore.radio.connected(true); core.runPostman() }
        Views.waitUntil(15_000) { core.groupStore.rows.out(original.id)!!.state == AppGroupStore.STATE_SENT }
        TestCore.onMain {
            TestCore.radio.acceptBroadcasts = false
            assertTrue(f.post.tick(f.now + 600_000)); core.runPostman()
        }
        Views.waitUntil(15_000) { TestCore.radio.broadcasts.count { it.payload.contentEquals(original.packet) } == 2 }
        val flight = TestCore.onMain { TestCore.radio.broadcasts.last { it.payload.contentEquals(original.packet) } }
        TestCore.onMain {
            assertTrue(f.post.tick(f.now + 3_600_000)); core.runPostman()
            assertEquals("in-flight retry intent shares the same row", 1, radioRows(f).size)
            assertEquals(2, TestCore.radio.broadcasts.count { it.payload.contentEquals(original.packet) })
            TestCore.radio.deliver(flight.packetId, org.phonemesh.ble.DeliveryState.SUBMITTING)
            assertEquals(1, core.groupStore.rows.out(original.id)!!.airings)
            TestCore.radio.deliver(flight.packetId, org.phonemesh.ble.DeliveryState.QUEUED)
            TestCore.radio.deliver(flight.packetId, org.phonemesh.ble.DeliveryState.QUEUED)
            assertEquals("duplicate late callback earns no extra acceptance", 2, core.groupStore.rows.out(original.id)!!.airings)
            assertEquals(AppGroupStore.STATE_SENT, core.groupStore.rows.out(original.id)!!.state)
        }
    }

    @Test fun aMismatchingRadioPacketRefusesTheWholeRetryWithoutReplacingBytes() {
        val f = prepare()
        TestCore.onMain {
            val original = originalEnvelope(f)
            core.groupStore.aired(original.id)
            val different = original.packet.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            core.history.writableDatabase.execSQL("UPDATE group_outbox SET packet = ? WHERE id = ?", arrayOf<Any>(different, original.id))
            val saved = core.groupStore.saved(f.group)!!.state
            val total = core.groupStore.rows.outWaiting().size
            assertFalse("a different packet at the radio position is not the same envelope", f.post.tick(f.now + 600_000))
            assertArrayEquals("no reseal or silent replacement", different, core.groupStore.rows.out(original.id)!!.packet)
            assertArrayEquals("refused retry leaves its saved count unchanged", saved, core.groupStore.saved(f.group)!!.state)
            assertEquals(total, core.groupStore.rows.outWaiting().size)
        }
    }

    @Test fun ambiguousExistingRadioIdentitiesAreRefusedInsteadOfChoosingOne() {
        val f = prepare()
        TestCore.onMain {
            val original = originalEnvelope(f)
            core.groupStore.rows.addOut(original.groupId, original.packet, false, null, original.epoch, original.seq, 0, f.now)
            assertEquals(2, radioRows(f).size)
            assertFalse("ambiguous inherited rows must not choose an arbitrary identity", f.post.tick(f.now + 600_000))
            assertEquals("refusal adds no third identity", 2, radioRows(f).size)
        }
    }

    @Test fun nineteenKeptEnvelopesRoundTripThroughARealSealedSqliteRowAndReopen() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val (bea, _) = PersonFixture.meet("Bea")
        val result = TestCore.onMain {
            val at = System.currentTimeMillis()
            val post = GroupPost(core.accounts.identity, core.groupStore)
            val group = post.create("Maximum market", 0x2E7D32, Avatar.Letter, false, listOf(AppMailStore.idOf(bea.account)), at)!!
            val member = post.member(group)!!
            val peers = (0 until 19).map {
                val pair = P256.generate()
                val id = P256.compress(P256.generate().public as ECPublicKey)
                val signer = P256.compress(pair.public as ECPublicKey)
                id to GroupMember(id, signer, { data -> P256.sign(pair.private, data) }, group, core.accounts.identity)
            }
            val roster = GroupRoster("Maximum market", 0x2E7D32, listOf(
                GroupRoster.Member(core.accounts.identity, true, member.mySigner)) + peers.map { (id, m) -> GroupRoster.Member(id, false, m.mySigner) })
            val initial = member.newEpoch(roster, at)
            assertTrue(core.groupStore.commit(GroupPost.Change(group, saved = GroupStore.Saved(group, member.encode(), member.mySigner))))
            for ((id, peer) in peers) {
                assertTrue(peer.receiveKey(core.accounts.identity, GroupKey.decode(initial.keys.getValue(id.toList()))!!, at))
                initial.rosterPackets.forEach { peer.receive(it, at) }
                assertTrue(post.receive(peer.makeAndAnnounceBoxKey(at).second.packet, at))
            }
            assertTrue(post.restyle(group, "Maximum retry market", 0x336699, Avatar.Letter, false, at))
            val saved = core.groupStore.saved(group)!!.state
            val encoded = post.member(group)!!
            val increment = encoded.encode().size - encoded.encode(ext = 3).size
            assertEquals("19 complete actual-envelope entries and their count", 19 * (33 + 33 + 2 + 209 + 4 + 4 + 1 + 1) + 1, increment)
            val sealed = core.groupStore.rows.group(AppGroupStore.hex(group))!!.sealed
            assertTrue("the full row, not only the tail, is Vault sealed", sealed.size > saved.size)
            android.util.Log.i("PhonemeshG3i", "MAX_RETENTION_ROW encoded=${saved.size} sealed=${sealed.size} appended=$increment recipients=19")
            Triple(group, saved, peers.last().second)
        }
        TestCore.restart()
        TestCore.onMain {
            val saved = core.groupStore.saved(result.first)!!
            assertArrayEquals("a database/core reopen returns every encoded byte", result.second, saved.state)
            val reopened = core.groups.member(result.first)!!
            val due = reopened.envelopeRetryDue!!
            val retries = reopened.dueRetries(due)
            assertEquals("all retained recipient obligations survived", 19, retries.count { it.op == org.phonemesh.core.group.GroupEnvelope.OP })
            for (retry in retries) result.third.receive(retry.sealed.packet, due)
            assertEquals("maximum-row retry is usable, not just readable", 2L, result.third.epoch)
        }
    }

    private class RetryHubWire : org.phonemesh.core.hub.HubWire {
        val sent = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        override fun send(port: Int, packet: ByteArray): org.phonemesh.core.hub.HubClient.Sent {
            check(port == org.phonemesh.ble.MeshRadio.ACCOUNT_PORT)
            val before = sent.any { it.contentEquals(packet) }
            sent += packet.copyOf()
            return if (before) org.phonemesh.core.hub.HubClient.Sent.ALREADY else org.phonemesh.core.hub.HubClient.Sent.STORED
        }
        override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<org.phonemesh.core.hub.HubClient.Mail> = emptyList()
        override fun latest() = 0L
    }

    private class RetryHubLink(private val wire: RetryHubWire) : HubLink {
        var up = true
        override fun wire(): org.phonemesh.core.hub.HubWire? = if (up) wire else null
        override fun join(card: org.phonemesh.core.hub.HubCard, changed: () -> Unit) { up = true; changed() }
        override fun leave() { up = false }
    }

    @Test fun aJoinedHubReceivesTheSameRetryBytesAndCustodyIsNotCalledRadioEmission() {
        val f = prepare()
        val original = TestCore.onMain { originalEnvelope(f) }
        val wire = RetryHubWire()
        val link = RetryHubLink(wire)
        TestCore.onMain {
            val pin = org.phonemesh.core.hub.HubCard.pinOf(ByteArray(91) { it.toByte() })
            core.settings.hubCard = org.phonemesh.core.hub.HubCard("Retry fixture", "password1", 8443, pin).encode()
            core.hub = link
            core.activityStarted()
            core.runPostman()
        }
        Views.waitUntil(15_000) { core.groupStore.rows.out(original.id)!!.state == AppGroupStore.STATE_HUB }
        TestCore.onMain { assertTrue(f.post.tick(f.now + 600_000)); core.runPostman() }
        Views.waitUntil(15_000) { wire.sent.count { it.contentEquals(original.packet) } == 2 }
        TestCore.onMain {
            assertEquals("hub custody shares the original acceptance budget", 2, core.groupStore.rows.out(original.id)!!.airings)
            assertEquals(1, radioRows(f).size)
            assertTrue("joined-hub custody is not a direct RF send", TestCore.radio.broadcasts.isEmpty())
            assertEquals(AppGroupStore.STATE_HUB, core.groupStore.rows.out(original.id)!!.state)
            wire.sent.filter { it.contentEquals(original.packet) }.forEach { assertArrayEquals(original.packet, it) }
        }
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() && (0..bytes.size - needle.size).any { at -> needle.indices.all { bytes[at + it] == needle[it] } }
}

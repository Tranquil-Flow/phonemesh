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
            assertFalse("live group id is excluded", contains(plain, f.group))
            assertNotNull(TestCore.onMain { f.post.member(f.group) })
        } finally { secrets.third.fill(0); secrets.second.fill(0) }
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() && (0..bytes.size - needle.size).any { at -> needle.indices.all { bytes[at + it] == needle[it] } }
}

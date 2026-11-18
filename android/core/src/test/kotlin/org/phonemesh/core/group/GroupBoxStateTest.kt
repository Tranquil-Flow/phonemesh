package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/** G3h: durable own keys, and rotation only after a checked envelope-delivered epoch is taken. */
class GroupBoxStateTest {
    private val group = ByteArray(16) { 7 }
    private class FixedRandom(private val secret: ByteArray) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) { secret.copyInto(bytes) }
    }
    private inner class Phone(val admin: Boolean) {
        private val identity = P256.generate()
        private val signing = P256.generate()
        val id = P256.compress(identity.public as ECPublicKey)
        val signer = P256.compress(signing.public as ECPublicKey)
        var member = GroupMember(id, signer, { P256.sign(signing.private, it) }, group)
        fun entry() = GroupRoster.Member(id, admin, signer)
        fun load(bytes: ByteArray = member.encode()): GroupMember? =
            GroupMember.decode(id, signer, { P256.sign(signing.private, it) }, group, bytes)
        fun restart() {
            val restored = load()
            assertNotNull("the saved member must restore", restored)
            member = restored!!
        }
        fun joins(a: Phone) { member = GroupMember(id, signer, { P256.sign(signing.private, it) }, group, a.id) }
    }
    private fun two(): Pair<Phone, Phone> {
        val a = Phone(true); val b = Phone(false)
        b.joins(a)
        return initialise(a, b)
    }
    private fun initialise(a: Phone, b: Phone): Pair<Phone, Phone> {
        val list = GroupRoster("Puits", 0x2E7D32, listOf(a.entry(), b.entry()))
        val out = a.member.newEpoch(list, 0)
        assertTrue(b.member.receiveKey(a.id, GroupKey.decode(out.keys.getValue(b.id.toList()))!!, 0))
        out.rosterPackets.forEach { b.member.receive(it, 0) }
        val (_, announced) = b.member.makeAndAnnounceBoxKey(100)
        a.member.receive(announced.packet, 100)
        assertFalse(b.member.needsBoxKey)
        return a to b
    }
    private fun next(a: Phone, b: Phone) = GroupRoster("Nouveau", 0x336699, listOf(a.entry(), b.entry()))
    private fun envelopeEpoch(a: Phone, b: Phone): GroupMember.Outgoing =
        a.member.newEpoch(next(a, b), 1000, envelopeFor = listOf(b.id))
    private fun take(b: Phone, out: GroupMember.Outgoing) { out.rosterPackets.forEach { b.member.receive(it, 1001) } }

    @Test fun aWaitingEnvelopeIsNotAnEpochReached() {
        val (a, b) = two(); val out = envelopeEpoch(a, b)
        b.member.receive(out.envelopes.single().packet, 1000)
        assertNotNull(b.member.waitingOn())
        assertFalse("no acknowledgement before the list checks", b.member.needsBoxKey)
        take(b, out)
        assertTrue("the checked epoch now requires rotation", b.member.needsBoxKey)
    }

    @Test fun thePendingEnvelopeOriginSurvivesARestart() {
        val (a, b) = two(); val out = envelopeEpoch(a, b)
        b.member.receive(out.envelopes.single().packet, 1000); b.restart()
        assertFalse(b.member.needsBoxKey)
        take(b, out); b.restart()
        assertTrue("restart must not lose the owed rotation", b.member.needsBoxKey)
        b.member.makeAndAnnounceBoxKey(1100); b.restart()
        assertFalse("one announcement pays the debt durably", b.member.needsBoxKey)
    }

    @Test fun anInvalidListDoesNotTriggerRotation() {
        val (a, b) = two(); val list = next(a, b); val secret = ByteArray(32) { 42 }
        val bad = GroupKey(group, 2, secret, ByteArray(16), a.member.digest, list.parts().size, a.signer)
        val packet = a.member.sealBody(GroupEnvelope.seal(bad, a.id, b.id, a.member.boxKeyOf(b.id)!!)).packet
        val out = a.member.newEpoch(list, 1000, FixedRandom(secret))
        b.member.receive(packet, 1000); take(b, out)
        assertEquals(1L, b.member.epoch)
        assertTrue(b.member.canSend)
        assertFalse("an accepted key with an invalid list reached no epoch", b.member.needsBoxKey)
    }

    @Test fun anExpiredWaitingEnvelopeDoesNotTriggerRotation() {
        val (a, b) = two(); val out = envelopeEpoch(a, b)
        b.member.receive(out.envelopes.single().packet, 1000)
        b.member.expectedCodes(1001 + GroupMember.KEEP_MS); b.restart()
        assertTrue(b.member.canSend)
        assertFalse("a list that never arrived is not acknowledged", b.member.needsBoxKey)
    }

    @Test fun aPrivateEpochDoesNotRotateTheBoxKey() {
        val (a, b) = two(); val out = a.member.newEpoch(next(a, b), 1000)
        assertTrue(b.member.receiveKey(a.id, GroupKey.decode(out.keys.getValue(b.id.toList()))!!, 1000))
        take(b, out); b.restart()
        assertEquals(2L, b.member.epoch)
        assertFalse(b.member.needsBoxKey)
    }

    @Test fun aLateEnvelopeForTheAlreadyTakenPrivateEpochDoesNotRotate() {
        val (a, b) = two(); val list = next(a, b); val secret = ByteArray(32) { 42 }
        val key = GroupKey(group, 2, secret, list.digest(group, 2, a.member.digest), a.member.digest, list.parts().size, a.signer)
        val packet = a.member.sealBody(GroupEnvelope.seal(key, a.id, b.id, a.member.boxKeyOf(b.id)!!)).packet
        val out = a.member.newEpoch(list, 1000, FixedRandom(secret))
        assertTrue(b.member.receiveKey(a.id, GroupKey.decode(out.keys.getValue(b.id.toList()))!!, 1000))
        take(b, out)
        b.member.receive(packet, 1002)
        assertTrue("the late envelope was read on the old chain", b.member.took)
        assertFalse("a duplicate key must not create rotation debt", b.member.needsBoxKey)
    }

    @Test fun thePrivateHalfActuallyOpensAnEnvelopeAfterARestart() {
        val (a, b) = two(); b.restart()
        val out = envelopeEpoch(a, b)
        b.member.receive(out.envelopes.single().packet, 1000); take(b, out)
        assertEquals("public bytes alone cannot open this", 2L, b.member.epoch)
    }

    @Test fun thePreviousPrivateHalfHasTheSameDeadlineAfterARestart() {
        val (_, b) = two()
        val old = b.member.ownBoxKeys(100).single().public
        b.member.makeAndAnnounceBoxKey(200); b.restart()
        assertEquals(2, b.member.ownBoxKeys(200 + GroupMember.KEEP_MS).size)
        assertArrayEquals(old, b.member.ownBoxKeys(200 + GroupMember.KEEP_MS)[1].public)
        assertEquals(1, b.member.ownBoxKeys(201 + GroupMember.KEEP_MS).size)
        b.restart()
        assertEquals(1, b.member.ownBoxKeys(201 + GroupMember.KEEP_MS).size)
    }

    @Test fun leavingRemovesOwnPrivateKeysFromTheSavedState() {
        val (_, b) = two(); b.member.leave(); b.restart()
        assertTrue(b.member.ownBoxKeys(1000).isEmpty())
    }

    @Test fun endingRemovesOwnPrivateKeysFromTheSavedState() {
        val (_, b) = two(); b.member.end(); b.restart()
        assertTrue(b.member.ownBoxKeys(1000).isEmpty())
    }

    @Test fun beingRemovedRemovesOwnPrivateKeysFromTheSavedState() {
        val (a, b) = two()
        assertTrue(b.member.receiveControl(a.id, GroupControl.Removed(group, 2), 1000).first)
        b.restart(); assertTrue(b.member.ownBoxKeys(1000).isEmpty())
    }

    @Test fun oldExtensionVersionsLoadWithoutOwnKeysAndNeedAnAnnouncement() {
        val (_, b) = two()
        for (ext in 1..2) {
            val loaded = b.load(b.member.encode(ext))!!
            assertTrue(loaded.ownBoxKeys(1000).isEmpty())
            assertTrue(loaded.needsBoxKey)
            assertEquals(1L, loaded.epoch)
        }
    }

    @Test fun pendingEnvelopeClaimsAreUniqueAndForFutureKeys() {
        val (a, b) = two(); val out = envelopeEpoch(a, b)
        b.member.receive(out.envelopes.single().packet, 1000)
        val bytes = b.member.encode(ext = 3)
        val id = bytes.copyOfRange(bytes.size - P256.POINT, bytes.size)
        val twice = bytes.copyOfRange(0, bytes.size - P256.POINT - 1) + byteArrayOf(2) + id + id
        assertNull("one claim per pending sender", b.load(twice))
        val alreadyHeld = bytes.copyOf().also { java.nio.ByteBuffer.wrap(it, 2, 4).putInt(2) }
        assertNull("a rival is not a pending future delivery", b.load(alreadyHeld))
    }

    @Test fun malformedOwnKeyCountPublicPointAndDeadlineAreRefused() {
        val (_, b) = two()
        val bytes = b.member.encode(ext = 3)
        val start = b.member.encode(2).size
        val entrySize = 2 + P256.POINT + 2 + 32 + 8
        val entry = bytes.copyOfRange(start + 1, start + 1 + entrySize)
        // Otherwise-valid previous entries: repeating the current entry's Long.MAX_VALUE deadline would make
        // the deadline guard mask the count guard. The two-key positive control proves this tail is readable.
        val previous = entry.copyOf().also { java.nio.ByteBuffer.wrap(it, entrySize - 8, 8).putLong(1000) }
        val two = bytes.copyOfRange(0, start) + byteArrayOf(2) + entry + previous + byteArrayOf(0, 0)
        assertNotNull("valid current and previous entries", b.load(two))
        val three = bytes.copyOfRange(0, start) + byteArrayOf(3) + entry + previous + previous + byteArrayOf(0, 0)
        assertNull("at most current and previous", b.load(three))
        assertNull("compressed point required", b.load(bytes.copyOf().also { it[start + 3] = 0 }))
        val deadline = start + 1 + entrySize - 8
        assertNull("current key has no retirement time", b.load(bytes.copyOf().also { it.fill(0, deadline, deadline + 8) }))
        assertNull("terminal states retain no private halves", b.load(bytes.copyOf().also { it[1] = GroupMember.State.LEFT.ordinal.toByte() }))
    }

    @Test fun everyTruncationOfTheNewExtensionAndTrailingDataIsRefused() {
        val (_, b) = two(); val bytes = b.member.encode(ext = 3)
        // The older complete extension is valid; every partial version-3 tail is not.
        val start = b.member.encode(2).size
        for (n in start until bytes.size) assertNull("truncated at $n", b.load(bytes.copyOf(n)))
        assertNull(b.load(bytes + byteArrayOf(0)))
        assertNull(b.load(byteArrayOf()))
        assertNull(b.load(bytes.copyOf().also { it[it.lastIndex] = 2 }))
        assertNull("non-boolean rotation flag", b.load(bytes.copyOf().also { it[it.lastIndex - 1] = 2 }))
    }
}

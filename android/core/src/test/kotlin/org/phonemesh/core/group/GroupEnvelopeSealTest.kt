package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/**
 * The admin's side of the relayed envelope (v0.1.3 G3f; relayed group control design, "The envelope"; accounts
 * decisions 79 and 80).
 *
 * An admin that is not a member's contact seals the next epoch's key to the box key that member announced on the list
 * held, and sends it as a group packet on its own radio chain of the epoch the member holds, before it takes the new
 * epoch: the member reads it on the chain it already reads, and the key inside names the epoch after the one held.
 *
 * The admin seals the key to the box key it read on that member's own chain, which the group signature every packet
 * carries binds to that member. It cannot be told to seal it to any other key, and a member that announced none
 * cannot be reached this way at all.
 */
class GroupEnvelopeSealTest {
    private val group = ByteArray(16) { 7 }

    private fun pub(k: KeyPair) = P256.compress(k.public as ECPublicKey)

    /** A SecureRandom that always gives the same bytes, so the epoch's secret is known in advance. */
    private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(b: ByteArray) { bytes.copyInto(b) }
    }

    /** One person: an identity key, the signing key the group list binds to it, and their phone's view of it. */
    private inner class Person(val name: String) {
        val identity = P256.generate()
        val signing = P256.generate()
        val id = pub(identity)
        val sk = pub(signing)
        var member = GroupMember(id, sk, { P256.sign(signing.private, it) }, group)
        /** The box key this person announced and keeps. */
        var box: KeyPair? = null
        fun entry(admin: Boolean = false) = GroupRoster.Member(id, admin, sk)
        fun joins(admin: Person) { member = GroupMember(id, sk, { P256.sign(signing.private, it) }, group, admin.id) }
        /** This person makes a box key, keeps it, and announces it on its own radio chain. */
        fun announceBoxKey(): ByteArray {
            val key = P256.generate()
            box = key
            member.setOwnBoxKey(GroupEnvelope.BoxKey(key.private, pub(key)), 0)
            return member.sealBody(GroupBoxKey.encode(pub(key))).packet
        }
    }

    private fun roster(vararg members: GroupRoster.Member) = GroupRoster("Puits", 0x2E7D32, members.toList())

    /** How many list packets the first epoch's list took, so the chain position of an envelope can be checked. */
    private var firstEpochParts = 0

    /** The secret of the first epoch, which the test chose, so it can read the admin's own chain back. */
    private val firstSecret = ByteArray(32) { (it + 1).toByte() }

    /** Delivers the key and the list packets of [out] to everyone but [from]. */
    private fun deliver(from: Person, out: GroupMember.Outgoing, people: List<Person>, now: Long) {
        for (p in people) {
            out.keys[p.id.toList()]?.let { assertTrue("${p.name} takes the key", p.member.receiveKey(from.id, GroupKey.decode(it)!!, now)) }
        }
        for (packet in out.rosterPackets) for (p in people) if (p !== from) p.member.receive(packet, now)
    }

    /** Ama (admin) and Kofi in epoch 1; Kofi announced a box key and Ama read it on Kofi's own chain. */
    private fun two(): Pair<Person, Person> {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.joins(ama)
        assertTrue(ama.member.receiveControl(kofi.id, GroupControl.decode(GroupControl.SIGNER, kofi.member.introduce())!!, 0).first)
        val list = roster(GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!), ama.entry(true))
        val out = ama.member.newEpoch(list, 0, FixedRandom(firstSecret))
        firstEpochParts = out.rosterPackets.size
        deliver(ama, out, listOf(ama, kofi), 0)
        assertEquals("Kofi is in the group", GroupMember.State.ACTIVE, kofi.member.state)
        ama.member.receive(kofi.announceBoxKey(), 100)
        assertArrayEquals("Ama holds the box key Kofi announced", pub(kofi.box!!), ama.member.boxKeyOf(kofi.id))
        return ama to kofi
    }

    /** Ama (admin), Kofi and Esi in epoch 1; only Kofi announced a box key, so Ama holds one only for Kofi. */
    private fun three(): Triple<Person, Person, Person> {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val esi = Person("Esi")
        for (p in listOf(kofi, esi)) {
            p.joins(ama)
            assertTrue(ama.member.receiveControl(p.id, GroupControl.decode(GroupControl.SIGNER, p.member.introduce())!!, 0).first)
        }
        val list = roster(ama.entry(true),
            GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!),
            GroupRoster.Member(esi.id, false, ama.member.signerOf(esi.id)!!))
        val out = ama.member.newEpoch(list, 0, FixedRandom(firstSecret))
        firstEpochParts = out.rosterPackets.size
        deliver(ama, out, listOf(ama, kofi, esi), 0)
        for (p in listOf(ama, kofi, esi)) assertEquals("${p.name} is in the group", GroupMember.State.ACTIVE, p.member.state)
        ama.member.receive(kofi.announceBoxKey(), 100)
        return Triple(ama, kofi, esi)
    }

    /**
     * The body the admin sealed at [seq] on its own radio chain of [epoch], read with the secret this test chose for
     * that epoch: the envelope body is inside the chain packet, so this is the only way to see the ephemeral key.
     */
    private fun bodyOnAdminChain(ama: Person, epoch: Long, secret: ByteArray, seq: Long, packet: ByteArray): ByteArray {
        var chain = GroupCipher.chain0(group, epoch, secret, ama.id)
        var step = GroupCipher.step(chain)
        for (i in 0 until seq) { chain = step.next; step = GroupCipher.step(chain) }
        return GroupCipher.open(group, epoch, seq, step, ama.id, ama.sk, packet)
            ?: throw AssertionError("the admin's own chain packet does not open")
    }

    /** The list of epoch 2, the same one for everyone in the test. */
    private fun nextList(ama: Person, kofi: Person) =
        roster(ama.entry(true), GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!))

    /** The list of epoch 2 for three people, the same one for everyone in the test. */
    private fun nextList(ama: Person, kofi: Person, esi: Person) =
        roster(ama.entry(true), GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!),
            GroupRoster.Member(esi.id, false, ama.member.signerOf(esi.id)!!))

    @Test fun theAdminSealsAnEnvelopeForAMemberItReachesByEnvelope() {
        val (ama, kofi) = two()
        val out = ama.member.newEpoch(nextList(ama, kofi), 1000, envelopeFor = listOf(kofi.id))
        assertEquals("one envelope", 1, out.envelopes.size)
        val sealed = out.envelopes[0]
        // Kofi reads it on Ama's radio chain.
        assertTrue("an envelope is shown to nobody", kofi.member.receive(sealed.packet, 1000).isEmpty())
        assertArrayEquals("Kofi waits for Ama's list", ama.id, kofi.member.waitingOn())
        assertEquals("the epoch is not taken before the list", 1L, kofi.member.epoch)
        assertNull("an envelope is receipted by nobody", kofi.member.receipt())
    }

    @Test fun theMemberTakesTheEpochWhenTheListArrivesAfterTheEnvelope() {
        val (ama, kofi) = two()
        val out = ama.member.newEpoch(nextList(ama, kofi), 1000, envelopeFor = listOf(kofi.id))
        // Kofi reads the envelope, then the list, and takes the epoch.
        assertTrue(kofi.member.receive(out.envelopes[0].packet, 1000).isEmpty())
        for (packet in out.rosterPackets) kofi.member.receive(packet, 1000)
        assertEquals("Kofi takes the epoch", 2L, kofi.member.epoch)
        assertEquals(GroupMember.State.ACTIVE, kofi.member.state)
    }

    @Test fun theEnvelopeRidesTheChainOfTheEpochHeld() {
        val (ama, kofi) = two()
        val out = ama.member.newEpoch(nextList(ama, kofi), 1000, envelopeFor = listOf(kofi.id))
        val sealed = out.envelopes[0]
        assertEquals("the envelope is on the chain of the epoch held", 1L, sealed.epoch)
        assertEquals("and at the position after that epoch's list", firstEpochParts.toLong(), sealed.seq)
        assertFalse("not a hub packet", sealed.hub)
        // A message sealed after the epoch is taken rides the new chain, which Kofi cannot read yet.
        val later = ama.member.sealBody(byteArrayOf(GroupBody.MESSAGE.toByte(), 1)).packet
        assertTrue("Kofi cannot read the new chain yet", kofi.member.receive(later, 1000).isEmpty())
        // The envelope, sealed before the epoch was taken, is on the chain Kofi does read.
        assertTrue(kofi.member.receive(sealed.packet, 1000).isEmpty())
        assertArrayEquals("Kofi waits for Ama's list", ama.id, kofi.member.waitingOn())
    }

    @Test fun aMemberReachedByAnEnvelopeGetsNoPrivateKey() {
        val (ama, kofi) = two()
        val out = ama.member.newEpoch(nextList(ama, kofi), 1000, envelopeFor = listOf(kofi.id))
        assertNull("Kofi is reached by an envelope, not by a private key", out.keys[kofi.id.toList()])
        assertEquals("one envelope", 1, out.envelopes.size)
    }

    @Test fun aMemberReachedPrivatelyStillGetsItsPrivateKey() {
        val (ama, kofi) = two()
        val out = ama.member.newEpoch(nextList(ama, kofi), 1000)
        assertNotEquals("Kofi is reached privately", null, out.keys[kofi.id.toList()])
        assertEquals("and by no envelope", 0, out.envelopes.size)
    }

    @Test fun anEnvelopeForAMemberThatAnnouncedNoBoxKeyIsRefused() {
        // Esi is on the list held but announced no box key, so Ama holds none for it.
        val (ama, kofi, esi) = three()
        assertNull("Ama holds no box key for Esi", ama.member.boxKeyOf(esi.id))
        try {
            ama.member.newEpoch(nextList(ama, kofi, esi), 1000, envelopeFor = listOf(esi.id))
            fail("a member that announced no box key cannot be reached by an envelope")
        } catch (_: IllegalArgumentException) {
            // as it should be
        }
        assertEquals("the epoch does not move", 1L, ama.member.epoch)
        assertEquals(GroupMember.State.ACTIVE, ama.member.state)
    }

    @Test fun anEnvelopeForAMemberNotOnTheNextListIsRefused() {
        // Esi is on the list held and announced a box key, but the next list drops it: it is not reached at all.
        val (ama, kofi, esi) = three()
        esi.announceBoxKey()
        ama.member.receive(esi.announceBoxKey(), 200)
        assertNotNull("Ama holds a box key for Esi", ama.member.boxKeyOf(esi.id))
        val next = roster(ama.entry(true), GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!))
        try {
            ama.member.newEpoch(next, 1000, envelopeFor = listOf(esi.id))
            fail("an envelope is only for a member of the next list")
        } catch (_: IllegalArgumentException) {
            // as it should be
        }
        assertEquals("the epoch does not move", 1L, ama.member.epoch)
    }

    @Test fun anEnvelopeToTheAdminItselfIsRefused() {
        val (ama, kofi) = two()
        // The admin holds its own key, so the rule that refuses an envelope to itself is what stops this, not the
        // box-key rule: a phone never reads its own chain, so it never holds a box key for itself.
        val own = P256.generate()
        ama.member.putBoxKeyUnchecked(ama.id, pub(own))
        assertNotNull("the admin holds a box key for itself", ama.member.boxKeyOf(ama.id))
        try {
            ama.member.newEpoch(nextList(ama, kofi), 1000, envelopeFor = listOf(ama.id))
            fail("the admin holds its own key")
        } catch (_: IllegalArgumentException) {
            // as it should be
        }
        assertEquals("the epoch does not move", 1L, ama.member.epoch)
    }

    @Test fun oneEnvelopePerMember() {
        val (ama, kofi) = two()
        try {
            ama.member.newEpoch(nextList(ama, kofi), 1000, envelopeFor = listOf(kofi.id, kofi.id))
            fail("one envelope per member")
        } catch (_: IllegalArgumentException) {
            // as it should be
        }
        assertEquals("the epoch does not move", 1L, ama.member.epoch)
    }

    @Test fun eachEnvelopeHasItsOwnEphemeralKey() {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val esi = Person("Esi")
        for (p in listOf(kofi, esi)) {
            p.joins(ama)
            assertTrue(ama.member.receiveControl(p.id, GroupControl.decode(GroupControl.SIGNER, p.member.introduce())!!, 0).first)
        }
        val list = roster(ama.entry(true),
            GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!),
            GroupRoster.Member(esi.id, false, ama.member.signerOf(esi.id)!!))
        deliver(ama, ama.member.newEpoch(list, 0, FixedRandom(firstSecret)), listOf(ama, kofi, esi), 0)
        for (p in listOf(kofi, esi)) ama.member.receive(p.announceBoxKey(), 100)
        val next = roster(ama.entry(true),
            GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!),
            GroupRoster.Member(esi.id, false, ama.member.signerOf(esi.id)!!))
        val out = ama.member.newEpoch(next, 1000, envelopeFor = listOf(kofi.id, esi.id))
        assertEquals("one envelope each", 2, out.envelopes.size)
        assertEquals("the envelopes are on the chain of the epoch held", 1L, out.envelopes[0].epoch)
        // The envelope body is inside the chain packet: read it back off the admin's own chain of the epoch held.
        val a = bodyOnAdminChain(ama, 1, firstSecret, out.envelopes[0].seq, out.envelopes[0].packet)
        val b = bodyOnAdminChain(ama, 1, firstSecret, out.envelopes[1].seq, out.envelopes[1].packet)
        assertEquals("the body is an op 8 envelope", GroupEnvelope.OP.toByte(), a[0])
        // The ephemeral key is the third byte on: each envelope has its own, so each AES key and nonce is used once.
        assertNotEquals("the ephemeral keys differ", a.copyOfRange(2, 35).toList(), b.copyOfRange(2, 35).toList())
        assertNotEquals("the envelopes differ", a.toList(), b.toList())
    }

    @Test fun anAdminThatLeavesSealsNoEnvelope() {
        // Ama is the last admin and hands the group over to Kofi while leaving.
        val (ama, kofi, esi) = three()
        val next = roster(GroupRoster.Member(kofi.id, true, ama.member.signerOf(kofi.id)!!), esi.entry())
        try {
            ama.member.newEpoch(next, 1000, leaving = true, envelopeFor = listOf(kofi.id))
            fail("an admin that leaves reaches no member by an envelope")
        } catch (_: IllegalArgumentException) {
            // as it should be
        }
    }

    @Test fun anEnvelopeIsReadOnlyOnce() {
        val (ama, kofi) = two()
        val out = ama.member.newEpoch(nextList(ama, kofi), 1000, envelopeFor = listOf(kofi.id))
        val packet = out.envelopes[0].packet
        assertTrue(kofi.member.receive(packet, 1000).isEmpty())
        // The same packet again: the position is used once.
        assertTrue("a replayed envelope is not read again", kofi.member.receive(packet, 1000).isEmpty())
        assertEquals("and it changes nothing", 1L, kofi.member.epoch)
    }
}

package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/**
 * The member's side of the relayed envelope (v0.1.3 G3e; relayed group control design, "The envelope"; accounts
 * decisions 79 and 80).
 *
 * An admin that is not a member's contact seals the next epoch's key to that member's announced box key and sends
 * it as a group packet on its own radio chain of the epoch the member holds. The member opens it with its own box
 * key and the key goes through the unchanged `receiveKey` rules: next epoch only, parent = the list held, one
 * waiting key per admin, and no change at all when the key is not for this phone.
 */
class GroupEnvelopeTransportTest {
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
        // Kofi is first and Ama, the admin, second: a member's own entry in the list is what is used, not the first.
        val list = roster(GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!), ama.entry(true))
        deliver(ama, ama.member.newEpoch(list, 0), listOf(ama, kofi), 0)
        assertEquals("Kofi is in the group", GroupMember.State.ACTIVE, kofi.member.state)
        ama.member.receive(kofi.announceBoxKey(), 100)
        assertArrayEquals("Ama holds the box key Kofi announced", pub(kofi.box!!), ama.member.boxKeyOf(kofi.id))
        return ama to kofi
    }

    /** Ama (admin), Kofi and Esi in epoch 1; Kofi announced a box key and Ama read it. */
    private fun three(): Triple<Person, Person, Person> {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val esi = Person("Esi")
        val people = listOf(ama, kofi, esi)
        for (p in listOf(kofi, esi)) {
            p.joins(ama)
            assertTrue(ama.member.receiveControl(p.id, GroupControl.decode(GroupControl.SIGNER, p.member.introduce())!!, 0).first)
        }
        val list = roster(ama.entry(true),
            GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!),
            GroupRoster.Member(esi.id, false, ama.member.signerOf(esi.id)!!))
        deliver(ama, ama.member.newEpoch(list, 0), people, 0)
        for (p in people) assertEquals("${p.name} is in the group", GroupMember.State.ACTIVE, p.member.state)
        ama.member.receive(kofi.announceBoxKey(), 100)
        return Triple(ama, kofi, esi)
    }

    /** The list of epoch 2, the same one for everyone in the test. */
    private fun nextList(ama: Person, kofi: Person) =
        roster(ama.entry(true), GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!))

    /**
     * The key for the epoch after the one held, as the admin holds it: the same secret, digests and signing key the
     * epoch is taken with. The parent is the digest of the list held.
     */
    private fun nextKey(ama: Person, list: GroupRoster, secret: ByteArray): GroupKey {
        val parent = ama.member.digest
        return GroupKey(group, 2, secret, list.digest(group, 2, parent), parent, list.parts().size, ama.sk)
    }

    /** The envelope Ama seals to Kofi's announced box key, carrying [key]. */
    private fun envelope(ama: Person, kofi: Person, key: GroupKey, box: ByteArray = pub(kofi.box!!)): ByteArray =
        GroupEnvelope.seal(key, ama.id, kofi.id, box)

    @Test fun theMemberTakesTheKeyFromAnEnvelopeOnTheAdminsChain() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val list = nextList(ama, kofi)
        val key = nextKey(ama, list, secret)
        val packet = ama.member.sealBody(envelope(ama, kofi, key)).packet
        // Kofi reads it on Ama's radio chain.
        assertTrue("an envelope is shown to nobody", kofi.member.receive(packet, 1000).isEmpty())
        assertArrayEquals("Kofi waits for Ama's list", ama.id, kofi.member.waitingOn())
        assertEquals("the epoch is not taken before the list", 1L, kofi.member.epoch)
        assertNull("an envelope is receipted by nobody", kofi.member.receipt())
    }

    @Test fun theMemberTakesTheEpochWhenTheListArrives() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val list = nextList(ama, kofi)
        val key = nextKey(ama, list, secret)
        val sealed = ama.member.sealBody(envelope(ama, kofi, key)).packet
        // Ama takes the epoch with that very secret and sends the list.
        val out = ama.member.newEpoch(list, 1000, FixedRandom(secret))
        assertArrayEquals("the sealed key is the epoch's key", key.encode(), out.keys[kofi.id.toList()])
        // Kofi reads the envelope, then the list.
        assertTrue(kofi.member.receive(sealed, 1000).isEmpty())
        for (packet in out.rosterPackets) kofi.member.receive(packet, 1000)
        assertEquals("Kofi takes the epoch", 2L, kofi.member.epoch)
        assertEquals(GroupMember.State.ACTIVE, kofi.member.state)
    }

    @Test fun anEnvelopeForSomebodyElseIsNotTaken() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val key = nextKey(ama, nextList(ama, kofi), secret)
        // Sealed to a box key that is not Kofi's.
        val packet = ama.member.sealBody(envelope(ama, kofi, key, pub(P256.generate()))).packet
        assertTrue(kofi.member.receive(packet, 1000).isEmpty())
        assertNull("Kofi takes nothing that is not his", kofi.member.waitingOn())
        assertEquals("the epoch does not move", 1L, kofi.member.epoch)
    }

    @Test fun anEnvelopeFromAMemberThatIsNotAnAdminIsDropped() {
        val (ama, kofi, esi) = three()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val key = nextKey(ama, nextList(ama, kofi), secret)
        // Esi is not an admin, so an envelope on Esi's chain is not the admin's.
        val packet = esi.member.sealBody(envelope(ama, kofi, key)).packet
        assertTrue(kofi.member.receive(packet, 1000).isEmpty())
        assertNull("only an admin's envelope is taken", kofi.member.waitingOn())
    }

    @Test fun anEnvelopeOnTheHubChainIsNotRead() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val key = nextKey(ama, nextList(ama, kofi), secret)
        val body = envelope(ama, kofi, key)
        // A forged op 8 on the hub chain: an envelope travels on the radio chain only.
        val forged = ama.member.sealUnchecked(GroupEnvelope.OP, body.copyOfRange(1, body.size), true)
        assertTrue(kofi.member.receive(forged.packet, 1000).isEmpty())
        assertNull("the hub chain carries no envelope", kofi.member.waitingOn())
    }

    @Test fun anEnvelopeForAnotherEpochIsNotTaken() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val parent = ama.member.digest
        // An envelope for epoch 3, while this phone holds epoch 1.
        val key = GroupKey(group, 3, secret, ByteArray(GroupRoster.DIGEST), parent, 1, ama.sk)
        val packet = ama.member.sealBody(envelope(ama, kofi, key)).packet
        assertTrue(kofi.member.receive(packet, 1000).isEmpty())
        assertNull("the epoch must be the next one", kofi.member.waitingOn())
    }

    @Test fun anEnvelopeWhoseParentIsNotTheListHeldIsRefused() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val list = nextList(ama, kofi)
        // The same key, but built on another list.
        val key = GroupKey(group, 2, secret, list.digest(group, 2, ByteArray(GroupRoster.DIGEST)),
            ByteArray(GroupRoster.DIGEST), list.parts().size, ama.sk)
        val packet = ama.member.sealBody(envelope(ama, kofi, key)).packet
        assertTrue(kofi.member.receive(packet, 1000).isEmpty())
        assertNull("the parent must be the list held", kofi.member.waitingOn())
    }

    @Test fun thePreviousBoxKeyStillOpensAnEnvelope() {
        val (ama, kofi) = two()
        val old = kofi.box!!
        // Kofi makes a new box key; the one before it is kept, because an admin may have sealed to it.
        val fresh = P256.generate()
        kofi.member.setOwnBoxKey(GroupEnvelope.BoxKey(fresh.private, pub(fresh)), 1000)
        assertEquals("the current key and the one before it", 2, kofi.member.ownBoxKeys(1000).size)
        val secret = ByteArray(32) { (it + 1).toByte() }
        val key = nextKey(ama, nextList(ama, kofi), secret)
        // Ama sealed to the key Kofi announced before it changed.
        val packet = ama.member.sealBody(envelope(ama, kofi, key, pub(old))).packet
        assertTrue(kofi.member.receive(packet, 1000).isEmpty())
        assertNotNull("the kept key opens the envelope", kofi.member.waitingOn())
    }

    @Test fun aBoxKeyPastItsKeepTimeDoesNotOpenAnEnvelope() {
        val (ama, kofi) = two()
        val old = kofi.box!!
        val fresh = P256.generate()
        kofi.member.setOwnBoxKey(GroupEnvelope.BoxKey(fresh.private, pub(fresh)), 1000)
        val secret = ByteArray(32) { (it + 1).toByte() }
        val key = nextKey(ama, nextList(ama, kofi), secret)
        val packet = ama.member.sealBody(envelope(ama, kofi, key, pub(old))).packet
        val until = 1000 + GroupMember.KEEP_MS
        assertEquals("the old key is kept for its whole time", 2, kofi.member.ownBoxKeys(until).size)
        val later = until + 1
        assertEquals("only the current key is kept", 1, kofi.member.ownBoxKeys(later).size)
        assertTrue(kofi.member.receive(packet, later).isEmpty())
        assertNull("the old key is gone, so the envelope stays shut", kofi.member.waitingOn())
    }

    @Test fun anEnvelopeFromAMemberThatIsNoLongerAnAdminIsDropped() {
        // Ama and Kofi are both admins of the first list; the next list keeps Kofi but makes it an ordinary member.
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.joins(ama)
        assertTrue(ama.member.receiveControl(kofi.id, GroupControl.decode(GroupControl.SIGNER, kofi.member.introduce())!!, 0).first)
        val first = roster(ama.entry(true), GroupRoster.Member(kofi.id, true, ama.member.signerOf(kofi.id)!!))
        deliver(ama, ama.member.newEpoch(first, 0), listOf(ama, kofi), 0)
        // Ama's box key: the envelope is sealed to it, so Ama is the one who opens it.
        val box = P256.generate()
        ama.member.setOwnBoxKey(GroupEnvelope.BoxKey(box.private, pub(box)), 0)
        val parent = ama.member.digest
        // Kofi, still an admin of the list it holds, seals a key for the epoch Ama is about to take, on its own chain.
        val rival = GroupKey(group, 2, ByteArray(32) { 9 }, parent, parent, 1, kofi.sk)
        val sealed = kofi.member.sealBody(GroupEnvelope.seal(rival, kofi.id, ama.id, pub(box))).packet
        // Ama takes the next list, where Kofi is only a member.
        val next = roster(ama.entry(true), GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!))
        deliver(ama, ama.member.newEpoch(next, 1000), listOf(ama, kofi), 1000)
        assertEquals("Kofi is no longer an admin", false, ama.member.roster!!.member(kofi.id)!!.admin)
        // Ama reads the envelope late, on the chain of the epoch before.
        assertTrue(ama.member.receive(sealed, 1100).isEmpty())
        assertNull("a member that is not an admin of the list held cannot give this phone a key", ama.member.ask())
        assertEquals("the epoch does not move", 2L, ama.member.epoch)
        assertEquals(GroupMember.State.ACTIVE, ama.member.state)
    }

    @Test fun anEnvelopeForTheEpochHeldChangesNothing() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val list = nextList(ama, kofi)
        val key = nextKey(ama, list, secret)
        val sealed = ama.member.sealBody(envelope(ama, kofi, key)).packet
        val out = ama.member.newEpoch(list, 1000, FixedRandom(secret))
        kofi.member.receive(sealed, 1000)
        for (packet in out.rosterPackets) kofi.member.receive(packet, 1000)
        assertEquals("Kofi took the epoch", 2L, kofi.member.epoch)
        // The same key again, on the admin's new chain: nothing moves.
        val again = ama.member.sealBody(envelope(ama, kofi, key)).packet
        assertTrue(kofi.member.receive(again, 2000).isEmpty())
        assertEquals("the epoch is still the one held", 2L, kofi.member.epoch)
        assertEquals(GroupMember.State.ACTIVE, kofi.member.state)
    }

    @Test fun theSameKeyByBothPathsIsOnePendingKey() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val list = nextList(ama, kofi)
        val key = nextKey(ama, list, secret)
        // The private path first: Kofi waits for the list.
        assertTrue("the private key is taken", kofi.member.receiveKey(ama.id, key, 500))
        val waiting = kofi.member.waiting()
        assertNotNull("Kofi waits", waiting)
        // The same key, sealed to Kofi's box key, on the admin's chain of the epoch Kofi holds.
        val sealed = ama.member.sealBody(envelope(ama, kofi, key)).packet
        assertTrue(kofi.member.receive(sealed, 1000).isEmpty())
        assertEquals("the same waiting key, not a second one", waiting, kofi.member.waiting())
        assertEquals("the epoch does not move", 1L, kofi.member.epoch)
    }

    @Test fun anEnvelopeOnTheChainOfTheEpochBeforeIsStillTheSameKey() {
        val (ama, kofi) = two()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val list = nextList(ama, kofi)
        val key = nextKey(ama, list, secret)
        // The admin seals the envelope on its chain of epoch 1, then takes epoch 2.
        val sealed = ama.member.sealBody(envelope(ama, kofi, key)).packet
        val out = ama.member.newEpoch(list, 1000, FixedRandom(secret))
        // Kofi takes the epoch by the private path, so the admin's chain of epoch 1 is now the one before.
        assertTrue(kofi.member.receiveKey(ama.id, key, 1000))
        for (packet in out.rosterPackets) kofi.member.receive(packet, 1000)
        assertEquals("Kofi took the epoch", 2L, kofi.member.epoch)
        // The envelope, read late on the chain of the epoch before, names the epoch Kofi holds: nothing changes.
        assertTrue(kofi.member.receive(sealed, 2000).isEmpty())
        assertEquals("the epoch is still the one held", 2L, kofi.member.epoch)
        assertEquals(GroupMember.State.ACTIVE, kofi.member.state)
    }
}

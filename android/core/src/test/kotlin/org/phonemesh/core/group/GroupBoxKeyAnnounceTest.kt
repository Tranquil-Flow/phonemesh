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
 * The member's side of the box key (v0.1.3 G3g; relayed group control design, "Box keys"; accounts decision 79).
 *
 * A member makes a P-256 key pair for one group, keeps the private half and announces the public half on its own
 * radio chain. The announcement is bound to that member by the group signature every packet carries, so an admin that
 * has not added it can still reach it: the admin seals the next epoch's key to the announced public half in an
 * envelope. Without this, no envelope could ever be addressed to a real member.
 *
 * The private half never leaves the phone and is never in a chat backup (decision 75); the caller keeps it and gives
 * it back with [GroupMember.setOwnBoxKey].
 */
class GroupBoxKeyAnnounceTest {
    private val group = ByteArray(16) { 7 }

    /** The first epoch's secret, fixed so the test can rebuild a member's own radio chain and read a packet back. */
    private val EPOCH_SECRET = ByteArray(32) { (it + 1).toByte() }

    /** A SecureRandom that always gives the same bytes, so the epoch's secret is known in advance. */
    private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(b: ByteArray) { bytes.copyInto(b) }
    }

    private fun pub(k: KeyPair) = P256.compress(k.public as ECPublicKey)

    /** One person: an identity key, the signing key the group list binds to it, and their phone's view of it. */
    private inner class Person(val name: String) {
        val identity = P256.generate()
        val signing = P256.generate()
        val id = pub(identity)
        val sk = pub(signing)
        var member = GroupMember(id, sk, { P256.sign(signing.private, it) }, group)
        /** The box key this person made and keeps. */
        var box: GroupEnvelope.BoxKey? = null
        fun entry(admin: Boolean = false) = GroupRoster.Member(id, admin, sk)
        fun joins(admin: Person) { member = GroupMember(id, sk, { P256.sign(signing.private, it) }, group, admin.id) }
    }

    private fun roster(vararg members: GroupRoster.Member) = GroupRoster("Puits", 0x2E7D32, members.toList())

    /** Delivers the key and the list packets of [out] to everyone but [from]. */
    private fun deliver(from: Person, out: GroupMember.Outgoing, people: List<Person>, now: Long) {
        for (p in people) {
            if (p.id.contentEquals(from.id)) continue
            for ((id, k) in out.keys) if (id == p.id.toList()) p.member.receiveKey(from.id, GroupKey.decode(k)!!, now)
            for (packet in out.rosterPackets) p.member.receive(packet, now)
        }
    }

    /** Ama (admin) and Kofi in epoch 1: Kofi joined and took the epoch. */
    private fun two(): Pair<Person, Person> {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.joins(ama)
        check(ama.member.receiveControl(kofi.id, GroupControl.decode(GroupControl.SIGNER, kofi.member.introduce())!!, 0).first)
        val list = roster(GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!), ama.entry(true))
        deliver(ama, ama.member.newEpoch(list, 0, FixedRandom(EPOCH_SECRET)), listOf(ama, kofi), 0)
        return ama to kofi
    }

    // ---- the announcement

    @Test fun theMemberAnnouncesItsBoxKeyOnItsOwnRadioChain() {
        val (ama, kofi) = two()
        val (key, sealed) = kofi.member.makeAndAnnounceBoxKey(100)
        kofi.box = key
        assertEquals("the announcement is on the radio chain", false, sealed.hub)
        assertEquals("the announcement is of the epoch held", 1L, sealed.epoch)
        assertArrayEquals("the public half is the one kept", key.public, kofi.member.ownBoxKeys(100)[0].public)

        // Ama reads it on Kofi's own chain: it is bound to Kofi, not to anyone else.
        check(ama.member.receive(sealed.packet, 200).isEmpty())
        assertArrayEquals("Ama holds the box key Kofi announced", key.public, ama.member.boxKeyOf(kofi.id))
    }

    @Test fun theAnnouncementCarriesOnlyThePublicHalf() {
        val (_, kofi) = two()
        val (key, sealed) = kofi.member.makeAndAnnounceBoxKey(100)
        val body = bodyOf(kofi, sealed)
        assertEquals("the body is an op 9 announcement", GroupBoxKey.OP.toByte(), body[0])
        assertEquals("the body is the op and the key", GroupBoxKey.SIZE, body.size)
        assertArrayEquals("the announced key is the public half", key.public, body.copyOfRange(2, GroupBoxKey.SIZE))
    }

    @Test fun theMemberMakesAFreshKeyEachTime() {
        val (_, kofi) = two()
        val (first, _) = kofi.member.makeAndAnnounceBoxKey(100)
        val (second, _) = kofi.member.makeAndAnnounceBoxKey(200)
        assertNotEquals("each key is a new one", first.public.toList(), second.public.toList())
        assertEquals("the newest is the one it keeps", second.public.toList(), kofi.member.ownBoxKeys(200)[0].public.toList())
    }

    @Test fun theMemberKeepsThePreviousKeyWhileAnAdminMayStillHaveSealedToIt() {
        val (_, kofi) = two()
        val (first, _) = kofi.member.makeAndAnnounceBoxKey(100)
        // The window runs from the moment the key was replaced: an admin may have sealed to the old one until then.
        kofi.member.makeAndAnnounceBoxKey(200)
        assertEquals("the previous key is still there while it is kept", 2, kofi.member.ownBoxKeys(200 + GroupMember.KEEP_MS).size)
        assertArrayEquals("the previous key is the one that was there", first.public, kofi.member.ownBoxKeys(200 + GroupMember.KEEP_MS)[1].public)
        assertEquals("the previous key is gone once its window passed", 1, kofi.member.ownBoxKeys(200 + GroupMember.KEEP_MS + 1).size)
    }

    @Test fun theAnnouncementIsBoundToTheMemberThatMadeIt() {
        val (ama, kofi) = two()
        val (key, sealed) = kofi.member.makeAndAnnounceBoxKey(100)
        // Ama cannot open it on her own chain: the chain it was sealed on is Kofi's, and the group signature in it
        // is Kofi's, so only a reader of Kofi's chain opens it.
        val amaReader = GroupReader(group, sealed.epoch, ama.id, ama.sk, GroupCipher.chain0(group, sealed.epoch, EPOCH_SECRET, ama.id))
        assertNull("the announcement does not open on another member's chain", amaReader.open(sealed.packet))
        // And Ama claims no key of her own from it: it is nobody else's key.
        assertNull("Ama holds no key of her own", ama.member.boxKeyOf(ama.id))
        assertArrayEquals("Kofi's key is what it is", key.public, kofi.member.ownBoxKeys(100)[0].public)
    }

    // ---- the announcement is what an envelope is sealed to

    @Test fun anAdminSealsToTheAnnouncedKeyAndTheMemberOpensIt() {
        val (ama, kofi) = two()
        val (key, sealed) = kofi.member.makeAndAnnounceBoxKey(100)
        kofi.box = key
        check(ama.member.receive(sealed.packet, 200).isEmpty())
        val announced = ama.member.boxKeyOf(kofi.id)
        assertNotNull("Ama holds the announced key", announced)

        // The admin seals the next epoch's key to that announced public half; the member opens it.
        val next = GroupKey(group, 2, ByteArray(32) { 9 }, ByteArray(16), ByteArray(16), 1, kofi.sk)
        val body = GroupEnvelope.seal(next, ama.id, kofi.id, announced!!)
        val opened = GroupEnvelope.open(body, group, 2, ama.id, ama.sk, kofi.id, kofi.member.ownBoxKeys(200))
        assertNotNull("the member opens the envelope", opened)
        assertArrayEquals("the key inside is the one the admin sealed", next.secret, opened!!.secret)
    }

    // ---- refusals

    @Test fun aMemberThatCannotSendAnnouncesNothing() {
        // A phone that is not in the group cannot send, so it announces nothing -- and it keeps no key either:
        // a key it made but could not announce would be a key it can never be reached by.
        val stranger = Person("Yaw")
        try {
            stranger.member.makeAndAnnounceBoxKey(100)
            fail("a phone that is not in the group announces nothing")
        } catch (_: IllegalStateException) {
            // as it should be
        }
        assertEquals("the phone keeps no key it could not announce", 0, stranger.member.ownBoxKeys(100).size)
    }

    @Test fun theAnnouncementIsNeverOnTheHubChain() {
        val (_, kofi) = two()
        val (_, sealed) = kofi.member.makeAndAnnounceBoxKey(100)
        assertFalse("the announcement is not for the hub", sealed.hub)
        // Op 9 does not travel on the hub chain at all: the rule is in the body table, not in the caller.
        assertFalse("op 9 is a radio-chain body", GroupBody.allowed(GroupBoxKey.OP, hub = true))
    }

    @Test fun aMemberThatLeftAnnouncesNothing() {
        val (ama, kofi) = two()
        // The removal notice names the epoch after the one held, from an admin of the list held.
        val notice = GroupControl.Removed(group, 2)
        kofi.member.receiveControl(ama.id, notice, 100)
        assertEquals("Kofi is out of the group", GroupMember.State.REMOVED, kofi.member.state)
        try {
            kofi.member.makeAndAnnounceBoxKey(100)
            fail("a member that left announces nothing")
        } catch (_: IllegalStateException) {
            // as it should be
        }
    }

    /**
     * The body of [sealed], read by opening it on the chain it was sealed on. The chain key comes from the epoch's
     * secret, which [two] fixes, so the test can rebuild the member's own radio chain and open its packet.
     */
    private fun bodyOf(person: Person, sealed: GroupMember.Sealed): ByteArray {
        val chain = GroupCipher.chain0(group, sealed.epoch, EPOCH_SECRET, person.id)
        val reader = GroupReader(group, sealed.epoch, person.id, person.sk, chain)
        return reader.open(sealed.packet) ?: throw AssertionError("the announcement does not open on its own chain")
    }
}

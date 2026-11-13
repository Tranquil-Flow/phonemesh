package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

/**
 * A member's box key announcement on its own radio chain (v0.1.3 G3d; relayed group control design, "Box keys";
 * accounts decisions 79 and 80).
 *
 * A member announces its box key on the radio chain it writes on, so the announcement is bound to that member by the
 * group signature every packet carries. Another member reads it there and keeps it as that member's key.
 */
class GroupAnnounceTest {
    private val group = ByteArray(16) { 7 }

    private fun pub(k: KeyPair) = P256.compress(k.public as ECPublicKey)

    /** One person: an identity key, the signing key the group list binds to it, and their phone's view of it. */
    private inner class Person(val name: String) {
        val identity = P256.generate()
        val signing = P256.generate()
        val id = pub(identity)
        val sk = pub(signing)
        var member = GroupMember(id, sk, { P256.sign(signing.private, it) }, group)
        /** The box key this person announced, if any. */
        var box: KeyPair? = null
        fun entry(admin: Boolean = false) = GroupRoster.Member(id, admin, sk)
        fun joins(admin: Person) { member = GroupMember(id, sk, { P256.sign(signing.private, it) }, group, admin.id) }
        /** The packet this person announces its box key with, on its own radio chain. */
        fun announce(key: KeyPair): ByteArray = member.sealBody(GroupBoxKey.encode(pub(key))).packet
    }

    private fun roster(vararg members: GroupRoster.Member) = GroupRoster("Puits", 0x2E7D32, members.toList())

    /** Delivers the key and the list packets of [out] to everyone but [from]. */
    private fun deliver(from: Person, out: GroupMember.Outgoing, people: List<Person>, now: Long) {
        for (p in people) {
            out.keys[p.id.toList()]?.let { assertTrue("${p.name} takes the key", p.member.receiveKey(from.id, GroupKey.decode(it)!!, now)) }
        }
        for (packet in out.rosterPackets) for (p in people) if (p !== from) p.member.receive(packet, now)
    }

    /** A group of two with [ama] as the admin, in epoch 1. */
    private fun two(): Pair<Person, Person> {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.joins(ama)
        assertTrue(ama.member.receiveControl(kofi.id, GroupControl.decode(GroupControl.SIGNER, kofi.member.introduce())!!, 0).first)
        val list = roster(ama.entry(true), GroupRoster.Member(kofi.id, false, ama.member.signerOf(kofi.id)!!))
        deliver(ama, ama.member.newEpoch(list, 0), listOf(ama, kofi), 0)
        for (p in listOf(ama, kofi)) assertEquals(p.name, GroupMember.State.ACTIVE, p.member.state)
        return ama to kofi
    }

    /** A group of three with [ama] as the admin, in epoch 1: everyone has a chain of their own. */
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
        for (p in people) assertEquals(p.name, GroupMember.State.ACTIVE, p.member.state)
        return Triple(ama, kofi, esi)
    }

    @Test fun aMemberLearnsTheKeyAnotherMemberAnnounced() {
        val (ama, kofi) = two()
        val box = P256.generate()
        kofi.box = box
        assertNull("nothing announced yet", ama.member.boxKeyOf(kofi.id))
        ama.member.receive(kofi.announce(box), 1000)
        assertArrayEquals("the key is the one announced", pub(box), ama.member.boxKeyOf(kofi.id))
    }

    @Test fun theAnnouncementIsReadOnTheAnnouncersOwnChain() {
        val (ama, kofi) = two()
        val box = P256.generate()
        kofi.box = box
        ama.member.receive(kofi.announce(box), 1000)
        assertNull("the announcer does not learn its own key from the chain", kofi.member.boxKeyOf(kofi.id))
        assertNull("and nobody else's", ama.member.boxKeyOf(ama.id))
    }

    @Test fun theNewestAnnouncementIsTheOneKept() {
        val (ama, kofi) = two()
        val first = P256.generate()
        val second = P256.generate()
        ama.member.receive(kofi.announce(first), 1000)
        assertArrayEquals(pub(first), ama.member.boxKeyOf(kofi.id))
        ama.member.receive(kofi.announce(second), 1000)
        assertArrayEquals("the later position wins", pub(second), ama.member.boxKeyOf(kofi.id))
    }

    @Test fun aKeyAnnouncedOnTheHubChainIsNotRead() {
        val (ama, kofi) = two()
        val box = P256.generate()
        assertThrows(IllegalArgumentException::class.java) {
            kofi.member.sealBody(GroupBoxKey.encode(pub(box)), hub = true)
        }
    }

    @Test fun aKeyForgedOnTheHubChainIsNotLearned() {
        val (ama, kofi) = two()
        val box = P256.generate()
        // A modified client that seals op 9 on the hub chain anyway: it is not read, so no key is learned.
        val forged = kofi.member.sealUnchecked(GroupBoxKey.OP, GroupBoxKey.encode(pub(box)).copyOfRange(1, GroupBoxKey.SIZE), true)
        assertTrue("nothing is read from it", ama.member.receive(forged.packet, 1000).isEmpty())
        assertNull("and no key is learned", ama.member.boxKeyOf(kofi.id))
    }

    @Test fun aPacketThatIsNotOnTheAnnouncersChainIsNotRead() {
        val (ama, kofi) = two()
        val box = P256.generate()
        // The same body sealed by the admin on the admin's own chain says nothing about Kofi's key.
        val forged = ama.member.sealBody(GroupBoxKey.encode(pub(box))).packet
        ama.member.receive(forged, 1000)
        assertNull(ama.member.boxKeyOf(kofi.id))
        assertNull("nor the admin's own", ama.member.boxKeyOf(ama.id))
    }

    @Test fun aMalformedAnnouncementIsNotRead() {
        val (ama, kofi) = two()
        val box = P256.generate()
        val body = GroupBoxKey.encode(pub(box))
        val bad = body.copyOf().also { it[1] = 2 }
        ama.member.receive(kofi.member.sealBody(bad).packet, 1000)
        assertNull(ama.member.boxKeyOf(kofi.id))
    }

    @Test fun aMemberWhoLeftLosesItsKey() {
        val (ama, kofi) = two()
        val box = P256.generate()
        kofi.box = box
        ama.member.receive(kofi.announce(box), 1000)
        assertNotNull(ama.member.boxKeyOf(kofi.id))
        // Kofi leaves: the admin's next list does not have it, so its key is dropped.
        val esi = Person("Esi")
        esi.joins(ama)
        assertTrue(ama.member.receiveControl(esi.id, GroupControl.decode(GroupControl.SIGNER, esi.member.introduce())!!, 1500).first)
        val next = roster(ama.entry(true), GroupRoster.Member(esi.id, false, ama.member.signerOf(esi.id)!!))
        ama.member.newEpoch(next, 2000)
        assertNull("a member that left keeps no key", ama.member.boxKeyOf(kofi.id))
    }

    @Test fun theAnnouncementIsNotShownAsAMessage() {
        val (ama, kofi) = two()
        val box = P256.generate()
        kofi.box = box
        val read = ama.member.receive(kofi.announce(box), 1000)
        assertTrue("an announcement is not something people say", read.isEmpty())
        assertFalse("and it is not a receipted message either", ama.member.receipt() != null)
    }

    @Test fun theLearnedKeySurvivesASaveAndLoad() {
        val (ama, kofi) = two()
        val box = P256.generate()
        kofi.box = box
        ama.member.receive(kofi.announce(box), 1000)
        assertArrayEquals(pub(box), ama.member.boxKeyOf(kofi.id))
        // The app saves the state around every change and loads it back: the key must still be there.
        val reloaded = GroupMember.decode(ama.id, ama.sk, { P256.sign(ama.signing.private, it) }, group, ama.member.encode())
        assertNotNull("the saved state loads", reloaded)
        assertArrayEquals("the key survives the restart", pub(box), reloaded!!.boxKeyOf(kofi.id))
    }

    @Test fun aSaveWithoutTheKeysStillLoads() {
        val (ama, kofi) = two()
        val box = P256.generate()
        kofi.box = box
        ama.member.receive(kofi.announce(box), 1000)
        // A v0.1.2 save: extension version 1 and no box keys. It loads with no keys, as the design note says.
        val v012 = ama.member.encode(ext = 1)
        val reloaded = GroupMember.decode(ama.id, ama.sk, { P256.sign(ama.signing.private, it) }, group, v012)
        assertNotNull("a v0.1.2 save still loads", reloaded)
        assertNull("with no keys", reloaded!!.boxKeyOf(kofi.id))
    }

    @Test fun aSaveWithAnUnknownExtensionIsRefused() {
        val (ama, kofi) = two()
        val box = P256.generate()
        kofi.box = box
        ama.member.receive(kofi.announce(box), 1000)
        // An extension this version does not know: its shape is unknown, so the save is refused rather than guessed at.
        val future = ama.member.encode(ext = 3)
        assertNull("an unknown extension is not a save", GroupMember.decode(ama.id, ama.sk, { P256.sign(ama.signing.private, it) }, group, future))
    }

    @Test fun aClaimedKeyIsNotTakenByAnotherMemberAfterAReload() {
        val (ama, kofi, esi) = three()
        val box = P256.generate()
        kofi.box = box
        ama.member.receive(kofi.announce(box), 1000)
        // Ama's phone restarts: the key Kofi announced is still Kofi's, so Esi may not take it.
        val reloaded = GroupMember.decode(ama.id, ama.sk, { P256.sign(ama.signing.private, it) }, group, ama.member.encode())
        assertNotNull("the save loads", reloaded)
        assertArrayEquals("the key is kept", pub(box), reloaded!!.boxKeyOf(kofi.id))
        // Esi announces the same key: it belongs to Kofi, and the claim survived the restart.
        reloaded.receive(esi.announce(box), 2000)
        assertNull("esi may not take kofi's key", reloaded.boxKeyOf(esi.id))
        assertArrayEquals("kofi still has it", pub(box), reloaded.boxKeyOf(kofi.id))
    }
}

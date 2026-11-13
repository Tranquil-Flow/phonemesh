package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

/**
 * How an admin reaches each member of the next list with the new epoch's key (v0.1.3 G3c; relayed group control
 * design, "Which path an admin uses"; accounts decisions 79 and 80).
 *
 * A contact is reached over the private session as today, which works with a v0.1.2 member. A member the admin has
 * not added is reached by an envelope sealed to the box key that member announced on the list held. A member with
 * neither stops the whole change: nothing is sent to anyone, and the admin is told who is missing.
 */
class GroupReachTest {
    private fun pub(k: KeyPair) = P256.compress(k.public as ECPublicKey)

    /** One person: an identity key and the signing key the group list binds to it. */
    private inner class Person(val name: String) {
        val identity = P256.generate()
        val signing = P256.generate()
        val id = pub(identity)
        val sk = pub(signing)
        /** The box key this person announced, if any. */
        var box: ByteArray? = null
        fun entry(admin: Boolean = false) = GroupRoster.Member(id, admin, sk)
    }

    private fun roster(vararg members: GroupRoster.Member) = GroupRoster("Puits", 0x2E7D32, members.toList())

    /** The keys of a list held by [members]. */
    private fun keys(vararg members: Person) = GroupBoxKeys { m -> members.any { it.id.contentEquals(m) } }

    /** The plan for [next], with the admin [me] and [contacts] reachable privately. */
    private fun plan(next: GroupRoster, held: GroupRoster?, me: Person, contacts: List<Person>, k: GroupBoxKeys) =
        GroupReach.plan(next, held, me.id, { c -> contacts.any { it.id.contentEquals(c) } }, k)

    @Test fun aContactIsReachedPrivately() {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val next = roster(ama.entry(true), kofi.entry())
        val p = plan(next, next, ama, listOf(kofi), keys(ama, kofi))
        assertTrue(p.canStart)
        assertEquals(1, p.choices.size)
        assertEquals(GroupReach.Path.PRIVATE, p.pathOf(kofi.id))
        assertNull("a private path names no box key", p.boxKeyFor(kofi.id))
        assertTrue(p.unreachable.isEmpty())
    }

    @Test fun aContactWithAnAnnouncedKeyIsStillReachedPrivately() {
        // The private path works with a v0.1.2 member, so it wins over the envelope.
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.box = pub(P256.generate())
        val next = roster(ama.entry(true), kofi.entry())
        val k = keys(ama, kofi)
        assertTrue(k.learn(kofi.id, GroupBoxKey.encode(kofi.box!!), 1, 0))
        val p = plan(next, next, ama, listOf(kofi), k)
        assertEquals(GroupReach.Path.PRIVATE, p.pathOf(kofi.id))
        assertNull(p.boxKeyFor(kofi.id))
    }

    @Test fun aMemberWithAnAnnouncedKeyIsReachedByAnEnvelope() {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.box = pub(P256.generate())
        val next = roster(ama.entry(true), kofi.entry())
        val k = keys(ama, kofi)
        assertTrue(k.learn(kofi.id, GroupBoxKey.encode(kofi.box!!), 1, 0))
        val p = plan(next, next, ama, emptyList(), k)
        assertTrue(p.canStart)
        assertEquals(GroupReach.Path.ENVELOPE, p.pathOf(kofi.id))
        assertArrayEquals("the envelope is sealed to that key", kofi.box, p.boxKeyFor(kofi.id))
        assertTrue(p.unreachable.isEmpty())
    }

    @Test fun aMemberWithNeitherAPrivateSessionNorAKeyIsUnreachable() {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val next = roster(ama.entry(true), kofi.entry())
        val p = plan(next, next, ama, emptyList(), keys(ama, kofi))
        assertFalse("the change does not start", p.canStart)
        assertEquals(GroupReach.Path.NONE, p.pathOf(kofi.id))
        assertEquals(listOf(kofi.id.toList()), p.unreachable.map { it.toList() })
    }

    @Test fun oneUnreachableMemberStopsTheWholeChange() {
        // No partial change: nobody is sent anything, because the change does not start at all.
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val esi = Person("Esi")
        val yaw = Person("Yaw")
        esi.box = pub(P256.generate())
        val next = roster(ama.entry(true), kofi.entry(), esi.entry(), yaw.entry())
        val k = keys(ama, kofi, esi, yaw)
        assertTrue(k.learn(esi.id, GroupBoxKey.encode(esi.box!!), 1, 0))
        val p = plan(next, next, ama, listOf(kofi), k)
        assertFalse("the change does not start", p.canStart)
        assertEquals("kofi would have been reached privately", GroupReach.Path.PRIVATE, p.pathOf(kofi.id))
        assertEquals("esi would have been reached by an envelope", GroupReach.Path.ENVELOPE, p.pathOf(esi.id))
        assertEquals(GroupReach.Path.NONE, p.pathOf(yaw.id))
        assertEquals(listOf(yaw.id.toList()), p.unreachable.map { it.toList() })
    }

    @Test fun theAdminIsNotReached() {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val next = roster(ama.entry(true), kofi.entry())
        val p = plan(next, next, ama, listOf(kofi), keys(ama, kofi))
        assertNull("the admin holds the key itself", p.pathOf(ama.id))
        assertEquals(1, p.choices.size)
    }

    @Test fun aKeyAnnouncedBeforeTheMemberJoinedIsNotUsed() {
        // The envelope is sealed to a key announced on the list held; a member of the next list that is not on it
        // announced nothing there, so it has no key to be reached by.
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val esi = Person("Esi")
        esi.box = pub(P256.generate())
        val held = roster(ama.entry(true), kofi.entry())
        val next = roster(ama.entry(true), kofi.entry(), esi.entry())
        val k = keys(ama, kofi, esi)
        assertTrue(k.learn(esi.id, GroupBoxKey.encode(esi.box!!), 1, 0))
        val p = plan(next, held, ama, emptyList(), k)
        assertFalse(p.canStart)
        assertEquals(GroupReach.Path.NONE, p.pathOf(esi.id))
        assertNull(p.boxKeyFor(esi.id))
    }

    @Test fun withNoListHeldThereIsNoEnvelopePath() {
        // The first list is made of the admin and the people it invited, who are contacts.
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.box = pub(P256.generate())
        val next = roster(ama.entry(true), kofi.entry())
        val k = keys(ama, kofi)
        assertTrue(k.learn(kofi.id, GroupBoxKey.encode(kofi.box!!), 1, 0))
        val p = plan(next, null, ama, emptyList(), k)
        assertFalse(p.canStart)
        assertEquals(GroupReach.Path.NONE, p.pathOf(kofi.id))
    }

    @Test fun theNewestAnnouncedKeyIsTheOneUsed() {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.box = pub(P256.generate())
        val newer = pub(P256.generate())
        val next = roster(ama.entry(true), kofi.entry())
        val k = keys(ama, kofi)
        assertTrue(k.learn(kofi.id, GroupBoxKey.encode(kofi.box!!), 1, 0))
        assertTrue("kofi rotates its key", k.learn(kofi.id, GroupBoxKey.encode(newer), 1, 9))
        val p = plan(next, next, ama, emptyList(), k)
        assertArrayEquals(newer, p.boxKeyFor(kofi.id))
    }

    @Test fun everyUnreachableMemberIsNamed() {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        val esi = Person("Esi")
        val next = roster(ama.entry(true), kofi.entry(), esi.entry())
        val p = plan(next, next, ama, emptyList(), keys(ama, kofi, esi))
        assertEquals(listOf(kofi.id.toList(), esi.id.toList()), p.unreachable.map { it.toList() })
    }

    @Test fun theBoxKeyHandedOutIsACopy() {
        val ama = Person("Ama")
        val kofi = Person("Kofi")
        kofi.box = pub(P256.generate())
        val next = roster(ama.entry(true), kofi.entry())
        val k = keys(ama, kofi)
        assertTrue(k.learn(kofi.id, GroupBoxKey.encode(kofi.box!!), 1, 0))
        val p = plan(next, next, ama, emptyList(), k)
        val taken = p.boxKeyFor(kofi.id)!!
        taken[0] = (taken[0] + 1).toByte()
        assertArrayEquals("the kept key is not changed from outside", kofi.box, p.boxKeyFor(kofi.id))
    }
}

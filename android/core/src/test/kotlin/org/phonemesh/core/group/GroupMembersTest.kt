package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/** Draft GK2 (decision 47): epochs, rosters and admins, with phones that only talk through packets. */
class GroupMembersTest {
    private class Person(val name: String) {
        val keys: KeyPair = P256.generate()
        val id: ByteArray = P256.compress(keys.public as ECPublicKey)
        fun sign(m: ByteArray) = P256.sign(keys.private, m)
    }

    private val group = ByteArray(16) { (it * 7).toByte() }
    private val ama = Person("Ama"); private val kofi = Person("Kofi"); private val yaw = Person("Yaw")
    private val esi = Person("Esi")

    private fun phone(p: Person) = GroupMember(p.id, p::sign, group)
    private fun roster(vararg people: Pair<Person, Boolean>) =
        GroupRoster("Marché", 0x2E7D32, people.map { GroupRoster.Member(it.first.id, it.second) })

    /** Delivers what an admin's [GroupMember.newEpoch] produced: keys pairwise, roster packets to everyone. */
    private fun deliver(from: Person, out: GroupMember.Outgoing, to: Map<Person, GroupMember>, rosterOrder: List<Int>? = null) {
        for ((p, phone) in to) {
            val key = out.keys[p.id.toList()] ?: continue
            assertTrue("${p.name} takes the key", phone.receiveKey(from.id, GroupKey.decode(key)!!))
        }
        val packets = rosterOrder?.map { out.rosterPackets[it] } ?: out.rosterPackets
        for (phone in to.values) for (packet in packets) assertNull(phone.receive(packet))
    }

    private fun text(phone: GroupMember, packet: ByteArray): String? = phone.receive(packet)?.second?.let { String(it) }

    /** Review C9: a member list names identity keys on the curve only. */
    @Test fun rosterIdentitiesMustBeOnTheCurve() {
        assertTrue(runCatching { GroupRoster.Member(ByteArray(33) { 5 }, false) }.isFailure)
    }

    @Test fun anAdminStartsAGroupAndEveryoneReadsEveryone() {
        val a = phone(ama); val k = phone(kofi); val y = phone(yaw)
        val out = a.newEpoch(roster(ama to true, kofi to false, yaw to false))
        assertEquals(2, out.keys.size)
        assertEquals("one key per other member, one packet each", GroupKey.SIZE, out.keys.values.first().size)
        deliver(ama, out, mapOf(kofi to k, yaw to y), rosterOrder = out.rosterPackets.indices.reversed().toList())
        for (p in listOf(a, k, y)) { assertEquals(GroupMember.State.ACTIVE, p.state); assertEquals(1L, p.epoch) }
        val hello = a.send("bonjour".toByteArray())
        assertEquals("bonjour", text(k, hello)); assertEquals("bonjour", text(y, hello))
        val reply = k.send("salut".toByteArray())
        assertEquals("salut", text(a, reply)); assertEquals("salut", text(y, reply))
        assertArrayEquals("the sender is known from the packet", kofi.id, a.receive(k.send(byteArrayOf(1)))!!.first)
        assertNull("nobody reads their own packet back", a.receive(a.send(byteArrayOf(2))))
    }

    @Test fun twentyPeopleFitAndTheirListTakesSixPackets() {
        val people = listOf(ama) + List(19) { Person("p$it") }
        val r = GroupRoster("Grand groupe", 0x123456, people.mapIndexed { i, p -> GroupRoster.Member(p.id, i == 0) })
        assertTrue(r.parts().size <= GroupKey.MAX_PARTS)
        assertTrue(r.parts().all { it.size <= GroupCipher.MAX_BODY })
        assertTrue(runCatching { GroupRoster("x", 0, (people + Person("21")).map { GroupRoster.Member(it.id, true) }) }.isFailure)
    }

    @Test fun aRemovedPersonCannotReadTheNextEpoch() {
        val a = phone(ama); val k = phone(kofi); val y = phone(yaw)
        deliver(ama, a.newEpoch(roster(ama to true, kofi to false, yaw to false)), mapOf(kofi to k, yaw to y))
        val out = a.newEpoch(roster(ama to true, kofi to false))
        assertFalse("no key for Yaw", out.keys.containsKey(yaw.id.toList()))
        deliver(ama, out, mapOf(kofi to k, yaw to y))
        assertEquals(2L, k.epoch)
        assertEquals("Yaw never learnt the new epoch", 1L, y.epoch)
        val secret = a.send("sans Yaw".toByteArray())
        assertEquals("sans Yaw", text(k, secret))
        assertNull(y.receive(secret))
    }

    @Test fun someoneLeftOffTheListTheyReceiveIsRemoved() {
        val a = phone(ama); val k = phone(kofi); val y = phone(yaw)
        deliver(ama, a.newEpoch(roster(ama to true, kofi to false, yaw to false)), mapOf(kofi to k, yaw to y))
        val out = a.newEpoch(roster(ama to true, kofi to false))
        // The key reaches Yaw anyway (an old copy, a mistake): the list he rebuilds does not name him.
        assertTrue(y.receiveKey(ama.id, GroupKey.decode(out.keys.values.first())!!))
        out.rosterPackets.forEach { assertNull(y.receive(it)) }
        assertEquals(GroupMember.State.REMOVED, y.state)
        assertTrue(runCatching { y.send(byteArrayOf(1)) }.isFailure)
        val forged = GroupKey(group, 2, ByteArray(32), roster(ama to true, kofi to false).digest(group, 2), out.rosterPackets.size)
        val other = phone(yaw)
        assertTrue(other.receiveKey(ama.id, forged))
        out.rosterPackets.forEach { other.receive(it) }
        assertEquals("a wrong secret opens nothing", GroupMember.State.INVITED, other.state)
    }

    @Test fun aNewMemberSeesOnlyNewMessages() {
        val a = phone(ama); val k = phone(kofi)
        deliver(ama, a.newEpoch(roster(ama to true, kofi to false)), mapOf(kofi to k))
        val before = a.send("avant".toByteArray())
        val e = phone(esi)
        deliver(ama, a.newEpoch(roster(ama to true, kofi to false, esi to false)), mapOf(kofi to k, esi to e))
        assertEquals(GroupMember.State.ACTIVE, e.state)
        assertNull("no earlier history", e.receive(before))
        assertEquals("après", text(e, a.send("après".toByteArray())))
    }

    @Test fun onlyAnAdminOfTheCurrentListStartsAnEpoch() {
        val a = phone(ama); val k = phone(kofi); val y = phone(yaw)
        deliver(ama, a.newEpoch(roster(ama to true, kofi to false, yaw to false)), mapOf(kofi to k, yaw to y))
        assertTrue(runCatching { k.newEpoch(roster(kofi to true, yaw to false)) }.isFailure)
        // Kofi forges an epoch on the wire anyway: Yaw refuses his key.
        val forged = GroupKey(group, 2, ByteArray(32) { 1 }, roster(kofi to true, yaw to false).digest(group, 2), 1)
        assertFalse(y.receiveKey(kofi.id, forged))
        assertFalse("an old epoch is never taken again", y.receiveKey(ama.id, GroupKey(group, 1, ByteArray(32), ByteArray(16), 1)))
        // Ama makes Kofi an admin; then his epoch is taken.
        deliver(ama, a.newEpoch(roster(ama to true, kofi to true, yaw to false)), mapOf(kofi to k, yaw to y))
        deliver(kofi, k.newEpoch(roster(ama to true, kofi to true, yaw to false)), mapOf(ama to a, yaw to y))
        assertEquals(3L, y.epoch); assertEquals(3L, a.epoch)
    }

    @Test fun aListThatDoesNotMatchTheKeyIsRefused() {
        val a = phone(ama); val k = phone(kofi)
        val out = a.newEpoch(roster(ama to true, kofi to false))
        val key = GroupKey.decode(out.keys.getValue(kofi.id.toList()))!!
        val wrong = GroupKey(group, key.epoch, key.secret, ByteArray(16), key.parts)
        assertTrue(k.receiveKey(ama.id, wrong))
        out.rosterPackets.forEach { k.receive(it) }
        assertEquals(GroupMember.State.INVITED, k.state)
    }

    @Test fun codecsRoundTripAndRefuseDamage() {
        val r = roster(ama to true, kofi to false)
        assertArrayEquals(r.encode(), GroupRoster.decode(r.encode())!!.encode())
        assertNull(GroupRoster.decode(r.encode() + 0))
        assertNull(GroupRoster.decode(r.encode().copyOf(r.encode().size - 1)))
        val k = GroupKey(group, 7, ByteArray(32) { 3 }, ByteArray(16) { 4 }, 2)
        val back = GroupKey.decode(k.encode())!!
        assertEquals(7L, back.epoch); assertEquals(2, back.parts); assertArrayEquals(k.secret, back.secret)
        assertNull(GroupKey.decode(k.encode().copyOf(69)))
        assertTrue(runCatching { GroupRoster("", 0, r.members) }.isFailure)
        assertTrue(runCatching { GroupRoster("x".repeat(25), 0, r.members) }.isFailure)
        assertTrue("an admin is needed", runCatching { roster(ama to false, kofi to false) }.isFailure)
    }

    @Test fun theListDigestMatchesTheIndependentVector() {
        val v = javaClass.getResourceAsStream("/group/group-vectors.txt")!!.bufferedReader().readLines()
            .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ids = listOf(hex("identity"), hex("member2"), hex("member3"))
        val r = GroupRoster(String(hex("rosterName")), 0x2E7D32, ids.mapIndexed { i, id -> GroupRoster.Member(id, i == 0) })
        assertArrayEquals(hex("roster"), r.encode())
        assertArrayEquals(hex("rosterDigest"), r.digest(hex("group"), v.getValue("epoch").toLong()))
        val key = GroupKey(hex("group"), v.getValue("epoch").toLong(), hex("secret"), hex("rosterDigest"), 1)
        assertArrayEquals(hex("groupKey"), key.encode())
    }

    @Test fun secretsAreFresh() {
        val a = phone(ama)
        val one = GroupKey.decode(a.newEpoch(roster(ama to true, kofi to false), SecureRandom()).keys.values.first())!!
        val two = GroupKey.decode(a.newEpoch(roster(ama to true, kofi to false), SecureRandom()).keys.values.first())!!
        assertFalse(one.secret.contentEquals(two.secret))
        assertNotNull(two)
    }
}

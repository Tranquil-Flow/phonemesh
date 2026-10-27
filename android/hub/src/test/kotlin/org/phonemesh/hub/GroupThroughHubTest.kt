package org.phonemesh.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.group.GroupMember
import org.phonemesh.core.group.GroupKey
import org.phonemesh.core.group.GroupRoster
import java.security.interfaces.ECPublicKey

/**
 * Private groups through an unchanged hub (group keys draft): group packets are ordinary private mail to the hub, stored
 * and handed out by their on-air code, so members fetch them with the codes they expect and nobody else learns more.
 */
class GroupThroughHubTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun membersFetchTheGroupsPacketsByTheCodesTheyExpect() {
        val ama = P256.generate(); val kofi = P256.generate()
        val amaSk = P256.generate(); val kofiSk = P256.generate()
        fun id(k: java.security.KeyPair) = P256.compress(k.public as ECPublicKey)
        val amaId = id(ama); val kofiId = id(kofi)
        val group = ByteArray(16) { 9 }
        val a = GroupMember(amaId, id(amaSk), { P256.sign(amaSk.private, it) }, group)
        // Kofi said Join to Ama's invitation, so he takes his first key only from her (GK4).
        val k = GroupMember(kofiId, id(kofiSk), { P256.sign(kofiSk.private, it) }, group, amaId)
        val list = GroupRoster("Puits", 0x123456, listOf(GroupRoster.Member(amaId, true, id(amaSk)), GroupRoster.Member(kofiId, false, id(kofiSk))))
        val out = a.newEpoch(list, 0)
        assertTrue(k.receiveKey(amaId, GroupKey.decode(out.keys.values.single())!!, 0))

        val mailbox = Mailbox(folder.newFolder(), 30)
        for (p in out.rosterPackets) assertEquals(Mailbox.Kind.CODE, mailbox.put(301, p, 1_000)!!.kind)
        // Kofi asks for the codes he expects (the roster on Ama's new chain) and rebuilds the group from what he gets.
        fun fetch(codes: List<ByteArray>) = mailbox.after(0, codes.map { Mailbox.hex(it) }.toSet(), hellos = false, shared = false)
        for (r in fetch(k.expectedCodes(1_000))) k.receive(r.bytes, 1_000)
        assertEquals(GroupMember.State.ACTIVE, k.state)

        val text = a.send("à midi au puits".toByteArray())
        mailbox.put(301, text, 2_000)
        assertTrue("someone asking for other codes gets nothing", mailbox.after(0, setOf("00".repeat(8)), false, false).isEmpty())
        val got = fetch(k.expectedCodes(2_000)).flatMap { k.receive(it.bytes, 2_000) }
        assertEquals(1, got.size)
        assertArrayEquals(amaId, got.single().sender)
        assertEquals("à midi au puits", String(got.single().body))
    }
}

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
import java.security.interfaces.ECPublicKey

/** Draft GK4 (decisions 47, 66, 69, 72, 74) and the agent review's group findings C2, C3, C4, C6, C7, C8. */
class GroupMembersTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/group/group-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val group = ByteArray(16) { 7 }

    private fun pub(k: KeyPair) = P256.compress(k.public as ECPublicKey)

    /** One person: an identity key, a signing key for this group, and their phone's view of it. */
    private inner class Person(val name: String) {
        val identity = P256.generate()
        val signing = P256.generate()
        val id = pub(identity)
        val sk = pub(signing)
        var member = GroupMember(id, sk, { P256.sign(signing.private, it) }, group)
        fun entry(admin: Boolean = false) = GroupRoster.Member(id, admin, sk)
        /** Joins [admin]'s invitation: the first key is taken only from that admin. */
        fun joins(admin: Person) { member = GroupMember(id, sk, { P256.sign(signing.private, it) }, group, admin.id) }
        /** Save and load, as the app does around every change (review C8). */
        fun reload() {
            member = GroupMember.decode(id, sk, { P256.sign(signing.private, it) }, group, member.encode())!!
        }
    }

    private fun roster(vararg members: GroupRoster.Member, name: String = "Puits") = GroupRoster(name, 0x2E7D32, members.toList())

    /** Delivers [out] (keys pairwise, then the list packets) to everyone in [people] but [from]. */
    private fun deliver(from: Person, out: GroupMember.Outgoing, people: List<Person>, now: Long) {
        for (p in people) {
            out.keys[p.id.toList()]?.let { assertTrue("${p.name} takes the key", p.member.receiveKey(from.id, GroupKey.decode(it)!!, now)) }
            out.removed[p.id.toList()]?.let { assertTrue(p.member.receiveControl(from.id, GroupControl.decode(GroupControl.REMOVED, it)!!, now).first) }
        }
        for (packet in out.rosterPackets) for (p in people) if (p !== from) p.member.receive(packet, now)
    }

    private fun three(): Triple<Person, Person, Person> {
        val ama = Person("Ama"); val kofi = Person("Kofi"); val esi = Person("Esi")
        // Invitation (Join), then their signing keys back to Ama (decision 66).
        for (p in listOf(kofi, esi)) {
            p.joins(ama)
            assertTrue(ama.member.receiveControl(p.id, GroupControl.decode(GroupControl.SIGNER, p.member.introduce())!!, 0).first)
        }
        val list = roster(ama.entry(true), GroupRoster.Member(kofi.id, true, ama.member.signerOf(kofi.id)!!),
            GroupRoster.Member(esi.id, false, ama.member.signerOf(esi.id)!!))
        deliver(ama, ama.member.newEpoch(list, 0), listOf(ama, kofi, esi), 0)
        for (p in listOf(ama, kofi, esi)) assertEquals(p.name, GroupMember.State.ACTIVE, p.member.state)
        return Triple(ama, kofi, esi)
    }

    // ---- formats against the independent vectors

    @Test fun listKeyAndControlBytesMatchTheIndependentVectors() {
        val r = GroupRoster.decode(hex("roster"))!!
        assertArrayEquals(hex("roster"), r.encode())
        assertEquals("Marché", r.name)
        assertEquals(3, r.members.size)
        assertTrue(r.isAdmin(hex("identity")))
        assertArrayEquals(hex("signer2"), r.member(hex("member2"))!!.signer)
        assertArrayEquals(hex("rosterDigest"), r.digest(hex("group"), v.getValue("epoch").toLong(), hex("parent")))
        val k = GroupKey.decode(hex("groupKey"))!!
        assertArrayEquals(hex("groupKey"), k.encode())
        assertEquals(GroupKey.SIZE, hex("groupKey").size)
        assertArrayEquals(hex("parent"), k.parent)
        assertArrayEquals(hex("signer"), k.signer)
        assertArrayEquals(hex("signer2"), (GroupControl.decode(GroupControl.SIGNER, hex("signerBody")) as GroupControl.Signer).key)
        assertEquals(4L, (GroupControl.decode(GroupControl.REMOVED, hex("removedBody")) as GroupControl.Removed).epoch)
        // GK4: the list's picture and flag, invitation, leaving, receipt, the secret's tag.
        assertEquals(org.phonemesh.core.account.Avatar.Emoji(String(hex("emoji"))), r.avatar)
        assertTrue(r.recentForNew)
        val invite = GroupControl.decode(GroupControl.INVITE, hex("inviteBody")) as GroupControl.Invite
        assertArrayEquals(hex("inviteBody"), invite.encode())
        assertEquals("Marché", invite.name); assertEquals(6, invite.people); assertEquals(0x2E7D32, invite.colour)
        assertArrayEquals(hex("signer"), invite.signer)
        assertNull("nothing after an invitation", GroupControl.decode(GroupControl.INVITE, hex("inviteBody") + byteArrayOf(0)))
        assertEquals(3L, (GroupControl.decode(GroupControl.LEAVE, hex("leaveBody")) as GroupControl.Leave).epoch)
        val receipt = GroupReceipt.decode(hex("receiptBody"))!!
        assertArrayEquals(hex("receiptBody"), receipt.encode())
        val e = receipt.entries[0]
        assertEquals(listOf(true, true, true, true, false, true, true, false, true, true, false),
            (0L..10L).map { e.covers(it) })
        assertTrue(receipt.entries[1].covers(2) && !receipt.entries[1].covers(3))
        assertTrue("31 below the highest is known", GroupReceipt.Entry(0, 40, 0).covers(8))
        assertFalse("32 below is unknown, so not counted", GroupReceipt.Entry(0, 40, 0).covers(7))
        assertNull("a receipt names each sender once", GroupReceipt.decode(byteArrayOf(2) + hex("receiptBody").copyOfRange(1, 10) + hex("receiptBody").copyOfRange(1, 10)))
        assertArrayEquals(hex("secretTag"), GroupKey.tag(hex("group"), v.getValue("epoch").toLong(), hex("secret")))
    }

    /** GK4: the hub chain is its own chain, and what it carries reads like any group packet. */
    @Test fun hubChainPacketsMatchTheIndependentVectors() {
        val g = hex("group"); val epoch = v.getValue("epoch").toLong()
        assertArrayEquals(hex("hubChain0"), GroupCipher.chain0(g, epoch, hex("secret"), hex("identity"), hub = true))
        assertFalse(hex("hubChain0").contentEquals(hex("chain0")))
        val reader = GroupReader(g, epoch, hex("identity"), hex("signer"), hex("hubChain0"))
        assertArrayEquals(hex("hubPlain"), reader.open(hex("hubPacket0")))
        val shared = GroupShared.decode(hex("hubPlain").copyOfRange(1, hex("hubPlain").size))!!
        assertArrayEquals(ByteArray(8) { (it + 1).toByte() }, shared.author)
        assertEquals(1_793_000_000L, shared.atSeconds)
        assertArrayEquals(hex("plain"), shared.body)
        val radio = GroupReader(g, epoch, hex("identity"), hex("signer"), hex("chain0"))
        assertNull("a hub packet is not on the radio chain", radio.open(hex("hubPacket0")))
    }

    @Test fun listRules() {
        val a = Person("a"); val b = Person("b")
        assertTrue("needs an admin", runCatching { roster(a.entry(), b.entry()) }.isFailure)
        assertTrue("each signing key once", runCatching { roster(a.entry(true), GroupRoster.Member(b.id, false, a.sk)) }.isFailure)
        val offCurve = (1..255).map { x -> ByteArray(33).also { it[0] = 2; it[32] = x.toByte() } }.first { P256.decompress(it) == null }
        assertTrue("off-curve signing key", runCatching { GroupRoster.Member(a.id, true, offCurve) }.isFailure)
        val groupFlags = 2 + hex("rosterName").size + 3 + 2 + hex("emoji").size
        val flags = groupFlags + 2
        assertNull("flags are 0 or 1", GroupRoster.decode(hex("roster").also { it[flags] = 2 }))
        assertNull("the group's flags are 0 or 1", GroupRoster.decode(hex("roster").also { it[groupFlags] = 2 }))
        assertNull("an emoji every phone draws", GroupRoster.decode(hex("roster").also { it[groupFlags - 1] = 0x41 }))
        assertNull("nothing after the list", GroupRoster.decode(hex("roster") + byteArrayOf(0)))
        assertNull("version 2 lists are gone", GroupRoster.decode(hex("roster").also { it[0] = 2 }))
        val twenty = List(GroupRoster.MAX_MEMBERS) { Person("p$it").entry(it == 0) }
        val longestEmoji = org.phonemesh.core.account.EmojiAvatars.all.maxBy { it.toByteArray().size }
        val big = GroupRoster("x".repeat(GroupRoster.MAX_NAME), 0, twenty, org.phonemesh.core.account.Avatar.Emoji(longestEmoji), true)
        assertEquals("twenty people fit the key's part count", GroupKey.MAX_PARTS, big.parts().size)
        assertTrue(big.parts().all { it.size <= GroupCipher.MAX_BODY })
        assertTrue(runCatching { GroupRoster("x", 0, twenty + Person("21").entry()) }.isFailure)
    }

    // ---- talking

    @Test fun threePeopleTalkAndCompareTheirSafetyCodes() {
        val (ama, kofi, esi) = three()
        val m = kofi.member.send("à midi".toByteArray())
        for (p in listOf(ama, esi)) {
            val got = p.member.receive(m, 10).single()
            assertArrayEquals(kofi.id, got.sender)
            assertEquals("à midi", String(got.body))
            assertFalse(got.oldEpoch)
        }
        assertTrue("nobody reads a packet twice", ama.member.receive(m, 11).isEmpty())
        assertTrue("the sender does not read its own packet", kofi.member.receive(m, 11).isEmpty())
        assertArrayEquals(ama.member.safetyCode(), kofi.member.safetyCode())
        assertArrayEquals(ama.member.safetyCode(), esi.member.safetyCode())
    }

    /** Review C6: a phone asks a hub for its own next codes with everyone else's, so asking does not name the sender. */
    @Test fun aPhoneAsksForItsOwnCodesToo() {
        val (_, kofi, esi) = three()
        val mine = kofi.member.expectedCodes(1).map { it.toList() }.toSet()
        val next = kofi.member.send("x".toByteArray())
        assertTrue(GroupCipher.codeOf(next)!!.toList() in mine)
        assertTrue(GroupCipher.codeOf(next)!!.toList() in esi.member.expectedCodes(1).map { it.toList() })
    }

    // ---- review C2: keys must be the next epoch, from an admin of the list held, and a key alone cannot stall the group

    @Test fun onlyTheNextEpochFromAnAdminOfTheListHeldIsTaken() {
        val (ama, kofi, esi) = three()
        fun key(epoch: Long, parent: ByteArray = esi.member.digest, signer: ByteArray = ama.sk) =
            GroupKey(group, epoch, ByteArray(32) { 1 }, ByteArray(16) { 2 }, parent, 1, signer)
        assertFalse("an epoch far ahead", esi.member.receiveKey(ama.id, key(0xFFFF_FFFEL), 1))
        assertTrue("two ahead is kept for later, not taken", esi.member.receiveKey(ama.id, key(3), 1))
        assertEquals(1L, esi.member.epoch)
        assertTrue(esi.member.canSend)
        assertFalse("kept only from an admin", esi.member.receiveKey(esi.id, key(3), 1))
        assertFalse("not built on the list held", esi.member.receiveKey(ama.id, key(2, parent = ByteArray(16)), 1))
        assertFalse("not an admin", esi.member.receiveKey(esi.id, key(2), 1))
        val stranger = Person("stranger")
        assertFalse("not on the list", esi.member.receiveKey(stranger.id, key(2), 1))
        assertTrue(esi.member.canSend)
        assertTrue("the next epoch from an admin waits for its list", esi.member.receiveKey(kofi.id, key(2, signer = kofi.sk), 1))
        assertEquals(1L, esi.member.epoch)
    }

    @Test fun aKeyWhoseListNeverComesIsDroppedAndSendingResumes() {
        val (ama, kofi, esi) = three()
        val out = kofi.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry()), 100)
        assertTrue(esi.member.receiveKey(kofi.id, GroupKey.decode(out.keys.getValue(esi.id.toList()))!!, 100))
        assertFalse("no sending in the old epoch while a newer key waits", esi.member.canSend)
        assertNotNull("she can ask for it again", esi.member.ask())
        esi.reload()
        assertFalse(esi.member.canSend)
        esi.member.expectedCodes(100 + GroupMember.KEEP_MS + 1)
        assertTrue("dropped after the resend horizon", esi.member.canSend)
        assertNull(esi.member.ask())
        assertEquals(1L, esi.member.epoch)
    }

    @Test fun aMissingListIsAskedForAndSentAgain() {
        val (ama, _, esi) = three()
        val yaw = Person("Yaw")
        val out = ama.member.newEpoch(roster(ama.entry(true), esi.entry(), yaw.entry()), 100)
        assertTrue(esi.member.receiveKey(ama.id, GroupKey.decode(out.keys.getValue(esi.id.toList()))!!, 100))
        // The list packets are lost; Esi asks Ama, who answers only someone on that list.
        val (to, ask) = esi.member.ask()!!
        assertArrayEquals(ama.id, to)
        ama.reload()
        assertFalse(ama.member.receiveControl(Person("x").id, GroupControl.decode(GroupControl.ASK, ask)!!, 200).first)
        val (ok, again) = ama.member.receiveControl(esi.id, GroupControl.decode(GroupControl.ASK, ask)!!, 200)
        assertTrue(ok)
        for (p in again!!.rosterPackets) esi.member.receive(p, 200)
        assertEquals(2L, esi.member.epoch)
        assertTrue(esi.member.canSend)
        val (late, _) = ama.member.receiveControl(esi.id, GroupControl.decode(GroupControl.ASK, ask)!!, 100 + GroupMember.KEEP_MS + 1)
        assertFalse("nothing kept past the horizon", late)
    }

    // ---- decision 66: signing keys as the list names them

    /** An admin who signs the list with a key other than the one its own list entry names is refused. */
    @Test fun anAdminMustSignUnderTheKeyItsListEntryNames() {
        val (ama, kofi, esi) = three()
        val other = P256.generate()
        val secret = ByteArray(32) { 5 }
        val list = roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Autre")
        val digest = list.digest(group, 2, esi.member.digest)
        val parts = list.parts()
        val key = GroupKey(group, 2, secret, digest, esi.member.digest, parts.size, pub(other))
        val writer = GroupWriter(group, 2, ama.id, GroupCipher.chain0(group, 2, secret, ama.id), sign = { P256.sign(other.private, it) })
        val packets = parts.map { writer.seal(it) }
        assertTrue(esi.member.receiveKey(ama.id, key, 100))
        for (packet in packets) esi.member.receive(packet, 100)
        assertEquals("the list is not taken", 1L, esi.member.epoch)
    }

    /** A list that gives this phone another signing key (someone could then write as it) is not taken. */
    @Test fun aListThatGivesThisPhoneAnotherSigningKeyIsNotTaken() {
        val (ama, kofi, esi) = three()
        val fake = GroupRoster.Member(esi.id, false, pub(P256.generate()))
        deliver(ama, ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), fake), 100), listOf(ama, kofi, esi), 100)
        assertEquals(2L, kofi.member.epoch)
        assertEquals("Esi stays out of a list that misnames her key", 1L, esi.member.epoch)
        assertTrue(esi.member.state != GroupMember.State.REMOVED)
    }

    // ---- review C3: two admins starting the same epoch

    @Test fun twoListsForOneEpochStopSendingUntilAnAdminStartsTheNext() {
        val (ama, kofi, esi) = three()
        val fromAma = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Puits A"), 100)
        val fromKofi = kofi.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Puits K"), 100)
        val everyone = listOf(ama, kofi, esi)
        deliver(ama, fromAma, everyone, 101)
        esi.reload()
        deliver(kofi, fromKofi, everyone, 102)
        for (p in everyone) {
            assertEquals("${p.name} sees the conflict", GroupMember.State.CONFLICT, p.member.state)
            assertFalse(p.member.canSend)
            assertTrue(runCatching { p.member.send("x".toByteArray()) }.isFailure)
        }
        esi.reload()
        assertEquals(GroupMember.State.CONFLICT, esi.member.state)
        assertFalse("a member cannot settle it", runCatching { esi.member.newEpoch(roster(esi.entry(true), ama.entry()), 103) }.isSuccess)
        // Kofi settles it from his list; everyone, whichever list they held, takes epoch 3.
        deliver(kofi, kofi.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Puits"), 104), everyone, 104)
        for (p in everyone) {
            assertEquals(p.name, GroupMember.State.ACTIVE, p.member.state)
            assertEquals(3L, p.member.epoch)
        }
        assertArrayEquals(ama.member.safetyCode(), esi.member.safetyCode())
        assertEquals("Puits", String(esi.member.receive(ama.member.send("Puits".toByteArray()), 105).single().body))
    }

    @Test fun aRivalKeyWhoseListNeverComesIsNoConflict() {
        val (ama, kofi, esi) = three()
        val fromAma = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "A"), 100)
        val rival = kofi.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "K"), 100)
        deliver(ama, fromAma, listOf(ama, esi), 100)
        // Kofi's rival key arrives, but its list never does: no conflict, and Esi keeps sending.
        assertTrue(esi.member.receiveKey(kofi.id, GroupKey.decode(rival.keys.getValue(esi.id.toList()))!!, 101))
        assertEquals(GroupMember.State.ACTIVE, esi.member.state)
        assertTrue(esi.member.canSend)
        // A list that does not match its key's digest is no conflict either.
        val bent = rival.rosterPackets.map { it.copyOf().also { b -> b[b.size - 1] = (b[b.size - 1] + 1).toByte() } }
        for (p in bent) esi.member.receive(p, 102)
        assertEquals(GroupMember.State.ACTIVE, esi.member.state)
    }

    // ---- review C4: removal is told, and the first messages before a list are kept

    @Test fun aRemovedPhoneIsToldAndStops() {
        val (ama, kofi, esi) = three()
        val out = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true)), 100)
        assertEquals(setOf(esi.id.toList()), out.removed.keys)
        assertFalse("a notice from a non-admin is refused",
            esi.member.receiveControl(Person("x").id, GroupControl.decode(GroupControl.REMOVED, out.removed.values.single())!!, 100).first)
        deliver(ama, out, listOf(ama, kofi, esi), 100)
        assertEquals(GroupMember.State.REMOVED, esi.member.state)
        assertFalse(esi.member.canSend)
        assertTrue(esi.member.expectedCodes(100).isEmpty())
        esi.reload()
        assertEquals(GroupMember.State.REMOVED, esi.member.state)
    }

    @Test fun aListWithoutThisPhoneRemovesItToo() {
        val (ama, kofi, esi) = three()
        val out = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true)), 100)
        // Esi was sent a key by mistake, with the list that drops her: she leaves rather than staying behind.
        val key = GroupKey.decode(out.keys.getValue(kofi.id.toList()))!!
        assertTrue(esi.member.receiveKey(ama.id, key, 100))
        for (p in out.rosterPackets) esi.member.receive(p, 100)
        assertEquals(GroupMember.State.REMOVED, esi.member.state)
    }

    @Test fun aRemovedMembersOldEpochPacketsAreNotRead() {
        val (ama, kofi, esi) = three()
        val late = esi.member.send("still here".toByteArray())
        val lateHub = esi.member.seal(GroupBody.MESSAGE, "a picture part".toByteArray(), hub = true).packet
        val kofiOld = kofi.member.send("old epoch".toByteArray())
        deliver(ama, ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true)), 100), listOf(ama, kofi, esi), 100)
        assertTrue("the removed member's old packets are dropped", ama.member.receive(late, 101).isEmpty())
        assertTrue("on the hub chain too", ama.member.receive(lateHub, 101).isEmpty())
        val old = ama.member.receive(kofiOld, 101).single()
        assertTrue("a member of the new list, read and marked old", old.oldEpoch)
        val kofiLater = kofi.member.send("new epoch".toByteArray())
        assertFalse(ama.member.receive(kofiLater, 100 + GroupMember.KEEP_MS + 1).single().oldEpoch)
    }

    @Test fun theAdminsFirstMessageBeforeItsListIsKeptAndReleased() {
        val (ama, kofi, esi) = three()
        val out = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Neuf"), 100)
        val hello = ama.member.send("bienvenue".toByteArray())
        assertTrue(esi.member.receiveKey(ama.id, GroupKey.decode(out.keys.getValue(esi.id.toList()))!!, 100))
        assertTrue("held until the list comes", esi.member.receive(hello, 100).isEmpty())
        esi.reload()
        val released = out.rosterPackets.flatMap { esi.member.receive(it, 101) }
        assertEquals("bienvenue", String(released.single().body))
        assertArrayEquals(ama.id, released.single().sender)
    }

    // ---- review C8: saved state goes on exactly

    @Test fun aReloadedPhoneGoesOnWhereItWas() {
        val (ama, kofi, esi) = three()
        val packets = (0 until 3).map { kofi.member.send("m$it".toByteArray()) }
        assertEquals("m1", String(esi.member.receive(packets[1], 10).single().body))
        esi.reload()
        assertEquals("a late packet still reads", "m0", String(esi.member.receive(packets[0], 11).single().body))
        assertTrue("a read packet stays read", esi.member.receive(packets[1], 11).isEmpty())
        kofi.reload()
        val next = kofi.member.send("after".toByteArray())
        assertEquals("after", String(ama.member.receive(next, 12).single().body))
        assertEquals("m2", String(ama.member.receive(packets[2], 12).single().body))
        assertNull("other bytes are not a saved member", GroupMember.decode(esi.id, esi.sk, { it }, group, byteArrayOf(9, 9)))
    }

    /** Review C7: once a key's list is taken, the saved state holds the chains, never the epoch secret. */
    @Test fun aTakenKeysSecretIsNotKept() {
        val (ama, kofi, esi) = three()
        val out = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "W"), 100)
        val secret = GroupKey.decode(out.keys.getValue(esi.id.toList()))!!.secret
        assertTrue(esi.member.receiveKey(ama.id, GroupKey.decode(out.keys.getValue(esi.id.toList()))!!, 100))
        assertTrue("waiting, it is saved with the key", esi.member.encode().toList().windowed(32).any { it.toByteArray().contentEquals(secret) })
        for (p in out.rosterPackets) esi.member.receive(p, 100)
        assertEquals(2L, esi.member.epoch)
        assertFalse(esi.member.encode().toList().windowed(32).any { it.toByteArray().contentEquals(secret) })
    }

    // ---- GK4: invitations, the hub chain, receipts, leaving, one list with two secrets

    @Test fun theFirstKeyIsTakenOnlyFromTheAdminWhoInvited() {
        val ama = Person("Ama"); val kofi = Person("Kofi"); val esi = Person("Esi")
        esi.joins(ama)
        esi.reload()
        assertTrue(ama.member.receiveControl(esi.id, GroupControl.decode(GroupControl.SIGNER, esi.member.introduce())!!, 0).first)
        assertTrue(kofi.member.receiveControl(esi.id, GroupControl.decode(GroupControl.SIGNER, esi.member.introduce())!!, 0).first)
        val fromKofi = kofi.member.newEpoch(roster(kofi.entry(true), GroupRoster.Member(esi.id, false, kofi.member.signerOf(esi.id)!!)), 0)
        assertFalse("a key from someone who did not invite her", esi.member.receiveKey(kofi.id, GroupKey.decode(fromKofi.keys.getValue(esi.id.toList()))!!, 0))
        val fromAma = ama.member.newEpoch(roster(ama.entry(true), GroupRoster.Member(esi.id, false, ama.member.signerOf(esi.id)!!)), 0)
        assertTrue(esi.member.receiveKey(ama.id, GroupKey.decode(fromAma.keys.getValue(esi.id.toList()))!!, 0))
        esi.reload()
        for (p in fromAma.rosterPackets) esi.member.receive(p, 1)
        assertEquals(GroupMember.State.ACTIVE, esi.member.state)
        assertTrue(esi.member.joinedThisEpoch)
        assertFalse("the phone that made the group takes no key", Person("x").member.receiveKey(ama.id, GroupKey.decode(fromAma.keys.getValue(esi.id.toList()))!!, 0))
        assertTrue("an invitation is the app's to show", runCatching {
            val invite = GroupControl.Invite(group, ama.sk, 2, "Puits", 0x2E7D32, org.phonemesh.core.account.Avatar.Letter)
            check(!esi.member.receiveControl(ama.id, invite, 2).first)
        }.isSuccess)
    }

    @Test fun hubChainPacketsLeaveTheRadioChainWithoutGaps() {
        val (ama, kofi, esi) = three()
        val hubOnly = (0 until GroupReader.WINDOW + 5).map {
            kofi.reload()
            kofi.member.seal(GroupBody.MESSAGE, "part $it".toByteArray(), hub = true)
        }
        val radio = kofi.member.seal(GroupBody.MESSAGE, "à midi".toByteArray())
        assertEquals("the radio chain did not move", 0L, radio.seq)
        val got = esi.member.receive(radio.packet, 10).single()
        assertEquals("à midi", String(got.body))
        assertFalse(got.hub)
        assertEquals(0L, got.seq)
        // Someone fetching from a hub reads the hub chain in order, marked as such.
        hubOnly.forEachIndexed { i, s ->
            val m = ama.member.receive(s.packet, 11).single()
            assertTrue(m.hub); assertEquals(i.toLong(), m.seq); assertEquals("part $i", String(m.body))
        }
        assertTrue("a hub code is asked of a hub", GroupCipher.codeOf(kofi.member.seal(GroupBody.SHARED, "x".toByteArray(), hub = true).packet)!!.toList() in
            ama.member.expectedCodes(12).map { it.toList() })
        val next = kofi.member.seal(GroupBody.MESSAGE, "x".toByteArray(), hub = true)
        assertFalse("but not listened for on the radio", GroupCipher.codeOf(next.packet)!!.toList() in
            ama.member.expectedCodes(12, hub = false).map { it.toList() })
        assertTrue("the app never sends a list part", runCatching { kofi.member.seal(GroupBody.ROSTER, byteArrayOf(0)) }.isFailure)
    }

    @Test fun receiptsSayWhichPacketsEachMemberRead() {
        val (ama, kofi, esi) = three()
        val sent = (0 until 3).map { kofi.member.seal(GroupBody.MESSAGE, "m$it".toByteArray()) }
        esi.member.receive(sent[0].packet, 10); esi.member.receive(sent[2].packet, 10)
        val body = esi.member.receipt()!!
        assertNull("one receipt per batch", esi.member.receipt())
        val receipt = esi.member.seal(GroupBody.RECEIPT, body)
        val atKofi = kofi.member.receive(receipt.packet, 11).single()
        assertEquals(GroupBody.RECEIPT, atKofi.op)
        val entry = kofi.member.receiptForMe(atKofi)!!
        assertEquals(listOf(true, false, true), sent.map { entry.covers(it.seq) })
        val atAma = ama.member.receive(receipt.packet, 11).single()
        val forAma = ama.member.receiptForMe(atAma)!!
        val listPackets = ama.member.roster!!.parts().size.toLong()
        assertTrue("Esi read Ama's list packets and nothing after", forAma.covers(listPackets - 1) && !forAma.covers(listPackets))
        assertNull("a receipt alone asks for no receipt", ama.member.receipt())
        kofi.reload()
        assertNull(kofi.member.receipt())
        // Esi's missing packet arrives late: her next receipt covers it.
        esi.member.receive(sent[1].packet, 12)
        esi.reload()
        val again = kofi.member.receive(esi.member.seal(GroupBody.RECEIPT, esi.member.receipt()!!).packet, 13).single()
        assertTrue(sent.all { kofi.member.receiptForMe(again)!!.covers(it.seq) })
        val kofiIndex = kofi.member.roster!!.indexOf(kofi.id)
        assertNull("a receipt with an index past the list is refused whole", kofi.member.receiptForMe(GroupMember.Message(esi.id,
            GroupReceipt(listOf(GroupReceipt.Entry(5, 1, 0), GroupReceipt.Entry(kofiIndex, 1, 0))).encode(), op = GroupBody.RECEIPT, epoch = 1)))
    }

    @Test fun aMemberWhoLeavesTellsOneAdminWhoDropsThem() {
        val (ama, kofi, esi) = three()
        val (to, body) = esi.member.leave()!!
        assertArrayEquals("the first admin on the list", ama.id, to)
        assertEquals(GroupMember.State.LEFT, esi.member.state)
        assertTrue(esi.member.expectedCodes(1).isEmpty())
        assertFalse(esi.member.canSend)
        assertFalse("someone not on the list cannot say Esi left",
            ama.member.receiveControl(Person("x").id, GroupControl.decode(GroupControl.LEAVE, body)!!, 1).first)
        assertTrue(ama.member.receiveControl(esi.id, GroupControl.decode(GroupControl.LEAVE, body)!!, 1).first)
        ama.reload()
        assertEquals(listOf(esi.id.toList()), ama.member.leaving().map { it.toList() })
        deliver(ama, ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true)), 2), listOf(ama, kofi), 2)
        assertTrue(ama.member.leaving().isEmpty())
        esi.reload()
        assertEquals(GroupMember.State.LEFT, esi.member.state)
        assertFalse("a phone that left takes no key",
            esi.member.receiveKey(ama.id, GroupKey(group, esi.member.epoch + 1, ByteArray(32), ByteArray(16), esi.member.digest, 1, ama.sk), 3))
        // Esi comes back later: her old leaving notice does not drop her again.
        assertTrue(ama.member.receiveControl(esi.id, GroupControl.decode(GroupControl.LEAVE, body)!!, 3).first == false)
        val back = Person("Esi again")
        deliver(ama, ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry()), 4), listOf(ama, kofi), 4)
        assertTrue("a leaving notice is forgotten once its epoch is gone", ama.member.leaving().isEmpty())
        check(back.id.isNotEmpty())
    }

    @Test fun theLastAdminMakesSomeoneAdminThenLeaves() {
        val ama = Person("Ama"); val kofi = Person("Kofi"); val esi = Person("Esi")
        for (p in listOf(kofi, esi)) { p.joins(ama); ama.member.receiveControl(p.id, GroupControl.decode(GroupControl.SIGNER, p.member.introduce())!!, 0) }
        val everyone = listOf(ama, kofi, esi)
        deliver(ama, ama.member.newEpoch(roster(ama.entry(true), kofi.entry(), esi.entry()), 0), everyone, 0)
        assertTrue("an admin staying cannot use the leaving epoch",
            runCatching { ama.member.newEpoch(roster(ama.entry(true), esi.entry(true)), 1, leaving = true) }.isFailure)
        val out = ama.member.newEpoch(roster(kofi.entry(), esi.entry(true)), 1, leaving = true)
        assertEquals(GroupMember.State.LEFT, ama.member.state)
        assertTrue(out.removed.isEmpty())
        deliver(ama, out, everyone, 1)
        for (p in listOf(kofi, esi)) {
            assertEquals(2L, p.member.epoch)
            assertNull(p.member.roster!!.member(ama.id))
            assertTrue(p.member.roster!!.isAdmin(esi.id))
        }
        assertTrue("the leaver's chain is not read in the new epoch", ama.member.expectedCodes(2).isEmpty())
        assertEquals("à midi", String(kofi.member.receive(esi.member.send("à midi".toByteArray()), 3).single().body))
        assertNotNull("receipts name only people on the list", GroupReceipt.decode(kofi.member.receipt()!!))
        // Only an admin of the list held may hand over.
        val (a2, k2, e2) = three()
        assertTrue(runCatching { e2.member.newEpoch(roster(a2.entry(true), k2.entry()), 1, leaving = true) }.isFailure)
    }

    @Test fun oneListWithTwoSecretsIsAConflict() {
        val (ama, kofi, esi) = three()
        val list = roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Same")
        val fromAma = ama.member.newEpoch(list, 100)
        val fromKofi = kofi.member.newEpoch(list, 100)
        val everyone = listOf(ama, kofi, esi)
        deliver(ama, fromAma, everyone, 101)
        deliver(kofi, fromKofi, everyone, 102)
        for (p in everyone) assertEquals("${p.name} sees two secrets", GroupMember.State.CONFLICT, p.member.state)
        // The same key twice is no conflict.
        assertTrue(esi.member.receiveKey(ama.id, GroupKey.decode(fromAma.keys.getValue(esi.id.toList()))!!, 103))
        deliver(ama, ama.member.newEpoch(list, 104), everyone, 104)
        for (p in everyone) assertEquals(GroupMember.State.ACTIVE, p.member.state)
        val again = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Again"), 105)
        deliver(ama, again, everyone, 105)
        deliver(ama, again, everyone, 106)
        for (p in everyone) assertEquals("a key and list delivered twice", GroupMember.State.ACTIVE, p.member.state)
    }

    @Test fun peopleAddedInAnEpochAreNamed() {
        val (ama, kofi, esi) = three()
        val yaw = Person("Yaw")
        yaw.joins(ama)
        ama.member.receiveControl(yaw.id, GroupControl.decode(GroupControl.SIGNER, yaw.member.introduce())!!, 1)
        val list = roster(ama.entry(true), kofi.entry(true), esi.entry(), GroupRoster.Member(yaw.id, false, ama.member.signerOf(yaw.id)!!))
        deliver(ama, ama.member.newEpoch(list, 2), listOf(ama, kofi, esi, yaw), 2)
        assertEquals(listOf(yaw.id.toList()), esi.member.added().map { it.toList() })
        assertFalse(esi.member.joinedThisEpoch)
        assertTrue(yaw.member.joinedThisEpoch)
        assertEquals(GroupMember.State.ACTIVE, yaw.member.state)
    }

    /** A later key that overtakes the list before it is kept, then taken once the earlier list arrives. */
    @Test fun aKeyThatComesBeforeTheEarlierListIsKeptUntilItsTurn() {
        val (ama, kofi, esi) = three()
        val second = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Deux"), 100)
        val third = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Trois"), 101)
        // Esi gets epoch 2's key, then epoch 3's key before epoch 2's list.
        assertTrue(esi.member.receiveKey(ama.id, GroupKey.decode(second.keys.getValue(esi.id.toList()))!!, 102))
        assertTrue("kept for later", esi.member.receiveKey(ama.id, GroupKey.decode(third.keys.getValue(esi.id.toList()))!!, 102))
        assertFalse("not from someone off the list", esi.member.receiveKey(Person("x").id, GroupKey.decode(third.keys.getValue(esi.id.toList()))!!, 102))
        assertFalse("nor from a member who is not an admin", esi.member.receiveKey(esi.id, GroupKey.decode(third.keys.getValue(esi.id.toList()))!!, 102))
        esi.reload()
        for (p in second.rosterPackets) esi.member.receive(p, 103)
        assertEquals(2L, esi.member.epoch)
        assertEquals("epoch 3's key now waits for its list", 3L, esi.member.waiting()!!.second)
        esi.reload()
        for (p in third.rosterPackets) esi.member.receive(p, 104)
        assertEquals(3L, esi.member.epoch)
        assertEquals("Trois", esi.member.roster!!.name)
        assertTrue(esi.member.canSend)
        assertFalse("too far ahead", kofi.member.receiveKey(ama.id, GroupKey(group, kofi.member.epoch + 1 + GroupMember.MAX_AHEAD, ByteArray(32), ByteArray(16), ByteArray(16), 1, ama.sk), 105))
    }

    @Test fun aPacketThatOnlyMovesAChainStillCountsAsTaken() {
        val (ama, kofi, esi) = three()
        val out = ama.member.newEpoch(roster(ama.entry(true), kofi.entry(true), esi.entry(), name = "Neuf"), 100)
        assertTrue(esi.member.receiveKey(ama.id, GroupKey.decode(out.keys.getValue(esi.id.toList()))!!, 100))
        assertTrue(esi.member.receive(out.rosterPackets[0], 101).isEmpty())
        assertTrue("a list part shows nothing but was read", esi.member.took)
        esi.member.receive(byteArrayOf(1, 2, 3), 101)
        assertFalse(esi.member.took)
    }
}

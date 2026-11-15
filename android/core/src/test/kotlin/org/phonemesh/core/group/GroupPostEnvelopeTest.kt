package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.Avatar
import org.phonemesh.core.account.Profile
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.Body
import java.security.KeyPair
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/**
 * The admin reaches a member it has no contact with, by an envelope (v0.1.3 G3h; relayed group control design,
 * "Which path an admin uses"; accounts decisions 79 and 80).
 *
 * The plan decides, for each member of the next list: a contact over the private session as today, a member that
 * announced a box key on the list held in an envelope, and anyone else not at all. A member with neither stops the
 * change: a change reaching some members and not others would leave the group half in one epoch and half in another.
 *
 * The member's side is here too: it announces its own box key once it is in a group, so an admin that has not added
 * it can reach it at all.
 */
class GroupPostEnvelopeTest {
    private fun pub(k: KeyPair) = P256.compress(k.public as ECPublicKey)

    /** A phone's group storage in maps: what [GroupStore.commit] got, kept for the test to deliver. */
    private inner class MapStore(val phone: Phone) : GroupStore {
        val saved = LinkedHashMap<List<Byte>, GroupStore.Saved>()
        val invites = LinkedHashMap<List<Byte>, GroupStore.Invitation>()
        val keys = HashMap<List<Byte>, PrivateKey>()
        val private = ArrayList<Pair<AccountId, Body.Group>>()
        val packets = ArrayList<GroupPost.Packet>()
        val events = ArrayList<GroupPost.Event>()
        val commits = ArrayList<GroupPost.Change>()
        var refuse = false
        override fun groups() = saved.keys.map { it.toByteArray() }
        override fun saved(group: ByteArray): GroupStore.Saved? = saved[group.toList()]
        override fun invitations() = invites.values.toList()
        override fun newSigner(group: ByteArray): ByteArray { val k = P256.generate(); keys[group.toList()] = k.private; return pub(k) }
        override fun sign(group: ByteArray, data: ByteArray): ByteArray = P256.sign(keys.getValue(group.toList()), data)
        override fun identityOf(account: AccountId) = phone.contacts[account]
        override fun myProfile() = phone.profile
        override fun myPhotoPieces(): List<ByteArray> = phone.photoPieces
        override fun groupPhotoPieces(group: ByteArray): List<ByteArray> = emptyList()
        override fun recent(group: ByteArray, sinceMs: Long, max: Int): List<GroupShared> = emptyList()
        override fun commit(change: GroupPost.Change): Boolean {
            if (refuse) return false
            // AppGroupStore decodes before it writes: map tests must not admit states the app cannot reopen.
            change.saved?.let { s ->
                if (GroupMember.decode(phone.id, s.signer, { data -> sign(change.group, data) }, change.group, s.state) == null) return false
            }
            commits += change
            val g = change.group.toList()
            if (change.deleted) saved.remove(g) else change.saved?.let { saved[g] = it }
            change.invitation?.let { invites[g] = it }
            if (change.dropInvitation) invites.remove(g)
            private += change.private; packets += change.packets; events += change.events
            return true
        }
    }

    private inner class Phone(val name: String) {
        val identity = P256.generate()
        val id = pub(identity)
        val account = AccountId.of(id)
        val contacts = HashMap<AccountId, ByteArray>()
        var profile: Profile? = Profile(name, 0x336699)
        var photoPieces: List<ByteArray> = emptyList()
        val store = MapStore(this)
        val post = GroupPost(id, store, SecureRandom(), quietDelay = { 20_000L }, profileDelay = { 0L })
        fun know(vararg others: Phone) = others.forEach { contacts[it.account] = it.id }
    }

    private val phones = ArrayList<Phone>()
    private fun phones(vararg names: String): List<Phone> {
        val made = names.map { Phone(it) }
        phones += made
        // Everyone has a private session with the first phone (the admin); the others may not know each other.
        for (p in made.drop(1)) { made[0].know(p); p.know(made[0]) }
        return made
    }

    private val hub = ArrayList<ByteArray>()
    private var now = 1_000_000L

    /** Private bodies (reliable) and group packets, until nothing moves. */
    private fun settle() {
        repeat(50) {
            var moved = false
            for (p in phones) {
                val private = p.store.private.toList(); p.store.private.clear()
                for ((to, body) in private) {
                    val target = phones.single { it.account == to }
                    assertTrue("${target.name} takes ${body.kind}", target.post.takePrivate(p.account, body, now)); moved = true
                }
                val packets = p.store.packets.toList(); p.store.packets.clear()
                for (pk in packets) {
                    hub += pk.sealed.packet
                    for (q in phones) if (q !== p) q.post.receive(pk.sealed.packet, now)
                    moved = true
                }
            }
            now += 1_000
            if (!moved) return
        }
    }

    private fun tickAll(after: Long = 61_000) { now += after; for (p in phones) assertTrue(p.post.tick(now)); settle() }

    /**
     * Ama makes "Puits" with the others; they all join. The phones named in [silent] never tick, so they announce no
     * box key: that is what a v0.1.2 member looks like to this version.
     */
    private fun group(vararg names: String, silent: Set<String> = emptySet()): Pair<List<Phone>, ByteArray> {
        val ps = phones(*names)
        val g = ps[0].post.create("Puits", 0x2E7D32, Avatar.Letter, false, ps.drop(1).map { it.account }, now)!!
        settle()
        for (p in ps.drop(1)) {
            val invitation = p.store.invitations().single()
            assertTrue(p.post.join(invitation, now))
        }
        settle()
        repeat(2) {
            now += 61_000
            for (p in ps) if (p.name !in silent) assertTrue(p.post.tick(now))
            settle()
        }
        return ps to g
    }

    // ---- the member announces its own box key

    @Test fun aMemberAnnouncesItsOwnBoxKeyOnceItIsInAGroup() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        // Kofi announced one: the admin holds it, read on Kofi's own chain.
        assertNotNull("the admin holds Kofi's box key", ama.post.member(g)!!.boxKeyOf(kofi.id))
        // And it is Kofi's own key: Kofi can open an envelope sealed to it.
        assertEquals("Kofi keeps one box key", 1, kofi.post.member(g)!!.ownBoxKeys(now).size)
    }

    @Test fun aMemberDoesNotAnnounceItsBoxKeyTwice() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val before = ama.post.member(g)!!.boxKeyOf(kofi.id)
        val announce = kofi.store.commits.flatMap { it.packets }.single { it.op == GroupBoxKey.OP }
        assertFalse("the announcement is radio traffic", announce.hubOnly)
        assertFalse(announce.sealed.hub)
        tickAll()
        tickAll()
        assertArrayEquals("the key did not change", before, ama.post.member(g)!!.boxKeyOf(kofi.id))
        assertEquals("Kofi still keeps one box key", 1, kofi.post.member(g)!!.ownBoxKeys(now).size)
    }

    // ---- the admin reaches a member it has no contact with

    @Test fun anAdminReachesAMemberItHasNoContactWithByAnEnvelope() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        // Esi is not a contact of the admin any more, but it announced a box key, so it can still be reached.
        ama.contacts.remove(esi.account)
        val epoch = ama.post.member(g)!!.epoch
        // The admin changes the group: Esi must be reached by an envelope.
        assertTrue(ama.post.restyle(g, "Puits", 0x336699, Avatar.Letter, false, now))
        val envelope = ama.store.packets.single { it.op == GroupEnvelope.OP }
        assertFalse("radio envelope must not wait for a hub", envelope.hubOnly)
        assertFalse(envelope.sealed.hub)
        assertEquals("on the held chain", epoch, envelope.sealed.epoch)
        assertTrue("envelope queued before list", ama.store.packets.indexOf(envelope) <
            ama.store.packets.indexOfFirst { it.op == GroupBody.ROSTER })
        settle()
        assertEquals("the epoch moved", epoch + 1, ama.post.member(g)!!.epoch)
        // Esi took the epoch: it read the envelope on the admin's chain and then the list.
        assertEquals("Esi took the epoch", epoch + 1, esi.post.member(g)!!.epoch)
        assertEquals("Esi is still in the group", GroupMember.State.ACTIVE, esi.post.member(g)!!.state)
    }

    @Test fun aMemberReachedByAnEnvelopeGetsNoPrivateKey() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        ama.contacts.remove(esi.account)
        ama.store.private.clear()
        assertTrue(ama.post.restyle(g, "Puits", 0x336699, Avatar.Letter, false, now))
        // Esi is reached by an envelope, so no private key goes to it: each is reached one way or the other.
        assertTrue("no private key for Esi", ama.store.private.none { it.first == esi.account && it.second.kind == GroupControl.KEY })
    }

    @Test fun aMemberWithNeitherAContactNorABoxKeyStopsTheChange() {
        // Esi never ticks, so it announces no box key: it is a member the admin cannot reach at all.
        val (ps, g) = group("Ama", "Kofi", "Esi", silent = setOf("Esi"))
        val (ama, kofi, esi) = ps
        assertNull("the admin holds no box key for Esi", ama.post.member(g)!!.boxKeyOf(esi.id))
        ama.contacts.remove(esi.account)
        val epoch = ama.post.member(g)!!.epoch
        // A joiner makes the admin start the next epoch, which includes Esi: it cannot be reached, so it does not start.
        val zed = Phone("Zed"); phones += zed; ama.know(zed); zed.know(ama)
        assertTrue(ama.post.invite(g, listOf(zed.account), now)); settle()
        assertTrue(zed.post.join(zed.store.invitations().single(), now)); settle()
        ama.store.events.clear()
        now += 61_000
        assertTrue(ama.post.tick(now))
        assertEquals("no epoch without a key for Esi", epoch, ama.post.member(g)!!.epoch)
        assertEquals("the admin is told who it cannot reach", listOf(esi.account),
            ama.store.events.filterIsInstance<GroupPost.Event.CannotReach>().single().people)
    }

    @Test fun aContactIsStillReachedPrivately() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        // Kofi is a contact and announced a box key: it is still reached over the private session.
        val epoch = ama.post.member(g)!!.epoch
        assertTrue(ama.post.restyle(g, "Puits", 0x336699, Avatar.Letter, false, now))
        assertTrue(ama.store.private.any { it.first == kofi.account && it.second.kind == GroupControl.KEY })
        assertTrue("no weaker duplicate route for contacts", ama.store.packets.none { it.op == GroupEnvelope.OP })
        settle()
        assertEquals("the epoch moved", epoch + 1, ama.post.member(g)!!.epoch)
        assertEquals("Kofi took the epoch", epoch + 1, kofi.post.member(g)!!.epoch)
    }

    // ---- the member's own key survives a restart

    @Test fun theMemberKeepsItsOwnBoxKeyAcrossARestart() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val before = kofi.post.member(g)!!.ownBoxKeys(now).single().public
        // The phone restarts: it reads its group state back from the store.
        val state = kofi.store.saved[g.toList()]!!.state
        val again = GroupMember.decode(kofi.id, kofi.store.saved[g.toList()]!!.signer,
            { kofi.store.sign(g, it) }, g, state)!!
        assertEquals("the key is kept", 1, again.ownBoxKeys(now).size)
        assertArrayEquals("it is the same key", before, again.ownBoxKeys(now).single().public)
    }

    private fun concurrentAdmins(bothEnvelopes: Boolean) {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        assertTrue(ama.post.makeAdmin(g, kofi.account, now)); settle(); tickAll()
        val held = esi.post.member(g)!!.epoch
        if (bothEnvelopes) ama.contacts.remove(esi.account)
        assertTrue(ama.post.restyle(g, "Ama market", 0x336699, Avatar.Letter, false, now))
        assertTrue(kofi.post.restyle(g, "Kofi market", 0x336699, Avatar.Letter, false, now))
        // The rival's envelope arrives first, but the other admin's list completes first.
        val rivalEnvelope = kofi.store.packets.single { it.op == GroupEnvelope.OP }
        assertTrue(esi.post.receive(rivalEnvelope.sealed.packet, now))
        if (bothEnvelopes) {
            assertTrue(esi.post.receive(ama.store.packets.single { it.op == GroupEnvelope.OP }.sealed.packet, now))
        } else {
            val key = ama.store.private.single { it.first == esi.account && it.second.kind == GroupControl.KEY }
            assertTrue(esi.post.takePrivate(ama.account, key.second, now))
        }
        for (p in ama.store.packets.filter { it.op == GroupBody.ROSTER }) {
            assertTrue("the winner's state must pass decode-before-save", esi.post.receive(p.sealed.packet, now))
        }
        assertEquals(held + 1, esi.post.member(g)!!.epoch)
        assertEquals("only the winning path creates rotation debt", bothEnvelopes, esi.post.member(g)!!.needsBoxKey)
        for (p in kofi.store.packets.filter { it.op == GroupBody.ROSTER }) assertTrue(esi.post.receive(p.sealed.packet, now))
        assertEquals(GroupMember.State.CONFLICT, esi.post.member(g)!!.state)
    }

    @Test fun aPrivateWinnerAndAnEnvelopeRivalRemainRestorable() = concurrentAdmins(false)
    @Test fun twoEnvelopeAdminsRemainRestorableWhenOneListWins() = concurrentAdmins(true)

    @Test fun anEnvelopeDeliveredEpochRotatesOnceInTheNewEpoch() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, _, esi) = ps
        val before = esi.post.member(g)!!.ownBoxKeys(now).single().public
        ama.contacts.remove(esi.account)
        assertTrue(ama.post.restyle(g, "Puits", 0x336699, Avatar.Letter, false, now)); settle()
        assertTrue(esi.post.member(g)!!.needsBoxKey)
        esi.store.commits.clear()
        tickAll()
        val packets = esi.store.commits.flatMap { it.packets }.filter { it.op == GroupBoxKey.OP }
        assertEquals(1, packets.size)
        assertEquals(esi.post.member(g)!!.epoch, packets.single().sealed.epoch)
        val after = esi.post.member(g)!!.ownBoxKeys(now)
        assertFalse(before.contentEquals(after.first().public))
        assertArrayEquals(before, after[1].public)
        assertArrayEquals(after.first().public, ama.post.member(g)!!.boxKeyOf(esi.id))
        tickAll()
        assertEquals(1, esi.store.commits.flatMap { it.packets }.count { it.op == GroupBoxKey.OP })
    }

    @Test fun anOrdinaryProfileUpdateDoesNotRotateAnUnusedKey() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val before = kofi.post.member(g)!!.ownBoxKeys(now).single().public
        kofi.store.commits.clear()
        assertTrue(kofi.post.profileChanged(now)); tickAll()
        assertTrue(kofi.store.commits.flatMap { it.packets }.none { it.op == GroupBoxKey.OP })
        assertArrayEquals(before, ama.post.member(g)!!.boxKeyOf(kofi.id))
    }

    @Test fun failedAnnouncementCommitKeepsTheOldStateAndEmitsNothing() {
        val (ps, g) = group("Ama", "Kofi", silent = setOf("Kofi"))
        val (_, kofi) = ps
        val before = kofi.store.saved.getValue(g.toList()).state.copyOf()
        kofi.store.refuse = true
        assertFalse(kofi.post.tick(now))
        assertArrayEquals(before, kofi.store.saved.getValue(g.toList()).state)
        assertTrue(kofi.store.packets.isEmpty())
        kofi.store.refuse = false
        assertTrue(kofi.post.tick(now))
        assertEquals(1, kofi.store.packets.count { it.op == GroupBoxKey.OP })
        assertEquals(1, kofi.post.member(g)!!.ownBoxKeys(now).size)
    }

    @Test fun failedEpochCommitKeepsItsChainAndQueuesNoPartialChange() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, _, esi) = ps
        ama.contacts.remove(esi.account)
        val before = ama.store.saved.getValue(g.toList()).state.copyOf()
        ama.store.refuse = true
        assertFalse(ama.post.restyle(g, "Puits", 0x336699, Avatar.Letter, false, now))
        assertArrayEquals(before, ama.store.saved.getValue(g.toList()).state)
        assertTrue(ama.store.packets.isEmpty()); assertTrue(ama.store.private.isEmpty())
        ama.store.refuse = false
        assertTrue(ama.post.restyle(g, "Puits", 0x336699, Avatar.Letter, false, now)); settle()
        assertEquals(ama.post.member(g)!!.epoch, esi.post.member(g)!!.epoch)
    }

    @Test fun aPreviousPrivateKeyExpiresDurablyWithoutAnotherMessage() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, _, esi) = ps
        ama.contacts.remove(esi.account)
        assertTrue(ama.post.restyle(g, "Puits", 0x336699, Avatar.Letter, false, now)); settle()
        tickAll(); tickAll()
        val deadline = now + GroupMember.KEEP_MS + 1
        assertNotNull("expiry must wake the normal scheduler", esi.post.nextDue())
        assertTrue(esi.post.nextDue()!! <= deadline)
        assertTrue(esi.post.tick(deadline))
        // Read at time zero: the saved bytes themselves must omit the expired key, not merely filter it on use.
        assertEquals(1, esi.post.member(g)!!.ownBoxKeys(0).size)
    }
}

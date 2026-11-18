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
 * G3i: the admin keeps the envelopes it sealed and airs them again for a member that has not taken the epoch
 * (relayed group control design, "Asking again, retries and limits"; accounts decisions 79 and 80).
 *
 * A member that receives an envelope gets the next epoch's key. If that envelope or the list behind it is lost
 * on the radio, the member waits. The admin still holds the epoch's copy for 72 hours, so it can air that
 * recipient's envelope and the list again: at most twice, ten and sixty minutes after the change, and only
 * while that member has not shown a new box key (which is what it announces once it took the epoch).
 *
 * The bytes aired again are the bytes made before: no new chain position, no new nonce, nothing new on air.
 */
class GroupEnvelopeRetryTest {
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
            // The app decodes before it writes: a map test must not admit a state the app cannot reopen.
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
        var post = GroupPost(id, store, SecureRandom(), quietDelay = { 20_000L }, profileDelay = { 0L })
        fun restart() { post = GroupPost(id, store, SecureRandom(), quietDelay = { 20_000L }, profileDelay = { 0L }) }
        fun know(vararg others: Phone) = others.forEach { contacts[it.account] = it.id }
    }

    private val phones = ArrayList<Phone>()
    private var now = 1_000_000L

    private fun phones(vararg names: String): List<Phone> {
        val made = names.map { Phone(it) }
        phones += made
        // Everyone has a private session with the first phone (the admin); the others may not know each other.
        for (p in made.drop(1)) { made[0].know(p); p.know(made[0]) }
        return made
    }

    /**
     * Private bodies and group packets, until nothing moves. A packet [skip] refuses is not delivered: that is
     * the radio losing it. The clock does not move here, so a test can say exactly when a packet was lost.
     */
    private fun settle(skip: (Phone, Phone, GroupPost.Packet) -> Boolean = { _, _, _ -> false }) {
        repeat(60) {
            var moved = false
            for (p in phones) {
                val privates = p.store.private.toList(); p.store.private.clear()
                for ((to, body) in privates) {
                    val target = phones.single { it.account == to }
                    assertTrue("${target.name} takes ${body.kind}", target.post.takePrivate(p.account, body, now)); moved = true
                }
                val packets = p.store.packets.toList(); p.store.packets.clear()
                for (pk in packets) {
                    for (q in phones) if (q !== p && !skip(p, q, pk)) q.post.receive(pk.sealed.packet, now)
                    moved = true
                }
            }
            if (!moved) return
        }
    }

    private fun tick(who: Phone, at: Long = now) { now = at; assertTrue(who.post.tick(now)) }

    /**
     * Ama (admin), Kofi and Esi in one group; all three join and announce a box key. Esi is then no longer a
     * contact of Ama, so the next change reaches Esi in an envelope.
     */
    private fun group(): Pair<List<Phone>, ByteArray> {
        val ps = phones("Ama", "Kofi", "Esi")
        val g = ps[0].post.create("Puits", 0x2E7D32, Avatar.Letter, false, ps.drop(1).map { it.account }, now)!!
        settle()
        for (p in ps.drop(1)) {
            val invitation = p.store.invitations().single()
            assertTrue(p.post.join(invitation, now))
        }
        settle()
        repeat(2) { now += 61_000; for (p in ps) assertTrue(p.post.tick(now)); settle() }
        ps[0].contacts.remove(ps[2].account)
        return ps to g
    }

    /** The admin's change that reaches Esi in an envelope: the packets it queued, and when it made them. */
    private class Change(val packets: List<GroupPost.Packet>, val at: Long)

    private fun changeReachingEsi(ama: Phone, g: ByteArray): Change {
        ama.store.packets.clear()
        assertTrue(ama.post.restyle(g, "New market", 0x336699, Avatar.Letter, false, now))
        // The packets stay queued: the radio still has to deliver them.
        val packets = ama.store.packets.toList()
        assertEquals("one envelope for Esi", 1, packets.count { it.op == GroupEnvelope.OP })
        assertTrue("the list goes with it", packets.any { it.op == GroupBody.ROSTER })
        return Change(packets, now)
    }

    // ---- the admin airs the envelope again

    @Test fun anEnvelopeThatIsNotTakenIsAiredAgainWithTheListAfterTenMinutes() {
        val (ps, g) = group()
        val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        val envelope = change.packets.single { it.op == GroupEnvelope.OP }
        val roster = change.packets.filter { it.op == GroupBody.ROSTER }
        // The radio loses the envelope: Esi never gets the key, so it waits.
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        assertEquals("Esi did not take the epoch", 1L, esi.post.member(g)!!.epoch)

        // Ten minutes after the change the admin airs it again, with the list.
        now = change.at + 10 * 60_000L + 1
        assertTrue(ama.post.tick(now))
        val aired = ama.store.packets.toList()
        assertEquals("the envelope is aired again", 1, aired.count { it.op == GroupEnvelope.OP })
        val again = aired.single { it.op == GroupEnvelope.OP }
        assertArrayEquals("the same bytes on air again", envelope.sealed.packet, again.sealed.packet)
        assertEquals("the same chain position", envelope.sealed.seq, again.sealed.seq)
        assertEquals("on the epoch the member holds", envelope.sealed.epoch, again.sealed.epoch)
        assertFalse("radio traffic, not hub only", again.hubOnly)
        assertFalse("on the radio chain", again.sealed.hub)
        val list = aired.filter { it.op == GroupBody.ROSTER }
        assertEquals("the list again", roster.size, list.size)
        roster.forEachIndexed { i, r -> assertArrayEquals("the same list part", r.sealed.packet, list[i].sealed.packet) }
        assertTrue("the envelope goes before the list", aired.indexOf(again) < aired.indexOf(list[0]))

        // Esi now hears them: it takes the epoch that waited.
        settle()
        assertEquals("Esi took the epoch", 2L, esi.post.member(g)!!.epoch)
    }

    @Test fun theSecondAiringIsAtSixtyMinutesAndThenStops() {
        val (ps, g) = group()
        val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }

        // The first airing again is ten minutes after the change.
        now = change.at + 10 * 60_000L + 1
        assertTrue(ama.post.tick(now))
        assertEquals("the first airing again", 1, ama.store.packets.count { it.op == GroupEnvelope.OP })
        ama.store.packets.clear()

        // The second is an hour after the change.
        now = change.at + 60 * 60_000L + 1
        assertTrue(ama.post.tick(now))
        assertEquals("the second airing again", 1, ama.store.packets.count { it.op == GroupEnvelope.OP })
        ama.store.packets.clear()

        // And then the admin stops.
        now = change.at + 120 * 60_000L + 1
        assertTrue(ama.post.tick(now))
        assertTrue("no third airing", ama.store.packets.none { it.op == GroupEnvelope.OP })
    }

    @Test fun aMemberThatTookTheEpochIsNotAiredAgain() {
        val (ps, g) = group()
        val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        val sealedTo = ama.post.member(g)!!.boxKeyOf(esi.id)!!
        esi.store.commits.clear()
        // Esi hears the change: it takes the epoch and announces a new box key.
        settle()
        assertEquals("Esi took the epoch", 2L, esi.post.member(g)!!.epoch)
        now += 61_000
        assertTrue(esi.post.tick(now))
        settle()
        val announcement = esi.store.commits.flatMap { it.packets }.filter { it.op == GroupBoxKey.OP }
        assertEquals("one post-change announcement", 1, announcement.size)
        assertEquals("acknowledged in the issued epoch", 2L, announcement.single().sealed.epoch)
        val newKey = esi.post.member(g)!!.ownBoxKeys(now).first().public
        assertFalse("a genuine new key", newKey.contentEquals(sealedTo))
        assertArrayEquals("admin learned the new key", newKey, ama.post.member(g)!!.boxKeyOf(esi.id))

        // The admin does not air for a member that took the epoch.
        now = change.at + 10 * 60_000L + 1
        assertTrue(ama.post.tick(now))
        assertTrue("a taken epoch is not aired again", ama.store.packets.none { it.op == GroupEnvelope.OP })
    }

    @Test fun theNextAiringIsScheduled() {
        val (ps, g) = group()
        val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        // The first airing is ten minutes after the change.
        assertEquals("the first airing is ten minutes after the change", change.at + 10 * 60_000L,
            ama.post.member(g)!!.envelopeRetryDue)
        // The phone must wake up for the airing, not only when something else happens: with the admin's own
        // profile announcement done, the group's next work is that airing and nothing else.
        ama.store.packets.clear()
        assertTrue(ama.post.tick(change.at))
        assertEquals("the scheduler wakes for the airing", change.at + 10 * 60_000L, ama.post.nextDue())
    }

    @Test fun theAiringCountSurvivesARestart() {
        val (ps, g) = group()
        val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        now = change.at + 10 * 60_000L + 1
        assertTrue(ama.post.tick(now))
        ama.store.packets.clear()

        // The phone restarts: the state comes back from the store alone.
        val saved = ama.store.saved.getValue(g.toList())
        val reopened = GroupMember.decode(ama.id, saved.signer, { data -> ama.store.sign(g, data) }, g, saved.state)!!
        assertEquals("the second airing is still due", change.at + 60 * 60_000L, reopened.envelopeRetryDue)
        assertArrayEquals("the state is the state", saved.state, reopened.encode())
    }

    @Test fun aRefusedStoreKeepsTheAiring() {
        val (ps, g) = group()
        val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        val before = ama.store.saved.getValue(g.toList()).state
        now = change.at + 10 * 60_000L + 1
        ama.store.refuse = true
        try { assertFalse(ama.post.tick(now)) } finally { ama.store.refuse = false }
        assertTrue("nothing left the phone", ama.store.packets.isEmpty())
        assertArrayEquals("the state did not move", before, ama.store.saved.getValue(g.toList()).state)
        // The airing is still owed: the counter did not move either.
        assertTrue(ama.post.tick(now))
        assertEquals("the envelope is aired again", 1, ama.store.packets.count { it.op == GroupEnvelope.OP })
    }

    // ---- regression controls from the independent review

    @Test fun retryDeadlinesAreExactAndRepeatedTicksSpendNoExtraSlots() {
        val (ps, g) = group(); val ama = ps[0]
        val change = changeReachingEsi(ama, g)
        val member = ama.post.member(g)!!
        val original = change.packets.single { it.op == GroupEnvelope.OP }
        fun at(offset: Long, count: Int) {
            val out = member.dueRetries(change.at + offset)
            assertEquals("retry count at +$offset", count, out.count { it.op == GroupEnvelope.OP })
            if (count > 0) {
                val envelope = out.single { it.op == GroupEnvelope.OP }
                assertArrayEquals(original.sealed.packet, envelope.sealed.packet)
                assertEquals(original.sealed.seq, envelope.sealed.seq)
                assertEquals(original.sealed.epoch, envelope.sealed.epoch)
                assertFalse(envelope.sealed.hub)
                assertEquals(change.packets.count { it.op == GroupBody.ROSTER }, out.count { it.op == GroupBody.ROSTER })
            } else assertTrue("no orphan list", out.isEmpty())
        }
        at(599_999, 0); at(600_000, 1); at(600_000, 0); at(600_001, 0)
        at(3_599_999, 0); at(3_600_000, 1); at(3_600_000, 0); at(3_600_001, 0)
        at(7_200_000, 0)
        assertNull("the retry budget is exhausted", member.envelopeRetryDue)
    }

    @Test fun aLateWakeCollapsesBothMissedSlotsIntoOneUsefulRound() {
        val (ps, g) = group(); val ama = ps[0]
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === ps[2] && pk.op == GroupEnvelope.OP }
        assertTrue(ama.post.tick(change.at + 4_200_000))
        assertEquals("one late catch-up envelope", 1, ama.store.packets.count { it.op == GroupEnvelope.OP })
        assertEquals("one late catch-up list", change.packets.count { it.op == GroupBody.ROSTER },
            ama.store.packets.count { it.op == GroupBody.ROSTER })
        ama.store.packets.clear()
        assertTrue(ama.post.tick(change.at + 4_201_000))
        assertTrue("no adjacent duplicate burst", ama.store.packets.none { it.op == GroupEnvelope.OP || it.op == GroupBody.ROSTER })
        assertNull("no new late-wake budget", ama.post.member(g)!!.envelopeRetryDue)
    }

    @Test fun aDelayedPrecedingEpochAnnouncementDoesNotAcknowledgeTheIssuedEpoch() {
        val (ps, g) = group(); val (ama, _, esi) = ps
        val sealedTo = ama.post.member(g)!!.boxKeyOf(esi.id)!!
        changeReachingEsi(ama, g); settle()
        assertEquals(2L, esi.post.member(g)!!.epoch)
        esi.store.commits.clear()
        assertTrue(esi.post.tick(++now))
        val delayed = esi.store.packets.toList(); esi.store.packets.clear()
        val announcement = delayed.single { it.op == GroupBoxKey.OP }
        assertEquals("ack belongs to the preceding epoch", 2L, announcement.sealed.epoch)
        val rotated = esi.post.member(g)!!.ownBoxKeys(now).first().public
        assertFalse("real rotation, not the setup key", rotated.contentEquals(sealedTo))
        assertArrayEquals("admin has not heard it", sealedTo, ama.post.member(g)!!.boxKeyOf(esi.id))
        val change = changeReachingEsi(ama, g)
        val original = change.packets.single { it.op == GroupEnvelope.OP }
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        assertEquals("the second envelope was lost", 2L, esi.post.member(g)!!.epoch)
        assertTrue("delayed announcement really consumed", ama.post.receive(announcement.sealed.packet, now))
        assertArrayEquals("the delayed key is learned", rotated, ama.post.member(g)!!.boxKeyOf(esi.id))
        assertTrue(ama.post.tick(change.at + 600_000))
        val retry = ama.store.packets.singleOrNull { it.op == GroupEnvelope.OP }
        assertNotNull("preceding-epoch rotation cannot cancel this retry", retry)
        assertArrayEquals("still sealed to the original key, never resealed", original.sealed.packet, retry!!.sealed.packet)
        now = change.at + 600_000; settle()
        assertEquals("the previous private half opens the retained packet", 3L, esi.post.member(g)!!.epoch)
        assertTrue(esi.post.tick(++now)); settle()
        assertTrue("genuine issued-epoch announcement stops retries", ama.post.member(g)!!.dueRetries(change.at + 3_600_000).isEmpty())
        assertFalse("older announcement is now a replay", ama.post.receive(announcement.sealed.packet, now))
        assertNull("an older replay cannot revive retries", ama.post.member(g)!!.envelopeRetryDue)
    }

    @Test fun multipleRecipientsShareOneListAndAcknowledgementStopsOnlyOne() {
        val (ps, g) = group(); val (ama, kofi, esi) = ps
        ama.contacts.remove(kofi.account)
        assertTrue(ama.post.restyle(g, "Two wait", 0x336699, Avatar.Letter, false, now))
        val at = now
        val original = ama.store.packets.toList()
        assertEquals(2, original.count { it.op == GroupEnvelope.OP })
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        val first = ama.post.member(g)!!.dueRetries(at + 600_000)
        assertEquals("both unacknowledged recipients", 2, first.count { it.op == GroupEnvelope.OP })
        assertEquals("one complete list, not one per recipient", original.count { it.op == GroupBody.ROSTER },
            first.count { it.op == GroupBody.ROSTER })
        kofi.store.commits.clear()
        assertTrue(kofi.post.tick(++now)); settle()
        assertEquals("one new announcement", 1, kofi.store.commits.flatMap { it.packets }.count { it.op == GroupBoxKey.OP })
        assertEquals(1L, esi.post.member(g)!!.epoch)
        val remaining = ama.post.member(g)!!.dueRetries(at + 600_000)
        assertEquals("only the still-waiting recipient", 1, remaining.count { it.op == GroupEnvelope.OP })
        val packet = remaining.single { it.op == GroupEnvelope.OP }
        now = at + 600_000
        assertTrue("the remaining packet is actually for Esi", esi.post.receive(packet.sealed.packet, now))
        for (list in remaining.filter { it.op == GroupBody.ROSTER }) assertTrue(esi.post.receive(list.sealed.packet, now))
        assertEquals(2L, esi.post.member(g)!!.epoch)
    }

    @Test fun losingOnlyTheListIsRecoveredByTheCompleteRetry() {
        val (ps, g) = group(); val (ama, _, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === esi && pk.op == GroupBody.ROSTER }
        assertEquals("key acceptance is not epoch commit", 1L, esi.post.member(g)!!.epoch)
        assertNotNull("the envelope key is pending", esi.post.member(g)!!.waitingOn())
        assertFalse("pending is not acknowledged", esi.post.member(g)!!.needsBoxKey)
        now = change.at + 600_000
        assertTrue(ama.post.tick(now)); settle()
        assertEquals("the repeated list completes the pending epoch", 2L, esi.post.member(g)!!.epoch)
        assertTrue(esi.post.member(g)!!.needsBoxKey)
    }

    @Test fun thePostRestartSecondRetryActuallyDeliversTheEpoch() {
        val (ps, g) = group(); val (ama, _, esi) = ps
        val change = changeReachingEsi(ama, g)
        val original = change.packets.single { it.op == GroupEnvelope.OP }
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        assertTrue(ama.post.tick(change.at + 600_000)); ama.store.packets.clear()
        ama.restart(); esi.restart()
        now = change.at + 3_600_000
        assertTrue(ama.post.tick(now))
        assertEquals("one post-restart retry", 1, ama.store.packets.count { it.op == GroupEnvelope.OP })
        val packet = ama.store.packets.single { it.op == GroupEnvelope.OP }
        assertArrayEquals("the reopened sender retains the original ciphertext", original.sealed.packet, packet.sealed.packet)
        assertEquals(original.sealed.seq, packet.sealed.seq)
        settle()
        assertEquals("reopened receiver really takes it", 2L, esi.post.member(g)!!.epoch)
        assertNull("no extra round after restart", ama.post.member(g)!!.envelopeRetryDue)
    }

    @Test fun repeatsCreateNoMessageReceiptRotationOrSavedStateChange() {
        val (ps, g) = group(); val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g); settle()
        for (phone in listOf(kofi, esi)) {
            val saved = phone.store.saved.getValue(g.toList())
            val needs = phone.post.member(g)!!.needsBoxKey
            phone.store.events.clear(); phone.store.commits.clear()
            for (packet in change.packets) assertFalse("consumed packets are replay", phone.post.receive(packet.sealed.packet, now))
            assertTrue("no event of any kind", phone.store.events.isEmpty())
            assertTrue("no replay transaction", phone.store.commits.isEmpty())
            assertEquals("no new receipt", saved.receiptDue, phone.store.saved.getValue(g.toList()).receiptDue)
            assertEquals("no new rotation debt", needs, phone.post.member(g)!!.needsBoxKey)
            assertArrayEquals("replay leaves saved bytes unchanged", saved.state, phone.store.saved.getValue(g.toList()).state)
            val member = phone.post.member(g)!!
            assertTrue(member.receive(change.packets.first().sealed.packet, now).isEmpty())
            assertFalse("raw reader also rejects the consumed position", member.took)
        }
    }

    @Test fun aRetryAtTheInclusiveHorizonWorksButDirectUseAfterItExpiresTheWholeHolder() {
        val (ps, g) = group(); val (ama, kofi, _) = ps
        val change = changeReachingEsi(ama, g)
        val inside = ama.post.member(g)!!
        assertEquals("inclusive horizon still permits the final useful catch-up", 1,
            inside.dueRetries(change.at + GroupMember.KEEP_MS).count { it.op == GroupEnvelope.OP })
        val after = ama.post.member(g)!!
        assertTrue("direct use beyond horizon cannot air", after.dueRetries(change.at + GroupMember.KEEP_MS + 1).isEmpty())
        assertFalse("point-of-use expiry drops the entire holder, even when read back at zero",
            after.receiveControl(kofi.id, GroupControl.Ask(g, 2), 0).first)
        val atBoundary = ama.post.member(g)!!
        assertTrue("existing private Ask remains supported at the inclusive horizon",
            atBoundary.receiveControl(kofi.id, GroupControl.Ask(g, 2), change.at + GroupMember.KEEP_MS).first)
    }

    private fun idleExpiry(acknowledged: Boolean, refuse: Boolean) {
        val (ps, g) = group(); val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> !acknowledged && to === esi && pk.op == GroupEnvelope.OP }
        if (acknowledged) { assertTrue(esi.post.tick(++now)); settle() }
        else {
            assertTrue(ama.post.tick(change.at + 600_000)); ama.store.packets.clear()
            assertTrue(ama.post.tick(change.at + 3_600_000)); ama.store.packets.clear()
        }
        assertNull("no retries remain", ama.post.member(g)!!.envelopeRetryDue)
        val deadline = change.at + GroupMember.KEEP_MS + 1
        assertEquals("idle cleanup stays scheduled after ack/exhaustion", deadline, ama.post.nextDue())
        assertTrue(ama.post.tick(deadline - 1))
        assertTrue("whole holder remains inside the inclusive horizon",
            ama.post.member(g)!!.receiveControl(kofi.id, GroupControl.Ask(g, 2), 0).first)
        val before = ama.store.saved.getValue(g.toList()).state
        if (refuse) {
            ama.store.refuse = true
            try { assertFalse("cleanup commit refusal is observable", ama.post.tick(deadline)) }
            finally { ama.store.refuse = false }
            assertArrayEquals("refusal keeps the durable holder", before, ama.store.saved.getValue(g.toList()).state)
            assertEquals("refused cleanup remains owed", deadline, ama.post.nextDue())
        }
        assertTrue(ama.post.tick(deadline)); ama.restart()
        val reopened = ama.post.member(g)!!
        assertFalse("expiry is durable, not a use-time filter",
            reopened.receiveControl(kofi.id, GroupControl.Ask(g, 2), 0).first)
        assertNull("no idle maintenance loop after cleanup", ama.post.nextDue())
    }

    @Test fun exhaustedRetriesStillExpireDurablyWithoutTraffic() = idleExpiry(false, false)
    @Test fun acknowledgedRetriesStillExpireDurablyWithoutTraffic() = idleExpiry(true, false)
    @Test fun refusedIdleCleanupIsRetriedAndSurvivesReopen() = idleExpiry(false, true)

    @Test fun aNewerPendingEpochDisablesRetrySchedulingButNotMaintenance() {
        val (ps, g) = group(); val (ama, kofi, esi) = ps
        // Give Kofi a real producer path for a newer key.
        ama.know(esi)
        assertTrue(ama.post.makeAdmin(g, kofi.account, now)); settle()
        ama.contacts.remove(esi.account)
        val change = changeReachingEsi(ama, g); settle()
        assertTrue(kofi.post.restyle(g, "Pending rival", 0x336699, Avatar.Letter, false, ++now))
        val key = kofi.store.private.single { it.first == ama.account && it.second.kind == GroupControl.KEY }
        assertTrue(ama.post.takePrivate(kofi.account, key.second, now))
        val member = ama.post.member(g)!!
        assertFalse("newer pending key disables sends", member.canSend)
        assertNull("do not advertise an unproductive overdue retry", member.envelopeRetryDue)
        assertTrue("direct callers cannot bypass disabled-send admission", member.dueRetries(change.at + 600_000).isEmpty())
        val due = ama.post.nextDue()
        assertNotNull("whole-holder maintenance remains reachable", due)
        assertTrue("no one-second stale-retry loop", due!! > change.at + 600_000)
        assertTrue(due <= change.at + GroupMember.KEEP_MS + 1)
    }

    @Test fun replacingTheIssuedEpochRetiresItsOldRetryObligations() {
        val (ps, g) = group(); val (ama, _, esi) = ps
        val first = changeReachingEsi(ama, g); settle()
        assertTrue(esi.post.tick(++now)); settle()
        now += 1_000
        val second = changeReachingEsi(ama, g)
        val member = ama.post.member(g)!!
        assertTrue("older deadline is not inherited", member.dueRetries(first.at + 600_000).isEmpty())
        val out = member.dueRetries(second.at + 600_000)
        assertEquals("new obligation only", 1, out.count { it.op == GroupEnvelope.OP })
        assertArrayEquals(second.packets.single { it.op == GroupEnvelope.OP }.sealed.packet,
            out.single { it.op == GroupEnvelope.OP }.sealed.packet)
    }

    @Test fun impossibleRetainedBlocksAreRefusedBesideValidPositiveControls() {
        val (ps, g) = group(); val (ama, kofi, esi) = ps
        changeReachingEsi(ama, g)
        val member = ama.post.member(g)!!
        val bytes = member.encode()
        val start = member.encode(ext = 3).size
        assertTrue("a full retention entry exists", bytes.size > start + 1)
        assertEquals("one entry in the positive control", 1, bytes[start].toInt())
        val prefix = bytes.copyOfRange(0, start)
        val entry = bytes.copyOfRange(start + 1, bytes.size)
        val length = java.nio.ByteBuffer.wrap(entry, 66, 2).short.toInt() and 0xFFFF
        val ep = 68 + length
        fun load(b: ByteArray) = GroupMember.decode(ama.id, member.mySigner, { ama.store.sign(g, it) }, g, b)
        fun one(e: ByteArray) = prefix + byteArrayOf(1) + e
        fun refuse(label: String, bad: ByteArray) {
            assertNotNull("valid retention before $label", load(bytes))
            assertArrayEquals("positive control round-trips before $label", bytes, load(bytes)!!.encode())
            assertNull(label, load(bad))
        }
        fun u32(e: ByteArray, offset: Int, n: Long) = e.copyOf().also {
            java.nio.ByteBuffer.wrap(it, offset, 4).putInt(n.toInt())
        }
        fun withPacket(packet: ByteArray) = entry.copyOfRange(0, 66) +
            java.nio.ByteBuffer.allocate(2).putShort(packet.size.toShort()).array() + packet + entry.copyOfRange(ep, entry.size)
        refuse("empty packet", one(withPacket(byteArrayOf())))
        refuse("short packet", one(withPacket(byteArrayOf(2, 0, 0))))
        refuse("oversized packet", one(withPacket(ByteArray(229))))
        refuse("invalid packet framing", one(entry.copyOf().also { it[68] = 0 }))
        refuse("invalid recipient point", one(entry.copyOf().also { it[0] = 0 }))
        refuse("invalid sealed-to point", one(entry.copyOf().also { it[33] = 0 }))
        refuse("wrong held epoch", one(u32(entry, ep, member.epoch)))
        refuse("exhausted sequence", one(u32(entry, ep + 4, 0xFFFF_FFFFL)))
        refuse("self recipient", one(entry.copyOf().also { ama.id.copyInto(it) }))
        refuse("nonmember recipient", one(entry.copyOf().also { pub(P256.generate()).copyInto(it) }))
        val otherPosition = u32(entry, ep + 4, 17)
        refuse("duplicate recipient", prefix + byteArrayOf(2) + entry + otherPosition)
        val otherRecipient = entry.copyOf().also {
            kofi.id.copyInto(it); member.boxKeyOf(kofi.id)!!.copyInto(it, 33)
        }
        refuse("duplicate position", prefix + byteArrayOf(2) + entry + otherRecipient)
        refuse("inconsistent issued holder", bytes.copyOf().also {
            java.nio.ByteBuffer.wrap(it, 2, 4).putInt((member.epoch + 1).toInt())
        })
        // Two distinct members/positions with complete framing are valid structural input. Framing is not proof
        // of the encrypted opcode or the packet-to-recipient binding, which belongs to the authenticated producer.
        val validTwo = prefix + byteArrayOf(2) + entry + u32(otherRecipient, ep + 4, 17)
        assertNotNull("valid count-two structural positive control", load(validTwo))
    }

    @Test fun indexingPersistsWholeIssuedExpiryAndARefusedSaveKeepsMaintenanceOwed() {
        val (ps, g) = group(); val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        val deadline = change.at + GroupMember.KEEP_MS + 1
        val before = ama.store.saved.getValue(g.toList()).state
        ama.store.refuse = true
        try { ama.post.expectedCodes(deadline) } finally { ama.store.refuse = false }
        assertArrayEquals("refused index cleanup is not durable", before, ama.store.saved.getValue(g.toList()).state)
        assertTrue("refused cleanup remains scheduled", ama.post.nextDue()!! <= deadline)
        ama.post.expectedCodes(deadline)
        ama.restart()
        assertFalse("index cleanup must persist the whole holder, not only prune a transient reader",
            ama.post.member(g)!!.receiveControl(kofi.id, GroupControl.Ask(g, 2), 0).first)
    }

    @Test fun conflictingAdminSuppressesRetryButStillSchedulesWholeHolderExpiry() {
        val (ps, g) = group(); val (ama, kofi, esi) = ps
        ama.know(esi)
        assertTrue(ama.post.makeAdmin(g, kofi.account, now)); settle()
        ama.contacts.remove(esi.account)
        val change = changeReachingEsi(ama, g)
        assertTrue(kofi.post.restyle(g, "Other list", 0x336699, Avatar.Letter, false, now))
        val key = kofi.store.private.single { it.first == ama.account && it.second.kind == GroupControl.KEY }
        assertTrue(ama.post.takePrivate(kofi.account, key.second, now))
        for (packet in kofi.store.packets.filter { it.op == GroupBody.ROSTER }) assertTrue(ama.post.receive(packet.sealed.packet, now))
        val member = ama.post.member(g)!!
        assertEquals(GroupMember.State.CONFLICT, member.state)
        assertNull("conflict must not advertise a stale retry", member.envelopeRetryDue)
        assertTrue("direct conflict retry is disabled", member.dueRetries(change.at + 600_000).isEmpty())
        val due = ama.post.nextDue()
        assertNotNull("conflict still has maintenance", due)
        assertTrue(due!! > change.at + 600_000 && due <= change.at + GroupMember.KEEP_MS + 1)
    }

    @Test fun identicalRetryCannotBypassTheReaderWindowButEarlierPacketsCanCatchItUp() {
        val (ps, g) = group(); val (ama, _, esi) = ps
        val earlier = ArrayList<GroupPost.Packet>()
        repeat(GroupReader.WINDOW) {
            earlier += ama.post.send(g, listOf(byteArrayOf(1) to false), null, now)!!
        }
        ama.store.packets.clear()
        val change = changeReachingEsi(ama, g)
        val envelope = change.packets.single { it.op == GroupEnvelope.OP }
        assertFalse("original beyond the reading window", esi.post.receive(envelope.sealed.packet, now))
        val retry = ama.post.member(g)!!.dueRetries(change.at + 600_000)
        assertEquals(1, retry.count { it.op == GroupEnvelope.OP })
        val repeated = retry.single { it.op == GroupEnvelope.OP }
        assertArrayEquals(envelope.sealed.packet, repeated.sealed.packet)
        assertFalse("identical bytes do not enlarge the reading window", esi.post.receive(repeated.sealed.packet, now))
        for (packet in earlier) assertTrue(esi.post.receive(packet.sealed.packet, now))
        for (packet in retry) assertTrue(esi.post.receive(packet.sealed.packet, now))
        assertEquals("earlier authentic positions allow bounded catch-up", 2L, esi.post.member(g)!!.epoch)
    }

    // ---- the saved state

    @Test fun oldExtensionsLoadWithoutTheKeptEnvelopes() {
        val (ps, g) = group()
        val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        val member = ama.post.member(g)!!
        val saved = ama.store.saved.getValue(g.toList())
        for (ext in 1..3) {
            val loaded = GroupMember.decode(ama.id, saved.signer, { data -> ama.store.sign(g, data) }, g, member.encode(ext = ext))
            assertNotNull("extension $ext still loads", loaded)
            assertNull("extension $ext keeps no envelope", loaded!!.envelopeRetryDue)
            assertTrue("extension $ext airs nothing at the retry deadline", loaded.dueRetries(change.at + 600_000L).isEmpty())
        }
    }

    @Test fun aMalformedKeptEnvelopeIsRefused() {
        val (ps, g) = group()
        val (ama, kofi, esi) = ps
        val change = changeReachingEsi(ama, g)
        settle { _, to, pk -> to === esi && pk.op == GroupEnvelope.OP }
        val saved = ama.store.saved.getValue(g.toList())
        val member = ama.post.member(g)!!
        val bytes = member.encode()
        // The tail of the state is the kept envelopes: it starts where the older extension ends.
        val tail = bytes.size - member.encode(ext = 3).size
        assertTrue("the extension grew", tail > 1)
        fun load(b: ByteArray) = GroupMember.decode(ama.id, saved.signer, { data -> ama.store.sign(g, data) }, g, b)
        for (n in bytes.size - tail until bytes.size) {
            assertNull("a truncation at $n", load(bytes.copyOf(n)))
        }
        assertNull("trailing data", load(bytes + byteArrayOf(0)))
        assertNull("too many kept envelopes", load(bytes.copyOf().also { it[bytes.size - tail] = 0xFF.toByte() }))
        assertNull("an envelope on a hub chain", load(bytes.copyOf().also { it[bytes.lastIndex - 1] = 1 }))
        assertNull("too many airings", load(bytes.copyOf().also { it[bytes.lastIndex] = 3 }))
    }
}

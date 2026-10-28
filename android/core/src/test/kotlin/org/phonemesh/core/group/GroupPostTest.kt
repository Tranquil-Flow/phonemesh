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
import kotlin.random.Random

/** The group engine (draft GK4) with phones made of maps, a lossy shared radio and a hub that keeps everything. */
class GroupPostTest {
    private fun pub(k: KeyPair) = P256.compress(k.public as ECPublicKey)

    /** A phone's group storage in maps: what [GroupStore.commit] got, kept for the test to deliver. */
    private class MapStore(val phone: Phone) : GroupStore {
        val saved = LinkedHashMap<List<Byte>, GroupStore.Saved>()
        val invites = LinkedHashMap<List<Byte>, GroupStore.Invitation>()
        val keys = HashMap<List<Byte>, PrivateKey>()
        val private = ArrayList<Pair<AccountId, Body.Group>>()
        val packets = ArrayList<GroupPost.Packet>()
        val events = ArrayList<GroupPost.Event>()
        var refuse = false
        var recentItems: List<GroupShared> = emptyList()
        var groupPhoto: List<ByteArray> = emptyList()
        override fun groups() = saved.keys.map { it.toByteArray() }
        override fun saved(group: ByteArray) = saved[group.toList()]
        override fun invitations() = invites.values.toList()
        override fun newSigner(group: ByteArray): ByteArray { val k = P256.generate(); keys[group.toList()] = k.private; return P256.compress(k.public as ECPublicKey) }
        override fun sign(group: ByteArray, data: ByteArray) = P256.sign(keys.getValue(group.toList()), data)
        override fun identityOf(account: AccountId) = phone.contacts[account]
        override fun myProfile() = phone.profile
        override fun myPhotoPieces(): List<ByteArray> = phone.photoPieces
        override fun groupPhotoPieces(group: ByteArray) = groupPhoto
        override fun recent(group: ByteArray, sinceMs: Long, max: Int) = recentItems.take(max)
        override fun commit(change: GroupPost.Change): Boolean {
            if (refuse) return false
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
        fun messages(group: ByteArray) = store.events.filterIsInstance<GroupPost.Event.Message>().filter { it.group.contentEquals(group) }
        fun texts(group: ByteArray) = messages(group).map { String(it.body) }
        fun state(group: ByteArray) = post.member(group)?.state
    }

    private val phones = ArrayList<Phone>()
    private fun phones(vararg names: String): List<Phone> {
        val made = names.map { Phone(it) }
        phones += made
        // Everyone has a private session with the first phone (the admin); the others may not know each other.
        for (p in made.drop(1)) { made[0].know(p); p.know(made[0]) }
        return made
    }

    /** The shared radio: a packet reaches each other phone unless lost; the hub keeps every packet it is given. */
    private val hub = ArrayList<ByteArray>()
    private var loss = 0.0
    private val rnd = Random(7)
    private var now = 1_000_000L

    /** Private bodies (reliable, like the Postman with retries) and group packets, until nothing moves. */
    private fun settle(reachHub: Set<Phone> = phones.toSet(), radio: Set<Phone> = phones.toSet()) {
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
                    if (p in reachHub) hub += pk.sealed.packet
                    if (pk.hubOnly || p !in radio) continue
                    for (q in phones) if (q !== p && q in radio && rnd.nextDouble() >= loss) q.post.receive(pk.sealed.packet, now)
                    moved = true
                }
            }
            now += 1_000
            if (!moved) return
        }
    }

    /** Phones in [who] fetch everything the hub has, as often as reading moves their windows. */
    private fun fetch(who: Collection<Phone>) = repeat(6) { for (p in who) for (pk in hub.toList()) p.post.receive(pk, now) }

    private fun tickAll(after: Long = 61_000) { now += after; for (p in phones) assertTrue(p.post.tick(now)); settle() }

    /** Ama makes "Puits" with the others; they all join. */
    private fun group(vararg names: String, recent: Boolean = false): Pair<List<Phone>, ByteArray> {
        val ps = phones(*names)
        val g = ps[0].post.create("Puits", 0x2E7D32, Avatar.Letter, recent, ps.drop(1).map { it.account }, now)!!
        settle()
        for (p in ps.drop(1)) {
            val invitation = p.store.invitations().single()
            assertEquals("Puits", invitation.invite.name)
            assertEquals(ps.size, invitation.invite.people)
            assertTrue(p.post.join(invitation, now))
        }
        settle()
        tickAll() // the admin's epoch with everyone who joined
        tickAll() // everyone's name and picture to the group
        return ps to g
    }

    @Test fun peopleJoinTalkAndSeeEachOthersNames() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        for (p in ps) assertEquals(p.name, GroupMember.State.ACTIVE, p.state(g))
        assertEquals(3, ama.post.member(g)!!.roster!!.members.size)
        // Kofi and Esi are not each other's contacts: each learns the other's name from the group (decision 72 b).
        assertTrue(kofi.store.events.filterIsInstance<GroupPost.Event.ProfileOf>().any { it.member == esi.account && it.profile.name == "Esi" })
        val sent = kofi.post.send(g, listOf(Body.encode(Body.Text("fr", "à midi")) to false), 42, now)!!.single()
        assertEquals(2, sent.audience)
        assertEquals(42L, sent.messageId)
        settle()
        for (p in listOf(ama, esi)) assertEquals("à midi", (Body.decode(p.messages(g).last().body) as Body.Text).text)
        tickAll()
        val delivered = kofi.store.events.filterIsInstance<GroupPost.Event.Delivered>().filter { it.entry.covers(sent.sealed.seq) && it.epoch == sent.sealed.epoch }
        assertEquals("Delivered to 2 of 2", setOf(ama.account, esi.account), delivered.map { it.member }.toSet())
        assertTrue(ama.post.member(g)!!.safetyCode().contentEquals(esi.post.member(g)!!.safetyCode()))
    }

    @Test fun aReadMessageCarriesItsOnAirCodeSoRepliesMatchTheSendersBubble() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val sent = ama.post.send(g, listOf(Body.encode(Body.Text("en-US", "hello")) to false), 7L, now)!!.single()
        settle()
        val got = kofi.messages(g).last()
        assertArrayEquals(sent.sealed.packet.copyOfRange(1, 9), got.code)
    }

    @Test fun aGroupPictureIsItsHeadByRadioAndItsPartsOnlyThroughAHub() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val picture = ByteArray(300) { (it * 5).toByte() }
        val packets = org.phonemesh.core.PictureWire.splitGroup(5, org.phonemesh.core.PictureCodec.WEBP, 4, 3, picture, byteArrayOf(7, 7))
        assertEquals("300 bytes in parts of 133", 4, packets.size)
        // Cut for a group, the head counts its parts by 133; a person chat's reader refuses it.
        assertNull(org.phonemesh.core.PictureWire.decodeV2(org.phonemesh.core.PictureWire.encode(packets[0])))
        val bodies = packets.map { pk ->
            val b = org.phonemesh.core.PictureWire.encode(pk)
            Body.encode(if (pk is org.phonemesh.core.PictureWire.Head) Body.PictureHead(b) else Body.PicturePart(b)) to (pk !is org.phonemesh.core.PictureWire.Head)
        }
        val sent = ama.post.send(g, bodies, 9L, now)!!
        assertEquals(listOf(false, true, true, true), sent.map { it.hubOnly })
        settle()
        val byRadio = kofi.messages(g).map { Body.decode(it.body) }
        assertEquals("only the head (with its sketch) by radio", 1, byRadio.count { it is Body.PictureHead })
        assertEquals(0, byRadio.count { it is Body.PicturePart })
        fetch(listOf(kofi))
        val pieces = kofi.messages(g).mapNotNull { Body.decode(it.body) }
        val parts = pieces.filterIsInstance<Body.PicturePart>().map { org.phonemesh.core.PictureWire.decodeGroup(it.bytes) as org.phonemesh.core.PictureWire.Part }
        assertEquals(listOf(0, 1, 2), parts.map { it.index })
        assertArrayEquals(picture, parts.sortedBy { it.index }.fold(ByteArray(0)) { a, part -> a + part.data })
    }

    @Test fun aGroupNobodyJoinedYetHasNoListAndCannotSend() {
        val ps = phones("Ama", "Kofi")
        val g = ps[0].post.create("Puits", 0x2E7D32, Avatar.Emoji("\uD83C\uDF3D"), false, listOf(ps[1].account), now)!!
        assertEquals("Puits", ps[0].post.looks(g)!!.name)
        assertNull(ps[0].post.send(g, listOf(byteArrayOf(1, 2) to false), null, now))
        settle()
        assertTrue("Ignore sends nothing", ps[1].post.ignore(ps[1].store.invitations().single()))
        settle()
        assertTrue(ps[0].store.private.isEmpty() && ps[1].store.invitations().isEmpty())
        assertNull(ps[0].post.member(g)!!.roster)
        assertTrue("the maker can drop it", ps[0].post.leave(g, null, now))
        assertNull(ps[0].post.member(g))
    }

    @Test fun twentyPeopleOnALossyRadioCatchUpThroughTheHub() {
        val names = (0 until GroupRoster.MAX_MEMBERS).map { "p$it" }.toTypedArray()
        val (ps, g) = group(*names)
        for (p in ps) assertEquals(p.name, GroupMember.State.ACTIVE, p.state(g))
        loss = 0.3
        val sender = ps[3]
        val radioOnly = ps[5]
        repeat(10) { i ->
            // A picture: its head by radio, its parts only through the hub (decision 69); then a text.
            sender.post.send(g, listOf(byteArrayOf(4, i.toByte()) to false) + List(25) { byteArrayOf(5, it.toByte()) to true }, null, now)
            sender.post.send(g, listOf(Body.encode(Body.Text("en-US", "m$i")) to false), null, now)
            settle()
        }
        loss = 0.0
        val heard = radioOnly.messages(g).filter { !it.hub }.count { it.sender == sender.account }
        assertTrue("a radio-only phone keeps reading the radio chain past hub-only parts ($heard)", heard >= 8)
        fetch(ps.filter { it !== radioOnly })
        for (p in ps.filter { it !== sender && it !== radioOnly }) {
            val got = p.messages(g).filter { it.sender == sender.account }
            assertEquals(p.name, 20, got.count { !it.hub })
            assertEquals(p.name, 250, got.count { it.hub })
        }
    }

    @Test fun aMemberWhoLeavesIsDroppedByTheAdmin() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        val epoch = kofi.post.member(g)!!.epoch
        assertTrue(esi.post.leave(g, null, now))
        assertEquals(GroupMember.State.LEFT, esi.state(g))
        settle(); tickAll()
        assertEquals(epoch + 1, kofi.post.member(g)!!.epoch)
        assertNull(kofi.post.member(g)!!.roster!!.member(esi.id))
        assertTrue(kofi.store.events.filterIsInstance<GroupPost.Event.ListChanged>().last().left.contains(esi.account))
        assertNull("she cannot send", esi.post.send(g, listOf(byteArrayOf(1, 1) to false), null, now))
        assertTrue(esi.post.forget(g))
        assertNull(esi.post.member(g))
        assertEquals(GroupMember.State.ACTIVE, ama.state(g))
    }

    @Test fun theLastAdminChoosesTheNextAdminAndLeaves() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        assertFalse("the last admin must choose", ama.post.leave(g, null, now))
        assertTrue(ama.post.leave(g, kofi.account, now))
        settle(); tickAll()
        val list = esi.post.member(g)!!.roster!!
        assertTrue(list.isAdmin(kofi.id))
        assertNull(list.member(ama.id))
        assertNotNull(kofi.post.send(g, listOf(Body.encode(Body.Text("en-US", "ok")) to false), null, now))
        settle()
        assertEquals("ok", (Body.decode(esi.messages(g).last().body) as Body.Text).text)
    }

    @Test fun newPeopleGetRecentMessagesOnlyWhenTheSettingIsOn() {
        val (ps, g) = group("Ama", "Kofi", recent = true)
        val (ama, kofi) = ps
        val yaw = Phone("Yaw"); phones += yaw; ama.know(yaw); yaw.know(ama)
        ama.store.recentItems = listOf(GroupShared(kofi.account.toBytes(), 1_790_000_000, Body.encode(Body.Text("en-US", "prices"))))
        assertTrue(ama.post.invite(g, listOf(yaw.account), now))
        settle()
        assertTrue(yaw.post.join(yaw.store.invitations().single(), now))
        settle(); tickAll()
        // By radio alone nothing is shared; through the hub Yaw gets it, shown as Ama's share; Kofi shows nothing new.
        assertTrue(yaw.messages(g).none { it.shared != null })
        fetch(listOf(yaw, kofi))
        val shared = yaw.messages(g).single { it.shared != null }
        assertEquals(ama.account, shared.sender)
        assertArrayEquals(kofi.account.toBytes(), shared.shared!!.author)
        assertTrue(kofi.messages(g).none { it.shared != null })
        // A member who is not an admin cannot share recent messages.
        val before = yaw.messages(g).size
        val saved = kofi.store.saved(g)!!
        val o = GroupMember.decode(kofi.id, saved.signer, { kofi.store.sign(g, it) }, g, saved.state)!!
        assertFalse(o.roster!!.isAdmin(kofi.id))
        val fake = GroupShared(kofi.account.toBytes(), 1_790_000_100, Body.encode(Body.Text("en-US", "fake")))
        yaw.post.receive(o.seal(GroupBody.SHARED, fake.encode(), hub = true).packet, now)
        assertEquals(before, yaw.messages(g).size)
        // Even an admin's share counts only from her hub chain: one on the radio chain is not shown as shared.
        val amaSaved = ama.store.saved(g)!!
        val a = GroupMember.decode(ama.id, amaSaved.signer, { ama.store.sign(g, it) }, g, amaSaved.state)!!
        yaw.post.receive(a.seal(GroupBody.SHARED, fake.encode(), hub = false).packet, now)
        assertTrue(yaw.messages(g).none { it.shared != null && String(it.shared!!.body).contains("fake") })
    }

    @Test fun aLeavingThatReachedAPhoneBeforeItBecameAdminIsActedOn() {
        val (ps, g) = group("Ama", "Kofi", "Esi", "Yaw")
        val (ama, kofi, esi, yaw) = ps
        esi.know(kofi); kofi.know(esi, yaw); yaw.know(kofi)
        val epoch = yaw.post.member(g)!!.epoch
        // Ama hands over to Kofi and leaves; Kofi's radio misses the new list (it waits on the hub).
        assertTrue(ama.post.leave(g, kofi.account, now))
        settle(radio = phones.toSet() - kofi)
        assertFalse(kofi.post.member(g)!!.roster!!.isAdmin(kofi.id))
        // Esi holds the new list, so her leaving goes to Kofi, who is no admin on the list he holds yet.
        assertTrue(esi.post.leave(g, null, now))
        settle(radio = phones.toSet() - kofi)
        // The list reaches Kofi through the hub: now an admin, he drops Esi in his next epoch.
        fetch(listOf(kofi))
        assertTrue(kofi.post.member(g)!!.roster!!.isAdmin(kofi.id))
        fetch(listOf(kofi, yaw)); tickAll(); fetch(listOf(kofi, yaw)); tickAll()
        val list = yaw.post.member(g)!!.roster!!
        assertNull(list.member(esi.id))
        assertTrue(yaw.post.member(g)!!.epoch >= epoch + 2)
    }

    @Test fun aLeavingGoesToAnAdminTheLeaverKnows() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        assertTrue(ama.post.makeAdmin(g, kofi.account, now)); settle(); tickAll()
        // Esi no longer has Ama as a contact, but knows Kofi: her notice goes to him, not to the first admin listed.
        esi.contacts.remove(ama.account); esi.know(kofi); kofi.know(esi)
        assertTrue(esi.post.leave(g, null, now))
        assertEquals(listOf(kofi.account), esi.store.private.map { it.first })
    }

    @Test fun anAdminChangesTheGroupOnlyWhenEveryMemberIsAContact() {
        val (ps, g) = group("Ama", "Kofi", "Esi", "Yaw")
        val (ama, kofi, esi, yaw) = ps
        assertTrue(ama.post.makeAdmin(g, kofi.account, now)); settle(); tickAll()
        // Keys go privately: Kofi knows neither Esi nor Yaw, so he cannot start an epoch for them.
        assertFalse(kofi.post.remove(g, esi.account, now))
        kofi.know(yaw); yaw.know(kofi)
        assertTrue(kofi.post.remove(g, esi.account, now))
        settle()
        assertNull(yaw.post.member(g)!!.roster!!.member(esi.id))
    }

    @Test fun aListThatNeverCameIsAskedForOnce() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        assertTrue(ama.post.restyle(g, "Puits 2", 0x2E7D32, Avatar.Letter, false, now))
        // Esi's radio misses the new list; there is no hub.
        settle(reachHub = emptySet(), radio = setOf(ama, kofi))
        assertEquals("Puits", esi.post.member(g)!!.roster!!.name)
        assertNull("sending waits", esi.post.send(g, listOf(byteArrayOf(1, 1) to false), null, now))
        now += GroupPost.ASK_AFTER_MS + 1
        assertTrue(esi.post.tick(now))
        settle()
        assertEquals("asked once, the list came", "Puits 2", esi.post.member(g)!!.roster!!.name)
        assertNotNull(esi.post.send(g, listOf(byteArrayOf(1, 1) to false), null, now))
        assertTrue(kofi.post.member(g)!!.canSend)
    }

    @Test fun aPhotoPictureGoesOnlyThroughAHub() {
        val ps = phones("Ama", "Kofi")
        ps[1].profile = Profile("Kofi", 0x336699, Avatar.Photo(ByteArray(8) { 1 }))
        ps[1].photoPieces = listOf(byteArrayOf(7, 0), byteArrayOf(7, 1))
        val g = ps[0].post.create("Puits", 0x2E7D32, Avatar.Letter, false, listOf(ps[1].account), now)!!
        settle()
        assertTrue(ps[1].post.join(ps[1].store.invitations().single(), now))
        settle(); tickAll(); tickAll()
        assertTrue(ps[0].store.events.filterIsInstance<GroupPost.Event.Photo>().isEmpty())
        fetch(listOf(ps[0]))
        assertEquals(2, ps[0].store.events.filterIsInstance<GroupPost.Event.Photo>().count { !it.ofGroup && it.sender == ps[1].account && it.group.contentEquals(g) })
    }

    @Test fun invitationsAreCappedAndAgainForAKnownGroupIsDropped() {
        val ama = Phone("Ama"); val kofi = Phone("Kofi"); phones += listOf(ama, kofi); ama.know(kofi); kofi.know(ama)
        repeat(GroupPost.MAX_INVITATIONS + 2) { ama.post.create("G$it", 0, Avatar.Letter, false, listOf(kofi.account), now) }
        settle()
        assertEquals(GroupPost.MAX_INVITATIONS, kofi.store.invitations().size)
        val first = kofi.store.invitations().first()
        assertTrue(kofi.post.join(first, now))
        assertTrue("an invitation to a group already held is taken and dropped",
            kofi.post.takePrivate(ama.account, Body.Group(GroupControl.INVITE, first.invite.encode()), now))
        assertEquals(GroupPost.MAX_INVITATIONS - 1, kofi.store.invitations().size)
    }

    @Test fun twoAdminsChangingAtOnceAreFixedByOne() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        kofi.know(esi); esi.know(kofi)
        assertTrue(ama.post.makeAdmin(g, kofi.account, now)); settle(); tickAll()
        assertFalse("nothing to fix while the group agrees", ama.post.fix(g, now))
        assertTrue(ama.post.restyle(g, "Puits A", 1, Avatar.Letter, false, now))
        assertTrue(kofi.post.restyle(g, "Puits K", 2, Avatar.Letter, false, now))
        settle()
        assertEquals(GroupMember.State.CONFLICT, esi.state(g))
        assertNull(esi.post.send(g, listOf(byteArrayOf(1, 1) to false), null, now))
        assertFalse("a member cannot fix it", esi.post.fix(g, now))
        assertTrue(ama.post.fix(g, now))
        settle(); tickAll()
        for (p in ps) assertEquals(p.name, GroupMember.State.ACTIVE, p.state(g))
    }

    @Test fun privateBodiesAreTakenTwiceAndStrangersRefused() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val stranger = Phone("x")
        assertFalse("not a contact", kofi.post.takePrivate(stranger.account, Body.Group(GroupControl.ASK, GroupControl.Ask(g, 1).encode()), now))
        assertTrue("a key for a group this phone does not hold is dropped",
            kofi.post.takePrivate(ama.account, Body.Group(GroupControl.KEY, GroupKey(ByteArray(16), 1, ByteArray(32), ByteArray(16), ByteArray(16), 1, ama.id).encode()), now))
        assertTrue(ama.post.remove(g, kofi.account, now) == false)
        // A crash after commit sends the same private bodies again: taken twice, no harm.
        assertTrue(ama.post.invite(g, listOf(Phone("y").also { phones += it; ama.know(it); it.know(ama) }.account), now))
        val again = ama.store.private.toList()
        settle()
        for ((to, body) in again) phones.single { it.account == to }.post.takePrivate(ama.account, body, now)
        assertEquals(1, phones.last().store.invitations().size)
    }

    @Test fun aRefusedCommitLeavesNothingBehind() {
        val (ps, g) = group("Ama", "Kofi")
        val kofi = ps[1]
        val before = kofi.store.saved(g)!!.state
        kofi.store.refuse = true
        assertNull(kofi.post.send(g, listOf(byteArrayOf(1, 1) to false), null, now))
        assertArrayEquals("no chain position was used", before, kofi.store.saved(g)!!.state)
    }

    @Test fun theGroupPhotoSketchGoesByRadioAndTheRestThroughAHub() {
        val (ps, g) = group("Ama", "Kofi")
        val ama = ps[0]
        ama.store.groupPhoto = listOf(byteArrayOf(9, 0), byteArrayOf(9, 1), byteArrayOf(9, 2))
        assertTrue(ama.post.restyle(g, "Puits", 0x2E7D32, Avatar.Photo(ByteArray(8) { 3 }), false, now))
        val photo = ama.store.packets.filter { !it.sealed.packet.isEmpty() }.takeLast(3)
        assertEquals(listOf(false, true, true), photo.map { it.hubOnly })
        settle()
        val kofi = ps[1]
        assertEquals("the sketch by radio", 1, kofi.store.events.filterIsInstance<GroupPost.Event.Photo>().count { it.ofGroup })
        fetch(listOf(kofi))
        assertEquals(3, kofi.store.events.filterIsInstance<GroupPost.Event.Photo>().count { it.ofGroup })
        // A member who is no admin cannot set the group photo: their piece is not taken as one.
        val saved = kofi.store.saved(g)!!
        val o = GroupMember.decode(kofi.id, saved.signer, { kofi.store.sign(g, it) }, g, saved.state)!!
        ama.post.receive(o.seal(GroupBody.GROUP_PHOTO, byteArrayOf(9, 9), hub = true).packet, now)
        assertEquals(0, ama.store.events.filterIsInstance<GroupPost.Event.Photo>().count { it.ofGroup })
    }
}

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
        /** How many times the engine opened a saved group: what a stranger's packet must not cost (review G9-6). */
        var savedCalls = 0
        /** Every packet any change ever held, kept after [packets] is drained for delivery. */
        val everSent = ArrayList<GroupPost.Packet>()
        var recentItems: List<GroupShared> = emptyList()
        var groupPhoto: List<ByteArray> = emptyList()
        override fun groups() = saved.keys.map { it.toByteArray() }
        override fun saved(group: ByteArray): GroupStore.Saved? { savedCalls++; return saved[group.toList()] }
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
            private += change.private; packets += change.packets; everSent += change.packets; events += change.events
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

    /** Real photo pieces cut for a group packet (head first): the engine reads nothing else (review G9-3). */
    private fun groupPhotoPieces(bytes: Int, seed: Int) =
        org.phonemesh.core.account.AvatarPhotos.pieces(ByteArray(bytes) { (it * seed + 1).toByte() }, 64, 64, ByteArray(8) { 1 }, group = true)

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
        assertTrue("the engine itself will not seal one", runCatching { a.seal(GroupBody.SHARED, fake.encode(), hub = false) }.isFailure)
        yaw.post.receive(a.sealUnchecked(GroupBody.SHARED, fake.encode(), hub = false).packet, now)
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
        ps[1].photoPieces = groupPhotoPieces(300, 7)
        val g = ps[0].post.create("Puits", 0x2E7D32, Avatar.Letter, false, listOf(ps[1].account), now)!!
        settle()
        assertTrue(ps[1].post.join(ps[1].store.invitations().single(), now))
        settle(); tickAll(); tickAll()
        assertTrue(ps[0].store.events.filterIsInstance<GroupPost.Event.Photo>().isEmpty())
        fetch(listOf(ps[0]))
        assertEquals(1 + 3, ps[0].store.events.filterIsInstance<GroupPost.Event.Photo>().count { !it.ofGroup && it.sender == ps[1].account && it.group.contentEquals(g) })
    }

    @Test fun invitationsAreCappedAndAgainForAKnownGroupIsDropped() {
        val ama = Phone("Ama"); val kofi = Phone("Kofi"); phones += listOf(ama, kofi); ama.know(kofi); kofi.know(ama)
        repeat(GroupPost.MAX_INVITATIONS + 2) { ama.post.create("G$it", 0, Avatar.Letter, false, listOf(kofi.account), now) }
        settle()
        assertEquals("one contact can leave only so many", GroupPost.MAX_INVITATIONS_PER_SENDER, kofi.store.invitations().size)
        val first = kofi.store.invitations().first()
        assertTrue(kofi.post.join(first, now))
        assertTrue("an invitation to a group already held is taken and dropped",
            kofi.post.takePrivate(ama.account, Body.Group(GroupControl.INVITE, first.invite.encode()), now))
        assertEquals(GroupPost.MAX_INVITATIONS_PER_SENDER - 1, kofi.store.invitations().size)
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
        ama.store.groupPhoto = groupPhotoPieces(266, 9)
        assertEquals(1 + 2, ama.store.groupPhoto.size)
        assertTrue(ama.post.restyle(g, "Puits", 0x2E7D32, Avatar.Photo(ByteArray(8) { 3 }), false, now))
        val photo = ama.store.packets.filter { !it.sealed.packet.isEmpty() }.takeLast(3)
        assertEquals(listOf(false, true, true), photo.map { it.hubOnly })
        settle()
        val kofi = ps[1]
        assertEquals("the sketch by radio", 1, kofi.store.events.filterIsInstance<GroupPost.Event.Photo>().count { it.ofGroup })
        fetch(listOf(kofi))
        assertEquals(1 + 2, kofi.store.events.filterIsInstance<GroupPost.Event.Photo>().count { it.ofGroup })
        // A member who is no admin cannot set the group photo: their piece is not taken as one.
        val saved = kofi.store.saved(g)!!
        val o = GroupMember.decode(kofi.id, saved.signer, { kofi.store.sign(g, it) }, g, saved.state)!!
        ama.post.receive(o.seal(GroupBody.GROUP_PHOTO, ama.store.groupPhoto[1], hub = true).packet, now)
        assertEquals(0, ama.store.events.filterIsInstance<GroupPost.Event.Photo>().count { it.ofGroup })
    }

    // ---- review G9: findings fixed in v0.1.2 (see G9-fixes.md)

    private fun signerBody(group: ByteArray, key: ByteArray) = Body.Group(GroupControl.SIGNER, GroupControl.Signer(group, key).encode())

    /** A group of Ama and Kofi, with Yaw and Vik invited: what the G9-1 tests start from. */
    private inner class Invitees(val ama: Phone, val kofi: Phone, val yaw: Phone, val vik: Phone, val g: ByteArray)

    private fun withInvitees(): Invitees {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val yaw = Phone("Yaw"); val vik = Phone("Vik"); phones += listOf(yaw, vik)
        for (p in listOf(yaw, vik)) { ama.know(p); p.know(ama) }
        assertTrue(ama.post.invite(g, listOf(yaw.account, vik.account), now))
        settle()
        return Invitees(ama, kofi, yaw, vik, g)
    }

    /** After the bad Join reply from Yaw: Vik, honest, is in; Yaw is not; the batch is done and nothing throws. */
    private fun Invitees.assertBatchWorks() {
        assertNull("no signing key kept for Yaw", ama.post.member(g)!!.signerOf(yaw.id))
        assertTrue(vik.post.join(vik.store.invitations().single(), now)); settle()
        now += 61_000
        assertTrue("tick does not throw", ama.post.tick(now)); settle()
        assertNotNull(ama.post.member(g)!!.roster!!.member(vik.id))
        assertNull(ama.post.member(g)!!.roster!!.member(yaw.id))
        assertNull("the batch is done", ama.store.saved(g)!!.adminDue)
        assertTrue(ama.post.tick(now + 61_000))
    }

    /** G9-1: a Join reply with the admin's own signing key (public in the invitation) is refused. */
    @Test fun aJoinReplyWithTheAdminsOwnKeyIsRefused() = withInvitees().run {
        assertTrue("taken, so the Postman receipts it", ama.post.takePrivate(yaw.account, signerBody(g, ama.post.member(g)!!.mySigner), now))
        assertBatchWorks()
    }

    /** G9-1: and before any list exists, when only the invitation has told the invitee the admin's key. */
    @Test fun aJoinReplyWithTheAdminsOwnKeyBeforeAnyListIsRefused() {
        val ama = Phone("Ama"); val yaw = Phone("Yaw"); val vik = Phone("Vik"); phones += listOf(ama, yaw, vik)
        for (p in listOf(yaw, vik)) { ama.know(p); p.know(ama) }
        val g = ama.post.create("Puits", 0x2E7D32, Avatar.Letter, false, listOf(yaw.account, vik.account), now)!!
        settle()
        assertNull("no list yet", ama.post.member(g)!!.roster)
        val invitedKey = yaw.store.invitations().single().invite.signer
        assertTrue(ama.post.takePrivate(yaw.account, signerBody(g, invitedKey), now))
        assertNull("the key the invitation carried is the admin's, not Yaw's", ama.post.member(g)!!.signerOf(yaw.id))
        assertTrue(vik.post.join(vik.store.invitations().single(), now)); settle()
        now += 61_000
        assertTrue(ama.post.tick(now)); settle()
        assertEquals(setOf(ama.id.toList(), vik.id.toList()), ama.post.member(g)!!.roster!!.members.map { it.identity.toList() }.toSet())
    }

    /** G9-1: or with a member's key that came to the list through another admin, so this admin never held it as a Join reply. */
    @Test fun aJoinReplyWithAMembersKeyIsRefused() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val esi = Phone("Esi"); val yaw = Phone("Yaw"); val vik = Phone("Vik"); phones += listOf(esi, yaw, vik)
        kofi.know(esi); esi.know(kofi)
        for (p in listOf(yaw, vik)) { ama.know(p); p.know(ama) }
        assertTrue(ama.post.makeAdmin(g, kofi.account, now)); settle(); tickAll()
        // Kofi, now an admin, brings Esi in; Ama gets Esi's key only from the list.
        assertTrue(kofi.post.invite(g, listOf(esi.account), now)); settle()
        assertTrue(esi.post.join(esi.store.invitations().single(), now)); settle(); tickAll(); tickAll()
        val esiKey = ama.post.member(g)!!.roster!!.member(esi.id)!!.signer
        assertNull("Ama never held it as a Join reply", ama.post.member(g)!!.signerOf(esi.id))
        ama.know(esi); esi.know(ama) // keys go to contacts, so Ama needs Esi as one to start any later epoch
        assertTrue(ama.post.invite(g, listOf(yaw.account, vik.account), now)); settle()
        assertTrue(ama.post.takePrivate(yaw.account, signerBody(g, esiKey), now))
        Invitees(ama, kofi, yaw, vik, g).assertBatchWorks()
    }

    /** G9-1: or with the key another invitee already gave, which that invitee keeps. */
    @Test fun aJoinReplyWithAnotherInviteesKeyIsRefused() = withInvitees().run {
        assertTrue(vik.post.join(vik.store.invitations().single(), now)); settle()
        val vikKey = ama.post.member(g)!!.signerOf(vik.id)!!
        assertTrue(ama.post.takePrivate(yaw.account, signerBody(g, vikKey), now))
        assertNull("another invitee's key is not Yaw's", ama.post.member(g)!!.signerOf(yaw.id))
        assertArrayEquals("and Vik keeps his", vikKey, ama.post.member(g)!!.signerOf(vik.id))
        now += 61_000
        assertTrue(ama.post.tick(now)); settle()
        assertNotNull(ama.post.member(g)!!.roster!!.member(vik.id))
        assertNull(ama.post.member(g)!!.roster!!.member(yaw.id))
    }

    /** G9-1: even if a duplicate key were stored, the batch drops that joiner instead of throwing, and tick clears the batch. */
    @Test fun aDuplicateKeyThatGotStoredDropsOnlyThatJoiner() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val yaw = Phone("Yaw"); val vik = Phone("Vik"); phones += listOf(yaw, vik)
        for (p in listOf(yaw, vik)) { ama.know(p); p.know(ama) }
        assertTrue(ama.post.invite(g, listOf(yaw.account, vik.account), now))
        settle()
        assertTrue(vik.post.join(vik.store.invitations().single(), now)); settle()
        val amaKey = ama.post.member(g)!!.mySigner
        val saved = ama.store.saved(g)!!
        val m = GroupMember.decode(ama.id, saved.signer, { ama.store.sign(g, it) }, g, saved.state)!!
        m.putSignerUnchecked(yaw.id, amaKey)
        ama.store.saved[g.toList()] = GroupStore.Saved(g, m.encode(), saved.signer, saved.invited, saved.receiptDue, saved.profileDue, saved.draft, now + 1, saved.askedEpoch)
        now += 61_000
        assertTrue(ama.post.tick(now)); settle()
        assertNotNull(ama.post.member(g)!!.roster!!.member(vik.id))
        assertNull(ama.post.member(g)!!.roster!!.member(yaw.id))
        assertNull(ama.store.saved(g)!!.adminDue)
        assertTrue("and every later tick", ama.post.tick(now + 120_000))
    }

    private fun photoEvents(p: Phone, ofGroup: Boolean) = p.store.events.filterIsInstance<GroupPost.Event.Photo>().count { it.ofGroup == ofGroup }
    private fun cloneOf(p: Phone, g: ByteArray) = p.store.saved(g)!!.let { GroupMember.decode(p.id, it.signer, { b -> p.store.sign(g, b) }, g, it.state)!! }
    private val oversizePiece = ByteArray(GroupCipher.MAX_BODY - 1) { 7 }

    /** G9-3: nothing a member sends can reach the photo table unless it is a whole group-cut piece. */
    @Test fun aMemberPhotoPieceThatIsNotAGroupPieceIsDropped() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val good = groupPhotoPieces(300, 3)
        assertEquals("the largest body leaves 138 bytes after the op", 138, oversizePiece.size)
        val personCut = org.phonemesh.core.PictureWire.encodeV2(
            org.phonemesh.core.PictureWire.splitV2(5, org.phonemesh.core.PictureCodec.WEBP, 4, 3, ByteArray(300), byteArrayOf(1)).first())
        val k = cloneOf(kofi, g)
        for (bad in listOf(oversizePiece, byteArrayOf(1, 2, 3), ByteArray(0), personCut)) {
            ama.post.receive(k.seal(GroupBody.MEMBER_PHOTO, bad, hub = true).packet, now)
        }
        assertEquals("none of those", 0, photoEvents(ama, false))
        ama.post.receive(k.seal(GroupBody.MEMBER_PHOTO, good[0], hub = true).packet, now)
        ama.post.receive(k.seal(GroupBody.MEMBER_PHOTO, good[1], hub = true).packet, now)
        assertEquals("the group-cut pieces", 2, photoEvents(ama, false))
    }

    /** G9-3: and the group's own photo, from an admin. */
    @Test fun aGroupPhotoPieceThatIsNotAGroupPieceIsDropped() {
        val (ps, g) = group("Ama", "Kofi")
        val (ama, kofi) = ps
        val a = cloneOf(ama, g)
        kofi.post.receive(a.seal(GroupBody.GROUP_PHOTO, oversizePiece, hub = true).packet, now)
        kofi.post.receive(a.seal(GroupBody.GROUP_PHOTO, byteArrayOf(1, 2, 3), hub = true).packet, now)
        assertEquals(0, photoEvents(kofi, true))
        kofi.post.receive(a.seal(GroupBody.GROUP_PHOTO, groupPhotoPieces(300, 3)[0], hub = false).packet, now)
        assertEquals(1, photoEvents(kofi, true))
    }

    /** G9-6: a packet for no group of this phone costs no group opened; a packet for one costs that group only. */
    @Test fun aStrangersPacketOpensNoGroupAndAMembersOpensOne() {
        val ama = Phone("Ama"); val kofi = Phone("Kofi"); phones += listOf(ama, kofi); ama.know(kofi); kofi.know(ama)
        val groups = (0 until GroupPost.MAX_INVITATIONS_PER_SENDER).map { ama.post.create("G$it", 0x2E7D32, Avatar.Letter, false, listOf(kofi.account), now)!! }
        settle()
        for (invitation in kofi.store.invitations()) assertTrue(kofi.post.join(invitation, now))
        settle(); tickAll(); tickAll()
        assertEquals(groups.size, kofi.store.groups().size)
        val third = groups[2]
        val sent = ama.post.send(third, listOf(Body.encode(Body.Text("en-US", "hi")) to false), 5, now)!!.single()
        kofi.post.expectedCodes(now) // the codes this phone listens for are worked out once
        val stranger = org.phonemesh.core.wire.WireV2.Header(ByteArray(8) { (it * 9 + 1).toByte() }, null).encode() + ByteArray(140) { (it * 3).toByte() }
        kofi.store.savedCalls = 0
        assertFalse(kofi.post.receive(stranger, now))
        assertEquals("a stranger's packet opens nothing", 0, kofi.store.savedCalls)
        assertTrue(kofi.post.receive(sent.sealed.packet, now))
        assertEquals("a member's packet opens its own group only", 1, kofi.store.savedCalls)
        assertEquals("hi", (Body.decode(kofi.messages(third).last().body) as Body.Text).text)
        // After the chain moved, the next packet and the old one's replay are still told apart without opening the others.
        val next = ama.post.send(third, listOf(Body.encode(Body.Text("en-US", "again")) to false), 6, now)!!.single()
        kofi.store.savedCalls = 0
        assertTrue(kofi.post.receive(next.sealed.packet, now))
        assertEquals(1, kofi.store.savedCalls)
        kofi.store.savedCalls = 0
        assertFalse("a replay is no longer expected", kofi.post.receive(sent.sealed.packet, now))
        assertEquals(0, kofi.store.savedCalls)
    }

    /** G9-7: keys go to contacts only, so an epoch that would need a stranger's key does not start, and the admin is told. */
    @Test fun anEpochForSomeoneWhoIsNoContactDoesNotStartAndIsReported() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        val zed = Phone("Zed"); phones += zed; ama.know(zed); zed.know(ama)
        ama.contacts.remove(esi.account)
        val epoch = ama.post.member(g)!!.epoch
        assertTrue(ama.post.invite(g, listOf(zed.account), now)); settle()
        assertTrue(zed.post.join(zed.store.invitations().single(), now)); settle()
        ama.store.private.clear(); ama.store.events.clear()
        now += 61_000
        assertTrue(ama.post.tick(now))
        assertEquals("no epoch without a key for Esi", epoch, ama.post.member(g)!!.epoch)
        assertTrue(ama.store.private.none { it.second.kind == GroupControl.KEY })
        assertEquals(listOf(esi.account), ama.store.events.filterIsInstance<GroupPost.Event.CannotReach>().single().people)
        // The contact comes back: the next tick starts the epoch with Zed in it.
        ama.know(esi)
        now += 61_000
        assertTrue(ama.post.tick(now)); settle()
        now += 61_000
        assertTrue(ama.post.tick(now)); settle()
        assertEquals(epoch + 1, ama.post.member(g)!!.epoch)
        assertNotNull(ama.post.member(g)!!.roster!!.member(zed.id))
    }

    @Test fun theLastAdminCannotLeaveWhileAKeyWouldHaveNoWay() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi, esi) = ps
        ama.contacts.remove(esi.account)
        ama.store.private.clear()
        assertFalse(ama.post.leave(g, kofi.account, now))
        assertEquals("nothing left the phone and it is still a member", GroupMember.State.ACTIVE, ama.state(g))
        assertTrue(ama.store.private.isEmpty())
        ama.know(esi)
        assertTrue(ama.post.leave(g, kofi.account, now))
    }

    /** G9-8: the first invitation per group is kept, one contact can leave only a few, and they expire. */
    @Test fun anInvitationForAGroupAlreadyOfferedKeepsTheFirstSender() {
        val ama = Phone("Ama"); val yaw = Phone("Yaw"); val kofi = Phone("Kofi"); phones += listOf(ama, yaw, kofi)
        kofi.know(ama, yaw); ama.know(kofi); yaw.know(kofi)
        val g = ama.post.create("Puits", 0x2E7D32, Avatar.Letter, false, listOf(kofi.account), now)!!
        settle()
        val first = kofi.store.invitations().single()
        assertEquals(ama.account, first.from)
        val again = GroupControl.Invite(g, P256.compress(P256.generate().public as java.security.interfaces.ECPublicKey), 2, "Other", 1, Avatar.Letter)
        assertTrue(kofi.post.takePrivate(yaw.account, Body.Group(GroupControl.INVITE, again.encode()), now))
        assertEquals(ama.account, kofi.store.invitations().single().from)
        assertEquals("Puits", kofi.store.invitations().single().invite.name)
    }

    @Test fun sixteenInvitationsFromOneContactDoNotBlockAnotherContacts() {
        val ama = Phone("Ama"); val yaw = Phone("Yaw"); val kofi = Phone("Kofi"); phones += listOf(ama, yaw, kofi)
        kofi.know(ama, yaw); ama.know(kofi); yaw.know(kofi)
        repeat(GroupPost.MAX_INVITATIONS + 2) { ama.post.create("G$it", 0, Avatar.Letter, false, listOf(kofi.account), now) }
        settle()
        yaw.post.create("Real", 0, Avatar.Letter, false, listOf(kofi.account), now)!!
        settle()
        assertTrue(kofi.store.invitations().any { it.invite.name == "Real" })
        assertEquals(GroupPost.MAX_INVITATIONS_PER_SENDER, kofi.store.invitations().count { it.from == ama.account })
    }

    @Test fun anInvitationThatExpiredDoesNotBlockANewOne() {
        val ama = Phone("Ama"); val yaw = Phone("Yaw"); val kofi = Phone("Kofi"); phones += listOf(ama, yaw, kofi)
        kofi.know(ama, yaw); ama.know(kofi); yaw.know(kofi)
        val g = ama.post.create("Puits", 0x2E7D32, Avatar.Letter, false, listOf(kofi.account), now)!!
        settle()
        now += GroupPost.INVITATION_TTL_MS + 5_000
        val again = GroupControl.Invite(g, P256.compress(P256.generate().public as java.security.interfaces.ECPublicKey), 2, "Newer", 1, Avatar.Letter)
        assertTrue(kofi.post.takePrivate(yaw.account, Body.Group(GroupControl.INVITE, again.encode()), now))
        assertEquals("the old one no longer holds the group's place", yaw.account, kofi.store.invitations().single().from)
    }

    @Test fun anInvitationExpiresAfterThirtyDays() {
        val ama = Phone("Ama"); val kofi = Phone("Kofi"); phones += listOf(ama, kofi); ama.know(kofi); kofi.know(ama)
        ama.post.create("Puits", 0, Avatar.Letter, false, listOf(kofi.account), now)!!
        settle()
        val at = kofi.store.invitations().single().at
        now = at + GroupPost.INVITATION_TTL_MS
        assertTrue(kofi.post.tick(now))
        assertEquals("not yet", 1, kofi.store.invitations().size)
        now += 1
        assertTrue(kofi.post.tick(now))
        assertEquals(0, kofi.store.invitations().size)
    }

    /** G9-9: the recent messages are still read when another epoch came before the new member reached a hub. */
    @Test fun recentMessagesSurviveTheEpochAfterTheJoin() {
        val (ps, g) = group("Ama", "Kofi", recent = true)
        val (ama, kofi) = ps
        val yaw = Phone("Yaw"); phones += yaw; ama.know(yaw); yaw.know(ama)
        ama.store.recentItems = listOf(GroupShared(kofi.account.toBytes(), 1_790_000_000, Body.encode(Body.Text("en-US", "prices"))))
        assertTrue(ama.post.invite(g, listOf(yaw.account), now)); settle()
        assertTrue(yaw.post.join(yaw.store.invitations().single(), now)); settle(); tickAll()
        assertTrue(ama.post.restyle(g, "Puits2", 0x2E7D32, Avatar.Letter, true, now)); settle()
        fetch(listOf(yaw, kofi))
        assertEquals("the batch of the join epoch is still read", 1, yaw.messages(g).count { it.shared != null })
        assertTrue(kofi.messages(g).none { it.shared != null })
        // One packet is shown once.
        fetch(listOf(yaw))
        assertEquals(1, yaw.messages(g).count { it.shared != null })
    }

    @Test fun aSharedMessageFromTheFutureIsShownNoLaterThanNow() {
        val (ps, g) = group("Ama", "Kofi", recent = true)
        val (ama, kofi) = ps
        val yaw = Phone("Yaw"); phones += yaw; ama.know(yaw); yaw.know(ama)
        ama.store.recentItems = listOf(GroupShared(kofi.account.toBytes(), 0xFFFF_FFF0L, Body.encode(Body.Text("en-US", "later"))))
        assertTrue(ama.post.invite(g, listOf(yaw.account), now)); settle()
        assertTrue(yaw.post.join(yaw.store.invitations().single(), now)); settle(); tickAll()
        fetch(listOf(yaw))
        val shared = yaw.messages(g).single { it.shared != null }.shared!!
        assertTrue("clamped to now (${shared.atSeconds})", shared.atSeconds <= now / 1000)
    }

    @Test fun aNewMemberTakesNoMoreSharedMessagesThanTheLimit() {
        val (ps, g) = group("Ama", "Kofi", recent = true)
        val (ama, kofi) = ps
        val yaw = Phone("Yaw"); phones += yaw; ama.know(yaw); yaw.know(ama)
        assertTrue(ama.post.invite(g, listOf(yaw.account), now)); settle()
        assertTrue(yaw.post.join(yaw.store.invitations().single(), now)); settle(); tickAll()
        val saved = ama.store.saved(g)!!
        val a = GroupMember.decode(ama.id, saved.signer, { ama.store.sign(g, it) }, g, saved.state)!!
        repeat(GroupPost.RECENT_MAX + 10) { i ->
            val s = GroupShared(kofi.account.toBytes(), 1_000L + i, Body.encode(Body.Text("en-US", "m$i")))
            yaw.post.receive(a.seal(GroupBody.SHARED, s.encode(), hub = true).packet, now)
        }
        assertEquals(GroupPost.RECENT_MAX, yaw.messages(g).count { it.shared != null })
    }

    /** G9-12: what the engine sends keeps to its chain: hub-only content is always marked hub only. */
    @Test fun everyHubOnlyOpComesOutHubOnly() {
        val (ps, g) = group("Ama", "Kofi", recent = true)
        val (ama, kofi) = ps
        kofi.profile = Profile("Kofi", 0x336699, Avatar.Photo(ByteArray(8) { 1 }))
        kofi.photoPieces = groupPhotoPieces(300, 4)
        ama.store.groupPhoto = groupPhotoPieces(300, 6)
        ama.store.recentItems = listOf(GroupShared(kofi.account.toBytes(), 1_790_000_000, Body.encode(Body.Text("en-US", "prices"))))
        val yaw = Phone("Yaw"); phones += yaw; ama.know(yaw); yaw.know(ama)
        assertTrue(ama.post.restyle(g, "Puits", 0x2E7D32, Avatar.Photo(ByteArray(8) { 3 }), true, now)); settle()
        assertTrue(ama.post.invite(g, listOf(yaw.account), now)); settle()
        assertTrue(yaw.post.join(yaw.store.invitations().single(), now)); settle(); tickAll(); tickAll()
        kofi.post.profileChanged(now); tickAll()
        val sent = (ama.store.everSent + kofi.store.everSent).filter { it.sealed.seq >= 0 }
        for (op in listOf(GroupBody.SHARED, GroupBody.MEMBER_PHOTO)) {
            assertTrue("op $op was sent", sent.any { it.op == op })
            assertTrue("op $op is always hub only", sent.filter { it.op == op }.all { it.hubOnly })
        }
        val photo = sent.filter { it.op == GroupBody.GROUP_PHOTO }
        assertTrue("the group photo's sketch by radio, the rest hub only", photo.any { !it.hubOnly } && photo.any { it.hubOnly })
        for (p in sent) assertTrue("op ${p.op} is allowed on the chain it took", GroupBody.allowed(p.op, p.hubOnly))
        for (p in sent.filter { it.op == GroupBody.RECEIPT || it.op == GroupBody.PROFILE }) assertFalse("op ${p.op} stays on the radio chain", p.hubOnly)
    }

    /** G9-13: an admin asking to remove or promote themselves is refused, never an exception out of the engine. */
    @Test fun anAdminCannotRemoveOrPromoteTheirOwnAccount() {
        val (ps, g) = group("Ama", "Kofi", "Esi")
        val (ama, kofi) = ps
        assertTrue(ama.post.makeAdmin(g, kofi.account, now)); settle(); tickAll()
        val epoch = ama.post.member(g)!!.epoch
        assertFalse("a list without the admin who starts it", ama.post.remove(g, ama.account, now))
        assertFalse(ama.post.makeAdmin(g, ama.account, now))
        assertEquals(epoch, ama.post.member(g)!!.epoch)
        assertEquals(GroupMember.State.ACTIVE, ama.state(g))
    }
}

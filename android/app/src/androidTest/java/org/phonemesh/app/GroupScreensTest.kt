package org.phonemesh.app

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.Avatar
import org.phonemesh.core.account.Profile
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.group.GroupMember
import org.phonemesh.core.group.GroupPost
import org.phonemesh.core.group.GroupShared
import org.phonemesh.core.group.GroupStore
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.wire.Body
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey

/**
 * Private groups on the phone (v0.1.2 G2-G4, decision 72): the installed app (Awa) and Bea, a second phone played by a
 * JVM Postman and GroupPost over memory, met in person with real codes. Everything goes through the real radio path:
 * Awa's packets are taken from the fake radio's broadcasts and given to Bea; Bea's come back in as received data.
 */
@RunWith(AndroidJUnit4::class)
class GroupScreensTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val core get() = TestCore.core

    @After fun tearDown() {
        repeat(4) { Views.finish(Views.resumed()) }
        TestCore.uninstall()
    }

    /** Bea's group side: her own GroupPost over memory, its private bodies sent by her Postman to Awa. */
    private class BeaGroups(val bea: PersonFixture.Bea, val awaIdentity: ByteArray) : GroupStore {
        val saved = LinkedHashMap<List<Byte>, GroupStore.Saved>()
        val invites = LinkedHashMap<List<Byte>, GroupStore.Invitation>()
        val keys = HashMap<List<Byte>, PrivateKey>()
        val private = ArrayList<Pair<AccountId, Body.Group>>()
        val packets = ArrayList<GroupPost.Packet>()
        val events = ArrayList<GroupPost.Event>()
        val post = GroupPost(bea.keys.identity, this, quietDelay = { 20_000L }, profileDelay = { 0L })
        override fun groups() = saved.keys.map { it.toByteArray() }
        override fun saved(group: ByteArray) = saved[group.toList()]
        override fun invitations() = invites.values.toList()
        override fun newSigner(group: ByteArray): ByteArray { val k = P256.generate(); keys[group.toList()] = k.private; return P256.compress(k.public as ECPublicKey) }
        override fun sign(group: ByteArray, data: ByteArray) = P256.sign(keys.getValue(group.toList()), data)
        /** Others this phone has as contacts besides Awa (a third phone in some tests). */
        val contacts = HashMap<AccountId, ByteArray>()
        override fun identityOf(account: AccountId) = contacts[account] ?: if (account == bea.awa) awaIdentity else null
        /** A photo for Bea's profile, as pieces sized for a group packet (head first); none by default. */
        var photoPieces: List<ByteArray> = emptyList()
        var photoId: ByteArray? = null
        override fun myProfile() = Profile(bea.name, bea.colour, photoId?.let { Avatar.Photo(it) } ?: Avatar.Letter)
        override fun myPhotoPieces(): List<ByteArray> = photoPieces
        override fun groupPhotoPieces(group: ByteArray): List<ByteArray> = emptyList()
        var recentToShare: List<GroupShared> = emptyList()
        var recentAsked: Pair<Long, Int>? = null
        override fun recent(group: ByteArray, sinceMs: Long, max: Int): List<GroupShared> { recentAsked = sinceMs to max; return recentToShare }
        override fun commit(change: GroupPost.Change): Boolean {
            val g = change.group.toList()
            if (change.deleted) saved.remove(g) else change.saved?.let { saved[g] = it }
            change.invitation?.let { invites[g] = it }
            if (change.dropInvitation) invites.remove(g)
            private += change.private; packets += change.packets; events += change.events
            return true
        }
    }

    private lateinit var bea: PersonFixture.Bea
    private lateinit var beaGroups: BeaGroups
    private lateinit var beaPostman: Postman
    private var beaChat = 0L
    private var taken = 0

    private fun prepare(language: String = "en-US") {
        TestCore.install(language = language, voices = listOf("fr", "en-US"), connected = true)
        val (b, chat) = PersonFixture.meet("Bea")
        bea = b
        beaChat = chat
        // Awa's identity, as Bea's phone has it from scanning Awa's code.
        val awaIdentity = TestCore.onMain { core.accounts.identity.copyOf() }
        beaGroups = BeaGroups(bea, awaIdentity)
        // Bea's Postman over the same sessions, now with her group inbox (as the app wires its own).
        beaPostman = Postman(bea.keys, bea.store, quietDelay = { 20_000L },
            groups = Postman.GroupInbox { from, body, now -> beaGroups.post.takePrivate(from, body, now) })
    }

    /** Every broadcast Awa sent since last time goes to Bea's phone. */
    private fun toBea(now: Long) {
        val sent = TestCore.onMain { TestCore.radio.broadcasts.drop(taken).map { it.payload } }
        taken += sent.size
        for (p in sent) if (!beaGroups.post.receive(p, now)) beaPostman.receive(p, now)
    }

    /** Everything Bea's phone has to send goes to Awa: private bodies through her Postman, group packets by radio. */
    private fun toAwa(now: Long) {
        val private = beaGroups.private.filter { it.first == bea.awa }; beaGroups.private.removeAll(private)
        for ((to, body) in private) PersonFixture.deliver(checkNotNull(beaPostman.send(to, body, null, now)).packet)
        val packets = beaGroups.packets.toList(); beaGroups.packets.clear()
        for (p in packets) if (!p.hubOnly) PersonFixture.deliver(p.sealed.packet)
        beaPostman.due(now + 60_000).forEach { PersonFixture.deliver(it.packet) }
    }

    /** The resumed activity named [name]; callable from any thread. */
    private fun resumed(name: String): Activity? {
        fun find() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it.javaClass.simpleName == name }
        return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) find() else TestCore.onMain { find() }
    }

    /**
     * Polls [condition] on the test thread (not on the main thread like Views.waitUntil), so it may use the Views
     * helpers and TestCore.onMain, which hop to the main thread themselves.
     */
    private fun until(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(android.os.SystemClock.uptimeMillis() < end) { "Condition not met in $timeoutMs ms" }
            Thread.sleep(50)
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun groupChat(): Long = TestCore.onMain { core.history.chats().single { it.kind == ChatKind.PRIVATE_GROUP }.id }

    @Test fun newPrivateGroupStartsFromAddSomeoneAndWaitsForPeopleToJoin() {
        prepare()
        val add = Views.start(AddSomeoneActivity::class.java)
        Views.tap(add, "add_new_group")
        until { resumed("GroupPeopleActivity") != null }
        val people = resumed("GroupPeopleActivity")!!
        assertNotNull(Views.withText(people, "Only people you added can join."))
        assertEquals("1 of 20 people, with you", Views.textOf(Views.tagged(people, "group_count")!!))
        assertFalse("Next waits for someone", Views.tagged(people, "group_next")!!.let { v -> TestCore.onMain { v.isEnabled } })
        Views.tap(people, "candidate:${bea.account}")
        assertEquals("2 of 20 people, with you", Views.textOf(Views.tagged(people, "group_count")!!))
        Views.tap(people, "group_next")
        until { resumed("NameActivity") != null }
        val name = resumed("NameActivity")!!
        assertNotNull(Views.withText(name, "Name the group"))
        assertNotNull(Views.withText(name, "A photo goes only through a local hub."))
        (Views.tagged(name, "name_field") as android.widget.EditText).let { f -> TestCore.onMain { f.setText("Market") } }
        Views.tap(name, "colour_0")
        Views.tap(name, "name_save")
        until { resumed("ConversationActivity") != null }
        val chat = resumed("ConversationActivity")!!
        assertEquals("Market", Views.textOf(Views.tagged(chat, "title")!!))
        until { Views.withText(chat, "Waiting for 1 people to join.") != null }
        // The invitation went to Bea privately, through the Postman's radio path; her phone shows Join and Ignore.
        toBea(2_000_000)
        assertEquals(1, beaGroups.invites.size)
        assertEquals("Market", beaGroups.invites.values.single().invite.name)
    }

    @Test fun beaJoinsThenMessagesShowDeliveredToNofM() {
        prepare()
        val chatId = checkNotNull(TestCore.onMain { core.createGroup("Market", 0x2E7D32, Avatar.Letter, null, listOf(bea.account)) })
        toBea(2_000_000)
        assertTrue(beaGroups.post.join(beaGroups.invites.values.single(), 2_000_000))
        toAwa(2_000_000)
        // Awa's admin batches joins for a minute, then sends the list and her key.
        TestCore.onMain { core.groups.tick(System.currentTimeMillis() + GroupPost.ADMIN_BATCH_MS + 1) ; core.runPostman() }
        until { TestCore.onMain { core.groupView(chatId)?.people } == 2 }
        toBea(2_100_000)
        until(10_000) { toBea(2_100_000); toAwa(2_100_000); beaGroups.post.member(beaGroups.saved.keys.single().toByteArray())?.state == GroupMember.State.ACTIVE }
        val group = beaGroups.saved.keys.single().toByteArray()
        // Bea writes; Awa's chat shows it with her name.
        val text = Body.encode(Body.Text("en-US", "Fresh bread today"))
        assertNotNull(beaGroups.post.send(group, listOf(text to false), null, 2_200_000))
        toAwa(2_200_000)
        val conversation = Views.launch<ConversationActivity>(ConversationActivity.intent(context, chatId))
        until { Views.withText(conversation, "Fresh bread today") != null }
        assertNotNull(Views.withText(conversation, "Bea"))
        assertNotNull("a group note says who joined", Views.withText(conversation, "Bea joined."))
        // Awa answers; her bubble counts Bea's receipt.
        TestCore.onMain { core.setDraft(chatId, "Thank you"); core.send(chatId) }
        val mine = TestCore.onMain { core.history.messages(chatId).last { it.direction == Direction.OUT } }
        until { Views.tagged(conversation, "delivered:${mine.id}") != null }
        assertEquals("Delivered to 0 of 1", Views.textOf(Views.tagged(conversation, "delivered:${mine.id}")!!))
        toBea(2_300_000)
        beaGroups.post.tick(2_400_000)
        toAwa(2_400_000)
        until { Views.tagged(conversation, "delivered:${mine.id}")?.let { Views.textOf(it) } == "Delivered to 1 of 1" }
    }

    /** Awa's group with Bea in it, both on the first list, Bea active. Returns Awa's chat and the group id. */
    private fun activeGroup(): Pair<Long, ByteArray> {
        val chatId = checkNotNull(TestCore.onMain { core.createGroup("Market", 0x2E7D32, Avatar.Letter, null, listOf(bea.account)) })
        toBea(2_000_000)
        assertTrue(beaGroups.post.join(beaGroups.invites.values.single(), 2_000_000))
        toAwa(2_000_000)
        TestCore.onMain { core.groups.tick(System.currentTimeMillis() + GroupPost.ADMIN_BATCH_MS + 1); core.runPostman() }
        until(10_000) {
            toBea(2_100_000); toAwa(2_100_000)
            beaGroups.post.member(beaGroups.saved.keys.single().toByteArray())?.state == GroupMember.State.ACTIVE
        }
        return chatId to beaGroups.saved.keys.single().toByteArray()
    }

    private fun picture(bytes: Int): PreparedPicture = PreparedPicture(org.phonemesh.core.PictureCodec.WEBP, 2, 2,
        ByteArray(bytes) { (it * 7).toByte() }, byteArrayOf(1, 2, 3), android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888), 70)

    @Test fun aGroupPictureSendsItsSketchByRadioAndItsPartsOnlyThroughAHub() {
        prepare()
        val (chatId, group) = activeGroup()
        toBea(2_150_000); beaGroups.events.clear()
        val before = TestCore.onMain { TestCore.radio.broadcasts.size }
        assertTrue(TestCore.onMain { core.canSendPictures(chatId) })
        assertTrue(TestCore.onMain { core.sendPicture(chatId, picture(400)) })
        val mine = TestCore.onMain { core.history.messages(chatId).last { it.direction == Direction.OUT && it.kind == MessageKind.PICTURE } }
        val rows = TestCore.onMain { core.groupStore.rows.outFor(mine.id) }
        // 400 bytes in parts of 133: four parts, all hub-only; the head (with the sketch) by radio.
        assertEquals(listOf(false, true, true, true, true), rows.map { it.hubOnly })
        TestCore.onMain { core.runPostman() }
        until { TestCore.onMain { TestCore.radio.broadcasts.drop(before).any { b -> b.payload.contentEquals(rows[0].packet) } } }
        val aired = TestCore.onMain { TestCore.radio.broadcasts.drop(before).map { it.payload } }
        assertTrue("no part goes by radio", rows.drop(1).none { r -> aired.any { it.contentEquals(r.packet) } })
        // Bea's radio-only phone gets the sketch; the parts reach her only through a hub.
        toBea(2_200_000)
        val heads = beaGroups.events.filterIsInstance<GroupPost.Event.Message>().filter { Body.decode(it.body) is Body.PictureHead }
        assertEquals(1, heads.size)
        assertFalse(heads.single().hub)
        for (r in rows.drop(1)) assertTrue(beaGroups.post.receive(r.packet, 2_200_000))
        val parts = beaGroups.events.filterIsInstance<GroupPost.Event.Message>().filter { Body.decode(it.body) is Body.PicturePart }
        assertEquals(4, parts.size)
        assertTrue(parts.all { it.hub })
        assertNotNull(group)
    }

    @Test fun beasGroupPictureShowsItsSketchThenTheWholePictureFromTheHub() {
        prepare()
        val (chatId, group) = activeGroup()
        val bytes = ByteArray(300) { (it * 3).toByte() }
        val packets = org.phonemesh.core.PictureWire.splitGroup(77, org.phonemesh.core.PictureCodec.WEBP, 2, 2, bytes, byteArrayOf(9, 9))
        val bodies = packets.map { pk ->
            val b = org.phonemesh.core.PictureWire.encode(pk)
            Body.encode(if (pk is org.phonemesh.core.PictureWire.Head) Body.PictureHead(b) else Body.PicturePart(b)) to (pk !is org.phonemesh.core.PictureWire.Head)
        }
        val sent = checkNotNull(beaGroups.post.send(group, bodies, null, 2_300_000))
        assertEquals(1, sent.count { !it.hubOnly })
        PersonFixture.deliver(sent.first { !it.hubOnly }.sealed.packet)
        until { TestCore.onMain { core.history.messages(chatId).any { it.kind == MessageKind.PICTURE && it.direction == Direction.IN } } }
        val bubble = TestCore.onMain { core.history.messages(chatId).last { it.kind == MessageKind.PICTURE } }
        val sketchOnly = TestCore.onMain { core.history.picture(bubble.id)!! }
        assertNull("only the sketch so far", sketchOnly.file)
        assertArrayEquals(byteArrayOf(9, 9), sketchOnly.sketch)
        // The parts come in through the hub (the app reads group packets the same way from either path).
        for (p in sent.filter { it.hubOnly }) PersonFixture.deliver(p.sealed.packet)
        until { TestCore.onMain { core.history.picture(bubble.id)?.file } != null }
        val file = java.io.File(context.filesDir, "pictures/" + TestCore.onMain { core.history.picture(bubble.id)!!.file })
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun aNewMemberSeesRecentMessagesSharedByTheAdmin() {
        prepare()
        // Bea's group, which shares recent messages with new members, already has Cey (a third phone, its private
        // bodies handed over directly); Bea has one message from last week's market day.
        val cey = PersonFixture.Bea("Cey", 0x00838F)
        val ceyGroups = BeaGroups(cey, ByteArray(0))
        val beaId = bea.qr.card.accountId
        val ceyId = cey.qr.card.accountId
        beaGroups.contacts[ceyId] = cey.keys.identity
        ceyGroups.contacts[beaId] = bea.keys.identity
        fun between(t: Long) {
            repeat(4) {
                val toCey = beaGroups.private.filter { it.first == ceyId }; beaGroups.private.removeAll(toCey)
                for ((_, body) in toCey) assertTrue(ceyGroups.post.takePrivate(beaId, body, t))
                val toBea = ceyGroups.private.toList(); ceyGroups.private.clear()
                for ((_, body) in toBea) assertTrue(beaGroups.post.takePrivate(ceyId, body, t))
                for (p in beaGroups.packets.toList()) ceyGroups.post.receive(p.sealed.packet, t)
                for (p in ceyGroups.packets.toList()) beaGroups.post.receive(p.sealed.packet, t)
                ceyGroups.packets.clear()
            }
        }
        val group = checkNotNull(beaGroups.post.create("Couture", 0x8E24AA, Avatar.Letter, true, listOf(ceyId), 1_000_000))
        between(1_000_000)
        assertTrue(ceyGroups.post.join(ceyGroups.invites.values.single(), 1_000_000))
        between(1_000_000)
        assertTrue(beaGroups.post.tick(1_000_000 + GroupPost.ADMIN_BATCH_MS + 1))
        between(1_100_000)
        assertEquals(GroupMember.State.ACTIVE, ceyGroups.post.member(group)?.state)
        beaGroups.packets.clear()
        // Now Bea invites Awa, who joins.
        beaGroups.recentToShare = listOf(GroupShared(beaId.toBytes(), 1_900L, Body.encode(Body.Text("en-US", "Bread at six"))))
        assertTrue(beaGroups.post.invite(group, listOf(bea.awa), 2_000_000))
        toAwa(2_000_000)
        until { TestCore.onMain { core.groupInvitations().size } == 1 }
        val chatId = checkNotNull(TestCore.onMain { core.joinGroup(core.groupInvitations().single().first) })
        // Awa's Join goes out through her radio queue; Bea's admin then batches it for a minute.
        until(10_000) { toBea(2_000_000); beaGroups.saved[group.toList()]?.adminDue != null }
        val due = beaGroups.saved[group.toList()]!!.adminDue!! + 1
        beaGroups.packets.clear()
        assertTrue(beaGroups.post.tick(due))
        assertEquals("the engine asks for 7 days, at most 50", (due - GroupPost.RECENT_MS) to GroupPost.RECENT_MAX, beaGroups.recentAsked)
        val shared = beaGroups.packets.filter { it.hubOnly }
        assertTrue("the shared message goes only through a hub", shared.isNotEmpty())
        // Everything reaches Awa, the hub-only packets as a hub would hand them over.
        toAwa(2_100_000)
        for (p in shared) PersonFixture.deliver(p.sealed.packet)
        until { TestCore.onMain { core.history.messages(chatId).any { it.text == "Bread at six" } } }
        val m = TestCore.onMain { core.history.messages(chatId).single { it.text == "Bread at six" } }
        assertEquals(bea.account, m.sharedBy)
        assertEquals(bea.account, m.senderId)
        assertEquals("kept at its own time", 1_900_000L, m.time)
        val conversation = Views.launch<ConversationActivity>(ConversationActivity.intent(context, chatId))
        until { Views.withText(conversation, "Shared by Bea") != null }
        assertNotNull(group)
    }

    @Test fun anAdminSharesOnlyTextFromTheLastSevenDaysAtMostFifty() {
        prepare()
        val (chatId, group) = activeGroup()
        val now = System.currentTimeMillis()
        TestCore.onMain {
            core.history.addIncoming(chatId, bea.account, "Too old", "en-US", now - GroupPost.RECENT_MS - 60_000, false)
            for (i in 1..55) core.history.addIncoming(chatId, bea.account, "News $i", "en-US", now - 1_000L * (60 - i), false)
        }
        val recent = TestCore.onMain { core.groupStore.recent(group, now - GroupPost.RECENT_MS, GroupPost.RECENT_MAX) }
        assertEquals(GroupPost.RECENT_MAX, recent.size)
        val texts = recent.map { (Body.decode(it.body) as Body.Text).text }
        assertEquals("the newest 50", (6..55).map { "News $it" }, texts)
        assertTrue(recent.all { org.phonemesh.core.account.AccountId.fromBytes(it.author).toString() == bea.account })
        assertFalse("notes are never shared", texts.any { it.contains("joined") })
        // With room for more, the message older than seven days still stays out.
        val all = TestCore.onMain { core.groupStore.recent(group, now - GroupPost.RECENT_MS, 100) }
        assertEquals((1..55).map { "News $it" }, all.map { (Body.decode(it.body) as Body.Text).text })
    }

    @Test fun anInvitationWaitsWithJoinAndIgnoreUntilChosen() {
        prepare("fr")
        // Bea makes a group with Awa in it: Awa must choose Join (decision 72 c).
        val group = checkNotNull(beaGroups.post.create("Couture", 0x8E24AA, Avatar.Letter, false, listOf(bea.awa), 2_000_000))
        // Each layer on its own first, so a failure says which one refused.
        val (to, body) = beaGroups.private.single()
        assertEquals(bea.awa, to)
        val beaId = bea.qr.card.accountId
        assertNotNull("Awa knows Bea's identity", TestCore.onMain { core.groupStore.identityOf(beaId) })
        val sealed = checkNotNull(beaPostman.send(to, body, null, 2_000_000)).packet
        beaGroups.private.clear()
        val got = TestCore.onMain { core.postman.receive(sealed, System.currentTimeMillis()) }
        assertNotNull("Awa's Postman took the invitation", got)
        until { TestCore.onMain { core.groupStore.rows.invitations().size } == 1 }
        assertEquals("the kept invitation opens", 1, TestCore.onMain { core.groupInvitations().size })
        val chats = Views.start(ChatsActivity::class.java)
        until { Views.tagged(chats, "invitation") != null }
        assertEquals("Bea vous a ajouté à Couture.", Views.textOf(Views.tagged(chats, "invitation_title")!!))
        assertTrue("no chat before Join", TestCore.onMain { core.history.chats().none { it.kind == ChatKind.PRIVATE_GROUP } })
        Views.tap(chats, "invitation_join")
        until { resumed("ConversationActivity") != null }
        assertEquals(1, TestCore.onMain { core.history.chats().count { it.kind == ChatKind.PRIVATE_GROUP } })
        assertNotNull(group)
    }

    /** Review G9-2: a member's photo of more than one part reaches the app whole through a hub (it never joined before). */
    @Test fun beasPhotoOfSeveralPartsBecomesHerPictureInTheGroup() {
        prepare()
        val (chatId, group) = activeGroup()
        val webp = ByteArray(600) { (it * 7 + 3).toByte() }
        beaGroups.photoPieces = org.phonemesh.core.account.AvatarPhotos.pieces(webp, 64, 64, byteArrayOf(1, 2, 3), group = true)
        beaGroups.photoId = org.phonemesh.core.account.AvatarPhotos.id(webp)
        assertEquals("a head and five parts of at most 133 bytes", 6, beaGroups.photoPieces.size)
        beaGroups.post.profileChanged(2_500_000)
        beaGroups.post.tick(2_600_000)
        val sent = beaGroups.packets.toList(); beaGroups.packets.clear()
        for (p in sent) PersonFixture.deliver(p.sealed.packet) // by radio or hub: the app reads group packets the same way
        val hex = AppGroupStore.hex(group)
        until(10_000) { TestCore.onMain { core.groupStore.rows.photo(hex, bea.account) } != null }
        val photo = TestCore.onMain { core.groupStore.rows.photo(hex, bea.account)!! }
        assertArrayEquals(webp, photo.webp)
        assertEquals(64, photo.width)
        assertNotNull(chatId)
    }

    /** Review G9-5: deleting the chat of a group this phone is still in is refused; once left it removes chat and group. */
    @Test fun aLiveGroupCannotBeDeletedFromTheChatListButALeftOneCan() {
        prepare()
        val (chatId, group) = activeGroup()
        val hex = AppGroupStore.hex(group)
        assertFalse(TestCore.onMain { core.deleteChat(chatId) })
        assertEquals("Leave the group first. Then delete it.", TestCore.onMain { core.text(core.lastNote!!) })
        assertNotNull("the chat is still there", TestCore.onMain { core.history.chat(chatId) })
        assertNotNull("and the group", TestCore.onMain { core.groupStore.rows.group(hex) })
        assertFalse("the group's own delete is refused as well", TestCore.onMain { core.forgetGroup(chatId) })
        assertNotNull(TestCore.onMain { core.history.chat(chatId) })
        // Leaving first: the group is LEFT, and then both go.
        assertTrue(TestCore.onMain { core.leaveGroup(chatId, null) })
        assertEquals(GroupMember.State.LEFT, TestCore.onMain { core.groupView(chatId)!!.state })
        assertTrue(TestCore.onMain { core.deleteChat(chatId) })
        assertNull(TestCore.onMain { core.history.chat(chatId) })
        assertNull("no live group behind a deleted chat", TestCore.onMain { core.groupStore.rows.group(hex) })
    }
}

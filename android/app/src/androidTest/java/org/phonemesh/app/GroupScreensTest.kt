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
        override fun identityOf(account: AccountId) = if (account == bea.awa) awaIdentity else null
        override fun myProfile() = Profile(bea.name, bea.colour)
        override fun myPhotoPieces(): List<ByteArray> = emptyList()
        override fun groupPhotoPieces(group: ByteArray): List<ByteArray> = emptyList()
        override fun recent(group: ByteArray, sinceMs: Long, max: Int): List<GroupShared> = emptyList()
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
        val private = beaGroups.private.toList(); beaGroups.private.clear()
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
}

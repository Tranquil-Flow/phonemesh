package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.backup.BackupStream
import org.phonemesh.core.backup.ChatExport
import org.phonemesh.core.backup.HistoryBackup
import org.phonemesh.core.backup.RecoveryKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Making a full chat copy (v0.1.2 B1): the snapshot of what was chosen, the preview's exact counts, the sealed file
 * checked in the private folder, at the destination and read back, group chats as read-only history (decision 75), and
 * nothing of keys, sessions, outbox or group state in it. Real History and real files; the destination is a file.
 */
@RunWith(AndroidJUnit4::class)
class ChatBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val core get() = TestCore.core
    private val key = RecoveryKey.generate()
    private val work get() = File(context.noBackupFilesDir, "backup-test")
    private val out = File(context.cacheDir, "chat-copy-test.pmbk")

    @After fun tearDown() {
        work.deleteRecursively()
        out.delete()
        TestCore.uninstall()
    }

    private fun scope(f: ChatFixture.Built, since: Long? = null, unsent: Boolean = false, pictures: Boolean = true, chats: Set<Long>? = null) =
        ChatScope(chats ?: setOf(f.person, f.shared, f.legacy, f.group), since, unsent, pictures)

    private fun snapshot(f: ChatFixture.Built, scope: ChatScope): ChatSnapshot =
        checkNotNull(ChatSnapshot.build(core.history, core.picturesDir, work, scope, f.now, core::exportLabel))

    private class Copy(val file: ByteArray, val records: List<HistoryBackup.Record>, val manifest: HistoryBackup.Manifest)

    private fun save(snap: ChatSnapshot): Copy {
        val preview = ChatExport.preview(snap, 5, 1) as ChatExport.Preview.Ok
        val saved = ChatBackup(work).save(snap, preview, key, 5, 1, { out.outputStream() }, { out.inputStream() })
        assertTrue("saved and checked: ${(saved as? ChatBackup.Saved.Failed)?.why}", saved is ChatBackup.Saved.Ok)
        assertEquals(preview.sealedBytes, out.length())
        return open(out.readBytes())
    }

    private fun open(bytes: ByteArray): Copy {
        val records = ArrayList<HistoryBackup.Record>()
        val read = ArchiveReader.read(ByteArrayInputStream(bytes), key, work) { records += it }
        return Copy(bytes, records, (read as ArchiveReader.Read.Ok).manifest)
    }

    private inline fun <reified T : HistoryBackup.Record> Copy.all() = records.filterIsInstance<T>()

    @Test fun everyMessageIsCountedAndSavedPastTheDisplayLimit() {
        TestCore.install()
        val f = ChatFixture.build(core)
        assertEquals("the display query would hold 500", 500, core.history.messages(f.person).size)
        snapshot(f, scope(f)).use { snap ->
            val copy = save(snap)
            val inPerson = copy.all<HistoryBackup.Message>().count { m -> copy.all<HistoryBackup.Chat>().single { it.id == m.chatId }.kind == HistoryBackup.PERSON }
            // 600 texts + 2 pictures; "never left" is held back (not chosen); the planned one too.
            assertEquals(ChatFixture.BIG + 2, inPerson)
            assertEquals(snap.counts().messages, copy.manifest.messages)
            assertEquals(copy.manifest.messages, copy.all<HistoryBackup.Message>().size)
            assertEquals("the planned message and the one that never left", 2, snap.left.unsent)
        }
    }

    @Test fun ninetyDaysKeepsTheRecentOnesAndMarksRepliesToOlderMessagesMissing() {
        TestCore.install()
        val f = ChatFixture.build(core)
        val since = f.now - 90 * ChatFixture.DAY
        snapshot(f, scope(f, since = since)).use { snap ->
            val copy = save(snap)
            val person = copy.all<HistoryBackup.Chat>().single { it.kind == HistoryBackup.PERSON }
            val inPerson = copy.all<HistoryBackup.Message>().filter { it.chatId == person.id }
            assertEquals("the 20 old ones are not in the range", ChatFixture.BIG - 20 + 2, inPerson.size)
            assertTrue(inPerson.all { it.time >= since })
            assertEquals(since, copy.manifest.scopeStart)
            assertEquals(f.now, copy.manifest.scopeEnd)
            for (text in listOf("bea 25", "me 28")) {
                val replyToOld = inPerson.single { it.text == text }
                assertTrue("$text: its target is older than the range", replyToOld.replyMissing)
                assertEquals(0L, replyToOld.replyId)
            }
        }
    }

    @Test fun allHistoryMapsRepliesAndKeepsAMissingTargetAsAMarker() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f)).use { snap ->
            val copy = save(snap)
            val byText = copy.all<HistoryBackup.Message>().associateBy { it.text }
            val old = byText.getValue("bea 1")
            for (text in listOf("bea 5", "me 4", "bea 25", "me 28")) {
                assertEquals(text, old.id, byText.getValue(text).replyId)
                assertFalse(text, byText.getValue(text).replyMissing)
            }
            assertTrue("a reply to a message that never existed", byText.getValue("me 30").replyMissing)
            assertEquals(0L, byText.getValue("me 30").replyId)
            assertEquals(0, copy.manifest.scopeStart.toInt())
            assertEquals(0L, copy.manifest.scopeEnd)
        }
    }

    @Test fun onlyTheChosenChatsAreInTheCopy() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f, chats = setOf(f.group))).use { snap ->
            val copy = save(snap)
            assertEquals(listOf(HistoryBackup.GROUP_CHAT), copy.all<HistoryBackup.Chat>().map { it.kind })
            assertTrue(copy.all<HistoryBackup.Message>().none { it.text.startsWith("bea ") || it.text.startsWith("me ") })
            assertEquals(HistoryBackup.GROUPS or HistoryBackup.PICTURE_DATA, copy.manifest.scopeFlags)
            assertEquals("contacts are always saved", 1, copy.all<HistoryBackup.Contact>().size)
        }
    }

    @Test fun aGroupChatIsReadOnlyHistoryNamedByWhatTheGroupKnows() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f, chats = setOf(f.group))).use { snap ->
            val copy = save(snap)
            val chat = copy.all<HistoryBackup.Chat>().single()
            assertEquals("Famille", chat.title)
            assertEquals(HistoryBackup.GROUP_CHAT, chat.kind)
            assertEquals(0L, chat.contactId)
            val messages = copy.all<HistoryBackup.Message>().associateBy { it.text }
            val bea = copy.all<HistoryBackup.Contact>().single { it.contact.savedName == "Bea" }.contact
            assertEquals("a contact is named by contact and label", bea.id, messages.getValue("on se retrouve ?").authorContactId)
            assertEquals("Bea", messages.getValue("on se retrouve ?").authorLabel)
            assertEquals("a member who is not a contact is only a name", 0L, messages.getValue("oui !").authorContactId)
            assertEquals("Yaw", messages.getValue("oui !").authorLabel)
            assertEquals(messages.getValue("on se retrouve ?").id, messages.getValue("oui !").replyId)
            assertTrue("my own message", messages.getValue("moi aussi").outgoing)
            assertEquals(1, messages.getValue("moi aussi").status)
            assertEquals("a group line the app wrote is not saved text", 1, snap.left.notes)
            assertEquals(1, copy.all<HistoryBackup.Reaction>().size)
            assertEquals("Yaw", copy.all<HistoryBackup.Reaction>().single().authorLabel)
        }
    }

    @Test fun noGroupKeyStateOrMemberListIsInTheCopyAndLivePrivatesAreUntouched() {
        TestCore.install()
        val f = ChatFixture.build(core)
        val before = liveRows()
        snapshot(f, scope(f)).use { snap ->
            val copy = save(snap)
            val plain = (BackupStream.openStream(key, ByteArrayInputStream(copy.file)) as BackupStream.Streamed.Ok).stream.readBytes()
            fun has(needle: ByteArray) = (0..plain.size - needle.size).any { i -> needle.indices.all { plain[i + it] == needle[it] } }
            assertFalse("the group id", has(ByteArray(16) { 0xAB.toByte() }))
            assertFalse("the group signer", has(ByteArray(33) { 2 }))
            assertFalse("the account id of a member who is not a contact", has(f.yaw.account.toByteArray()))
        }
        assertEquals("exporting changes no live row", before, liveRows())
        assertEquals("History is not attached to the scratch file any more", listOf("main"), databases())
    }

    @Test fun unsentTextIsLeftOutAndCountedUntilChosen() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f, chats = setOf(f.person))).use { snap ->
            val copy = save(snap)
            assertTrue(copy.all<HistoryBackup.Message>().none { it.text == "never left" || it.text == "planned hello" })
            assertEquals(0, copy.manifest.scopeFlags and HistoryBackup.UNSENT)
            assertEquals(2, snap.left.unsent)
        }
        snapshot(f, scope(f, chats = setOf(f.person), unsent = true)).use { snap ->
            val copy = save(snap)
            val stuck = copy.all<HistoryBackup.Message>().single { it.text == "never left" }
            assertEquals(5, stuck.status)
            val planned = copy.all<HistoryBackup.Message>().single { it.text == "planned hello" }
            assertEquals(5, planned.status)
            assertEquals(f.now + ChatFixture.DAY, planned.plannedAt)
            assertTrue(copy.manifest.scopeFlags and HistoryBackup.UNSENT != 0)
            assertEquals(0, snap.left.unsent)
        }
    }

    @Test fun picturesKeepTheirBytesAndALostOneIsACountedPlaceholder() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f, chats = setOf(f.person))).use { snap ->
            val copy = save(snap)
            val pictures = copy.all<HistoryBackup.Picture>().sortedWith(Comparator { a, b -> java.lang.Long.compare(a.id, b.id) })
            assertEquals(2, pictures.size)
            assertTrue(pictures[0].complete)
            assertArrayEquals(f.picture, pictures[0].data)
            assertFalse(pictures[1].complete)
            assertEquals(0, pictures[1].data.size)
            assertEquals(1, copy.manifest.missingPictures)
        }
        snapshot(f, scope(f, chats = setOf(f.person), pictures = false)).use { snap ->
            val copy = save(snap)
            assertTrue("no picture bytes were chosen", copy.all<HistoryBackup.Picture>().none { it.complete })
            assertEquals(2, copy.manifest.missingPictures)
            assertEquals(0, copy.manifest.scopeFlags and HistoryBackup.PICTURE_DATA)
        }
    }

    @Test fun reactionsKeepOnlyTargetsInTheRangeAndStatusesAreMapped() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f, since = f.now - 90 * ChatFixture.DAY, chats = setOf(f.person))).use { snap ->
            val copy = save(snap)
            assertEquals("the reaction to an old message is outside the range", listOf("👍", "❤️"), copy.all<HistoryBackup.Reaction>().map { it.emoji })
            assertEquals(1, snap.left.reactions)
            val sent = copy.all<HistoryBackup.Message>().filter { it.outgoing }.associateBy { it.text }
            assertEquals(3, sent.getValue("me 28").status)
            assertEquals(1, sent.getValue("me 30").status)
            assertTrue(copy.all<HistoryBackup.Message>().filter { !it.outgoing }.all { it.status == 0 })
        }
    }

    @Test fun theCopyIsAPictureOfTheMomentOfChoosingWhateverHappensAfter() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f, chats = setOf(f.person))).use { snap ->
            val counted = snap.counts().messages
            TestCore.onMain {
                core.history.addIncoming(f.person, f.bea.account, "later", "fr", f.now, false, packetId = 77_777L)
                val id = core.history.readableDatabase.rawQuery("SELECT id FROM messages WHERE text = 'bea 31'", null).use { it.moveToFirst(); it.getLong(0) }
                core.history.deleteMessage(id)
            }
            val copy = save(snap)
            assertEquals(counted, copy.manifest.messages)
            assertTrue(copy.all<HistoryBackup.Message>().any { it.text == "bea 31" })
            assertTrue(copy.all<HistoryBackup.Message>().none { it.text == "later" })
        }
    }

    @Test fun aSaveThatFailsSaysWhichStepAndNeverLeavesPlaintextBehind() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f, chats = setOf(f.person))).use { snap ->
            val preview = ChatExport.preview(snap, 5, 1) as ChatExport.Preview.Ok
            val backup = ChatBackup(work)
            val noDestination = backup.save(snap, preview, key, 5, 1, { throw IOException("gone") }, { null })
            assertEquals(ChatBackup.Why.DESTINATION, (noDestination as ChatBackup.Saved.Failed).why)
            val unreadable = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { null })
            assertEquals("written but never read back is not checked", ChatBackup.Why.NOT_CHECKED, (unreadable as ChatBackup.Saved.Failed).why)
            assertTrue(unreadable.partial)
            val cut = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { ByteArrayInputStream(out.readBytes().copyOf(1000)) })
            assertEquals(ChatBackup.Why.NOT_CHECKED, (cut as ChatBackup.Saved.Failed).why)
            val flipped = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { ByteArrayInputStream(out.readBytes().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }) })
            assertEquals(ChatBackup.Why.NOT_CHECKED, (flipped as ChatBackup.Saved.Failed).why)
            val other = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { ByteArrayInputStream(otherCopy(f)) })
            assertEquals("a different good file is not this copy", ChatBackup.Why.NOT_CHECKED, (other as ChatBackup.Saved.Failed).why)
            val cancelled = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { out.inputStream() }, cancelled = { true })
            assertEquals(ChatBackup.Why.CANCELLED, (cancelled as ChatBackup.Saved.Failed).why)
            assertEquals("nothing but the snapshot is left in the private folder", emptyList<String>(),
                (work.listFiles() ?: emptyArray()).map { it.name }.filterNot { it.startsWith(ChatSnapshot.PREFIX) })
        }
    }

    private fun otherCopy(f: ChatFixture.Built): ByteArray {
        snapshot(f, scope(f, chats = setOf(f.shared))).use { snap ->
            val preview = ChatExport.preview(snap, 5, 1) as ChatExport.Preview.Ok
            val bytes = ByteArrayOutputStream()
            SqlIndex(File(work, "index-other.db")).use { index -> ChatExport.seal(snap, key, 5, 1, preview.plainBytes, bytes, index) }
            return bytes.toByteArray()
        }
    }

    @Test fun theCopyHoldsNoReadableMessageAndSweepRemovesWhatACrashLeft() {
        TestCore.install()
        val f = ChatFixture.build(core)
        snapshot(f, scope(f, chats = setOf(f.group))).use { snap ->
            val copy = save(snap)
            val needle = "on se retrouve".toByteArray()
            assertFalse((0..copy.file.size - needle.size).any { i -> needle.indices.all { copy.file[i + it] == needle[it] } })
        }
        File(work, "snapshot-left.db").writeText("x"); File(work, "out-left.pmbk").writeText("x"); File(work, "index-left.db").writeText("x")
        ChatSnapshot.sweep(work); ChatBackup.sweep(work)
        assertEquals(emptyList<String>(), work.listFiles()!!.map { it.name })
    }

    @Test fun aPhoneWithoutANameHasNothingToSave() {
        TestCore.install(named = false)
        assertEquals(null, ChatSnapshot.build(core.history, core.picturesDir, work, ChatScope(emptySet(), null), 1, core::exportLabel))
    }

    private fun liveRows(): Map<String, Int> = TestCore.onMain {
        val db = core.history.readableDatabase
        listOf("messages", "chats", "contacts", "sessions", "outbox", "held", "private_groups", "group_members", "group_outbox", "planned", "pictures", "received_mail")
            .associateWith { db.rawQuery("SELECT COUNT(*) FROM $it", null).use { c -> c.moveToFirst(); c.getInt(0) } }
    }

    private fun databases(): List<String> = TestCore.onMain {
        core.history.readableDatabase.rawQuery("PRAGMA database_list", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
    }

    @Test fun aCopyIsSavedThroughTheContentResolverAndReadBackFromThere() {
        TestCore.install()
        val f = ChatFixture.build(core)
        val uri = android.net.Uri.fromFile(out)
        val resolver = context.contentResolver
        snapshot(f, scope(f)).use { snap ->
            val preview = ChatExport.preview(snap, 5, 1) as ChatExport.Preview.Ok
            val saved = ChatBackup(work).save(snap, preview, key, 5, 1, { resolver.openOutputStream(uri, "wt") }, { resolver.openInputStream(uri) })
            val ok = saved as ChatBackup.Saved.Ok
            assertEquals(out.length(), ok.bytes)
            assertEquals(preview.counts.messages, ok.counts.messages)
            assertEquals("read back from the saved file, every record", preview.counts.messages, open(out.readBytes()).manifest.messages)
        }
    }

    @Test fun aPictureFileThatIsNotAPictureOrIsTooBigIsAPlaceholderNotACopiedBlob() {
        TestCore.install()
        val f = ChatFixture.build(core)
        val file = File(core.picturesDir, "in-fixture.pic")
        file.writeBytes(ByteArray(300) { 1 })
        snapshot(f, scope(f, chats = setOf(f.person))).use { snap ->
            val copy = save(snap)
            assertTrue("a file that is not a WEBP is not saved as a picture", copy.all<HistoryBackup.Picture>().none { it.complete })
            assertEquals(2, copy.manifest.missingPictures)
        }
        file.writeBytes(ChatFixture.webp(HistoryBackup.MAX_PICTURE_BYTES + 1))
        snapshot(f, scope(f, chats = setOf(f.person))).use { snap ->
            assertTrue("past the picture limit", save(snap).all<HistoryBackup.Picture>().none { it.complete })
        }
        file.writeBytes(ChatFixture.webp(HistoryBackup.MAX_PICTURE_BYTES))
        snapshot(f, scope(f, chats = setOf(f.person))).use { snap ->
            assertEquals("at the limit it is kept", 1, save(snap).all<HistoryBackup.Picture>().count { it.complete })
        }
    }
}

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
import org.phonemesh.core.DeliveryStatus
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
            assertFalse("a file that could not be read back may be fine: it is kept", unreadable.discard)
            assertTrue("an early failure leaves the picker's empty document: it goes", (noDestination as ChatBackup.Saved.Failed).discard)
            val cut = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { ByteArrayInputStream(out.readBytes().copyOf(1000)) })
            assertEquals("read to its end and cut: a bad file", ChatBackup.Why.MISMATCH, (cut as ChatBackup.Saved.Failed).why)
            assertTrue("a known-bad document is removed (B4-7)", cut.discard && cut.partial)
            val flipped = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { ByteArrayInputStream(out.readBytes().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }) })
            assertEquals(ChatBackup.Why.MISMATCH, (flipped as ChatBackup.Saved.Failed).why)
            val other = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { ByteArrayInputStream(otherCopy(f)) })
            assertEquals("a different good file is not this copy", ChatBackup.Why.MISMATCH, (other as ChatBackup.Saved.Failed).why)
            val cancelled = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { out.inputStream() }, cancelled = { true })
            assertEquals(ChatBackup.Why.CANCELLED, (cancelled as ChatBackup.Saved.Failed).why)
            assertTrue(cancelled.discard)
            // No room: a failure before the destination is even asked for still removes the document the picker made.
            val huge = ChatExport.Preview.Ok(preview.counts, preview.records, preview.plainBytes, Long.MAX_VALUE / 4)
            val noRoom = backup.save(snap, huge, key, 5, 1, { throw AssertionError("never asked") }, { null }) as ChatBackup.Saved.Failed
            assertEquals(ChatBackup.Why.NO_SPACE, noRoom.why)
            assertTrue(noRoom.discard)
            // A bug in the app is not a problem with the place chosen (B4-15).
            val bug = backup.save(snap, preview, key, 5, 1, { out.outputStream() }, { throw IllegalStateException("bug") }) as ChatBackup.Saved.Failed
            assertEquals(ChatBackup.Why.NOT_CHECKED, bug.why)
            val wiped = RecoveryKey.generate().also { it.wipe() }
            assertEquals(ChatBackup.Why.FAILED, (backup.save(snap, preview, wiped, 5, 1, { out.outputStream() }, { null }) as ChatBackup.Saved.Failed).why)
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

    // ---- Review B4

    /** B4-1: a failure of this phone's storage while checking is its own answer, never "key or file does not match". */
    @Test fun aStorageFailureWhileReadingIsNotAVerdictOnTheFileOrTheKey() {
        TestCore.install()
        val f = ChatFixture.build(core)
        val bytes = snapshot(f, scope(f, chats = setOf(f.group))).use { save(it).file }
        fun read(thrown: RuntimeException?): ArchiveReader.Read = ArchiveReader.read(ByteArrayInputStream(bytes), key, work) { if (thrown != null) throw thrown }
        assertTrue(read(null) is ArchiveReader.Read.Ok)
        assertEquals(ArchiveReader.Problem.NO_SPACE, (read(android.database.sqlite.SQLiteFullException("full")) as ArchiveReader.Read.Failed).problem)
        assertEquals(ArchiveReader.Problem.STORAGE, (read(android.database.sqlite.SQLiteException("broken")) as ArchiveReader.Read.Failed).problem)
        assertEquals(ArchiveReader.Problem.STORAGE, (read(ArchiveReader.StorageFailure("picture file")) as ArchiveReader.Read.Failed).problem)
        assertEquals("a wrong key stays a wrong key", ArchiveReader.Problem.KEY_OR_FILE_DOES_NOT_MATCH,
            (ArchiveReader.read(ByteArrayInputStream(bytes), RecoveryKey.generate(), work) as ArchiveReader.Read.Failed).problem)
        assertEquals("a cancel is not swallowed", true, runCatching { read(java.util.concurrent.CancellationException("c")) }.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    /** B4-8: the reader closes what it was given, whatever happens. */
    @Test fun theReaderClosesItsInputOnSuccessFailureAndCancel() {
        TestCore.install()
        val f = ChatFixture.build(core)
        val bytes = snapshot(f, scope(f, chats = setOf(f.group))).use { save(it).file }
        class Tracking(b: ByteArray) : ByteArrayInputStream(b) { var closed = false; override fun close() { closed = true; super.close() } }
        for ((name, k, stop) in listOf(Triple("success", key, false), Triple("wrong key", RecoveryKey.generate(), false), Triple("cancel", key, true))) {
            val input = Tracking(bytes)
            runCatching { ArchiveReader.read(input, k, work, cancelled = { stop }) }
            assertTrue("$name: input closed", input.closed)
        }
        val short = Tracking(bytes.copyOf(40))
        ArchiveReader.read(short, key, work)
        assertTrue("a file that is not a copy: input closed", short.closed)
    }

    /** B4-11: nothing is shortened or turned into a placeholder without being counted; unconfirmed is not "unsent". */
    @Test fun shortenedMessagesAndUnconfirmedOnesAreCountedWithTheirOwnWords() {
        TestCore.install()
        val f = ChatFixture.build(core)
        TestCore.onMain {
            val h = core.history
            h.addIncoming(f.person, f.bea.account, "x".repeat(5_000), "fr", f.now - 1_000, false, packetId = 5_001L)
            h.addOutgoing(f.person, "sent but never confirmed", "fr", f.now - 2_000).also { h.setStatus(it.id, DeliveryStatus.UNCONFIRMED) }
            h.addIncomingPicture(f.person, f.bea.account, PictureRow(0, 9, 1, 600, 48, ByteArray(16) { 1 }, null, 1, 4, false), f.now - 500, false)
        }
        val missing = "Picture missing from this copy."
        snapshot(f, scope(f, chats = setOf(f.person))).use { snap ->
            assertEquals("the unconfirmed one is not an unsent one", 2, snap.left.unsent)
            assertEquals(1, snap.left.unconfirmed)
            assertEquals("the long message was cut", 1, snap.left.altered)
            assertTrue("the picture with impossible sizes is counted", snap.left.unsupported >= 1)
            val copy = save(snap)
            assertTrue(copy.all<HistoryBackup.Message>().none { it.text == "sent but never confirmed" })
            val cut = copy.all<HistoryBackup.Message>().single { it.text.startsWith("xxxx") }
            assertEquals(HistoryBackup.MAX_TEXT, cut.text.length)
            assertTrue("a picture message that cannot be saved stays as a text with a note", copy.all<HistoryBackup.Message>().any { it.text == missing && it.pictureId == 0L })
        }
        snapshot(f, scope(f, unsent = true, chats = setOf(f.person))).use { snap ->
            assertEquals(0, snap.left.unconfirmed)
            val copy = save(snap)
            assertEquals(6, copy.all<HistoryBackup.Message>().single { it.text == "sent but never confirmed" }.status)
        }
    }

    /** B4-10: the writer refuses nothing the phone really holds: a skewed clock, a repeated reaction, a title with a bidi override. */
    @Test fun whatThePhoneReallyHoldsAlwaysFitsTheRules() {
        TestCore.install()
        val f = ChatFixture.build(core)
        val tomorrowish = f.now + 10 * ChatFixture.DAY
        TestCore.onMain {
            val h = core.history
            h.addIncoming(f.person, f.bea.account, "from a clock ten days ahead", "fr", tomorrowish, false, packetId = 9_001L)
            h.addReaction(f.person, f.bea.account, "👍", 2000L + ChatFixture.BIG, f.now - 5 * 60_000L)
            h.privateGroupChat("cd".repeat(16), "Fa\u202Emille \u200Bdeux")
        }
        snapshot(f, scope(f, since = f.now - 90 * ChatFixture.DAY, chats = setOf(f.person, f.group) + TestCore.onMain { core.history.chats().filter { it.kind == ChatKind.PRIVATE_GROUP }.map { it.id } })).use { snap ->
            val copy = save(snap)
            assertTrue("the range includes the newest message", copy.manifest.scopeEnd >= tomorrowish)
            assertEquals("the same reaction twice is one", 1, copy.all<HistoryBackup.Reaction>().count { it.emoji == "👍" && it.authorContactId != 0L })
            assertTrue(copy.all<HistoryBackup.Chat>().none { c -> c.title.any { it == '\u202E' || it == '\u200B' } })
            assertTrue("the hidden characters are dropped, the name stays", copy.all<HistoryBackup.Chat>().any { it.title == "Famille deux" })
        }
    }

    /** B4-3: the history stays answerable while a big snapshot is taken (steps of a couple of thousand rows, not one lock). */
    @Test fun aBigSnapshotNeverHoldsTheHistoryForLong() {
        TestCore.install()
        val f = ChatFixture.build(core)
        TestCore.onMain {
            core.history.writableDatabase.execSQL(
                "INSERT INTO messages (chat_id, direction, kind, text, time, reaction, language) " +
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 120000) SELECT ?, 'IN', 'TEXT', 'filler ' || i, ?, 0, 'fr' FROM n",
                arrayOf<Any>(f.person, f.now - 60_000),
            )
        }
        val worst = LongArray(1)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var snap: ChatSnapshot? = null
        ChatSnapshot.steps = 0
        val worker = Thread { snap = snapshot(f, scope(f, chats = setOf(f.person))); done.set(true) }.also { it.start() }
        while (!done.get()) {
            val t = System.nanoTime()
            TestCore.onMain { core.history.chats() }
            worst[0] = maxOf(worst[0], (System.nanoTime() - t) / 1_000_000)
            Thread.sleep(5)
        }
        worker.join()
        snap!!.use { assertTrue("snapshot has the filler messages", it.counts().messages > 120_000) }
        assertTrue("the longest wait of a history call was ${worst[0]} ms", worst[0] < 600)
        assertTrue("one short transaction per couple of thousand rows, not one lock: ${ChatSnapshot.steps}", ChatSnapshot.steps >= 60)
    }

    /** B4-3: room is asked before anything is copied; a full disk is its own answer and leaves no scratch file. */
    @Test fun aSnapshotWithoutRoomIsRefusedBeforeAnythingIsCopied() {
        TestCore.install()
        val f = ChatFixture.build(core)
        val refused = runCatching {
            ChatSnapshot.build(core.history, core.picturesDir, work, scope(f), f.now, core::exportLabel, usableSpace = { 1_000 })
        }.exceptionOrNull()
        assertTrue("$refused", refused is ChatSnapshot.NoRoom)
        assertEquals(emptyList<String>(), work.listFiles()?.map { it.name }.orEmpty())
        val made = ChatSnapshot.build(core.history, core.picturesDir, work, scope(f), f.now, core::exportLabel)
        assertNotNull("with room it works", made)
        made!!.close()
    }
}

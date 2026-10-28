package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.backup.BackupFile
import org.phonemesh.core.backup.BackupStream
import org.phonemesh.core.backup.HistoryBackup
import org.phonemesh.core.backup.RecoveryKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CancellationException

/**
 * Restoring a full chat copy (v0.1.2 B2): into read-only archive tables, never live sessions, contacts, outbox or chats;
 * the same copy twice is idempotent, the same id with other bytes refused; every journal step survives a crash and a
 * reopen with exactly one whole copy or none; and a copy that fails any check changes nothing. Real History and files.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val core get() = TestCore.core
    private val key = RecoveryKey.generate()
    private val work get() = File(context.noBackupFilesDir, "backup-import-test")
    private val archives get() = File(context.filesDir, "archives")

    @After fun tearDown() {
        work.deleteRecursively()
        context.deleteDatabase(OLD)
        TestCore.uninstall()
    }

    /** Phone A saves a full copy, then this is a new phone (new identity, empty history). Returns the file and what A held. */
    private fun newPhoneWith(copyOf: (ChatFixture.Built) -> ByteArray = { ChatFixture.copy(core, it, key, work) }): Pair<ByteArray, ChatFixture.Built> {
        TestCore.install()
        val f = ChatFixture.build(core)
        val bytes = copyOf(f)
        TestCore.uninstall()
        TestCore.install()
        return bytes to f
    }

    private fun importer(h: History = core.history) = ArchiveImport(h, archives, work)

    private fun import(bytes: ByteArray, k: RecoveryKey = key, h: History = core.history, crashAt: ArchiveImport.Step? = null) =
        importer(h).import({ ByteArrayInputStream(bytes) }, k, 1_800_000_000_000, crashAt = crashAt)

    private fun live(): Map<String, Int> = TestCore.onMain {
        val db = core.history.readableDatabase
        listOf("messages", "chats", "contacts", "sessions", "outbox", "held", "private_groups", "group_members", "group_outbox", "group_profiles", "planned",
            "pictures", "received_mail", "qr_sessions", "hub_cursor", "account", "prekeys")
            .associateWith { db.rawQuery("SELECT COUNT(*) FROM $it", null).use { c -> c.moveToFirst(); c.getInt(0) } }
    }

    private fun count(table: String, h: History = core.history) = h.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    private fun assertNothingKept(h: History = core.history) {
        for (t in ArchiveRows.TABLES) assertEquals("$t is empty", 0, count(t, h))
        assertEquals("no picture folder", emptyList<String>(), archives.listFiles()?.map { it.name }.orEmpty())
        assertEquals("no staging or scratch file", emptyList<String>(), work.listFiles()?.map { it.name }.orEmpty())
    }

    @Test fun aCopyRestoresAsReadOnlyArchiveWithEveryMessageOfAFiveHundredPlusExport() {
        val (bytes, f) = newPhoneWith()
        val result = import(bytes) as ArchiveImport.Result.Saved
        val rows = ArchiveRows(core.history)
        val archive = checkNotNull(rows.archive(result.archiveId))
        assertEquals("Awa", archive.ownName)
        assertEquals(result.messages, archive.messages)
        assertEquals(count("archive_messages"), archive.messages)
        assertTrue("past the 500-message display limit", archive.messages > 500)
        assertEquals(4, archive.chats)
        val chats = rows.chats(result.archiveId)
        assertEquals(setOf(HistoryBackup.PERSON, HistoryBackup.SHARED_CHAT, HistoryBackup.LEGACY_CHAT, HistoryBackup.GROUP_CHAT), chats.map { it.kind }.toSet())
        val person = chats.single { it.kind == HistoryBackup.PERSON }
        assertEquals("Bea", person.title)
        assertEquals(ChatFixture.BIG + 2, person.messages)
        val group = chats.single { it.kind == HistoryBackup.GROUP_CHAT }
        assertEquals("Famille", group.title)
        val inGroup = rows.messages(result.archiveId, group.id)
        assertEquals(listOf("on se retrouve ?", "oui !", "il y avait ça avant", "moi aussi"), inGroup.map { it.text })
        assertEquals(listOf("Bea", "Yaw", "Bea", null), inGroup.map { it.authorLabel })
        assertEquals(inGroup[0].id, inGroup[1].replyId)
        assertEquals(1, rows.reactions(result.archiveId, group.id).size)
        // The picture bytes are in a file this app named, and a lost picture is a placeholder.
        val pictures = rows.messages(result.archiveId, person.id).filter { it.pictureId != 0L }.map { rows.picture(result.archiveId, it.pictureId)!! }
        val whole = pictures.single { it.complete }
        assertArrayEquals(f.picture, File(File(archives, ArchiveRows.hex(result.archiveId)), whole.file!!).readBytes())
        assertEquals(1, pictures.count { !it.complete && it.file == null })
        // The people are saved to add again, as a contacts-only copy would leave them.
        assertEquals(1, result.people)
        assertEquals(listOf("Bea"), core.history.recoveredRows().map { it.name })
        assertNull(rows.archive(ByteArray(16)))
        assertEquals("the journal is empty once done", 0, count("backup_imports"))
        assertEquals("the staging folder is gone", emptyList<String>(), work.listFiles()?.map { it.name }.orEmpty())
    }

    @Test fun aRestoreCreatesNothingLiveAndSendsAndSaysNothing() {
        val (bytes, _) = newPhoneWith()
        val before = live()
        val chatsBefore = TestCore.onMain { core.history.chats().map { it.id to it.unread } }
        val broadcasts = TestCore.radio.broadcasts.size
        val spoken = TestCore.speech.spoken.size
        assertTrue(import(bytes) is ArchiveImport.Result.Saved)
        assertEquals("no live row is made, changed or removed", before, live())
        assertEquals(chatsBefore, TestCore.onMain { core.history.chats().map { it.id to it.unread } })
        assertEquals("nothing sent", broadcasts, TestCore.radio.broadcasts.size)
        assertEquals("nothing spoken", spoken, TestCore.speech.spoken.size)
        assertTrue("no live chat is a group or a saved person's", TestCore.onMain { core.history.chats().all { it.kind == ChatKind.GROUP } })
        assertEquals(0, count("outbox") + count("held") + count("sessions") + count("contacts") + count("private_groups"))
        assertEquals("a new phone keeps its own identity", false, core.history.accountRow()!!.identity.contentEquals(
            ArchiveRows(core.history).archives().single().sourceIdentity))
    }

    @Test fun theSameCopyAgainChangesNothingAndTheSameIdWithOtherBytesIsRefused() {
        val (bytes, _) = newPhoneWith()
        val first = import(bytes) as ArchiveImport.Result.Saved
        val rows = count("archive_messages")
        val again = import(bytes)
        assertTrue(again is ArchiveImport.Result.Already)
        assertArrayEquals(first.archiveId, (again as ArchiveImport.Result.Already).archiveId)
        assertEquals(rows, count("archive_messages"))
        assertEquals(1, count("backup_archives"))
        // Another file with the same archive id (a forged or damaged-and-resealed copy) is refused, not merged or overwritten.
        val a = tiny(3, Fixed())
        val b = tiny(4, Fixed())
        assertTrue("same id", a.copyOfRange(12, 28).contentEquals(b.copyOfRange(12, 28)))
        assertFalse(a.contentEquals(b))
        assertTrue(import(a) is ArchiveImport.Result.Saved)
        val refused = import(b) as ArchiveImport.Result.Refused
        assertEquals(ArchiveImport.Refused.SAME_ID_OTHER_BYTES, refused.why)
        assertEquals("the first stays as it was", 3, ArchiveRows(core.history).archives().first { it.hex == ArchiveRows.hex(a.copyOfRange(12, 28)) }.messages)
        // A new snapshot from the same phone is another archive, kept beside the first.
        val (second, _) = newPhoneWith { ChatFixture.copy(core, it, key, work, chats = setOf(it.group)) }
        assertTrue(import(second) is ArchiveImport.Result.Saved)
    }

    @Test fun aCopyThatFailsAnyCheckChangesNothing() {
        val (bytes, _) = newPhoneWith { ChatFixture.copy(core, it, key, work, chats = setOf(it.person, it.group)) }
        val before = live()
        fun refused(file: ByteArray, k: RecoveryKey = key): ArchiveReader.Problem {
            val r = import(file, k)
            assertTrue("$r", r is ArchiveImport.Result.Failed)
            assertNothingKept()
            assertEquals(before, live())
            return (r as ArchiveImport.Result.Failed).problem
        }
        assertEquals(ArchiveReader.Problem.KEY_OR_FILE_DOES_NOT_MATCH, refused(bytes, RecoveryKey.generate()))
        for (at in listOf(130, 200, bytes.size / 2, bytes.size - 30, bytes.size - 1)) {
            assertEquals("byte $at changed", ArchiveReader.Problem.KEY_OR_FILE_DOES_NOT_MATCH, refused(bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }))
        }
        for (n in listOf(0, 50, 119, 120, 200, bytes.size / 2, bytes.size - 1)) assertTrue("cut at $n", refused(bytes.copyOf(n)) in setOf(ArchiveReader.Problem.NOT_A_COPY, ArchiveReader.Problem.KEY_OR_FILE_DOES_NOT_MATCH))
        assertEquals("a byte after the end", ArchiveReader.Problem.NOT_A_COPY, refused(bytes + 0))
        assertEquals(ArchiveReader.Problem.NOT_A_COPY, refused("hello, this is not a saved copy".toByteArray()))
        val contacts = BackupFile.seal(key, BackupFile.Purpose.CONTACTS, byteArrayOf(1))
        assertEquals("a contacts-only copy is not a chat copy", ArchiveReader.Problem.NOT_CHAT_COPY, refused(contacts))
        assertEquals("a copy whose records break a rule: authenticated but not valid", ArchiveReader.Problem.KEY_OR_FILE_DOES_NOT_MATCH, refused(sealed(byteArrayOf(1, 0, 0, 0, 0, 1, 9))))
    }

    @Test fun everyJournalStepSurvivesACrashAndAReopen() {
        val (bytes, _) = newPhoneWith { ChatFixture.copy(core, it, key, work, chats = setOf(it.person, it.group)) }
        for (step in ArchiveImport.Step.values()) {
            // The process "dies" right after [step]: no cleanup runs. Then the next start reads the journal.
            TestCore.install()
            val dead = History(context, TestCore.HISTORY)
            val crashed = runCatching { import(bytes, h = dead, crashAt = step) }.exceptionOrNull()
            assertTrue("$step: ${crashed}", crashed is ArchiveImport.Crash && crashed.at == step)
            dead.close()
            val reopened = History(context, TestCore.HISTORY)
            try {
                ArchiveImport.recover(reopened, archives, work)
                val committed = step >= ArchiveImport.Step.COMMITTED
                assertEquals("$step: exactly one whole copy or none", if (committed) 1 else 0, count("backup_archives", reopened))
                assertEquals("$step: the journal is empty", 0, count("backup_imports", reopened))
                assertEquals("$step: no staging", emptyList<String>(), work.listFiles()?.map { it.name }.orEmpty())
                if (committed) {
                    val row = ArchiveRows(reopened).archives().single()
                    assertEquals(row.messages, count("archive_messages", reopened))
                    assertEquals(row.chats, count("archive_chats", reopened))
                    assertEquals("$step: its picture folder is where the row says", listOf(row.hex), archives.listFiles()!!.map { it.name })
                    assertTrue(File(archives, row.hex).listFiles()!!.isNotEmpty())
                    assertTrue("$step: again is idempotent", import(bytes, h = reopened) is ArchiveImport.Result.Already)
                } else {
                    assertNothingKept(reopened)
                    assertTrue("$step: a retry works", import(bytes, h = reopened) is ArchiveImport.Result.Saved)
                    assertEquals(1, count("backup_archives", reopened))
                }
            } finally {
                reopened.close()
            }
        }
    }

    @Test fun aLateFailureInTheCommitLeavesNothingAndACancelStopsClean() {
        val (bytes, _) = newPhoneWith { ChatFixture.copy(core, it, key, work, chats = setOf(it.person, it.group)) }
        TestCore.onMain { core.history.writableDatabase.execSQL("CREATE TRIGGER fail_pictures BEFORE INSERT ON archive_pictures BEGIN SELECT RAISE(ABORT, 'late failure'); END") }
        val failure = runCatching { import(bytes) }.exceptionOrNull()
        assertNotNull(failure)
        assertNothingKept()
        TestCore.onMain { core.history.writableDatabase.execSQL("DROP TRIGGER fail_pictures") }
        var asked = 0
        val stopped = runCatching { importer().import({ ByteArrayInputStream(bytes) }, key, 1, cancelled = { ++asked > 50 }) }.exceptionOrNull()
        assertTrue(stopped is CancellationException)
        assertNothingKept()
        assertTrue("and then it works", import(bytes) is ArchiveImport.Result.Saved)
    }

    @Test fun recoverRemovesWhatNothingOwnsAndKeepsEveryCommittedCopy() {
        val (bytes, _) = newPhoneWith { ChatFixture.copy(core, it, key, work, chats = setOf(it.person, it.group)) }
        val saved = import(bytes) as ArchiveImport.Result.Saved
        File(archives, "deadbeef").apply { mkdirs(); File(this, "1.webp").writeText("x") }
        File(work, "staging-0123").apply { mkdirs(); File(this, "stage.db").writeText("x") }
        File(work, "index-left.db").writeText("x"); File(work, "snapshot-left.db").writeText("x"); File(work, "out-left.pmbk").writeText("x")
        ArchiveImport.recover(core.history, archives, work)
        assertEquals(listOf(ArchiveRows.hex(saved.archiveId)), archives.listFiles()!!.map { it.name })
        assertEquals(emptyList<String>(), work.listFiles()!!.map { it.name })
        assertEquals(1, count("backup_archives"))
        assertTrue(File(archives, ArchiveRows.hex(saved.archiveId)).listFiles()!!.isNotEmpty())
        ArchiveImport.recover(core.history, archives, work)
        assertEquals("nothing pending: a no-op", 1, count("backup_archives"))
    }

    @Test fun aPhoneKeepsAtMostEightCopiesAndDeletingOneRemovesItsRowsAndFolder() {
        val (bytes, _) = newPhoneWith { ChatFixture.copy(core, it, key, work, chats = setOf(it.group)) }
        val db = core.history.writableDatabase
        for (i in 1..ArchiveRows.MAX_ARCHIVES) db.execSQL("INSERT INTO backup_archives VALUES (?, ?, x'00', 1, 1, 'Awa', 0, 0, 0, 0, 0, 0, 0, 0, 0, ?, 1)", arrayOf<Any>("%032x".format(i), ByteArray(32), i))
        assertEquals(ArchiveImport.Refused.TOO_MANY_COPIES, (import(bytes) as ArchiveImport.Result.Refused).why)
        val rows = ArchiveRows(core.history)
        rows.delete(ArchiveRows.unhex("%032x".format(1)), archives)
        val saved = import(bytes) as ArchiveImport.Result.Saved
        assertTrue(File(archives, ArchiveRows.hex(saved.archiveId)).exists())
        rows.delete(saved.archiveId, archives)
        assertFalse(File(archives, ArchiveRows.hex(saved.archiveId)).exists())
        for (t in listOf("archive_chats", "archive_messages", "archive_contacts", "archive_reactions", "archive_pictures")) assertEquals(t, 0, count(t))
        assertEquals(ArchiveRows.MAX_ARCHIVES - 1, count("backup_archives"))
    }

    /** History 21 -> 22 from the real old shape: the archive tables are dropped, the version set back, a message kept. */
    @Test fun aVersion21HistoryGainsTheArchiveTablesAndKeepsItsMessages() {
        context.deleteDatabase(OLD)
        History(context, OLD).apply {
            val chat = personChat("0011223344556677", "Kofi")
            addIncoming(chat.id, "0011223344556677", "Salut", "fr", 5, countUnread = true)
            close()
        }
        context.openOrCreateDatabase(OLD, 0, null).use { db ->
            for (t in ArchiveRows.TABLES) db.execSQL("DROP TABLE IF EXISTS $t")
            db.execSQL("DROP INDEX IF EXISTS archive_messages_by_chat")
            db.version = 21
        }
        repeat(2) {
            val upgraded = History(context, OLD)
            try {
                val db = upgraded.readableDatabase
                assertEquals(22, db.version)
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
                assertTrue(tables.containsAll(ArchiveRows.TABLES))
                assertEquals("Salut", upgraded.lastMessage(upgraded.chats().single { it.name == "Kofi" }.id)!!.text)
                assertEquals(0, count("backup_archives", upgraded))
            } finally {
                upgraded.close()
            }
        }
    }

    // ---- helpers for hand-made copies

    private class Fixed : SecureRandom() {
        private val queue = ArrayDeque(listOf(ByteArray(16) { 0xA1.toByte() }, ByteArray(16) { 0x5A }, ByteArray(32) { it.toByte() }, ByteArray(12) { 0x77 }))
        override fun nextBytes(bytes: ByteArray) { queue.removeFirst().also { require(it.size == bytes.size) }.copyInto(bytes) }
    }

    private fun sealed(plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArrayOutputStream().also { BackupStream.seal(key, BackupFile.Purpose.HISTORY, ByteArrayInputStream(plain), plain.size.toLong(), it, random) }.toByteArray()

    /** A valid small copy with one group chat of [messages] messages. */
    private fun tiny(messages: Int, random: SecureRandom): ByteArray {
        val identity = core.history.accountRow()!!.identity
        val m = HistoryBackup.Manifest(identity, 1, 1, "Awa", 0, 0, 0, HistoryBackup.GROUPS, 0, 1, messages, 0, 0, 0)
        val out = ByteArrayOutputStream()
        HistoryBackup.Writer(out, m).apply {
            write(HistoryBackup.Chat(1, HistoryBackup.GROUP_CHAT, 0, "Famille", false))
            for (i in 1..messages) write(HistoryBackup.Message(i.toLong(), 1, false, 0, "Yaw", "message $i", null, i.toLong(), 0, 0, false, 0, 0))
            finish()
        }
        return sealed(out.toByteArray(), random)
    }

    private companion object {
        const val OLD = "archive-import-old.db"
    }
}

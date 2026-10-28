package org.phonemesh.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.widget.EditText
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.backup.RecoveryKey
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The screens of full chat copies (v0.1.2 B3): Recovery kit offers contacts only or contacts and chats; the scope
 * picker, the exact counts and size, the card, the save and its read-back; restoring into saved chats shown apart,
 * read only and marked "from this saved copy"; checking and removing a copy. The system file pickers are answered by
 * test monitors with a file in the app's cache, so a real file is written and read back.
 */
@RunWith(AndroidJUnit4::class)
class ChatBackupScreensTest {
    private val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val core get() = TestCore.core
    private val monitors = ArrayList<Instrumentation.ActivityMonitor>()
    private val copy = File(inst.targetContext.cacheDir, "chat-screens.pmbk")
    private val work get() = File(inst.targetContext.noBackupFilesDir, "backup-screens-test")
    private val key = RecoveryKey.generate()

    @After fun tearDown() {
        monitors.forEach { inst.removeMonitor(it) }
        copy.delete()
        work.deleteRecursively()
        TestCore.uninstall()
    }

    private fun answer(action: String) {
        monitors.forEach { inst.removeMonitor(it) }
        monitors.clear()
        monitors += inst.addMonitor(IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType(if (action == Intent.ACTION_CREATE_DOCUMENT) RecoveryActivity.MIME else "*/*")
        }, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(copy))), true)
    }

    /** Polls on the test thread: [Views] helpers hop to the main thread themselves, so they cannot run inside waitUntil. */
    private fun until(seconds: Int = 20, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(50)
        }
    }

    private fun note(a: Activity): String? = Views.tagged(a, "note")?.let { Views.textOf(it) }

    private fun checked(a: Activity, id: Long): Boolean {
        val v = Views.tagged(a, "scope_chat:$id") as Switch
        return TestCore.onMain { v.isChecked }
    }

    private fun clean(a: Activity, what: String) = assertTrue("$what: " + A11yCheck.problems(a).toString(), A11yCheck.problems(a).isEmpty())

    private fun typeWords(a: Activity, words: String) {
        val entry = Views.tagged(a, "recovery_entry") as EditText
        TestCore.onMain { entry.setText(words) }
        Views.tap(a, "recovery_open")
        inst.waitForIdleSync()
    }

    private fun wordsOf(k: RecoveryKey) = k.words(RecoveryKey.WordList.load("english")).joinToString(" ")

    private fun live(): Map<String, Int> = TestCore.onMain {
        val db = core.history.readableDatabase
        listOf("messages", "chats", "contacts", "sessions", "outbox", "held", "private_groups", "planned", "pictures")
            .associateWith { db.rawQuery("SELECT COUNT(*) FROM $it", null).use { c -> c.moveToFirst(); c.getInt(0) } }
    }

    @Test fun recoveryKitOffersContactsOnlyOrContactsAndChats() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val a = Views.start(RecoveryActivity::class.java)
        assertNotNull(Views.tagged(a, "recovery_make"))
        assertNotNull(Views.tagged(a, "recovery_make_chats"))
        assertEquals("Contacts only", Views.textOf(Views.tagged(a, "recovery_make")!!))
        assertEquals("Contacts and chats", Views.textOf(Views.tagged(a, "recovery_make_chats")!!))
        assertNull("no saved chats yet", Views.tagged(a, "recovery_archives"))
        clean(a, "start")
        Views.tap(a, "recovery_make_chats")
        until { Views.resumed() is ChatBackupActivity }
    }

    @Test fun aWholeSaveGoesThroughTheScreensAndIsReadBackBeforeItIsCalledChecked() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val f = ChatFixture.build(core)
        val a = Views.start(ChatBackupActivity::class.java)
        until { Views.tagged(a, "scope_count") != null }
        clean(a, "scope")
        assertTrue("a private chat is on", checked(a, f.person))
        assertTrue("a private group is on (decision 75)", checked(a, f.group))
        assertFalse("the shared chat is off until chosen", checked(a, f.shared))
        assertFalse("old radio chats are off until chosen", checked(a, f.legacy))
        // The last 90 days is the default; all history counts the 20 older messages too.
        until { Views.textOf(Views.tagged(a, "scope_chat:${f.person}")!!).contains("${ChatFixture.BIG - 20 + 2} messages") }
        Views.tap(a, "scope_all")
        until { Views.textOf(Views.tagged(a, "scope_chat:${f.person}")!!).contains("${ChatFixture.BIG + 2} messages") }
        Views.tap(a, "scope_count")
        until { Views.tagged(a, "preview_size") != null }
        val expected = ChatFixture.BIG + 2 + 4
        assertNotNull("exact counts before saving", Views.withText(a, "2 chats, $expected messages, 2 pictures."))
        assertNotNull(Views.withText(a, "2 unsent messages are left out."))
        assertNotNull(Views.withText(a, "1 group notices are left out."))
        assertNotNull(Views.withText(a, "1 pictures are missing."))
        assertTrue(Views.textOf(Views.tagged(a, "preview_size")!!).startsWith("Size: "))
        assertEquals("nothing is written before the person saves", 0L, copy.length())
        clean(a, "preview")
        Views.tap(a, "chatbackup_card")
        assertNotNull(Views.tagged(a, "chatbackup_qr"))
        val shown = Views.textOf(Views.tagged(a, "chatbackup_words")!!)
        val words = shown.split(Regex("\\s+")).filterNot { it.endsWith(".") && it.dropLast(1).all(Char::isDigit) }
        assertEquals(12, words.size)
        clean(a, "card")
        answer(Intent.ACTION_CREATE_DOCUMENT)
        Views.tap(a, "chatbackup_save")
        until(60) { Views.withText(a, "Saved and checked: 2 chats, $expected messages.") != null }
        assertNotNull(Views.withText(a, "Checked on this phone."))
        assertNull("the card is gone from the screen once saved", Views.tagged(a, "chatbackup_words"))
        clean(a, "done")
        // The file really holds it, opened with the typed words.
        val typed = RecoveryKey.fromWords(words.joinToString(" "), RecoveryKey.WordList.load("english"))!!
        val read = ArchiveReader.read(ByteArrayInputStream(copy.readBytes()), typed, work)
        assertEquals(expected, (read as ArchiveReader.Read.Ok).manifest.messages)
        assertTrue(core.settings.recoveryChecked > 0)
        assertEquals("the scratch folder holds nothing of the copy", emptyList<String>(), core.backupDir.listFiles()?.map { it.name }.orEmpty())
        Views.tap(a, "chatbackup_done")
        until { Views.resumed() !is ChatBackupActivity }
    }

    @Test fun aChoiceOfNoChatsSaysSoAndALimitSaysChooseLess() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val f = ChatFixture.build(core)
        val a = Views.start(ChatBackupActivity::class.java)
        until { Views.tagged(a, "scope_count") != null }
        for (id in listOf(f.person, f.group)) Views.tap(a, "scope_chat:$id")
        Views.tap(a, "scope_count")
        until { note(a) == "Choose at least one chat." }
        assertNull(Views.tagged(a, "preview_size"))
    }

    @Test fun aSaveThatFailsAtThePlaceSaysWhatToDo() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        ChatFixture.build(core)
        val a = Views.start(ChatBackupActivity::class.java)
        until { Views.tagged(a, "scope_count") != null }
        Views.tap(a, "scope_count")
        until { Views.tagged(a, "chatbackup_card") != null }
        Views.tap(a, "chatbackup_card")
        // A folder is not a place a file can be written to: the save fails at the destination.
        val folder = File(inst.targetContext.cacheDir, "not-a-file").apply { mkdirs() }
        monitors += inst.addMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType(RecoveryActivity.MIME) },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(folder))), true)
        Views.tap(a, "chatbackup_save")
        until(60) { Views.withText(a, "Could not save. Choose another place.") != null }
        assertNotNull("it can be tried again with the same card", Views.tagged(a, "chatbackup_retry"))
        clean(a, "failed")
        folder.deleteRecursively()
    }

    @Test fun aChatCopyIsRestoredIntoSavedChatsShownApartReadOnlyAndNothingLiveChanges() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val f = ChatFixture.build(core)
        copy.writeBytes(ChatFixture.copy(core, f, key, work))
        TestCore.uninstall()
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val before = live()
        val chatsBefore = TestCore.onMain { core.history.chats().map { it.id to it.unread } }
        val a = Views.start(RecoveryActivity::class.java)
        answer(Intent.ACTION_OPEN_DOCUMENT)
        Views.tap(a, "recovery_restore")
        until { Views.tagged(a, "recovery_entry") != null }
        typeWords(a, "abandon ".repeat(11) + "about")
        until { note(a) != null }
        assertTrue(note(a)!!, note(a) == "Check the twelve words." || note(a) == "Key or file does not match.")
        assertEquals("a wrong key keeps nothing", 0, ArchiveRows(core.history).archives().size)
        typeWords(a, wordsOf(key))
        until(60) { Views.tagged(a, "recovery_see_chats") != null }
        val row = ArchiveRows(core.history).archives().single()
        assertNotNull(Views.withText(a, "Saved apart: ${row.chats} chats, ${row.messages} messages."))
        assertNotNull(Views.withText(a, "From this saved copy."))
        assertNotNull(Views.withText(a, "These chats are read only."))
        clean(a, "restored")
        assertEquals(before, live())
        assertEquals(chatsBefore, TestCore.onMain { core.history.chats().map { it.id to it.unread } })
        Views.tap(a, "recovery_see_chats")
        until { Views.resumed() is ArchivesActivity }
        val list = Views.resumed()!!
        assertNotNull(Views.tagged(list, "archive_from:${row.hex}"))
        val chats = ArchiveRows(core.history).chats(row.archiveId)
        assertEquals(4, chats.size)
        clean(list, "archives")
        val group = chats.single { it.kind == org.phonemesh.core.backup.HistoryBackup.GROUP_CHAT }
        Views.tap(list, "archive_chat:${row.hex}:${group.id}")
        until { Views.resumed() is ArchiveChatActivity }
        val chat = Views.resumed()!!
        until { Views.withText(chat, "oui !") != null }
        assertNotNull(Views.tagged(chat, "archive_banner"))
        assertNotNull(Views.withText(chat, "Yaw"))
        assertNotNull("my own message keeps the status it had when saved", Views.withText(chat, "Sent when saved") ?: Views.all(chat).firstOrNull { it is android.widget.TextView && (it as android.widget.TextView).text.contains("Sent when saved") })
        clean(chat, "archive chat")
        // Read only: no field to type in, no send, speak or reply control, nothing spoken or sent.
        assertTrue(Views.all(chat).none { it is EditText })
        assertTrue(Views.all(chat).none { (it.tag as? String)?.let { t -> t in setOf("mic", "send", "draft", "reply") } == true })
        assertTrue(TestCore.speech.spoken.isEmpty())
        assertTrue(TestCore.radio.broadcasts.isEmpty())
    }

    @Test fun theSameCopyAgainIsAlreadySavedAndACopyCanBeCheckedWithoutKeepingAnything() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val f = ChatFixture.build(core)
        copy.writeBytes(ChatFixture.copy(core, f, key, work, chats = setOf(f.group)))
        TestCore.uninstall()
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val a = Views.start(RecoveryActivity::class.java)
        // Check: it opens with the card and keeps nothing; the check date is set.
        answer(Intent.ACTION_OPEN_DOCUMENT)
        Views.tap(a, "recovery_check")
        until { Views.tagged(a, "recovery_entry") != null }
        assertEquals(0L, core.settings.recoveryChecked)
        typeWords(a, wordsOf(key))
        until(60) { note(a)?.startsWith("It works: 1 chats, ") == true }
        assertTrue(core.settings.recoveryChecked > 0)
        assertEquals(0, ArchiveRows(core.history).archives().size)
        // Restore twice: the second is "already saved", not a second copy.
        Views.tap(a, "recovery_restore")
        until { Views.tagged(a, "recovery_entry") != null }
        typeWords(a, wordsOf(key))
        until(60) { Views.tagged(a, "recovery_see_chats") != null }
        Views.tap(a, "recovery_restored_done")
        until { Views.tagged(a, "recovery_archives") != null }
        assertEquals("Saved chats (1)", Views.textOf(Views.tagged(a, "recovery_archives")!!))
        Views.tap(a, "recovery_restore")
        until { Views.tagged(a, "recovery_entry") != null }
        typeWords(a, wordsOf(key))
        until(60) { note(a) == "This copy is already saved." }
        assertEquals(1, ArchiveRows(core.history).archives().size)
    }

    @Test fun aSavedCopyCanBeRemovedFromThePhoneAndItsFileIsUntouched() {
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val f = ChatFixture.build(core)
        val bytes = ChatFixture.copy(core, f, key, work, chats = setOf(f.group))
        copy.writeBytes(bytes)
        TestCore.uninstall()
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val result = ArchiveImport(core.history, core.archivesDir, core.backupDir).import({ ByteArrayInputStream(bytes) }, key, 5) as ArchiveImport.Result.Saved
        val hex = ArchiveRows.hex(result.archiveId)
        val a = Views.start(ArchivesActivity::class.java)
        until { Views.tagged(a, "archive_remove:$hex") != null }
        assertTrue(File(core.archivesDir, hex).exists())
        Views.tap(a, "archive_remove:$hex")
        val asked = checkNotNull(a.removeDialogForTest)
        assertTrue(A11yCheck.problems(asked).toString(), A11yCheck.problems(asked).isEmpty())
        TestCore.onMain { asked.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick() }
        inst.waitForIdleSync()
        until { Views.tagged(a, "archives_empty") != null }
        assertEquals(0, ArchiveRows(core.history).archives().size)
        assertFalse(File(core.archivesDir, hex).exists())
        assertEquals("the file it came from stays", bytes.size.toLong(), copy.length())
    }

    @Test fun theScreensAreFrenchWhenTheAppIs() {
        TestCore.install()
        ChatFixture.build(core)
        val a = Views.start(ChatBackupActivity::class.java)
        until { Views.tagged(a, "scope_count") != null }
        assertEquals("90 derniers jours", Views.textOf(Views.tagged(a, "scope_90")!!))
        assertEquals("Compter et mesurer", Views.textOf(Views.tagged(a, "scope_count")!!))
        clean(a, "scope fr")
        Views.tap(a, "scope_count")
        until { Views.tagged(a, "preview_size") != null }
        assertTrue(Views.textOf(Views.tagged(a, "preview_size")!!).startsWith("Taille : "))
        clean(a, "preview fr")
    }
}

package org.phonemesh.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.backup.HistoryBackup
import org.phonemesh.core.backup.RecoveryKey
import java.io.File

/**
 * Captures the full chat copy screens for review, light theme, English (v0.1.2 B3). Runs only in the screenshot build
 * (`-Pphonemesh.screenshots=true`, no FLAG_SECURE) and skips otherwise; the PNGs land in the app's external files.
 */
@RunWith(AndroidJUnit4::class)
class ChatBackupShotsTest {
    private val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val core get() = TestCore.core
    private val monitors = ArrayList<Instrumentation.ActivityMonitor>()
    private val copy = File(inst.targetContext.cacheDir, "chat-shots.pmbk")
    private val work get() = File(inst.targetContext.noBackupFilesDir, "backup-shots-test")
    private val dir get() = File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "chat-backup-shots")

    @After fun tearDown() {
        monitors.forEach { inst.removeMonitor(it) }
        copy.delete()
        work.deleteRecursively()
        TestCore.nextTheme = ThemeMode.AUTO
        TestCore.uninstall()
    }

    private fun shot(name: String) {
        val activity = checkNotNull(Views.resumed())
        Views.waitUntil { activity.window.decorView.isLaidOut && !activity.window.decorView.isLayoutRequested }
        inst.waitForIdleSync()
        Thread.sleep(900)
        val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot()) { "no capture of $name" }
        try { File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
    }

    private fun answer(action: String) {
        monitors.forEach { inst.removeMonitor(it) }
        monitors.clear()
        monitors += inst.addMonitor(IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType(if (action == Intent.ACTION_CREATE_DOCUMENT) RecoveryActivity.MIME else "*/*")
        }, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(copy))), true)
    }

    private fun until(seconds: Int = 30, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(50) }
    }

    @Test fun captureChatCopyScreens() {
        assumeFalse("only in the screenshot build", BuildConfig.SECURE_SCREENS)
        check(dir.deleteRecursively() || !dir.exists()); check(dir.mkdirs())
        TestCore.nextTheme = ThemeMode.LIGHT
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        val f = ChatFixture.build(core)
        val start = Views.start(RecoveryActivity::class.java)
        shot("01-recovery-kit")
        Views.finish(start)
        val a = Views.start(ChatBackupActivity::class.java)
        until { Views.tagged(a, "scope_count") != null }
        shot("02-choose-chats")
        Views.tap(a, "scope_count")
        until { Views.tagged(a, "preview_size") != null }
        shot("03-counts-and-size")
        Views.tap(a, "chatbackup_card")
        until { Views.tagged(a, "chatbackup_words") != null }
        shot("04-card")
        val words = Views.textOf(Views.tagged(a, "chatbackup_words")!!).split(Regex("\\s+")).filterNot { it.endsWith(".") && it.dropLast(1).all(Char::isDigit) }
        answer(Intent.ACTION_CREATE_DOCUMENT)
        Views.tap(a, "chatbackup_save")
        until(90) { Views.tagged(a, "chatbackup_done") != null }
        shot("05-saved-and-checked")
        Views.finish(a)
        // A new phone restores the file.
        val key = RecoveryKey.fromWords(words.joinToString(" "), RecoveryKey.WordList.load("english"))!!
        val bytes = copy.readBytes()
        TestCore.uninstall()
        TestCore.nextTheme = ThemeMode.LIGHT
        TestCore.install(language = "en-US", voices = listOf("en-US"))
        copy.writeBytes(bytes)
        val r = Views.start(RecoveryActivity::class.java)
        answer(Intent.ACTION_OPEN_DOCUMENT)
        Views.tap(r, "recovery_restore")
        until { Views.tagged(r, "recovery_entry") != null }
        val entry = Views.tagged(r, "recovery_entry") as EditText
        TestCore.onMain { entry.setText(key.words(RecoveryKey.WordList.load("english")).joinToString(" ")) }
        shot("06-restore-words")
        Views.tap(r, "recovery_open")
        until(90) { Views.tagged(r, "recovery_see_chats") != null }
        shot("07-restored-apart")
        val row = ArchiveRows(core.history).archives().single()
        Views.finish(r)
        val list = Views.start(ArchivesActivity::class.java)
        until { Views.tagged(list, "archive_from:${row.hex}") != null }
        shot("08-saved-chats")
        val group = ArchiveRows(core.history).chats(row.archiveId).single { it.kind == HistoryBackup.GROUP_CHAT }
        Views.finish(list)
        val chat = Views.launch<ArchiveChatActivity>(ArchiveChatActivity.intent(inst.targetContext, row.hex, group.id))
        until { Views.withText(chat, "oui !") != null }
        shot("09-saved-group-chat")
        val person = ArchiveRows(core.history).chats(row.archiveId).single { it.kind == HistoryBackup.PERSON }
        Views.finish(chat)
        val personChat = Views.launch<ArchiveChatActivity>(ArchiveChatActivity.intent(inst.targetContext, row.hex, person.id))
        until { Views.tagged(personChat, "archive_banner") != null }
        shot("10-saved-person-chat")
        assertTrue(dir.listFiles()!!.size == 10)
        check(f.person > 0)
    }
}

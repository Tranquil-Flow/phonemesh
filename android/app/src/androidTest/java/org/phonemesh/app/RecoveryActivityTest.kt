package org.phonemesh.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Recovery kit screen (account recovery 5.3-5.4, RC4 A): make a card, save the file, try it with the card typed
 * again, then restore it on a new phone into saved people. The system file pickers are answered by test monitors
 * with a file in the app's cache.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryActivityTest {
    private val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val core get() = TestCore.core
    private val monitors = ArrayList<Instrumentation.ActivityMonitor>()
    private val copy = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "test-copy.pmbk")

    @After fun tearDown() {
        monitors.forEach { inst.removeMonitor(it) }
        copy.delete()
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
    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(50)
        }
    }

    private fun note(a: Activity): String? = Views.tagged(a, "note")?.let { Views.textOf(it) }

    private fun type(a: Activity, text: String) {
        val entry = Views.tagged(a, "recovery_entry") as EditText
        TestCore.onMain { entry.setText(text) }
        Views.tap(a, "recovery_open")
        inst.waitForIdleSync()
    }

    @Test fun aKitIsSavedThenCheckedWithTheCardTypedAgain() {
        TestCore.install()
        PersonFixture.meet()
        val a = Views.start(RecoveryActivity::class.java)
        assertTrue(A11yCheck.problems(a).toString(), A11yCheck.problems(a).isEmpty())
        assertNull("no check yet", Views.withText(a, "Vérifiée sur ce téléphone"))
        Views.tap(a, "recovery_make")
        assertNotNull(Views.tagged(a, "recovery_qr"))
        val shown = Views.textOf(Views.tagged(a, "recovery_words")!!)
        val words = shown.split(Regex("\\s+")).filterNot { it.endsWith(".") && it.dropLast(1).all(Char::isDigit) }
        assertEquals("twelve French words", 12, words.size)
        assertTrue(A11yCheck.problems(a).toString(), A11yCheck.problems(a).isEmpty())

        answer(Intent.ACTION_CREATE_DOCUMENT)
        Views.tap(a, "recovery_save")
        until { copy.length() > 120 }
        assertNull("the card is gone from the screen once saved", Views.tagged(a, "recovery_words"))

        answer(Intent.ACTION_OPEN_DOCUMENT)
        Views.tap(a, "recovery_try")
        until { Views.tagged(a, "recovery_entry") != null }
        type(a, words.reversed().joinToString(" "))
        until { note(a) != null }
        assertTrue(note(a)!!, note(a) == "Vérifiez les douze mots." || note(a) == "La clé ou le fichier ne correspond pas.")
        assertEquals(0L, core.settings.recoveryChecked)
        type(a, words.joinToString(" "))
        until { note(a) == "Elle marche : 1 contacts gardés." }
        assertTrue(core.settings.recoveryChecked > 0)
        assertNotNull(Views.tagged(a, "recovery_make"))
    }

    @Test fun aRestoreOnANewPhoneShowsThePeopleThenKeepsThemToAddAgain() {
        TestCore.install()
        PersonFixture.meet()
        val a = Views.start(RecoveryActivity::class.java)
        Views.tap(a, "recovery_make")
        val words = Views.textOf(Views.tagged(a, "recovery_words")!!).split(Regex("\\s+"))
            .filterNot { it.endsWith(".") && it.dropLast(1).all(Char::isDigit) }
        answer(Intent.ACTION_CREATE_DOCUMENT)
        Views.tap(a, "recovery_save")
        until { copy.length() > 120 }
        Views.finish(a)
        val saved = copy.readBytes()
        TestCore.uninstall()

        TestCore.install()
        copy.writeBytes(saved)
        val b = Views.start(RecoveryActivity::class.java)
        answer(Intent.ACTION_OPEN_DOCUMENT)
        Views.tap(b, "recovery_restore")
        until { Views.tagged(b, "recovery_entry") != null }
        type(b, words.joinToString(" "))
        until { Views.withText(b, "1 contacts dans cette copie.") != null }
        assertNotNull(Views.withText(b, "Bea"))
        assertNotNull(Views.withText(b, "Ce téléphone a une nouvelle identité."))
        assertTrue(A11yCheck.problems(b).toString(), A11yCheck.problems(b).isEmpty())
        Views.tap(b, "recovery_keep")
        until { Views.tagged(b, "recovered:Bea") != null }
        assertTrue("saved people are not contacts", core.history.contactRows().isEmpty())
        assertEquals("Gardés. Ajoutez chaque personne à nouveau.", note(b))
        assertTrue(TestCore.radio.broadcasts.isEmpty())
    }
}

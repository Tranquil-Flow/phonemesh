package org.phonemesh.app

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore
import org.phonemesh.core.Readiness

/**
 * The pack list (Settings) and packs opened from other apps (Chats). The app's own pack folder is
 * emptied first and last (emulator only). Real packs come from push_test_packs.sh; speech is
 * synthesized to samples only: no microphone, no playback.
 */
@RunWith(AndroidJUnit4::class)
class PackManagerUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var screen: android.app.Activity? = null
    private val core get() = TestCore.core

    @After fun tearDown() {
        Views.finish(screen)
        TestCore.uninstall()
    }

    private fun settle() {
        inst.waitForIdleSync()
        core.awaitWorkForTest()
        inst.waitForIdleSync()
    }
    private fun note(): String = Views.textOf(checkNotNull(Views.tagged(checkNotNull(screen), "note")))
    private fun installedIds() = core.store.list().map { it.id }
    private fun view() = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(FixturePackProvider.VOICE), "application/zip")
    private fun share() = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, Uri.parse(FixturePackProvider.VOICE))
    private fun chats(): ChatsActivity = Views.start(ChatsActivity::class.java).also { screen = it }
    private fun deliver(intent: Intent) {
        val activity = screen as ChatsActivity
        TestCore.onMain { activity.onNewIntent(intent) }
        settle()
    }
    private fun press(which: Int) {
        val dialog = checkNotNull((screen as ChatsActivity).importDialogForTest) { "no confirmation shown" }
        TestCore.onMain { dialog.getButton(which).performClick() }
        settle()
    }

    @Test fun removingTheLoadedVoiceDropsSpeechWithoutCrashing() {
        TestCore.install(voices = emptyList(), realSpeech = true)
        val engine = Fixtures.real(context, "phonemesh-engine-whisper-tiny-1.zip")
        Fixtures.real(context, "phonemesh-voice-fr-siwis-1.zip")
        TestCore.onMain { core.settings.engine = engine.id }
        val speech = core.speech as DeviceSpeech
        speech.synthesizeForTest(core.readiness() as Readiness.Ready, "Bonjour.")
        assertTrue("speech did not load", speech.loadedForTest())
        screen = Views.start(SettingsActivity::class.java)
        Views.tap(screen!!, "remove:voice-fr-siwis")
        settle()
        assertNotNull(Views.tagged(screen!!, "download:voice-fr-siwis"))
        assertFalse("speech still holds the removed voice", speech.loadedForTest())
        assertTrue(core.readiness() is Readiness.MissingVoice)
        assertTrue(PackStore(Fixtures.packDir(context), GeneratedPackCatalog.entries).list().none { it.id == "voice-fr-siwis" })
    }

    /** sherpa-onnx sets up espeak-ng once per process from the first voice; removing it must not break the other language. */
    @Test fun englishStillSpeaksAfterTheFrenchVoiceIsRemoved() {
        TestCore.install(voices = emptyList(), realSpeech = true)
        val engine = Fixtures.real(context, "phonemesh-engine-whisper-tiny-1.zip")
        Fixtures.real(context, "phonemesh-voice-fr-siwis-1.zip")
        Fixtures.real(context, "phonemesh-voice-en-US-ljspeech-1.zip")
        TestCore.onMain { core.settings.engine = engine.id }
        val speech = core.speech as DeviceSpeech
        val store = PackStore(Fixtures.packDir(context), GeneratedPackCatalog.entries)
        fun ready(tag: String) = org.phonemesh.core.Readiness.Ready(store.list().first { it.id == engine.id },
            store.list().first { it.languageTag == tag })
        val french = speech.synthesizeForTest(ready("fr"), "Bonjour, ceci est un message radio.")
        assertTrue(french.sampleRate > 0 && french.samples.isNotEmpty())
        screen = Views.start(SettingsActivity::class.java)
        Views.tap(screen!!, "remove:voice-fr-siwis")
        settle()
        assertTrue(store.list().none { it.id == "voice-fr-siwis" })
        val english = speech.synthesizeForTest(ready("en-US"), "This is a radio test. Meet me at the library.")
        // Without pronunciation data the sentence collapses to about 0.3 s of sound (about 2.9 s when intact).
        val seconds = english.samples.size.toDouble() / english.sampleRate
        assertTrue("English speech is only $seconds s long", seconds > 1.5)
        val heard = speech.transcribeForTest(ready("en-US"), english)
        assertTrue("Unexpected transcript: $heard", heard.lowercase().contains("radio"))
    }

    @Test fun aDownloadInProgressCanBeCancelled() {
        TestCore.install(voices = emptyList())
        // Nothing listens on port 9, so the download keeps waiting to retry instead of finishing.
        val voice = GeneratedPackCatalog.entries.first { it.id == "voice-fr-siwis" }
        PackDownloads(context, PackStore(Fixtures.packDir(context), GeneratedPackCatalog.entries), "http://10.0.2.2:9/").start(voice)
        screen = Views.start(SettingsActivity::class.java)
        assertEquals("Annuler Français", Views.tagged(screen!!, "cancel:voice-fr-siwis")!!.contentDescription)
        Views.tap(screen!!, "cancel:voice-fr-siwis")
        assertEquals("Téléchargement annulé : Français", note())
        assertTrue(core.downloads().pending().isEmpty())
        assertNull(Views.tagged(screen!!, "cancel:voice-fr-siwis"))
        assertNotNull(Views.tagged(screen!!, "download:voice-fr-siwis"))
    }

    @Test fun downloadIsRefusedWhileRecording() {
        TestCore.install()
        TestCore.onMain { core.startRecording(core.groupChatId()) }
        screen = Views.start(SettingsActivity::class.java)
        Views.tap(screen!!, "download:voice-en-US-ljspeech")
        assertEquals("Attendez la fin de l'opération.", note())
        assertTrue(core.downloads().pending().isEmpty())
    }

    @Test fun aPackFromAnotherAppIsInstalledOnlyAfterConfirmation() {
        TestCore.install(voices = emptyList())
        val activity = chats()
        deliver(view())
        assertNotNull("no confirmation shown", activity.importDialogForTest)
        assertTrue("installed before confirming", installedIds().isEmpty())
        assertNull("the handled intent is forgotten", activity.intent.data)
        press(DialogInterface.BUTTON_POSITIVE)
        assertEquals(listOf("voice-fr-siwis"), installedIds())
        assertEquals("Pack importé : Français", note())
    }

    @Test fun decliningAPackFromAnotherAppInstallsNothing() {
        TestCore.install(voices = emptyList())
        val activity = chats()
        deliver(view())
        press(DialogInterface.BUTTON_NEGATIVE)
        assertNull(activity.importDialogForTest)
        deliver(share())
        val dialog = checkNotNull(activity.importDialogForTest) { "no confirmation for a shared ZIP" }
        TestCore.onMain { dialog.cancel() } // back button or tap outside
        settle()
        assertNull(activity.importDialogForTest)
        assertTrue(installedIds().isEmpty())
    }

    @Test fun reopeningFromRecentsDoesNotImportAgain() {
        TestCore.install(voices = emptyList())
        screen = Views.launch<ChatsActivity>(view().setClass(context, ChatsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        settle()
        assertNull("asked again after a Recents relaunch", (screen as ChatsActivity).importDialogForTest)
        assertTrue(installedIds().isEmpty())
    }

    @Test fun anUnreadableFileGetsItsOwnMessage() {
        TestCore.install(voices = emptyList())
        chats()
        deliver(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(FixturePackProvider.MISSING), "application/zip"))
        press(DialogInterface.BUTTON_POSITIVE)
        assertEquals("Fichier inaccessible. Réessayez.", note())
        assertTrue(installedIds().isEmpty())
    }

    @Test fun aPackOpenedWhileRecordingIsRefused() {
        TestCore.install()
        val before = installedIds()
        TestCore.onMain { core.startRecording(core.groupChatId()) }
        chats()
        deliver(view())
        assertNull("a confirmation was shown while recording", (screen as ChatsActivity).importDialogForTest)
        assertEquals("Attendez la fin de l'opération.", note())
        assertEquals(before, installedIds())
    }
}

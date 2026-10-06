package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The pack list on the main screen. Uses the app's own pack directory, which every test empties
 * first and last (emulator only). Real packs come from the ZIPs pushed by push_test_packs.sh.
 * Speech is synthesized to samples only: no microphone, no playback.
 */
@RunWith(AndroidJUnit4::class)
class PackManagerUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context: Context = inst.targetContext
    private val packDir = File(context.filesDir, "packs")
    private val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
    private val savedPrefs = mutableMapOf<String, String?>()
    private var activity: MainActivity? = null

    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun views(): List<View> {
        var all = emptyList<View>()
        inst.runOnMainSync { all = descendants(activity!!.window.decorView) }
        return all
    }
    private fun described(text: String): View? = views().firstOrNull { it.contentDescription == text }
    private fun textOf(description: String): String {
        var text = ""
        val view = described(description) as TextView
        inst.runOnMainSync { text = view.text.toString() }
        return text
    }
    private fun clear() {
        val downloads = PackDownloads(context, PackStore(packDir, GeneratedPackCatalog.entries, allowUnknown = true))
        downloads.pending().keys.forEach { downloads.cancel(it) }
        packDir.deleteRecursively()
    }
    private fun launch(): MainActivity {
        activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        inst.waitForIdleSync()
        return activity!!
    }
    /** Waits for queued work and the screen updates it posted. */
    private fun settle() { inst.waitForIdleSync(); activity!!.awaitWorkForTest(); inst.waitForIdleSync() }
    private fun tap(description: String) {
        val button = checkNotNull(described(description)) { "No view described as $description" }
        inst.runOnMainSync { button.performClick() }
        settle()
    }
    private fun real(name: String): PackInfo {
        val archive = File(checkNotNull(context.getExternalFilesDir(null)), name)
        assertTrue("Missing model pack $archive (run push_test_packs.sh)", archive.isFile)
        return archive.inputStream().use { PackStore(packDir, GeneratedPackCatalog.entries).install(it) }
    }
    private fun archive(files: Map<String, String>): ByteArrayInputStream {
        val all = LinkedHashMap(files)
        all["SHA256SUMS"] = files.toSortedMap().map { (path, text) ->
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } + "  " + path + "\n"
        }.joinToString("")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> all.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return ByteArrayInputStream(output.toByteArray())
    }
    private fun fixtureVoice() = archive(linkedMapOf(
        "pack.properties" to "format=2\nkind=voice\nid=voice-fr-siwis\nversion=1\nlabel=Français\nlanguageTag=fr\nwhisperLanguage=fr\n",
        "tts/model.onnx" to "fixture", "tts/tokens.txt" to "fixture", "tts/espeak-ng-data/phontab" to "fixture",
        "tts/espeak-ng-data/phondata" to "fixture", "tts/espeak-ng-data/phonindex" to "fixture",
        "tts/espeak-ng-data/intonations" to "fixture", "tts/espeak-ng-data/lang/roa/fr" to "fixture"))

    @Before fun setUp() {
        listOf("language", "engine").forEach { savedPrefs[it] = prefs.getString(it, null) }
        prefs.edit().putString("language", "fr").remove("engine").commit()
        clear()
    }

    @After fun tearDown() {
        inst.runOnMainSync { activity?.finish() }
        inst.waitForIdleSync()
        clear()
        prefs.edit().also { e -> savedPrefs.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) } }.commit()
    }

    @Test fun listsEveryCatalogPackWithItsDownloadSize() {
        launch()
        assertNotNull(described("Language packs"))
        GeneratedPackCatalog.entries.forEach { entry ->
            val button = described("Download ${entry.id}") as? Button
            assertNotNull("No download button for ${entry.id}", button)
            val size = "%.0f Mo".format(entry.zipBytes / 1e6)
            assertTrue("${button!!.text} lacks $size", button.text.toString().contains(size))
        }
    }

    @Test fun anInstalledVoiceCanBeRemoved() {
        val activity = launch()
        PackStore(packDir, GeneratedPackCatalog.entries, allowUnknown = true).install(fixtureVoice())
        activity.refreshPacksForTest()
        inst.waitForIdleSync()
        assertNotNull(described("Remove voice-fr-siwis"))
        assertNull(described("Download voice-fr-siwis"))
    }

    @Test fun removingTheLoadedVoiceDropsSpeechWithoutCrashing() {
        val engine = real("phonemesh-engine-whisper-tiny-1.zip")
        real("phonemesh-voice-fr-siwis-1.zip")
        prefs.edit().putString("engine", engine.id).commit()
        val activity = launch()
        activity.loadSpeechForTest()
        settle()
        assertTrue("speech did not load", activity.speechLoadedForTest())
        tap("Remove voice-fr-siwis")
        assertNull(described("Remove voice-fr-siwis"))
        assertNotNull(described("Download voice-fr-siwis"))
        assertEquals("Français : texte seulement ; ajoutez la voix", textOf("Offline voice status"))
        assertFalse("speech still holds the removed voice", activity.speechLoadedForTest())
        assertTrue(PackStore(packDir, GeneratedPackCatalog.entries).list().none { it.id == "voice-fr-siwis" })
    }

    private fun installedIds() = PackStore(packDir, GeneratedPackCatalog.entries, allowUnknown = true).list().map { it.id }
    private fun view() = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(FixturePackProvider.VOICE), "application/zip")
    private fun share() = Intent(Intent.ACTION_SEND).setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(FixturePackProvider.VOICE))
    private fun deliver(intent: Intent) {
        inst.runOnMainSync { activity!!.onNewIntent(intent) }
        settle()
    }
    private fun pressDialog(which: Int) {
        val dialog = checkNotNull(activity!!.importDialogForTest) { "no confirmation shown" }
        inst.runOnMainSync { dialog.getButton(which).performClick() }
        settle()
    }

    @Test fun aPackOpenedWhileRecordingIsRefused() {
        val activity = launch()
        inst.runOnMainSync { activity.recordingForTest = true }
        deliver(view())
        assertEquals("Attendez la fin de l'opération.", textOf("Operation status"))
        assertNull(activity.importDialogForTest)
        inst.runOnMainSync { activity.recordingForTest = false }
        assertTrue(installedIds().isEmpty())
    }

    @Test fun downloadIsRefusedWhileRecording() {
        val activity = launch()
        inst.runOnMainSync { activity.recordingForTest = true }
        tap("Download voice-fr-siwis")
        assertEquals("Attendez la fin de l'opération.", textOf("Operation status"))
        inst.runOnMainSync { activity.recordingForTest = false }
        assertTrue(PackDownloads(context, PackStore(packDir, GeneratedPackCatalog.entries)).pending().isEmpty())
    }

    @Test fun aDownloadInProgressCanBeCancelled() {
        // Nothing listens on port 9, so the download keeps waiting to retry instead of finishing.
        val voice = GeneratedPackCatalog.entries.first { it.id == "voice-fr-siwis" }
        PackDownloads(context, PackStore(packDir, GeneratedPackCatalog.entries), "http://10.0.2.2:9/").start(voice)
        launch()
        assertNotNull("no cancel button while downloading", described("Cancel voice-fr-siwis"))
        assertEquals("Annuler", textOf("Cancel voice-fr-siwis"))
        tap("Cancel voice-fr-siwis")
        assertEquals("Téléchargement annulé : Français", textOf("Operation status"))
        assertTrue(PackDownloads(context, PackStore(packDir, GeneratedPackCatalog.entries)).pending().isEmpty())
        assertNull(described("Cancel voice-fr-siwis"))
        assertNotNull(described("Download voice-fr-siwis"))
    }

    @Test fun anUnreadableFileGetsItsOwnMessage() {
        launch()
        deliver(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(FixturePackProvider.MISSING), "application/zip"))
        pressDialog(android.content.DialogInterface.BUTTON_POSITIVE)
        assertEquals("Fichier inaccessible. Réessayez.", textOf("Operation status"))
        assertTrue(installedIds().isEmpty())
    }

    @Test fun aPackFromAnotherAppIsInstalledOnlyAfterConfirmation() {
        val activity = launch()
        deliver(view())
        assertNotNull("no confirmation shown", activity.importDialogForTest)
        assertTrue("installed before confirming", installedIds().isEmpty())
        assertNull("the handled intent is forgotten", activity.intent.data)
        pressDialog(android.content.DialogInterface.BUTTON_POSITIVE)
        assertEquals(listOf("voice-fr-siwis"), installedIds())
        assertEquals("Pack importé : Français", textOf("Operation status"))
        assertNotNull(described("Remove voice-fr-siwis"))
    }

    @Test fun decliningAPackFromAnotherAppInstallsNothing() {
        val activity = launch()
        deliver(view())
        pressDialog(android.content.DialogInterface.BUTTON_NEGATIVE)
        assertNull(activity.importDialogForTest)
        deliver(share())
        val dialog = checkNotNull(activity.importDialogForTest) { "no confirmation for a shared ZIP" }
        inst.runOnMainSync { dialog.cancel() } // back button or tap outside
        settle()
        assertNull(activity.importDialogForTest)
        assertTrue(installedIds().isEmpty())
        assertEquals("Prêt. Aucun envoi automatique.", textOf("Operation status"))
    }

    @Test fun reopeningFromRecentsDoesNotImportAgain() {
        activity = inst.startActivitySync(view().setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)) as MainActivity
        settle()
        assertNull("asked again after a Recents relaunch", activity!!.importDialogForTest)
        assertEquals("Prêt. Aucun envoi automatique.", textOf("Operation status"))
        assertTrue(installedIds().isEmpty())
    }

    /**
     * sherpa-onnx sets up espeak-ng once per process from the first voice it loads; removing that
     * voice must not break the other language.
     */
    @Test fun englishStillSpeaksAfterTheFrenchVoiceIsRemoved() {
        val engine = real("phonemesh-engine-whisper-tiny-1.zip")
        real("phonemesh-voice-fr-siwis-1.zip")
        real("phonemesh-voice-en-US-ljspeech-1.zip")
        prefs.edit().putString("engine", engine.id).commit()
        val activity = launch()
        val french = activity.synthesizeForTest("fr", "Bonjour, ceci est un message radio.")
        assertTrue(french.sampleRate > 0 && french.samples.isNotEmpty())
        tap("Remove voice-fr-siwis")
        assertTrue(PackStore(packDir, GeneratedPackCatalog.entries).list().none { it.id == "voice-fr-siwis" })
        val english = activity.synthesizeForTest("en-US", "This is a radio test. Meet me at the library.")
        assertTrue(english.sampleRate > 0)
        assertTrue(english.samples.isNotEmpty())
        // Without pronunciation data the sentence collapses to about 0.3 s of sound (about 2.9 s when intact).
        val seconds = english.samples.size.toDouble() / english.sampleRate
        assertTrue("English speech is only $seconds s long", seconds > 1.5)
        val heard = activity.transcribeForTest("en-US", english)
        assertTrue("Unexpected transcript: $heard", heard.lowercase().contains("radio"))
    }
}

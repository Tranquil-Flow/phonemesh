package org.phonemesh.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackRejected
import org.phonemesh.core.PackStore
import java.io.File
import java.io.FileNotFoundException

/** Offline sharing: rebuilt packs verify on another phone, and the provider serves only cacheDir/share. */
@RunWith(AndroidJUnit4::class)
class SharingTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val voiceZip get() = File(ctx.getExternalFilesDir(null), "phonemesh-voice-fr-siwis-1.zip")

    private fun store(name: String, allowUnknown: Boolean = false): PackStore {
        val dir = File(ctx.filesDir, name)
        dir.deleteRecursively()
        return PackStore(dir, GeneratedPackCatalog.entries, allowUnknown)
    }

    private fun installedVoice(name: String): Pair<PackStore, PackInfo> {
        assertTrue("missing ${voiceZip.name}", voiceZip.isFile)
        val store = store(name)
        return store to voiceZip.inputStream().use { store.install(it) }
    }

    @Test fun rebuiltPackVerifiesOnAnotherStore() {
        val (_, pack) = installedVoice("share-a")
        val tmp = File(ctx.cacheDir, "rebuilt-test.zip")
        try {
            PackSharing.rebuild(pack, tmp)
            val second = store("share-b")
            // Catalog packs only: the rebuilt archive must fit under the import cap.
            val cap = checkNotNull(second.maxArchiveBytes)
            assertTrue("rebuilt ${tmp.length()} B is over the $cap B cap", tmp.length() <= cap)
            File(ctx.getExternalFilesDir(null), "rebuilt-sizes.txt").writeText(
                "${pack.id} catalog=${voiceZip.length()} rebuilt=${tmp.length()} cap=$cap\n")
            val again = tmp.inputStream().use { second.install(it) }
            assertEquals(pack.id, again.id)
            assertEquals(pack.version, again.version)
        } finally { tmp.delete() }
    }

    @Test fun providerServesOnlyItsDirectory() {
        val (store, _) = installedVoice("share-c")
        val uris = PackSharing.prepare(ctx, store).uris
        assertTrue(uris.size >= 2)
        for (uri in uris) {
            ctx.contentResolver.query(uri, null, null, null, null)!!.use { c ->
                assertTrue(c.moveToFirst())
                assertNotNull(c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                assertTrue(c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)) > 0)
            }
            ctx.contentResolver.openFileDescriptor(uri, "r")!!.close()
        }
        val prefs = File(ctx.dataDir, "shared_prefs").also { it.mkdirs() }
        File(prefs, "MainActivity.xml").takeIf { !it.exists() }?.writeText("<map/>")
        val base = "content://org.phonemesh.app.share/"
        for (bad in listOf("../shared_prefs/MainActivity.xml", "..%2Fshared_prefs%2FMainActivity.xml",
            "%2E%2E%2Fshared_prefs%2FMainActivity.xml", "missing.zip", ".hidden")) {
            try {
                ctx.contentResolver.openFileDescriptor(Uri.parse(base + bad), "r")!!.close()
                fail("served $bad")
            } catch (e: FileNotFoundException) {
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
            }
        }
        try {
            ctx.contentResolver.openFileDescriptor(uris.first(), "w")!!.close()
            fail("write mode opened")
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        } catch (e: FileNotFoundException) {
        }
        assertEquals("application/zip", ctx.contentResolver.getType(uris.first { it.lastPathSegment!!.endsWith(".zip") }))
        assertEquals("application/vnd.android.package-archive",
            ctx.contentResolver.getType(uris.first { it.lastPathSegment!!.endsWith(".apk") }))
    }

    @Test fun shareIntentGrantsReadOnly() {
        val uris = listOf(Uri.parse("content://org.phonemesh.app.share/a.zip"), Uri.parse("content://org.phonemesh.app.share/b.apk"))
        val intent = PackSharing.shareIntent(ctx, uris)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val clip: ClipData = intent.clipData!!
        assertEquals(uris, (0 until clip.itemCount).map { clip.getItemAt(it).uri })
    }

    @Test fun lowStorageFailsBeforeWritingAndLeavesNothing() {
        val (store, _) = installedVoice("share-d")
        val share = File(ctx.cacheDir, "share")
        try {
            PackSharing.prepare(ctx, store, freeSpace = { 1_000_000L })
            fail("expected NO_SPACE")
        } catch (e: PackRejected) {
            assertEquals(PackRejected.Reason.NO_SPACE, e.reason)
            assertEquals(UiText(R.string.no_space), PackMessages.forFailure(e))
        }
        assertTrue(share.listFiles().orEmpty().isEmpty())
    }

    @Test fun aDamagedPackIsLeftOutAndTheAppAndOtherPacksAreShared() {
        val (store, _) = installedVoice("share-f")
        val englishZip = File(ctx.getExternalFilesDir(null), "phonemesh-voice-en-US-ljspeech-1.zip")
        assertTrue("missing ${englishZip.name}", englishZip.isFile)
        val english = englishZip.inputStream().use { store.install(it) }
        val model = File(english.root, "tts/model.onnx")
        assertTrue(model.setReadable(false, false))
        try {
            val shared = PackSharing.prepare(ctx, store)
            assertEquals(listOf("Phonemesh.apk", "phonemesh-voice-fr-siwis-1.zip"), shared.uris.map { it.lastPathSegment })
            assertEquals(listOf(english.id), shared.skipped.map { it.id })
            val names = shared.skipped.map { pack -> PackDownloadText.name(GeneratedPackCatalog.entries.first { it.id == pack.id }).resolve(ctx) }
            assertEquals(UiText(R.string.pack_skipped, listOf("English")), PackMessages.forSkipped(names))
            assertEquals(listOf("Phonemesh.apk", "phonemesh-voice-fr-siwis-1.zip"),
                File(ctx.cacheDir, "share").list().orEmpty().sorted())
        } finally { model.setReadable(true, false) }
    }

    @Test fun failurePartwayCleansUpAndSkipsSymlinksAndTemps() {
        val (store, pack) = installedVoice("share-e")
        // A symlink inside the pack pointing outside must never be packed.
        val secret = File(ctx.filesDir, "secret.txt").apply { writeText("secret") }
        val link = File(pack.root, "tts/leak.txt")
        android.system.Os.symlink(secret.absolutePath, link.absolutePath)
        val dirLink = File(pack.root, "tts/leakdir")
        android.system.Os.symlink(ctx.dataDir.absolutePath, dirLink.absolutePath)
        File(pack.root, "tts/model.onnx.tmp").writeText("temp") // allowed prefix, but not in SHA256SUMS
        File(pack.root, "stray.txt").writeText("stray") // outside the allowlist
        val out = File(ctx.cacheDir, "sym.zip")
        try {
            PackSharing.rebuild(pack, out)
            val names = java.util.zip.ZipFile(out).use { z -> z.entries().toList().map { it.name } }
            assertFalse(names.any { "leak" in it || it == "stray.txt" })
            assertEquals(names.sorted(), names)
            // Leftover files from an earlier run are cleared by prepare.
            File(ctx.cacheDir, "share").mkdirs()
            File(ctx.cacheDir, "share/old.zip.tmp").writeText("x")
            val uris = PackSharing.prepare(ctx, store).uris
            assertFalse(File(ctx.cacheDir, "share/old.zip.tmp").exists())
            assertTrue(uris.isNotEmpty())
            // A write failure partway through the pack (after the APK was copied) cancels the whole
            // share and leaves nothing behind.
            val apkBytes = File(ctx.applicationInfo.sourceDir).length()
            var failedAt = 0L
            try {
                PackSharing.prepare(ctx, store, onProgress = { copied ->
                    if (copied > apkBytes) { failedAt = copied; throw java.io.IOException("injected write failure") }
                })
                fail("the injected failure was swallowed")
            } catch (e: IllegalStateException) {
                assertTrue(e.cause?.message == "injected write failure")
            }
            assertTrue("failed at $failedAt, not inside the pack", failedAt > apkBytes)
            assertTrue(File(ctx.cacheDir, "share").list().orEmpty().isEmpty())
        } finally { out.delete(); link.delete(); dirLink.delete(); secret.delete() }
    }
}

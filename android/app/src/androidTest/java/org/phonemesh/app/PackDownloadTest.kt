package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore
import java.io.File

/** Pack downloads against the host test server (android/scripts/test_pack_server.py at 10.0.2.2). */
@RunWith(AndroidJUnit4::class)
class PackDownloadTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val baseUrl get() = "http://10.0.2.2:${args.getString("port", "8765")}/"
    private fun waitFor(timeoutSec: Long, check: () -> Boolean) {
        val end = System.nanoTime() + timeoutSec * 1_000_000_000
        while (System.nanoTime() < end) { if (check()) return; Thread.sleep(500) }
        fail("timed out")
    }
    private fun fresh(): Pair<PackStore, PackDownloads> {
        val dir = File(ctx.filesDir, "download-test-packs").apply { deleteRecursively() }
        val store = PackStore(dir, GeneratedPackCatalog.entries)
        val downloads = PackDownloads(ctx, store, baseUrl)
        // Download ids persist across tests; never let an earlier test's download be collected here.
        downloads.pending().keys.forEach { downloads.cancel(it) }
        return store to downloads
    }
    private val voice get() = GeneratedPackCatalog.entries.first { it.id == "voice-fr-siwis" }

    private fun downloadVerifyInstall() {
        val (store, downloads) = fresh()
        assertEquals(DownloadDecision.Start, downloads.start(voice))
        waitFor(180) { downloads.collectFinished().any { it.isSuccess } }
        assertEquals(listOf("voice-fr-siwis"), store.list().map { it.id })
        assertTrue(downloads.pending().isEmpty())
        assertTrue(ctx.getExternalFilesDir("downloads")!!.listFiles().orEmpty().isEmpty())
    }

    @Test fun downloadsVerifiesAndInstalls() = downloadVerifyInstall()

    @Test fun finishedDownloadIsCollectedByANewInstanceExactlyOnce() {
        val (store, first) = fresh()
        first.start(voice)
        waitFor(180) { first.progress(voice.id)?.let { (got, total) -> got == total } ?: false }
        val second = PackDownloads(ctx, store, baseUrl)
        waitFor(60) { second.collectFinished().isNotEmpty() }
        assertTrue(second.collectFinished().isEmpty())
        assertEquals(1, store.list().size)
    }

    /** Run with `-e drop 1` against a server started with `--drop-after`; the host checks its log for a Range request. */
    @Test fun resumesAfterTheConnectionDrops() {
        assumeTrue(args.getString("drop") == "1")
        downloadVerifyInstall()
    }

    /** Run with `-e lowspace 1` on the small-data-partition AVD after filling it. */
    @Test fun lowSpaceIsRefusedBeforeDownloading() {
        assumeTrue(args.getString("lowspace") == "1")
        val (store, downloads) = fresh()
        val decision = downloads.start(voice)
        assertTrue("expected NotEnoughSpace, got $decision", decision is DownloadDecision.NotEnoughSpace)
        assertTrue(downloads.pending().isEmpty())
        assertTrue(store.list().isEmpty())
    }
}

package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackStore
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Pack downloads against the host test server (android/scripts/test_pack_server.py at 10.0.2.2). */
@RunWith(AndroidJUnit4::class)
class PackDownloadTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val baseUrl get() = "http://10.0.2.2:${args.getString("port", "8777")}/"
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
    private fun downloadsEmpty() = ctx.getExternalFilesDir("downloads")!!.listFiles().orEmpty().isEmpty()

    /** Collects once and fails at once, with the reason, if any download failed. */
    private fun collectOrFail(downloads: PackDownloads): List<Result<PackInfo>> =
        downloads.collectFinished().onEach { r -> r.exceptionOrNull()?.let { fail("download failed: ${it.message}") } }

    private fun downloadVerifyInstall() {
        val (store, downloads) = fresh()
        assertEquals(DownloadDecision.Start, downloads.start(voice))
        waitFor(180) { collectOrFail(downloads).any { it.isSuccess } }
        assertEquals(listOf("voice-fr-siwis"), store.list().map { it.id })
        assertTrue(downloads.pending().isEmpty())
        assertTrue(downloadsEmpty())
    }

    @Test fun downloadsVerifiesAndInstalls() = downloadVerifyInstall()

    @Test fun finishedDownloadIsCollectedByANewInstanceExactlyOnce() {
        val (store, first) = fresh()
        first.start(voice)
        waitFor(180) { first.progress(voice.id)?.let { (got, total) -> got == total } ?: false }
        val second = PackDownloads(ctx, store, baseUrl)
        // Both instances collect at the same time; exactly one of them may install the pack.
        val all = Collections.synchronizedList(mutableListOf<Result<PackInfo>>())
        val thrown = Collections.synchronizedList(mutableListOf<Throwable>())
        waitFor(60) {
            listOf(first, second).map { d ->
                thread { try { all.addAll(d.collectFinished()) } catch (t: Throwable) { thrown += t } }
            }.forEach { it.join() }
            if (thrown.isNotEmpty()) throw AssertionError("a collector threw", thrown.first())
            all.isNotEmpty()
        }
        assertEquals(1, all.size)
        assertTrue("collect failed: ${all.single().exceptionOrNull()?.message}", all.single().isSuccess)
        assertTrue(first.collectFinished().isEmpty())
        assertTrue(second.collectFinished().isEmpty())
        assertEquals(1, store.list().size)
        assertTrue(downloadsEmpty())
    }

    @Test fun startReturnsPromptlyWhileAFinishedDownloadInstalls() {
        val (store, _) = fresh()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = PackDownloads(ctx, store, baseUrl) { input ->
            entered.countDown()
            release.await(60, TimeUnit.SECONDS)
            store.install(input)
        }
        slow.start(voice)
        waitFor(180) { slow.progress(voice.id)?.let { (got, total) -> got == total } ?: false }
        var results = emptyList<Result<PackInfo>>()
        var thrown: Throwable? = null
        val collector = thread {
            try { waitFor(60) { results = slow.collectFinished(); results.isNotEmpty() } } catch (t: Throwable) { thrown = t }
        }
        try {
            assertTrue("the install never started", entered.await(60, TimeUnit.SECONDS))
            val english = GeneratedPackCatalog.entries.first { it.id == "voice-en-US-ljspeech" }
            val started = System.nanoTime()
            assertEquals(DownloadDecision.Start, PackDownloads(ctx, store, baseUrl + "absent/").start(english))
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue("start() waited $seconds s for the install", seconds < 2)
            PackDownloads(ctx, store, baseUrl).cancel(english.id)
        } finally {
            release.countDown()
            collector.join(120_000)
        }
        thrown?.let { throw AssertionError("the collector threw", it) }
        assertEquals(1, results.size)
        assertTrue("collect failed: ${results.single().exceptionOrNull()?.message}", results.single().isSuccess)
        assertEquals(listOf("voice-fr-siwis"), store.list().map { it.id })
        assertTrue(downloadsEmpty())
    }

    @Test fun leftoverFilesInTheDownloadsFolderAreDeletedWhenCollecting() {
        val (_, downloads) = fresh()
        val dir = ctx.getExternalFilesDir("downloads")!!
        File(dir, "phonemesh-voice-fr-siwis-1.zip").writeText("left by a crash mid-install")
        File(dir, "phonemesh-old-1.zip").writeText("orphan")
        assertTrue(downloads.collectFinished().isEmpty())
        assertTrue(downloadsEmpty())
    }

    @Test fun failedDownloadIsReportedPlainlyAndLeavesNothingBehind() {
        val (store, _) = fresh()
        // The test server answers 404 for any path below the root.
        val missing = PackDownloads(ctx, store, baseUrl + "absent/")
        assertEquals(DownloadDecision.Start, missing.start(voice))
        var results = emptyList<Result<PackInfo>>()
        waitFor(60) { results = missing.collectFinished(); results.isNotEmpty() }
        assertEquals(1, results.size)
        assertEquals(UiText(R.string.connection_problem), (results.single().exceptionOrNull() as PackDownloadFailure).text)
        assertTrue(missing.pending().isEmpty())
        assertTrue(store.list().isEmpty())
        assertTrue(downloadsEmpty())
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

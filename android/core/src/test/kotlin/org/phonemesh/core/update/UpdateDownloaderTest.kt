package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** The phone's side of a pack download (U4): hub only, byte ranges with resume, whole-file hash before use. */
class UpdateDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val data = ByteArray(700_000) { (it * 31 + it / 7).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val hub = FakeHub()
    private val store = MemoryTrustStore()
    private val trust: UpdateTrust by lazy { (UpdateTrust.open(store, TestCatalogs.roots, 3, TestCatalogs.baseline) as UpdateTrust.Opened.Ready).trust }
    private val dir by lazy { folder.newFolder("work") }

    private fun entry(bytes: ByteArray = data, version: Long = 2) = UpdateMetadata.Entry(UpdateMetadata.VOICE, "voice-fr-siwis", "$version", "", version, 1, 0, 26, 2, bytes.size.toLong(),
        sha(bytes), 2_000_000, 9, TestCatalogs.sha("sums"), ByteArray(32))

    private fun downloader(chunk: Int = 256 * 1024, free: Long = Long.MAX_VALUE / 2, failures: Int = 3, work: File = dir) =
        UpdateDownloader(trust, hub, work, freeSpace = { free }, chunkBytes = chunk, bufferBytes = 64 * 1024, maxFailures = failures)

    /** Publishes [data] as version 2 and returns the offer. */
    private fun offer(bytes: ByteArray = data, sequence: Long = 2, version: Long = 2): UpdateTrust.Offer {
        hub.add(bytes)
        trust.acceptCatalog(TestCatalogs.catalog(1, sequence, listOf(entry(bytes, version))))
        return trust.offers(3, 28).single { it.entry.id == "voice-fr-siwis" }
    }

    private fun part(offer: UpdateTrust.Offer) = File(dir, "partial/${hex(offer.entry.digest)}.part")
    private fun ready(r: UpdateDownloader.Download): UpdateDownloader.Download.Ready {
        if (r is UpdateDownloader.Download.Ready) return r
        throw AssertionError("not ready: ${r::class.simpleName} ${(r as? UpdateDownloader.Download.Refused)?.why ?: ""}")
    }
    /** A body that delivers [after] bytes and then dies with the app. */
    private fun dying(body: java.io.InputStream, after: Int) = object : java.io.InputStream() {
        var delivered = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delivered >= after) throw MemoryTrustStore.Crash()
            val n = body.read(b, off, minOf(len, after - delivered)); if (n > 0) delivered += n; return n
        }
    }
    private fun objectRequests() = hub.requests.filter { it.first.contains("/objects/") }

    // ---- metadata ----

    @Test fun theCatalogAndRecoveryPolicyGoThroughTheTrustRules() {
        hub.catalog = TestCatalogs.catalog(1, 3, listOf(entry()))
        val r = downloader().refresh() as UpdateDownloader.Refresh.Updated
        assertNull("no policy on the hub", r.recovery)
        assertTrue(r.catalog is UpdateTrust.CatalogResult.Accepted)
        assertEquals(3L, trust.state.sequence)
        assertEquals(listOf("/updates/v1/recovery", "/updates/v1/catalog"), hub.requests.map { it.first })
        // A policy on the hub is offered to the trust rules first.
        hub.recovery = TestCatalogs.policy(2, TestKeys.catalog2)
        hub.catalog = TestCatalogs.catalog(2, 1, listOf(entry()), key = TestKeys.catalog2, keyId = 2)
        val r2 = downloader().refresh() as UpdateDownloader.Refresh.Updated
        assertTrue(r2.recovery is UpdateTrust.RecoveryResult.Accepted)
        assertTrue(r2.catalog is UpdateTrust.CatalogResult.Accepted)
        assertEquals(2L, trust.state.epoch)
    }

    @Test fun anOlderHubAndStrangeAnswersAreHandledWithoutAChange() {
        val before = trust.state.encode()
        assertTrue("404 means no updates here", downloader().refresh() is UpdateDownloader.Refresh.NoUpdates)
        fun failsWith(bend: (String, LongRange?, HubResponse) -> HubResponse, why: String) {
            hub.catalog = TestCatalogs.catalog(1, 3, listOf(entry())); hub.bend = bend
            val r = downloader().refresh()
            assertTrue("$why: ${r::class.simpleName}", r is UpdateDownloader.Refresh.Failed)
            assertArrayEquals(why, before, trust.state.encode())
        }
        fun page(status: Int, body: ByteArray, h: Map<String, List<String>> = emptyMap()) = { _: String, _: LongRange?, _: HubResponse -> HubResponse(status, h, ByteArrayInputStream(body)) }
        failsWith(page(200, "<html>hello</html>".toByteArray()), "an unrelated page with status 200")
        failsWith(page(200, ByteArray(70_000) { 'P'.code.toByte() }.also { "PMUP".toByteArray().copyInto(it) }), "an oversized body")
        failsWith(page(302, ByteArray(0), mapOf("Location" to listOf("http://elsewhere/"))), "a redirect")
        failsWith(page(500, ByteArray(0)), "a server error")
        failsWith(page(200, TestCatalogs.catalog(1, 3, listOf(entry())), mapOf("Content-Encoding" to listOf("gzip"))), "content encoding")
        failsWith({ _, _, _ -> throw java.io.IOException("no route") }, "a connection error")
        failsWith(page(200, ByteArray(2)), "a stub")
    }

    @Test fun aCatalogTheTrustRulesRefuseIsReportedNotUsed() {
        hub.catalog = TestCatalogs.catalog(1, 3, listOf(entry()), key = TestKeys.catalog2)
        val r = downloader().refresh() as UpdateDownloader.Refresh.Updated
        assertEquals(UpdateTrust.Reason.NOT_SIGNED, (r.catalog as UpdateTrust.CatalogResult.Refused).reason)
        assertEquals(1L, trust.state.sequence)
        trust.acceptCatalog(TestCatalogs.catalog(1, 5, listOf(entry())))
        hub.catalog = TestCatalogs.catalog(1, 4, listOf(entry()))
        assertEquals(UpdateTrust.Reason.OLDER, ((downloader().refresh() as UpdateDownloader.Refresh.Updated).catalog as UpdateTrust.CatalogResult.Refused).reason)
    }

    // ---- files ----

    @Test fun aPackIsCopiedInRangesAndChecked() {
        val o = offer()
        val progress = ArrayList<Long>()
        val r = ready(downloader().download(o, { done, total -> progress += done; assertEquals(700_000L, total) }))
        assertArrayEquals(data, r.file.readBytes())
        assertEquals(listOf(0L, 262_144L, 524_288L, 700_000L), progress)
        assertEquals(listOf(0L..262_143L, 262_144L..524_287L, 524_288L..699_999L), objectRequests().map { it.second })
        assertTrue("every range is at most 256 KiB", objectRequests().all { it.second!!.let { r -> r.last - r.first < 256 * 1024 } })
        assertFalse(part(o).exists()); assertFalse(File(dir, "partial/${hex(o.entry.digest)}.state").exists())
        assertEquals("a finished file is not fetched again", r.file, ready(downloader().download(o)).file)
        assertEquals(3, objectRequests().size)
    }

    @Test fun aDroppedConnectionIsResumedFromTheDurableOffsetNotFromZero() {
        val o = offer()
        hub.dropNextAfter(100_000) // 100,000 bytes of the first range arrive, then the connection resets
        val r = ready(downloader().download(o))
        assertArrayEquals(data, r.file.readBytes())
        val starts = objectRequests().map { it.second!!.first }
        assertEquals("the retry starts where the bytes that arrived end", listOf(0L, 100_000L, 362_144L), starts.take(3))
        assertEquals(0, r.restarts)
    }

    @Test fun threeFailuresInARowPauseUntilTheyChooseResume() {
        val o = offer()
        hub.bend = { _, _, _ -> throw java.io.IOException("no route") }
        val first = downloader().download(o)
        assertTrue(first is UpdateDownloader.Download.NeedsResume)
        assertEquals(3, objectRequests().size)
        hub.bend = null
        hub.dropNextAfter(100_000)
        val second = ready(downloader().download(o))
        assertArrayEquals(data, second.file.readBytes())
    }

    @Test fun aKilledAppResumesAfterTheLastSyncedChunk() {
        val o = offer()
        // The second range dies after 10 bytes: the first range was synced and recorded, those 10 bytes were not.
        hub.bend = { _, _, ok -> if (objectRequests().size == 2) HubResponse(ok.status, ok.headers, dying(ok.body, 10)) else ok }
        try { downloader().download(o); fail("expected the crash") } catch (_: MemoryTrustStore.Crash) { }
        assertEquals("the unsynced tail is on disk", 262_144L + 10, part(o).length())
        hub.bend = null; hub.requests.clear()
        part(o).appendBytes(ByteArray(5_000) { 9 }) // more junk after the durable offset
        val r = ready(downloader().download(o))
        assertArrayEquals(data, r.file.readBytes())
        assertEquals("resumed after the first range, not from zero", 262_144L, objectRequests().first().second!!.first)
    }

    @Test fun aDamagedOrForeignSidecarStartsOverInsteadOfTrustingCachedBytes() {
        val o = offer()
        val name = hex(o.entry.digest)
        val sidecar = File(dir, "partial/$name.state")
        /** Stops a download at 100,000 bytes, lets [corrupt] spoil the leftovers, then returns where the next download began. */
        fun firstStart(corrupt: () -> Unit): Long {
            part(o).delete(); sidecar.delete(); File(dir, "ready/$name").delete()
            hub.dropNextAfter(100_000)
            downloader(failures = 1).download(o)
            assertTrue(sidecar.isFile && part(o).length() == 100_000L)
            corrupt(); hub.requests.clear()
            assertArrayEquals(data, ready(downloader().download(o)).file.readBytes())
            return objectRequests().first().second!!.first
        }
        assertEquals("control: an intact sidecar resumes", 100_000L, firstStart { })
        assertEquals("flipped byte", 0L, firstStart { sidecar.writeBytes(sidecar.readBytes().also { it[10] = (it[10] + 1).toByte() }) })
        assertEquals("cut short", 0L, firstStart { sidecar.writeBytes(sidecar.readBytes().copyOf(20)) })
        assertEquals("empty", 0L, firstStart { sidecar.writeBytes(ByteArray(0)) })
        assertEquals("offset beyond the partial file", 0L, firstStart { part(o).let { it.writeBytes(it.readBytes().copyOf(1000)) } })
    }

    @Test fun aFileThatIsNotWhatWasSignedIsNeverUsed() {
        val o = offer()
        val fake = data.copyOf().also { it[400_000] = (it[400_000] + 1).toByte() }
        hub.objects[hex(o.entry.digest)] = fake
        val r = downloader().download(o)
        assertTrue(r is UpdateDownloader.Download.WrongHash)
        assertFalse(part(o).exists()); assertFalse(File(dir, "ready/${hex(o.entry.digest)}").exists())
    }

    @Test fun aShortFileOrABadRangeAnswerIsRefusedOrRetried() {
        val o = offer()
        val name = hex(o.entry.digest)
        // The first answer ends after 1,000 bytes, every later one is empty: the third failure in a row pauses the copy, bytes kept.
        var answers = 0
        hub.bend = { _, range, ok -> HubResponse(206, ok.headers, ByteArrayInputStream(data, range!!.first.toInt(), if (answers++ == 0) 1000 else 0)) }
        val short = downloader().download(o)
        assertTrue(short::class.simpleName, short is UpdateDownloader.Download.NeedsResume)
        assertEquals(3, objectRequests().size)
        assertEquals("what arrived stays", 1_000L, part(o).length())
        part(o).delete()
        File(dir, "partial/$name.state").delete()
        fun refused(why: String, bend: (HubResponse, LongRange) -> HubResponse) {
            hub.requests.clear(); hub.bend = { _, range, ok -> bend(ok, range!!) }
            val r = downloader().download(o)
            assertTrue("$why: ${r::class.simpleName}", r is UpdateDownloader.Download.Refused)
            hub.bend = null; part(o).delete(); File(dir, "partial/$name.state").delete()
        }
        fun withHeaders(ok: HubResponse, edit: (MutableMap<String, List<String>>) -> Unit): HubResponse {
            val h = ok.headers.toMutableMap(); edit(h); return HubResponse(ok.status, h, ok.body)
        }
        refused("total larger than signed") { ok, _ -> withHeaders(ok) { it["Content-Range"] = listOf(it["Content-Range"]!![0].substringBeforeLast('/') + "/9999999") } }
        refused("total smaller than signed") { ok, _ -> withHeaders(ok) { it["Content-Range"] = listOf(it["Content-Range"]!![0].substringBeforeLast('/') + "/1000") } }
        // The body matches the header's claim in both, so only the range rules can refuse them.
        refused("another start") { _, r ->
            val from = r.first + 5
            HubResponse(206, mapOf("ETag" to listOf("\"$name\""), "Content-Range" to listOf("bytes $from-${r.last}/700000"), "Content-Length" to listOf((r.last - from + 1).toString())),
                ByteArrayInputStream(data, from.toInt(), (r.last - from + 1).toInt()))
        }
        refused("ends beyond the ask") { _, r ->
            val to = r.last + 10
            HubResponse(206, mapOf("ETag" to listOf("\"$name\""), "Content-Range" to listOf("bytes ${r.first}-$to/700000"), "Content-Length" to listOf((to - r.first + 1).toString())),
                ByteArrayInputStream(data, r.first.toInt(), (to - r.first + 1).toInt()))
        }
        refused("no content-range") { ok, _ -> withHeaders(ok) { it.remove("Content-Range") } }
        refused("two content-ranges") { ok, _ -> withHeaders(ok) { it["Content-Range"] = it["Content-Range"]!! + it["Content-Range"]!! } }
        refused("length differs from range") { ok, _ -> withHeaders(ok) { it["Content-Length"] = listOf("5") } }
        refused("two lengths") { ok, _ -> withHeaders(ok) { it["Content-Length"] = listOf("1", "1") } }
        refused("wrong etag") { ok, _ -> withHeaders(ok) { it["ETag"] = listOf("\"${"0".repeat(64)}\"") } }
        refused("no etag") { ok, _ -> withHeaders(ok) { it.remove("ETag") } }
        refused("weak etag") { ok, _ -> withHeaders(ok) { it["ETag"] = listOf("W/" + it["ETag"]!![0]) } }
        refused("content encoding") { ok, _ -> withHeaders(ok) { it["Content-Encoding"] = listOf("gzip") } }
        refused("multipart") { ok, _ -> withHeaders(ok) { it["Content-Type"] = listOf("multipart/byteranges; boundary=x") } }
        refused("redirect") { ok, _ -> HubResponse(302, mapOf("Location" to listOf("http://elsewhere/x")), ok.body) }
        refused("a 200 with the wrong length") { ok, _ -> HubResponse(200, mapOf("ETag" to ok.headers["ETag"]!!, "Content-Length" to listOf("12345")), ok.body) }
        refused("a 400") { ok, _ -> HubResponse(400, emptyMap(), ok.body) }
    }

    @Test fun bytesBeyondTheAnnouncedRangeAreNeverWritten() {
        val o = offer()
        hub.bend = { _, r, ok -> HubResponse(206, ok.headers, ByteArrayInputStream(data, r!!.first.toInt(), minOf(data.size - r.first.toInt(), (r.last - r.first + 1 + 5_000).toInt()))) }
        val r = ready(downloader().download(o))
        assertEquals(700_000L, r.file.length())
        assertArrayEquals(data, r.file.readBytes())
    }

    @Test fun aServerThatIgnoresRangesRestartsTheFileAndNeverAppends() {
        val o = offer()
        hub.dropNextAfter(100_000)
        downloader(failures = 1).download(o)
        assertEquals(100_000L, part(o).length())
        hub.bend = { path, _, _ ->
            val name = path.substringAfterLast('/')
            HubResponse(200, mapOf("ETag" to listOf("\"$name\""), "Content-Length" to listOf(data.size.toString())), ByteArrayInputStream(data))
        }
        val r = ready(downloader().download(o))
        assertEquals(1, r.restarts)
        assertArrayEquals("the whole file once, not partial plus whole", data, r.file.readBytes())
    }

    @Test fun aRefusedRangeDropsThePartialFileAndTriesAgain() {
        val o = offer()
        hub.dropNextAfter(100_000)
        downloader(failures = 1).download(o)
        var first = true
        hub.bend = { _, _, ok -> if (first) { first = false; HubResponse(416, mapOf("Content-Range" to listOf("bytes */700000")), ByteArrayInputStream(ByteArray(0))) } else ok }
        val r = ready(downloader().download(o))
        assertArrayEquals(data, r.file.readBytes())
        assertEquals("the refused range, then a fresh start from the beginning", listOf(0L, 100_000L, 0L), objectRequests().take(3).map { it.second!!.first })
    }

    @Test fun aBusyHubAndAMissingObjectArePlainAnswers() {
        val o = offer()
        hub.bend = { _, _, _ -> HubResponse(429, mapOf("Retry-After" to listOf("5")), ByteArrayInputStream(ByteArray(0))) }
        assertEquals(5, (downloader().download(o) as UpdateDownloader.Download.Busy).retryAfterSeconds)
        hub.bend = null; hub.objects.clear()
        assertTrue(downloader().download(o) is UpdateDownloader.Download.NotOnThisHub)
    }

    @Test fun thereMustBeRoomForTheFileItsUnpackingAndAReserve() {
        val o = offer()
        val tight = downloader(free = 700_000L + 2_000_000L + 20L * 1024 * 1024 - 1).download(o) as UpdateDownloader.Download.NoSpace
        assertEquals(700_000L + 2_000_000L + 20L * 1024 * 1024, tight.needed)
        assertTrue("nothing was asked of the hub", objectRequests().isEmpty())
        assertTrue(downloader(free = 700_000L + 2_000_000L + 20L * 1024 * 1024).download(o) is UpdateDownloader.Download.Ready)
    }

    @Test fun revokedOrDroppedOffersAreNotDownloaded() {
        val o = offer()
        trust.acceptCatalog(TestCatalogs.catalog(1, 3, listOf(entry()), revoked = listOf(o.entry.digest)))
        assertTrue(downloader().download(o) is UpdateDownloader.Download.NotAuthorized)
        assertTrue(objectRequests().isEmpty())
        // Revoked while paused in the middle: the next step stops and removes the partial file.
        val t = offer(ByteArray(600_000) { 5 }, sequence = 4, version = 3)
        hub.dropNextAfter(100_000)
        downloader(failures = 1).download(t)
        assertTrue(part(t).exists())
        trust.acceptCatalog(TestCatalogs.catalog(1, 5, listOf(entry(ByteArray(600_000) { 5 }, 3)), revoked = listOf(t.entry.digest)))
        assertTrue(downloader().download(t) is UpdateDownloader.Download.NotAuthorized)
        assertFalse(part(t).exists())
    }

    @Test fun aRevocationThatArrivesInTheMiddleStopsTheCopyAndRemovesTheBytes() {
        val o = offer()
        hub.bend = { _, _, ok ->
            // The second range request: the hub has meanwhile handed the phone a catalog that revokes this file.
            if (objectRequests().size == 2) trust.acceptCatalog(TestCatalogs.catalog(1, 3, listOf(entry()), revoked = listOf(o.entry.digest)))
            ok
        }
        assertTrue(downloader().download(o) is UpdateDownloader.Download.NotAuthorized)
        assertEquals("the copy stopped after the range in flight", 2, objectRequests().size)
        assertFalse(part(o).exists()); assertFalse(File(dir, "partial/${hex(o.entry.digest)}.state").exists())
    }

    @Test fun aFileThatWasFinishedBeforeItsRevocationIsNotHandedOutAsReady() {
        val o = offer()
        assertTrue(downloader().download(o) is UpdateDownloader.Download.Ready)
        trust.acceptCatalog(TestCatalogs.catalog(1, 3, listOf(entry()), revoked = listOf(o.entry.digest)))
        assertTrue(downloader().download(o) is UpdateDownloader.Download.NotAuthorized)
    }

    @Test fun pauseKeepsTheBytes() {
        val o = offer()
        var chunks = 0
        val r = downloader().download(o, { _, _ -> chunks++ }, cancelled = { chunks >= 2 })
        assertEquals(262_144L, (r as UpdateDownloader.Download.Paused).offset)
        assertEquals(262_144L, part(o).length())
        assertTrue(downloader().download(o) is UpdateDownloader.Download.Ready)
        assertEquals("resumed with the second range", 262_144L, objectRequests()[1].second!!.first)
    }

    @Test fun theUpdatePackageHasNoWayToReachTheRadioMobileDataOrTheMessagePaths() {
        val sources = File("src/main/kotlin/org/phonemesh/core/update").listFiles { f -> f.extension == "kt" }!!
        assertTrue(sources.size >= 8)
        val forbidden = Regex("""DownloadManager|ConnectivityManager|MeshRadio|java\.net|HttpURLConnection|\bSocket\b|SocketFactory|okhttp|android\.|/send\b|/mail\b|\bURL\(""")
        for (f in sources) {
            val text = f.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")
            assertNull("${f.name} mentions ${forbidden.find(text)?.value}", forbidden.find(text))
        }
        assertEquals(listOf(UpdateRoute.PINNED_HUB_WIFI), UpdateRoute.values().toList())
    }
}

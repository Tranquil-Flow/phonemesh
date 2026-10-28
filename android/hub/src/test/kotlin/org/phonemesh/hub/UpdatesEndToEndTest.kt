package org.phonemesh.hub

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackStore
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.HubResponse
import org.phonemesh.core.update.HubTransport
import org.phonemesh.core.update.PackActivation
import org.phonemesh.core.update.TrustStore
import org.phonemesh.core.update.UpdateDownloader
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import org.phonemesh.core.update.UpdateRoute
import org.phonemesh.core.update.UpdateTrust
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * The whole path with the real parts: a release made by the signing tool, imported from a stick, served by the hub's own
 * update routes over HTTP, copied by the phone's downloader in ranges (and resumed), and activated through PackStore.
 */
class UpdatesEndToEndTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalogKey = P256.generate()
    private val recoveryKey = P256.generate()
    private val roots = UpdateRoots.parse(
        "catalog key_id=1 public_key=${TestKeys.hex(TestKeys.compressed(catalogKey))}\n" +
            "recovery key_id=100 public_key=${TestKeys.hex(TestKeys.compressed(recoveryKey))}\n")
    private var server: HttpServer? = null
    @After fun stop() { server?.stop(0) }

    private class Slots : TrustStore {
        val slots = arrayOfNulls<ByteArray>(2)
        override fun load(slot: Int) = slots[slot]?.copyOf()
        override fun save(slot: Int, bytes: ByteArray) { slots[slot] = bytes.copyOf() }
    }

    /** A release folder as the signing tool writes it, with the voice pack at [version]. */
    private fun release(sequence: Int, version: Int): File {
        val dir = folder.newFolder()
        File(dir, "pack.zip").writeBytes(TestPacks.zip("voice-fr-siwis", version, "voice", extra = mapOf("tts/extra.bin" to ByteArray(30_000).also { java.util.Random(version.toLong()).nextBytes(it) })))
        File(dir, "packs.list").writeText("epoch = 1\nsequence = $sequence\nissued-at = 1793145600\npack file=pack.zip\n")
        val out = File(folder.newFolder(), "release")
        CatalogSigner.sign(CatalogSigner.readList(File(dir, "packs.list")), KeyFile.read(TestPem.encryptedPem(catalogKey, "pw"), "pw".toCharArray()), roots, out)
        return out
    }

    private class Http(val base: String, val dropAfter: () -> Long = { -1 }) : HubTransport {
        override val route = UpdateRoute.PINNED_HUB_WIFI
        val ranges = ArrayList<String?>()
        override fun get(path: String, range: LongRange?): HubResponse {
            val c = URL(base + path).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) c.setRequestProperty("Range", "bytes=${range.first}-${range.last}")
            ranges += c.getRequestProperty("Range")
            val status = c.responseCode
            val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value }
            val stream = if (status >= 400) c.errorStream ?: InputStream.nullInputStream() else c.inputStream
            val limit = if (path.contains("/objects/")) dropAfter() else -1L
            val body = if (limit < 0 || !path.contains("/objects/")) stream else object : InputStream() {
                var n = 0L
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n >= limit) throw IOException("connection reset")
                    return stream.read(b, off, minOf(len.toLong(), limit - n).toInt()).also { if (it > 0) n += it }
                }
            }
            return HubResponse(status, headers, body)
        }
    }

    private fun serve(hubDir: File): String {
        val routes = UpdateRoutes(hubDir)
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.createContext("/updates/v1/") { routes.handle(it) }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test fun aSignedPackTravelsFromTheStickToTheInstalledPhone() {
        val v1 = release(1, 1); val v2 = release(2, 2)
        val hubDir = folder.newFolder("hub")
        val imp = UpdateImport(hubDir, roots.recoveryKey, roots.catalogKey)
        assertTrue(imp.import(v2) is UpdateImport.Result.Imported)
        val base = serve(hubDir)

        // The phone: the baseline is release 1 (a real signed catalog), v1 of the pack is installed.
        val baseline = File(v1, "catalog.pmup").readBytes()
        val trust = (UpdateTrust.open(Slots(), roots, 3, baseline) as UpdateTrust.Opened.Ready).trust
        val packs = folder.newFolder("packs")
        val first = UpdateMetadata.catalog(UpdateMetadata.verify(baseline, mapOf(1 to roots.catalogKey), null)!!.payload)!!.entries.single()
        val compiled = listOf(CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "x", first.bytes, TestKeys.hex(first.sums)))
        PackStore(packs, compiled).install(File(v1, "objects/${TestKeys.hex(first.digest)}").inputStream())

        val http = Http(base)
        val work = folder.newFolder("work")
        val downloader = UpdateDownloader(trust, http, work, chunkBytes = 4096, bufferBytes = 1024)
        val refreshed = downloader.refresh() as UpdateDownloader.Refresh.Updated
        assertTrue(refreshed.catalog is UpdateTrust.CatalogResult.Accepted)
        val offer = trust.offers(3, 28, mapOf("voice-fr-siwis" to 1L)).single()
        assertEquals(2L, offer.entry.version)

        val done = downloader.download(offer) as UpdateDownloader.Download.Ready
        val served = File(hubDir, "updates/objects/${TestKeys.hex(offer.entry.digest)}")
        assertArrayEquals(served.readBytes(), done.file.readBytes())
        assertTrue("several real range requests, none larger than the chunk", http.ranges.count { it != null } > 3)

        val activated = PackActivation(packs, compiled).activate(trust, offer, done.file) as PackActivation.Result.Activated
        assertEquals(2, activated.info.version)
        assertEquals(2L, trust.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
        assertEquals(2, PackStore(packs, compiled).list().single().version)
    }

    @Test fun aConnectionResetIsResumedWithARealRangeRequest() {
        val v1 = release(1, 1); val v2 = release(2, 2)
        val hubDir = folder.newFolder("hub")
        UpdateImport(hubDir, roots.recoveryKey, roots.catalogKey).import(v2)
        val base = serve(hubDir)
        val trust = (UpdateTrust.open(Slots(), roots, 3, File(v1, "catalog.pmup").readBytes()) as UpdateTrust.Opened.Ready).trust
        var drops = 1
        val http = Http(base, dropAfter = { if (drops-- > 0) 6_000 else -1 })
        val work = folder.newFolder("work")
        UpdateDownloader(trust, http, work, chunkBytes = 20_000, bufferBytes = 1024).refresh()
        val offer = trust.offers(3, 28).single()
        val done = UpdateDownloader(trust, http, work, chunkBytes = 20_000, bufferBytes = 1024).download(offer) as UpdateDownloader.Download.Ready
        assertEquals("resumed from the 6,000 bytes that arrived", "bytes=6000-25999", http.ranges.filter { it != null }[1])
        assertArrayEquals(File(hubDir, "updates/objects/${TestKeys.hex(offer.entry.digest)}").readBytes(), done.file.readBytes())
    }

    @Test fun anOldHubWithoutUpdatesIsJustUnavailable() {
        val trust = (UpdateTrust.open(Slots(), roots, 3, File(release(1, 1), "catalog.pmup").readBytes()) as UpdateTrust.Opened.Ready).trust
        val base = serve(folder.newFolder("empty-hub"))
        assertTrue(UpdateDownloader(trust, Http(base), folder.newFolder("w")).refresh() is UpdateDownloader.Refresh.NoUpdates)
        assertEquals(1L, trust.state.sequence)
    }
}

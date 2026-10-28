package org.phonemesh.hub

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubStream
import org.phonemesh.core.hub.HubStreams
import org.phonemesh.core.update.TrustStore
import org.phonemesh.core.update.UpdateDownloader
import org.phonemesh.core.update.UpdateRoute
import org.phonemesh.core.update.UpdateRoots
import org.phonemesh.core.update.UpdateTrust
import java.io.File
import java.io.IOException
import javax.net.SocketFactory

/**
 * The phone's [HubClient] as the way to a hub's signed updates (U5): over the real [HubServer], real pinned TLS and real
 * sockets, with the real update routes and the real downloader. A wrong pin and a Bluetooth link give no update route at all.
 */
class HubClientUpdatesTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalogKey = P256.generate()
    private val recoveryKey = P256.generate()
    private val roots = UpdateRoots.parse(
        "catalog key_id=1 public_key=${TestKeys.hex(TestKeys.compressed(catalogKey))}\n" +
            "recovery key_id=100 public_key=${TestKeys.hex(TestKeys.compressed(recoveryKey))}\n")
    private var server: HubServer? = null
    private lateinit var tls: Tls

    @After fun stop() { server?.stop() }

    private class Slots : TrustStore {
        val slots = arrayOfNulls<ByteArray>(2)
        override fun load(slot: Int) = slots[slot]?.copyOf()
        override fun save(slot: Int, bytes: ByteArray) { slots[slot] = bytes.copyOf() }
    }

    private fun release(sequence: Int, version: Int): File {
        val dir = folder.newFolder()
        File(dir, "pack.zip").writeBytes(TestPacks.zip("voice-fr-siwis", version, "voice", extra = mapOf("tts/extra.bin" to ByteArray(30_000).also { java.util.Random(version.toLong()).nextBytes(it) })))
        File(dir, "packs.list").writeText("epoch = 1\nsequence = $sequence\nissued-at = 1793145600\npack file=pack.zip\n")
        val out = File(folder.newFolder(), "release")
        CatalogSigner.sign(CatalogSigner.readList(File(dir, "packs.list")), KeyFile.read(TestPem.encryptedPem(catalogKey, "pw"), "pw".toCharArray()), roots, out)
        return out
    }

    private fun start(hubDir: File?): HubClient {
        val mailbox = Mailbox(folder.newFolder("mail"), 30)
        tls = Tls(folder.newFolder("keys"))
        server = HubServer(Hub(mailbox, FakeRadio()), mailbox, "Test hub", tls, 0, hubDir).also { it.start() }
        return HubClient("127.0.0.1", server!!.port, tls.pin, SocketFactory.getDefault())
    }

    @Test fun theCatalogAndARangeComeBackWithEveryHeader() {
        val hubDir = folder.newFolder("hub")
        val rel = release(2, 2)
        assertTrue(UpdateImport(hubDir, roots.recoveryKey, roots.catalogKey).import(rel) is UpdateImport.Result.Imported)
        val transport = start(hubDir).updates()!!
        assertEquals(UpdateRoute.PINNED_HUB_WIFI, transport.route)
        transport.get("/updates/v1/catalog", null).use {
            assertEquals(200, it.status)
            assertArrayEquals(File(rel, "catalog.pmup").readBytes(), it.body.readAllBytes())
        }
        val name = File(rel, "objects").list()!!.single()
        val size = File(rel, "objects/$name").length()
        transport.get("/updates/v1/objects/$name", 100L..4195L).use {
            assertEquals(206, it.status)
            assertEquals("\"$name\"", it.one("etag"))
            assertEquals("bytes 100-4195/$size", it.one("content-range"))
            assertEquals("4096", it.one("content-length"))
            assertArrayEquals(File(rel, "objects/$name").readBytes().copyOfRange(100, 4196), it.body.readAllBytes())
        }
    }

    @Test fun anEmptyHubAnswers404NotAnError() {
        val transport = start(folder.newFolder("empty")).updates()!!
        transport.get("/updates/v1/catalog", null).use { assertEquals(404, it.status) }
        transport.get("/updates/v1/recovery", null).use { assertEquals(404, it.status) }
    }

    @Test fun anOldHubWithoutTheRoutesAnswersWithAClientError() {
        val old = start(null).updates()!!
        old.get("/updates/v1/catalog", null).use { assertTrue(it.status in 400..499) }
    }

    @Test fun onlyUpdatePathsCanBeAsked() {
        val transport = start(folder.newFolder("hub")).updates()!!
        for (bad in listOf("/mail", "/send?port=1", "/updates/v1/catalog?x=1", "/hello", "updates/v1/catalog", "/updates/v1/ca talog", "/updates/v1/catalog\r\nX: y")) {
            try { transport.get(bad, null).close(); fail("asked for $bad") } catch (_: IOException) { }
        }
    }

    @Test fun aWrongPinNeverGetsAnAnswer() {
        start(folder.newFolder("hub"))
        val wrong = HubClient("127.0.0.1", server!!.port, "A".repeat(43), SocketFactory.getDefault()).updates()!!
        try { wrong.get("/updates/v1/catalog", null).close(); fail("talked to a hub with another key") } catch (_: IOException) { }
    }

    @Test fun theBluetoothLinkHasNoUpdateRoute() {
        val streams = object : HubStreams { override fun open(totalMillis: Int): HubStream = throw IOException("no") }
        assertNull(HubClient(8443, "x".repeat(43), streams).updates())
    }

    @Test fun theRealDownloaderCopiesAPackThroughTheRealClient() {
        val v1 = release(1, 1); val v2 = release(2, 2)
        val hubDir = folder.newFolder("hub")
        UpdateImport(hubDir, roots.recoveryKey, roots.catalogKey).import(v2)
        val transport = start(hubDir).updates()!!
        val trust = (UpdateTrust.open(Slots(), roots, 3, File(v1, "catalog.pmup").readBytes()) as UpdateTrust.Opened.Ready).trust
        val work = folder.newFolder("work")
        val downloader = UpdateDownloader(trust, transport, work, chunkBytes = 8192, bufferBytes = 1024)
        val updated = downloader.refresh() as UpdateDownloader.Refresh.Updated
        assertTrue(updated.catalog is UpdateTrust.CatalogResult.Accepted)
        val offer = trust.offers(3, 28, mapOf("voice-fr-siwis" to 1L)).single()
        val done = downloader.download(offer) as UpdateDownloader.Download.Ready
        val served = File(hubDir, "updates/objects/${TestKeys.hex(offer.entry.digest)}")
        assertNotNull(served)
        assertArrayEquals(served.readBytes(), done.file.readBytes())
    }
}

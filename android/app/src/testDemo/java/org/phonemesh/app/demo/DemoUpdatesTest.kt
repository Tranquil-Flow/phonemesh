package org.phonemesh.app.demo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore
import org.phonemesh.core.update.TrustStore
import org.phonemesh.core.update.UpdateController.Notice
import org.phonemesh.core.update.UpdateController.State
import org.phonemesh.core.update.UpdateController.Why
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import org.phonemesh.core.update.UpdateTrust
import java.io.File
import java.util.concurrent.Executor

/**
 * The demo's signed pack update (U6): made with demo-only keys that the real app rejects, imported by the real hub import,
 * served the way the hub's own routes serve, and installed by the phone's real verifier, trust state and downloader.
 */
class DemoUpdatesTest {
    @get:Rule val folder = TemporaryFolder()
    private class Slots : TrustStore {
        val slots = arrayOfNulls<ByteArray>(2)
        override fun load(slot: Int) = slots[slot]?.copyOf()
        override fun save(slot: Int, bytes: ByteArray) { slots[slot] = bytes.copyOf() }
    }

    private val direct = Executor { it.run() }
    private fun files() = folder.newFolder("files")
    private fun store(files: File) = PackStore(File(files, "packs"), GeneratedPackCatalog.entries)
    private fun hex(b: ByteArray) = DemoUpdatePack.hex(b)

    @Test fun theDemoKeysSignWhatTheDemoRootsVerifyAndNothingElse() {
        val v2 = DemoUpdatePack.build(2).entry
        val signed = DemoUpdateKeys.sign(2, listOf(v2))
        assertNotNull(UpdateMetadata.verify(signed, mapOf(1 to DemoUpdateKeys.roots.catalogKey), null))
        // The public half in the roots really is the half of the private key that signs.
        assertTrue(DemoUpdateKeys.roots.isTest)
    }

    @Test fun theRealAppsRootsRejectEverythingSignedWithTheDemoKeys() {
        val real = UpdateRoots.compiled
        assertFalse("demo catalog key is not the build's", real.catalogKey.contentEquals(DemoUpdateKeys.roots.catalogKey))
        assertFalse("demo recovery key is not the build's", real.recoveryKey.contentEquals(DemoUpdateKeys.roots.recoveryKey))
        val v1 = DemoUpdatePack.build(1).entry; val v2 = DemoUpdatePack.build(2).entry
        // A real-app phone: its packaged baseline stands, a demo-signed catalog or baseline is not believed.
        val trust = (UpdateTrust.open(Slots(), real, 3) as UpdateTrust.Opened.Ready).trust
        val before = trust.state.encode()
        val result = trust.acceptCatalog(DemoUpdateKeys.sign(trust.state.sequence + 1, listOf(v2)))
        assertTrue("$result", result is UpdateTrust.CatalogResult.Refused && result.reason == UpdateTrust.Reason.NOT_SIGNED)
        assertArrayEquals(before, trust.state.encode())
        assertTrue(UpdateTrust.open(Slots(), real, 3, DemoUpdateKeys.sign(1, listOf(v1))) is UpdateTrust.Opened.BadBaseline)
        // And the other way round: the demo app does not believe the real app's packaged baseline.
        assertTrue(UpdateTrust.open(Slots(), DemoUpdateKeys.roots, 3) is UpdateTrust.Opened.BadBaseline)
    }

    @Test fun theRealHubImportTakesTheDemoReleaseOnceAndLeavesItAlone() {
        val dir = folder.newFolder("hub")
        val hub = DemoUpdateHub(dir, 0)
        assertTrue(hub.prepare())
        assertFalse("a second start finds it already there", hub.prepare())
        val state = org.phonemesh.hub.UpdateImport.State.decode(File(dir, "updates/trust-state.bin").readBytes())!!
        assertEquals(2L, state.sequence)
        assertEquals(1L, state.epoch)
        assertTrue(File(dir, "updates/objects/${hex(DemoUpdatePack.build(2).entry.digest)}").isFile)
        assertFalse("the stick folder is gone", File(dir, "update-stick").exists())
    }

    /** The answers the hub's own update routes give (UpdateRoutes: ETag, one byte range, 416 with its size, 404 for anything else). */
    @Test fun theDemoRoutesAnswerTheWayTheHubsOwnUpdateRoutesDo() {
        val dir = folder.newFolder("hub")
        val mine = DemoUpdateHub(dir, 0).also { it.prepare() }.transport
        val v2 = DemoUpdatePack.build(2)
        val name = hex(v2.entry.digest)
        val size = v2.zip.size.toLong()
        fun ask(path: String, range: LongRange?) = mine.get(path, range).use { r -> Triple(r.status, r, r.body.readBytes()) }
        ask("/updates/v1/catalog", null).let { (status, r, body) ->
            assertEquals(200, status)
            assertEquals(body.size.toString(), r.one("content-length"))
            assertNotNull(UpdateMetadata.verify(body, mapOf(1 to DemoUpdateKeys.roots.catalogKey), null))
        }
        assertEquals(404, ask("/updates/v1/recovery", null).first)
        ask("/updates/v1/objects/$name", 100L..199L).let { (status, r, body) ->
            assertEquals(206, status)
            assertEquals("\"$name\"", r.one("etag"))
            assertEquals("bytes 100-199/$size", r.one("content-range")); assertEquals("100", r.one("content-length"))
            assertArrayEquals(v2.zip.copyOfRange(100, 200), body)
        }
        ask("/updates/v1/objects/$name", (size - 10)..(size + 500)).let { (status, r, body) ->
            assertEquals(206, status)
            assertEquals("bytes ${size - 10}-${size - 1}/$size", r.one("content-range")); assertEquals("10", r.one("content-length"))
            assertArrayEquals(v2.zip.copyOfRange((size - 10).toInt(), size.toInt()), body)
        }
        ask("/updates/v1/objects/$name", size..(size + 5)).let { (status, r, _) -> assertEquals(416, status); assertEquals("bytes */$size", r.one("content-range")) }
        assertEquals(404, ask("/updates/v1/objects/${"0".repeat(64)}", 0L..9L).first)
        assertEquals(404, ask("/updates/v1/objects/zz", 0L..9L).first)
        assertEquals(404, ask("/updates/v1/other", null).first)
        try { mine.get("/mail", null); org.junit.Assert.fail("answered outside the update routes") } catch (_: java.io.IOException) { }
    }

    @Test fun theWholeDemoUpdateRunsThroughTheRealPhoneCode() {
        val files = files(); val hubDir = folder.newFolder("hub")
        var wifi = true
        val updates = DemoUpdates.build(files, store(files), hubDir, rangeMillis = 0, executor = direct, sdk = 28) { wifi }
        val controller = checkNotNull(updates.controller)
        assertEquals("the demo pack is on the phone first", 1, store(files).list().single { it.id == DemoUpdatePack.ID }.version)
        assertEquals("and the floor is raised for it at start", 1L, updates.trust!!.floor(UpdateMetadata.VOICE, DemoUpdatePack.ID))
        controller.refresh()
        val row = controller.view().rows.single()
        assertEquals(DemoUpdatePack.LABEL, row.label)
        assertEquals(State.Ready, row.state)
        assertEquals(1, updates.ready())
        val before = store(files).list().single { it.id == DemoUpdatePack.ID }.version
        assertEquals(1, before)
        controller.download(row.key)
        assertEquals(State.Done, controller.view().rows.single().state)
        assertEquals(2, store(files).list().single { it.id == DemoUpdatePack.ID }.version)
        assertEquals(2L, updates.trust!!.floor(UpdateMetadata.VOICE, DemoUpdatePack.ID))
        controller.refresh()
        assertEquals(Notice.UP_TO_DATE, controller.view().notice)
        // Without the hub's Wi-Fi the phone attempts nothing.
        wifi = false
        controller.refresh()
        assertEquals(Notice.NO_WIFI, controller.view().notice)
    }

    @Test fun aRemovedDemoPackIsNotPutBackOnTheNextStart() {
        val files = files(); val hubDir = folder.newFolder("hub")
        DemoUpdates.build(files, store(files), hubDir, rangeMillis = 0, executor = direct, sdk = 28) { true }
        store(files).remove(DemoUpdatePack.ID)
        DemoUpdates.build(files, store(files), hubDir, rangeMillis = 0, executor = direct, sdk = 28) { true }
        assertNull(store(files).list().firstOrNull { it.id == DemoUpdatePack.ID })
    }

    @Test fun aFileThatIsNotTheSignedOneOnTheDemoHubIsRefused() {
        val files = files(); val hubDir = folder.newFolder("hub")
        val updates = DemoUpdates.build(files, store(files), hubDir, rangeMillis = 0, executor = direct, sdk = 28) { true }
        val object2 = File(hubDir, "updates/objects/${hex(DemoUpdatePack.build(2).entry.digest)}")
        object2.writeBytes(object2.readBytes().also { it[500] = (it[500] + 1).toByte() })
        val controller = checkNotNull(updates.controller)
        controller.refresh()
        controller.download(controller.view().rows.single().key)
        assertEquals(Why.NOT_TRUSTED, (controller.view().rows.single().state as State.Failed).why)
        assertEquals(1, store(files).list().single { it.id == DemoUpdatePack.ID }.version)
    }
}

package org.phonemesh.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackStore
import org.phonemesh.core.update.UpdateController.Notice
import org.phonemesh.core.update.UpdateController.State
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import java.io.File
import java.util.concurrent.Executor

/** The phone's update state as the app opens it (U5a): durable files, floors repaired at start, damage never reset, keys decide. */
class HubUpdatesTest {
    @get:Rule val folder = TemporaryFolder()
    private val keys = UpdateKit.keys()
    private val v1 = UpdateKit.voice("voice-fr-siwis", 1)
    private val v2 = UpdateKit.voice("voice-fr-siwis", 2, modelBytes = 6_000)
    private val direct = Executor { it.run() }
    private val hub = UpdateKit.Hub()
    private val dir by lazy { File(folder.root, "updates") }
    private val packs by lazy { folder.newFolder("packs") }
    private val compiled get() = listOf(CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "x.zip", v1.zip.size.toLong(), UpdateKit.hex(v1.entry.sums)))
    private val baseline get() = UpdateKit.catalog(keys, 1, 1, listOf(v1.entry))

    private fun open(roots: UpdateRoots = keys.roots, transport: () -> org.phonemesh.core.update.HubTransport? = { hub }) =
        HubUpdates.open(dir, packs, PackStore(packs, compiled), transport, roots, baseline, compiled, direct, sdk = 28)

    @Test fun firstStartKeepsTheSignedBaselineInItsOwnFilesAndASecondStartReadsThemBack() {
        val first = open()
        assertNotNull(first.controller); assertNull(first.problem)
        val files = File(dir, "trust").listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("trust-1.bin"), files)
        val again = open()
        assertArrayEquals(first.trust!!.state.encode(), again.trust!!.state.encode())
    }

    @Test fun packsAlreadyInstalledGetTheirVersionFloorAtStart() {
        PackStore(packs, compiled).install(v1.zip.inputStream())
        val opened = open()
        assertEquals(1L, opened.trust!!.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
    }

    @Test fun anInstallThatFinishedJustBeforeACrashIsRepairedAtTheNextStart() {
        PackStore(packs, compiled).install(v1.zip.inputStream())
        open()
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(v2.entry))
        val live = open().also { it.controller!!.refresh(); it.controller!!.download("3/voice-fr-siwis") }
        assertEquals(State.Done, live.controller!!.view().rows.single().state)
        // The "crash": the floor is lost but the new pack is on disk.
        File(dir, "trust").listFiles()!!.forEach { it.delete() }
        val restarted = HubUpdates.open(dir, packs, PackStore(packs, compiled), { hub }, keys.roots, UpdateKit.catalog(keys, 1, 2, listOf(v2.entry)), compiled, direct, sdk = 28)
        assertEquals(2L, restarted.trust!!.floor(UpdateMetadata.VOICE, "voice-fr-siwis"))
    }

    @Test fun damagedSavedStateTurnsUpdatesOffAndIsNeverReplaced() {
        open()
        val file = File(dir, "trust/trust-1.bin")
        val saved = file.readBytes()
        File(dir, "trust/trust-0.bin").writeBytes(byteArrayOf(1, 2, 3))
        file.writeBytes(saved.copyOf(saved.size - 5))
        val off = open()
        assertNull(off.controller)
        assertEquals(R.string.updates_off, off.problem)
        assertEquals("nothing was reset", saved.size - 5, file.length().toInt())
    }

    @Test fun aCatalogSignedByAKeyOutsideTheRootsIsNotTrusted() {
        val stranger = UpdateKit.keys()
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(stranger, 1, 2, listOf(v2.entry))
        PackStore(packs, compiled).install(v1.zip.inputStream())
        val opened = open()
        opened.controller!!.refresh()
        assertEquals(Notice.LIST_NOT_TRUSTED, opened.controller!!.view().notice)
        assertTrue(opened.controller!!.view().rows.isEmpty())
        // Even a catalog signed by the stranger's key under the right id and number.
        assertEquals(1L, opened.trust!!.state.sequence)
    }

    @Test fun theKeysFileInTheBuildIsWhatDecidesAndOpensWithTheRealBaseline() {
        // The default roots are update/public-keys.txt and the baseline is baseline-catalog.pmup: one file pair to swap.
        val opened = HubUpdates.open(dir, packs, PackStore(packs, compiled), { null })
        assertNotNull(opened.controller)
        assertEquals(UpdateRoots.compiled.catalogKeyId, 1)
        assertFalse(opened.trust!!.state.sequence < 1)
    }

    @Test fun withoutTheHubsWifiTheTransportIsNotEvenTried() {
        var asked = 0
        val opened = open { asked++; null }
        PackStore(packs, compiled).install(v1.zip.inputStream())
        opened.controller!!.refresh()
        assertEquals(Notice.NO_WIFI, opened.controller!!.view().notice)
        assertTrue(hub.requests.isEmpty())
        assertTrue(asked >= 1)
    }

    @Test fun updatesAreOnlyEverOfferedForPacksThePhoneHas() {
        hub.add(v2.zip)
        hub.catalog = UpdateKit.catalog(keys, 1, 2, listOf(v2.entry))
        val opened = open()
        opened.controller!!.refresh()
        assertTrue("no pack installed, so nothing to update", opened.controller!!.view().rows.isEmpty())
        assertEquals(0, opened.ready())
    }
}

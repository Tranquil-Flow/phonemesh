package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackKind
import java.io.File

class PackRowsTest {
    private val catalog = GeneratedPackCatalog.entries
    private fun installed(id: String) = catalog.first { it.id == id }.let { PackInfo(File(id), id, it.version, it.kind, id) }

    @Test fun nothingInstalledOffersEveryDownloadEnginesFirst() {
        val rows = PackRows.build(catalog, emptyList(), emptyMap())
        assertEquals(catalog.size, rows.size)
        assertEquals(listOf(PackKind.ENGINE, PackKind.ENGINE, PackKind.VOICE, PackKind.VOICE), rows.map { it.kind })
        rows.forEach { assertEquals(PackRow.Action.Download, it.action) }
        assertEquals(UiText(R.string.plain, listOf("Français")), rows.first { it.id == "voice-fr-siwis" }.name)
        assertEquals(UiText(R.string.plain, listOf("English")), rows.first { it.id == "voice-en-US-ljspeech" }.name)
        assertEquals(UiText(R.string.pack_engine_name, listOf("Tiny")), rows.first { it.id == "engine-whisper-tiny" }.name)
    }

    @Test fun installedVoicesCanBeRemovedButTheOnlyEngineCannot() {
        val rows = PackRows.build(catalog, listOf(installed("engine-whisper-tiny"), installed("voice-fr-siwis")), emptyMap())
        assertEquals(PackRow.Action.None, rows.first { it.id == "engine-whisper-tiny" }.action)
        assertEquals(PackRow.Action.Remove, rows.first { it.id == "voice-fr-siwis" }.action)
    }

    @Test fun eitherEngineCanBeRemovedWhenBothAreInstalled() {
        val rows = PackRows.build(catalog, listOf(installed("engine-whisper-tiny"), installed("engine-whisper-base")), emptyMap())
        rows.filter { it.kind == PackKind.ENGINE }.forEach { assertEquals(PackRow.Action.Remove, it.action) }
    }

    @Test fun pendingDownloadsShowTheirProgress() {
        val rows = PackRows.build(catalog, emptyList(), mapOf("voice-fr-siwis" to (42L to 100L), "voice-en-US-ljspeech" to null))
        assertEquals(PackRow.Action.Progress(42), rows.first { it.id == "voice-fr-siwis" }.action)
        assertEquals(PackRow.Action.Progress(0), rows.first { it.id == "voice-en-US-ljspeech" }.action)
    }

    @Test fun installedPacksOutsideTheCatalogCanStillBeRemoved() {
        val extra = PackInfo(File("x"), "voice-de-fixture", 1, PackKind.VOICE, "Deutsch")
        val row = PackRows.build(catalog, listOf(extra), emptyMap()).single { it.id == extra.id }
        assertEquals(PackRow.Action.Remove, row.action)
        assertEquals(UiText(R.string.plain, listOf("Deutsch")), row.name)
    }
}

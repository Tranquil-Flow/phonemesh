package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackKind

class EngineCardsTest {
    private val tiny = GeneratedPackCatalog.entries.single { it.id == "engine-whisper-tiny" }
    private val base = GeneratedPackCatalog.entries.single { it.id == "engine-whisper-base" }
    private fun row(entry: CatalogEntry, action: PackRow.Action) =
        PackRow(entry.id, PackKind.ENGINE, UiText(R.string.plain, listOf(entry.id)), entry, action)

    @Test fun fastIsTinyAndAccurateIsBase() {
        val cards = EngineCards.build(listOf(row(base, PackRow.Action.Download), row(tiny, PackRow.Action.None)), tiny.id)
        assertEquals(listOf(tiny.id, base.id), cards.map { it.id })
        assertEquals(listOf(R.string.engine_fast, R.string.engine_accurate), cards.map { it.title })
        assertEquals(listOf(R.string.engine_fast_detail, R.string.engine_accurate_detail), cards.map { it.description })
        assertEquals(listOf(3 to 2, 1 to 3), cards.map { it.speed to it.accuracy })
        assertEquals(listOf(60, 94), cards.map { it.megabytes })
        assertEquals(listOf(EngineCard.State.InUse, EngineCard.State.Download), cards.map { it.state })
    }

    @Test fun anInstalledEngineNotInUseCanBeUsedOrRemoved() {
        val cards = EngineCards.build(listOf(row(tiny, PackRow.Action.Remove), row(base, PackRow.Action.Remove)), base.id)
        assertEquals(listOf(EngineCard.State.Installed(removable = true), EngineCard.State.InUse), cards.map { it.state })
    }

    @Test fun aDownloadShowsItsProgress() {
        val cards = EngineCards.build(listOf(row(tiny, PackRow.Action.None), row(base, PackRow.Action.Progress(40))), tiny.id)
        assertEquals(EngineCard.State.Progress(40), cards[1].state)
    }

    @Test fun enginesOutsideTheCatalogAreNotCards() {
        assertEquals(setOf(tiny.id, base.id), EngineCards.ids)
        assertEquals(emptyList<EngineCard>(), EngineCards.build(emptyList(), null))
        val extra = PackRow("another-engine", PackKind.ENGINE, UiText(R.string.plain, listOf("Other")), null, PackRow.Action.Remove)
        val voice = GeneratedPackCatalog.entries.first { it.kind == PackKind.VOICE }
        assertEquals(emptyList<EngineCard>(), EngineCards.build(listOf(extra, row(voice, PackRow.Action.Download)), extra.id))
    }

    @Test fun anInstalledEngineThatCannotBeRemovedStaysProtected() {
        val cards = EngineCards.build(listOf(row(tiny, PackRow.Action.None)), "another-engine")
        assertEquals(EngineCard.State.Installed(removable = false), cards.single().state)
    }

    @Test fun aPendingDownloadTakesPrecedenceOverTheSelectedEngine() {
        val cards = EngineCards.build(listOf(row(base, PackRow.Action.Progress(0))), base.id)
        assertEquals(EngineCard.State.Progress(0), cards.single().state)
    }
}

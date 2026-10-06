package org.phonemesh.app

import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackKind

/** One line of the pack list: a plain name and what the user can do with the pack. */
data class PackRow(val id: String, val kind: PackKind, val name: String, val entry: CatalogEntry?, val action: Action) {
    sealed interface Action {
        object Download : Action
        data class Progress(val percent: Int) : Action { val text get() = "↓ $percent %" }
        object Remove : Action
        /** Installed, but it cannot be removed (the only engine). */
        object None : Action
    }
}

/** Builds the pack list: every catalog entry (engines first), then installed packs outside the catalog. */
object PackRows {
    fun build(catalog: List<CatalogEntry>, installed: List<PackInfo>,
        pending: Map<String, Pair<Long, Long>?>): List<PackRow> {
        val installedIds = installed.map { it.id }.toSet()
        val engines = installed.count { it.kind == PackKind.ENGINE }
        fun removable(kind: PackKind) = if (kind == PackKind.VOICE || engines > 1) PackRow.Action.Remove else PackRow.Action.None
        val known = catalog.sortedBy { it.kind != PackKind.ENGINE }.map { entry ->
            val action = when {
                entry.id in pending -> PackRow.Action.Progress(percent(pending[entry.id]))
                entry.id in installedIds -> removable(entry.kind)
                else -> PackRow.Action.Download
            }
            PackRow(entry.id, entry.kind, PackDownloadText.name(entry), entry, action)
        }
        val catalogIds = catalog.map { it.id }.toSet()
        val extra = installed.filter { it.id !in catalogIds }.sortedBy { it.kind != PackKind.ENGINE }
            .map { PackRow(it.id, it.kind, it.label, null, removable(it.kind)) }
        return known + extra
    }

    private fun percent(progress: Pair<Long, Long>?): Int {
        val (done, total) = progress ?: return 0
        return if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
    }
}

package org.phonemesh.core

/** What the voice setup card still has to fetch, and its size in whole megabytes. */
object SetupPlan {
    const val DEFAULT_ENGINE = "engine-whisper-tiny"

    /** The default engine unless any engine is installed, then the voice unless any voice speaks [languageTag]. */
    fun missing(catalog: List<CatalogEntry>, installed: List<PackInfo>, voicePackId: String, languageTag: String): List<CatalogEntry> {
        val needEngine = installed.none { it.kind == PackKind.ENGINE }
        val needVoice = installed.none { it.kind == PackKind.VOICE && it.languageTag == languageTag }
        return listOfNotNull(
            catalog.firstOrNull { it.id == DEFAULT_ENGINE }?.takeIf { needEngine },
            catalog.firstOrNull { it.id == voicePackId }?.takeIf { needVoice },
        )
    }

    fun megabytes(entries: List<CatalogEntry>): Int = ((entries.sumOf { it.zipBytes } + 500_000L) / 1_000_000L).toInt()
}

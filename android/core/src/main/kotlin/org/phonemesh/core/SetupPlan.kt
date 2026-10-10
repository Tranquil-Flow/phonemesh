package org.phonemesh.core

/** The voice step's two ways to get the voice: whether Download shows (the friend always does), and whether the friend is the main one. */
data class VoiceChoices(val download: Boolean, val friendFirst: Boolean)

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

    /**
     * A running download replaces Download with its progress; the friend stays on offer under it, because a phone
     * without internet may show nothing but a download that never starts. Without internet the friend is the main
     * choice; with it, Download is.
     */
    fun voiceChoices(missing: Boolean, downloading: Boolean, noInternet: Boolean) =
        VoiceChoices(download = missing && !downloading, friendFirst = noInternet)

    fun megabytes(entries: List<CatalogEntry>): Int = ((entries.sumOf { it.zipBytes } + 500_000L) / 1_000_000L).toInt()
}

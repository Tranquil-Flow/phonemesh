package org.phonemesh.core

/** Whether a pack download may start, decided before anything is fetched. */
sealed interface DownloadDecision {
    object Start : DownloadDecision
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : DownloadDecision
}

object DownloadPlan {
    private const val HEADROOM_BYTES = 50_000_000L

    /** ZIP plus unpacked size (≤ 2× ZIP, conservative) plus 50 MB headroom. */
    fun decide(entry: CatalogEntry, freeBytes: Long): DownloadDecision {
        val needed = entry.zipBytes * 3 + HEADROOM_BYTES
        return if (freeBytes >= needed) DownloadDecision.Start else DownloadDecision.NotEnoughSpace(needed, freeBytes)
    }

    /** A catalog download must be exactly the catalog ZIP size. */
    fun sizeMatches(entry: CatalogEntry, downloadedBytes: Long): Boolean = downloadedBytes == entry.zipBytes
}

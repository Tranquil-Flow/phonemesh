package org.phonemesh.app

import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.PackRejected
import java.io.IOException

/** Short sentences for pack failures, from strings.xml. Raw exception messages never reach the screen. */
object PackMessages {
    /** A pack could not be removed (never phrased as a download problem). */
    val REMOVE_FAILED = UiText(R.string.pack_remove_failed)

    fun forFailure(error: Throwable): UiText = when (error) {
        is PackDownloadFailure -> error.text
        is PackRejected -> UiText(
            when (error.reason) {
                PackRejected.Reason.DAMAGED -> R.string.pack_damaged
                PackRejected.Reason.WRONG_FILE -> R.string.pack_wrong_file
                PackRejected.Reason.UNKNOWN_PACK -> R.string.pack_unknown
                PackRejected.Reason.OLDER_VERSION -> R.string.pack_older
                PackRejected.Reason.NO_SPACE -> R.string.no_space
                PackRejected.Reason.UNSAFE -> R.string.pack_unsafe
            },
        )
        is IOException -> UiText(R.string.download_failed_retry)
        else -> UiText(R.string.generic_failed)
    }

    fun forDecision(d: DownloadDecision): UiText? = when (d) {
        DownloadDecision.Start -> null
        is DownloadDecision.NotEnoughSpace -> UiText(R.string.no_space)
    }

    /** A picked or received file: an unreadable file is not a download problem. */
    fun forImportFailure(error: Throwable): UiText = when (error) {
        is PackRejected -> forFailure(error)
        is IOException -> UiText(R.string.file_unreachable)
        else -> UiText(R.string.generic_failed)
    }

    /** Any other background operation: a fixed sentence, never the exception's own text. */
    fun forOperation(error: Throwable): UiText = when {
        error is PackRejected || error is PackDownloadFailure -> forFailure(error)
        generateSequence(error) { it.cause }.take(8).any { e -> e.message?.let { "No space" in it || "ENOSPC" in it } == true } ->
            UiText(R.string.no_space)
        else -> UiText(R.string.operation_failed)
    }

    /** Names the damaged packs a share left out ([names] already worded), or null if none was. */
    fun forSkipped(names: List<String>): UiText? =
        if (names.isEmpty()) null else UiText(R.string.pack_skipped, listOf(names.joinToString(", ")))

    fun importProgress(bytes: Long) = UiText(R.string.import_progress, listOf(bytes / 1_000_000))

    fun shareProgress(bytes: Long) = UiText(R.string.share_progress, listOf(bytes / 1_000_000))
}

/** A failure whose message is already a plain-French sentence for the Phase 2 screen (removed with it in Task 14). */
class ShownError(message: String) : Exception(message)

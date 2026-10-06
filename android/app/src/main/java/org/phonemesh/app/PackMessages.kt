package org.phonemesh.app

import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.PackRejected
import java.io.IOException

/** Short plain-French sentences for pack failures. Raw exception messages never reach the screen. */
object PackMessages {
    private const val NO_SPACE = "Pas assez d'espace sur le téléphone."
    /** A pack could not be removed (never phrased as a download problem). */
    const val REMOVE_FAILED = "Impossible de retirer le pack."

    fun forFailure(error: Throwable): String = when (error) {
        // Already a fixed plain sentence chosen by PackDownloads.
        is PackDownloadFailure -> error.message ?: GENERIC
        is PackRejected -> when (error.reason) {
            PackRejected.Reason.DAMAGED -> "Fichier abîmé. Téléchargez-le à nouveau."
            PackRejected.Reason.WRONG_FILE -> "Ce n'est pas un pack Phonemesh."
            PackRejected.Reason.UNKNOWN_PACK -> "Pack inconnu. Il a été refusé."
            PackRejected.Reason.OLDER_VERSION -> "Une version plus récente est installée."
            PackRejected.Reason.NO_SPACE -> NO_SPACE
            PackRejected.Reason.UNSAFE -> "Fichier refusé pour votre sécurité."
        }
        is IOException -> "Échec du téléchargement. Réessayez."
        else -> GENERIC
    }

    fun forDecision(d: DownloadDecision): String? = when (d) {
        DownloadDecision.Start -> null
        is DownloadDecision.NotEnoughSpace -> NO_SPACE
    }

    /** A picked or received file: an unreadable file is not a download problem. */
    fun forImportFailure(error: Throwable): String = when (error) {
        is PackRejected -> forFailure(error)
        is IOException -> "Fichier inaccessible. Réessayez."
        else -> GENERIC
    }

    /** Any other background operation: a fixed sentence, never the exception's own text. */
    fun forOperation(error: Throwable): String = when {
        error is ShownError -> error.message ?: OPERATION_FAILED
        error is PackRejected || error is PackDownloadFailure -> forFailure(error)
        generateSequence(error) { it.cause }.take(8).any { e -> e.message?.let { "No space" in it || "ENOSPC" in it } == true } -> NO_SPACE
        else -> OPERATION_FAILED
    }

    /** Names the damaged packs a share left out, or null if none was. */
    fun forSkipped(names: List<String>): String? =
        if (names.isEmpty()) null else "Pack abîmé, non partagé : ${names.joinToString(", ")}."

    fun importProgress(bytes: Long) = "Import : ${bytes / 1_000_000} Mo"
    fun shareProgress(bytes: Long) = "Partage : ${bytes / 1_000_000} Mo"

    private const val OPERATION_FAILED = "Opération impossible. Réessayez."
    private const val GENERIC = "Échec. Réessayez."
}

/** A failure whose message is already a short plain-French sentence written for the screen. */
class ShownError(message: String) : Exception(message)

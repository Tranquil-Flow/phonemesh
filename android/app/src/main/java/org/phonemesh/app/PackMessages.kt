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

    private const val GENERIC = "Échec. Réessayez."
}

package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.PackRejected
import java.io.FileNotFoundException
import java.io.IOException

class PackMessagesTest {
    @Test fun eachReasonHasItsOwnShortMessage() {
        val texts = PackRejected.Reason.values().map { PackMessages.forFailure(PackRejected(it, "x")) }
        assertEquals(texts.size, texts.toSet().size)
        texts.forEach { assertTrue(it, it.split(" ").size <= 8) }
    }
    @Test fun unknownErrorsGetAGenericMessage() =
        assertEquals("Échec. Réessayez.", PackMessages.forFailure(IllegalStateException("boom")))
    @Test fun lowSpaceDecisionIsExplained() =
        assertEquals("Pas assez d'espace sur le téléphone.", PackMessages.forDecision(DownloadDecision.NotEnoughSpace(1, 0)))

    @Test fun startNeedsNoMessage() = assertNull(PackMessages.forDecision(DownloadDecision.Start))

    @Test fun inputOutputErrorsAreDownloadFailuresWithoutTheRawMessage() =
        assertEquals("Échec du téléchargement. Réessayez.", PackMessages.forFailure(IOException("ENOENT /data/x")))

    @Test fun plainDownloadFailuresKeepTheirSentence() {
        listOf("Pas assez d'espace sur le téléphone.", "Problème de connexion. Réessayez.", "Échec du téléchargement").forEach {
            assertEquals(it, PackMessages.forFailure(PackDownloadFailure(it)))
        }
    }

    @Test fun removalFailureNeverMentionsDownloading() {
        assertEquals("Impossible de retirer le pack.", PackMessages.REMOVE_FAILED)
        assertTrue(!PackMessages.REMOVE_FAILED.contains("téléchargement", ignoreCase = true))
    }

    @Test fun rejectionMessagesAreTheFixedSentencesNotTheRawOnes() =
        assertEquals("Fichier abîmé. Téléchargez-le à nouveau.",
            PackMessages.forFailure(PackRejected(PackRejected.Reason.DAMAGED, "Fichier endommagé : tts/model.onnx")))

    @Test fun importFailuresSayTheFileIsUnreachableNotTheDownload() {
        for (error in listOf(IOException("EACCES /storage/x"), FileNotFoundException("content://x"))) {
            assertEquals("Fichier inaccessible. Réessayez.", PackMessages.forImportFailure(error))
        }
        assertTrue(PackMessages.forImportFailure(IOException()).split(" ").size <= 8)
        assertEquals("Pack inconnu. Il a été refusé.",
            PackMessages.forImportFailure(PackRejected(PackRejected.Reason.UNKNOWN_PACK, "x")))
    }

    @Test fun operationFailuresNeverShowTheRawText() {
        assertEquals("Opération impossible. Réessayez.",
            PackMessages.forOperation(IllegalStateException("Cannot update /data/user/0/org.phonemesh.app/files/espeak-ng-data/phontab")))
        assertEquals("Opération impossible. Réessayez.", PackMessages.forOperation(IOException("Speech voice pack root is not a directory")))
        assertEquals("Pas assez d'espace sur le téléphone.", PackMessages.forOperation(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("Texte trop long : 200 octets maximum.", PackMessages.forOperation(ShownError("Texte trop long : 200 octets maximum.")))
        assertEquals("Fichier abîmé. Téléchargez-le à nouveau.", PackMessages.forOperation(PackRejected(PackRejected.Reason.DAMAGED, "x")))
    }

    @Test fun progressIsCountedInWholeMegabytes() {
        assertEquals("Import : 12 Mo", PackMessages.importProgress(12_345_678))
        assertEquals("Partage : 5 Mo", PackMessages.shareProgress(5_000_000))
    }

    @Test fun skippedPacksAreNamedInPlainWords() {
        assertNull(PackMessages.forSkipped(emptyList()))
        assertEquals("Pack abîmé, non partagé : Français.", PackMessages.forSkipped(listOf("Français")))
    }
}

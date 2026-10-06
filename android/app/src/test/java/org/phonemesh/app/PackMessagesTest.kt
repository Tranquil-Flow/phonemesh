package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.PackRejected
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
        listOf("Pas assez d'espace", "Problème de connexion. Réessayez.", "Échec du téléchargement").forEach {
            assertEquals(it, PackMessages.forFailure(PackDownloadFailure(it)))
        }
    }

    @Test fun rejectionMessagesAreTheFixedSentencesNotTheRawOnes() =
        assertEquals("Fichier abîmé. Téléchargez-le à nouveau.",
            PackMessages.forFailure(PackRejected(PackRejected.Reason.DAMAGED, "Fichier endommagé : tts/model.onnx")))
}

package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.PackRejected
import java.io.FileNotFoundException
import java.io.IOException

/** Wording lives in strings.xml (StringsFileTest checks length and words); these check which sentence is chosen. */
class PackMessagesTest {
    @Test fun eachReasonHasItsOwnMessage() {
        val texts = PackRejected.Reason.values().map { PackMessages.forFailure(PackRejected(it, "x")) }
        assertEquals(texts.size, texts.toSet().size)
    }
    @Test fun unknownErrorsGetAGenericMessage() =
        assertEquals(UiText(R.string.generic_failed), PackMessages.forFailure(IllegalStateException("boom")))
    @Test fun lowSpaceDecisionIsExplained() =
        assertEquals(UiText(R.string.no_space), PackMessages.forDecision(DownloadDecision.NotEnoughSpace(1, 0)))
    @Test fun startNeedsNoMessage() = assertNull(PackMessages.forDecision(DownloadDecision.Start))
    @Test fun inputOutputErrorsAreDownloadFailuresWithoutTheRawMessage() =
        assertEquals(UiText(R.string.download_failed_retry), PackMessages.forFailure(IOException("ENOENT /data/x")))
    @Test fun plainDownloadFailuresKeepTheirText() {
        listOf(R.string.no_space, R.string.connection_problem, R.string.download_failed).forEach {
            assertEquals(UiText(it), PackMessages.forFailure(PackDownloadFailure(UiText(it))))
        }
    }
    @Test fun removalFailureHasItsOwnText() = assertEquals(UiText(R.string.pack_remove_failed), PackMessages.REMOVE_FAILED)
    @Test fun rejectionMessagesAreTheFixedSentences() = assertEquals(UiText(R.string.pack_damaged),
        PackMessages.forFailure(PackRejected(PackRejected.Reason.DAMAGED, "Fichier endommagé : tts/model.onnx")))
    @Test fun importFailuresSayTheFileIsUnreachable() {
        for (error in listOf(IOException("EACCES /storage/x"), FileNotFoundException("content://x"))) {
            assertEquals(UiText(R.string.file_unreachable), PackMessages.forImportFailure(error))
        }
        assertEquals(UiText(R.string.pack_unknown), PackMessages.forImportFailure(PackRejected(PackRejected.Reason.UNKNOWN_PACK, "x")))
    }
    @Test fun operationFailuresNeverShowTheRawText() {
        assertEquals(UiText(R.string.operation_failed), PackMessages.forOperation(IllegalStateException("Cannot update /data/x")))
        assertEquals(UiText(R.string.no_space), PackMessages.forOperation(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(UiText(R.string.pack_damaged), PackMessages.forOperation(PackRejected(PackRejected.Reason.DAMAGED, "x")))
    }
    @Test fun progressIsCountedInWholeMegabytes() {
        assertEquals(UiText(R.string.import_progress, listOf(12L)), PackMessages.importProgress(12_345_678))
        assertEquals(UiText(R.string.share_progress, listOf(5L)), PackMessages.shareProgress(5_000_000))
    }
    @Test fun skippedPacksAreNamed() {
        assertNull(PackMessages.forSkipped(emptyList()))
        assertEquals(UiText(R.string.pack_skipped, listOf("Français")), PackMessages.forSkipped(listOf("Français")))
    }
}

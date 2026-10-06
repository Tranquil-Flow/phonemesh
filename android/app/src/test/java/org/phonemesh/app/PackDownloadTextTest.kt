package org.phonemesh.app

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.GeneratedPackCatalog

class PackDownloadTextTest {
    private fun entry(id: String) = GeneratedPackCatalog.entries.first { it.id == id }

    @Test fun notificationTitlesArePlainFrench() {
        assertEquals("Pack de langue Français", PackDownloadText.title(entry("voice-fr-siwis")))
        assertEquals("Pack de langue English", PackDownloadText.title(entry("voice-en-US-ljspeech")))
        assertEquals("Pack de reconnaissance vocale", PackDownloadText.title(entry("engine-whisper-tiny")))
    }

    @Test fun failureReasonsBecomeShortSentencesWithoutCodes() {
        assertEquals("Pas assez d'espace", PackDownloadText.failure(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        val connection = "Problème de connexion. Réessayez."
        listOf(DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
            DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_CANNOT_RESUME, 404, 503)
            .forEach { assertEquals("reason $it", connection, PackDownloadText.failure(it)) }
        listOf(DownloadManager.ERROR_UNKNOWN, DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_DEVICE_NOT_FOUND)
            .forEach { assertEquals("reason $it", "Échec du téléchargement", PackDownloadText.failure(it)) }
    }
}

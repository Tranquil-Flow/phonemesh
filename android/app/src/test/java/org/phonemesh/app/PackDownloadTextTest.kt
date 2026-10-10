package org.phonemesh.app

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.GeneratedPackCatalog

class PackDownloadTextTest {
    private fun entry(id: String) = GeneratedPackCatalog.entries.first { it.id == id }

    @Test fun notificationTitlesNameTheLanguage() {
        assertEquals(UiText(R.string.pack_title_voice, listOf("Français")), PackDownloadText.title(entry("voice-fr-siwis")))
        assertEquals(UiText(R.string.pack_title_voice, listOf("English")), PackDownloadText.title(entry("voice-en-US-ljspeech")))
        assertEquals(UiText(R.string.pack_title_engine), PackDownloadText.title(entry("engine-whisper-tiny")))
    }

    @Test fun failureReasonsBecomeShortSentencesWithoutCodes() {
        assertEquals(UiText(R.string.no_space), PackDownloadText.failure(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        listOf(DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
            DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_CANNOT_RESUME, 404, 503)
            .forEach { assertEquals("reason $it", UiText(R.string.connection_problem), PackDownloadText.failure(it)) }
        listOf(DownloadManager.ERROR_UNKNOWN, DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_DEVICE_NOT_FOUND)
            .forEach { assertEquals("reason $it", UiText(R.string.download_failed), PackDownloadText.failure(it)) }
    }

    @Test fun onlyAnUnreachableServerMeansNoInternet() {
        listOf(DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME)
            .forEach { assertEquals("reason $it", true, PackDownloadText.unreachable(it)) }
        // An answer from the server, a full phone or a file problem is not a missing connection.
        listOf(DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_UNHANDLED_HTTP_CODE, DownloadManager.ERROR_TOO_MANY_REDIRECTS,
            DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_UNKNOWN, 404, 503)
            .forEach { assertEquals("reason $it", false, PackDownloadText.unreachable(it)) }
    }

    @Test fun aPausedDownloadWaitingForTheNetworkMeansNoInternet() {
        listOf(DownloadManager.PAUSED_WAITING_FOR_NETWORK, DownloadManager.PAUSED_WAITING_TO_RETRY)
            .forEach { assertEquals("reason $it", true, PackDownloadText.waitsForInternet(it)) }
        listOf(DownloadManager.PAUSED_QUEUED_FOR_WIFI, DownloadManager.PAUSED_UNKNOWN)
            .forEach { assertEquals("reason $it", false, PackDownloadText.waitsForInternet(it)) }
    }
}

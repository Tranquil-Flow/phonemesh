package org.phonemesh.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Captures every screen state into screenshots-phase3/. Runs only in the screenshot build; skips otherwise. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    @Test fun captureEveryScreen() {
        assumeFalse("only in the screenshot build (-Pphonemesh.screenshots=true)", BuildConfig.SECURE_SCREENS)
        val inst = InstrumentationRegistry.getInstrumentation()
        val dir = File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "screenshots-phase3").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            for ((name, state) in ScreenStates.all) {
                val activity = state()
                inst.waitForIdleSync()
                Thread.sleep(500) // let the ring and the bars draw
                val shot = checkNotNull(inst.uiAutomation.takeScreenshot()) { "no screenshot for $name" }
                File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Views.finish(activity)
            }
        } finally {
            TestCore.uninstall()
        }
        assertEquals(ScreenStates.all.size, dir.listFiles().orEmpty().count { it.name.endsWith(".png") })
    }
}

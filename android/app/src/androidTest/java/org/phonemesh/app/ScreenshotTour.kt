package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.HowToScenes
import java.io.File

/** Captures every screen state into screenshots-phase3/. Runs only in the screenshot build; skips otherwise. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    @Test fun captureEveryScreen() {
        assumeFalse("only in the screenshot build (-Pphonemesh.screenshots=true)", BuildConfig.SECURE_SCREENS)
        val inst = InstrumentationRegistry.getInstrumentation()
        val states = ScreenStates.all + ScreenStates.dark + ScreenStates.region + ScreenStates.regionDark
        val dir = File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "screenshots-phase3").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            for ((name, state) in states) {
                val activity = state()
                try {
                    inst.waitForIdleSync()
                    Thread.sleep(500) // let the ring and the bars draw
                    val shot = checkNotNull(inst.uiAutomation.takeScreenshot()) { "no screenshot for $name" }
                    File(dir, "$name.png").outputStream().use { check(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    shot.recycle()
                } finally {
                    ScreenStates.finish(activity)
                }
            }
            for (scene in HowToScenes.all.values) {
                val bitmap = TestCore.onMain {
                    val view = HowToView(inst.targetContext, scene).apply {
                        freezeAt(scene.still)
                        measure(View.MeasureSpec.makeMeasureSpec(880, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        layout(0, 0, measuredWidth, measuredHeight)
                    }
                    Bitmap.createBitmap(880, 440, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
                }
                File(dir, "howto-${scene.name}.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        } finally {
            TestCore.uninstall()
        }
        val expected = states.map { "${it.first}.png" } +
            HowToScenes.all.keys.map { "howto-$it.png" }
        assertEquals(expected.size, dir.listFiles().orEmpty().count { it.name.endsWith(".png") })
        assertEquals(expected.toSet(), dir.listFiles().orEmpty().map { it.name }.toSet())
    }
}

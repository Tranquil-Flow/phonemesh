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
    private fun requireUnobscuredCapture(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var active = automation.rootInActiveWindow
        // Accessibility connects lazily; its first root may precede the window event.
        while (active == null && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            active = automation.rootInActiveWindow
        }
        val root = checkNotNull(active) { "no active capture window for $name after accessibility readiness wait" }
        check(root.findAccessibilityNodeInfosByText("isn't responding").isEmpty() &&
            root.findAccessibilityNodeInfosByViewId("android:id/aerr_wait").isEmpty()) {
            "System/application ANR obscures capture; recover the owned emulator before retrying"
        }
    }

    /** Additional real failure windows; emulator fixtures only, never owner-device instrumentation. */
    @Test fun captureAccountsPolishStates() {
        assumeFalse("only in the screenshot build", BuildConfig.SECURE_SCREENS)
        val inst = InstrumentationRegistry.getInstrumentation()
        val dir = File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "accounts-polish-captures")
        check(dir.deleteRecursively() || !dir.exists())
        check(dir.mkdirs())
        val expected = mutableSetOf<String>()
        fun capture(name: String, theme: ThemeMode) {
            val activity = checkNotNull(Views.resumed())
            Views.waitUntil { activity.window.decorView.isLaidOut && !activity.window.decorView.isLayoutRequested }
            inst.waitForIdleSync()
            Thread.sleep(1_200)
            requireUnobscuredCapture(name)
            assertEquals("capture theme must survive fixture resets", theme, TestCore.core.settings.theme)
            val shot = checkNotNull(inst.uiAutomation.takeScreenshot())
            try {
                File(dir, "$name.png").outputStream().use { check(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                expected += "$name.png"
            } finally { shot.recycle() }
        }
        val previousTheme = TestCore.nextTheme
        try {
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                TestCore.nextTheme = theme
                for (language in listOf("en-US", "fr")) {
                    val suffix = "$language-${theme.name.lowercase()}"
                    TestCore.nextTheme = theme
                    val camera = ScanCameraErrorsTest()
                    try {
                        if (language == "fr") camera.errorExplainsInFrenchAndClosesRunningResources()
                        else camera.openExceptionShowsActionableEnglishFeedback()
                        capture("camera-unavailable-$suffix", theme)
                    } finally { camera.tearDown() }
                    TestCore.nextTheme = theme
                    val safety = AddSomeoneScreensTest()
                    try {
                        if (language == "fr") safety.missingSafetyPicturesPreservePriorMetAndExplainInFrench()
                        else safety.missingSafetyPicturesStayUnconfirmedAndExplainInEnglish()
                        capture("safety-unavailable-$suffix", theme)
                    } finally { safety.tearDown() }
                    var hub: android.app.Activity? = null
                    try {
                        TestCore.nextTheme = theme
                        hub = ScreenStates.hub()
                        Views.finish(hub)
                        TestCore.onMain { TestCore.core.settings.language = language }
                        val screen = Views.start(HubActivity::class.java).also { hub = it }
                        Views.tap(screen, "hub_forget")
                        val dialog = checkNotNull(TestCore.onMain { screen.forgetDialogForTest })
                        Views.waitUntil { dialog.window?.decorView?.isLaidOut == true }
                        assertEquals(emptyList<String>(), A11yCheck.problems(dialog))
                        capture("hub-forget-$suffix", theme)
                        TestCore.onMain { dialog.dismiss() }
                    } finally {
                        Views.finish(hub)
                        TestCore.uninstall()
                    }
                }
            }
        } finally {
            TestCore.nextTheme = previousTheme
            TestCore.uninstall()
        }
        assertEquals(12, expected.size)
        assertEquals(expected, dir.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun captureEveryScreen() {
        assumeFalse("only in the screenshot build (-Pphonemesh.screenshots=true)", BuildConfig.SECURE_SCREENS)
        val inst = InstrumentationRegistry.getInstrumentation()
        val allStates = ScreenStates.all + ScreenStates.dark + ScreenStates.region + ScreenStates.regionDark
        val requested = InstrumentationRegistry.getArguments().getString("captureNames")?.split(',')?.toSet()
        val states = if (requested == null) allStates else allStates.filter { it.first in requested }
        if (requested != null) assertEquals("every requested capture exists", requested, states.map { it.first }.toSet())
        val scenes = if (requested == null) HowToScenes.all.values.toList() else emptyList()
        val dir = File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "screenshots-phase3").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            for ((name, state) in states) {
                val activity = state()
                try {
                    inst.waitForIdleSync()
                    Views.waitUntil { activity.window.decorView.isLaidOut && !activity.window.decorView.isLayoutRequested }
                    Thread.sleep(1_200) // let the window transition and system bars finish drawing
                    requireUnobscuredCapture(name)
                    val shot = checkNotNull(inst.uiAutomation.takeScreenshot()) { "no screenshot for $name" }
                    File(dir, "$name.png").outputStream().use { check(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    shot.recycle()
                } finally {
                    ScreenStates.finish(activity)
                }
            }
            for (scene in scenes) {
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
            scenes.map { "howto-${it.name}.png" }
        assertEquals(expected.size, dir.listFiles().orEmpty().count { it.name.endsWith(".png") })
        assertEquals(expected.toSet(), dir.listFiles().orEmpty().map { it.name }.toSet())
    }
}

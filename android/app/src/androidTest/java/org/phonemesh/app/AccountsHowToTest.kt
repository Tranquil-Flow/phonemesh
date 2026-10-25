package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.phonemesh.core.HowToScenes
import java.io.File

/** Silent production-renderer checks; optional frame export is synthetic instructional art only. */
class AccountsHowToTest {
    private var screen: SettingsActivity? = null
    @After fun close() { Views.finish(screen); TestCore.uninstall() }

    @Test fun bothLanguagesRenderBothSafetyOutcomesAndEveryApprovedAction() {
        val frames = linkedMapOf(
            "add_qr" to listOf(.14f, .30f, .38f, .74f, .86f),
            "add_link" to listOf(.12f, .29f, .53f, .71f, .84f),
            "add_accept" to listOf(.28f, .65f, .81f),
            "same_picture" to listOf(.16f, .43f, .50f, .62f, .76f, .87f, .93f),
        )
        for (language in listOf("en", "fr")) {
            TestCore.install(language = language)
            screen = Views.start(SettingsActivity::class.java)
            val activity = screen!!
            val export = InstrumentationRegistry.getArguments().getString("accountFrames") == "true"
            val directory = File(activity.getExternalFilesDir(null), "accounts-howto-frames")
            if (export) assertTrue(directory.isDirectory || directory.mkdirs())
            TestCore.onMain {
                val words = HowToKit.Words.of(activity).accounts
                assertEquals(activity.getString(R.string.add_title), words.add)
                assertEquals(activity.getString(R.string.add_send_link), words.sendLink)
                frames.forEach { (name, times) ->
                    val view = HowToView(activity, HowToScenes.get(name))
                    view.measure(View.MeasureSpec.makeMeasureSpec(880, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 880, 440)
                    times.forEach { t ->
                        view.freezeAt(t)
                        val bitmap = Bitmap.createBitmap(880, 440, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        assertEquals("$language $name backdrop", HowToKit.C.PANEL, bitmap.getPixel(8, 8))
                        if (export) File(directory, "$language-$name-${(t * 100).toInt()}.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        bitmap.recycle()
                    }
                }
            }
            assertTrue(TestCore.speech.spoken.isEmpty())
            Views.finish(screen); screen = null
            TestCore.uninstall()
        }
    }

    private fun linkFrame(t: Float): Bitmap {
        TestCore.install(language = "en")
        screen = Views.start(SettingsActivity::class.java)
        return TestCore.onMain {
            val view = HowToView(screen!!, HowToScenes.get("add_link"))
            view.measure(View.MeasureSpec.makeMeasureSpec(880, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 880, 440)
            view.freezeAt(t)
            Bitmap.createBitmap(880, 440, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
    }

    @Test fun receivingFingerActuallyTouchesTheLinkBubble() {
        val bitmap = linkFrame(.71f)
        // Receiver's link occupies x156..192, y29..45 in the 220x110 panel.
        assertEquals("finger must touch the link, not empty space below it", HowToKit.C.SKIN, bitmap.getPixel(172 * 4, 41 * 4))
        bitmap.recycle()
    }

    @Test fun theFlyingLinkIsNotHiddenBehindTheReceivingPhone() {
        val bitmap = linkFrame(.53f)
        assertEquals("the moving blue link must remain visible", HowToKit.C.BLUE, bitmap.getPixel(170 * 4, 41 * 4))
        bitmap.recycle()
    }

    @Test fun allFourPicturesRespectReducedMotionAndDetachWithoutSpeech() {
        TestCore.install()
        screen = Views.start(SettingsActivity::class.java)
        val activity = screen!!
        StoryView.animationsEnabled = { false }
        for (name in HowToScenes.ACCOUNTS) {
            val view = TestCore.onMain { HowToView(activity, HowToScenes.get(name)) }
            val host = TestCore.onMain { FrameLayout(activity).apply {
                addView(view, FrameLayout.LayoutParams(440, 220)); activity.setContentView(this)
            } }
            Views.waitUntil { view.width == 440 && view.isAttachedToWindow }
            assertFalse(TestCore.onMain { view.isRunning })
            TestCore.onMain { host.removeView(view) }
            assertFalse(TestCore.onMain { view.isRunning || view.isAttachedToWindow })
        }
        assertTrue(TestCore.speech.spoken.isEmpty())
    }
}

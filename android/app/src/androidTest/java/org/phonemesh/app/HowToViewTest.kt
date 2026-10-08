package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.HowToScenes
import java.io.File

@RunWith(AndroidJUnit4::class)
class HowToViewTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var screen: SettingsActivity? = null
    private var top: RadioActivity? = null

    @After fun tearDown() {
        Views.finish(top)
        Views.finish(screen)
        if (screen != null) TestCore.uninstall()
        StoryView.animationsEnabled = StoryView.systemAnimations
    }

    private fun laidOut(name: String, width: Int = 440) = HowToView(inst.targetContext, HowToScenes.get(name)).apply {
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun picture(view: HowToView): Bitmap = TestCore.onMain {
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun start(): SettingsActivity {
        TestCore.install()
        return Views.start(SettingsActivity::class.java).also { screen = it }
    }

    private fun attach(activity: SettingsActivity, view: HowToView): FrameLayout = TestCore.onMain {
        FrameLayout(activity).apply {
            addView(view, FrameLayout.LayoutParams(440, 220))
            activity.setContentView(this)
        }
    }

    @Test fun isTwiceAsWideAsTall() = assertEquals(220, TestCore.onMain { laidOut("power").measuredHeight })

    @Test fun drawsEverySceneAtEveryInstantOnThePanelColour() {
        TestCore.onMain {
            HowToScenes.all.keys.forEach { name ->
                val view = laidOut(name)
                val bitmap = Bitmap.createBitmap(440, 220, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                for (i in 0..50) {
                    view.freezeAt(i / 50f)
                    view.draw(canvas)
                    // The search rings legitimately cross (12, 100); use a clear corner instead.
                    assertEquals("$name panel at $i", HowToKit.C.PANEL, bitmap.getPixel(12, 12))
                }
                // Optional silent bitmap export; never audio, microphone or radio operations.
                if (InstrumentationRegistry.getArguments().getString("howtoExport") == "true") {
                    listOf("still" to view.scene.still, "early" to .2f).forEach { (label, time) ->
                        view.freezeAt(time)
                        view.draw(canvas)
                        val file = File(inst.targetContext.getExternalFilesDir(null), "howto-setup/$name-$label.png")
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    }
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun downloadDoesNotAddAHomeButtonAbsentFromTheMockup() {
        val view = TestCore.onMain { laidOut("download").apply { freezeAt(scene.still) } }
        assertEquals("download phone has no grey home dot", HowToKit.C.PHONE, picture(view).getPixel(310, 187))
    }

    @Test fun theWaxBandKeepsTheMockupsDarkOutlinedMotif() {
        val view = TestCore.onMain { laidOut("download").apply { freezeAt(scene.still) } }
        // Sample solid stroke ink, not the antialiased overlapping edge at y=214.
        assertEquals("pattern circles inherit the SVG's brown stroke", HowToKit.C.INK, picture(view).getPixel(12, 212))
    }

    @Test fun theBandIsDrawnOverTheFingerLikeTheMockup() {
        val background = TestCore.onMain { laidOut("download").apply { freezeAt(scene.still) } }
        val finger = TestCore.onMain { laidOut("power").apply { freezeAt(scene.still) } }
        val expected = Bitmap.createBitmap(picture(background), 2, 208, 436, 10)
        val actual = Bitmap.createBitmap(picture(finger), 2, 208, 436, 10)
        assertTrue("the finger must not cover the wax band", expected.sameAs(actual))
    }

    @Test fun reducedMotionShowsTheStillAndNeverTicks() {
        val activity = start()
        StoryView.animationsEnabled = { false }
        var loops = 0
        val view = TestCore.onMain { HowToView(activity, HowToScenes.get("power")).apply { onLoop = { loops++ } } }
        attach(activity, view)
        Views.waitUntil { view.width == 440 }
        val expected = TestCore.onMain { laidOut("power").apply { freezeAt(scene.still) } }
        val still = picture(view)
        assertTrue("system still shows the action", still.sameAs(picture(expected)))
        Thread.sleep(5_300)
        assertFalse(TestCore.onMain { view.isRunning })
        assertEquals(0, TestCore.onMain { loops })
        assertTrue("no reduced-motion time advances", still.sameAs(picture(view)))
    }

    @Test fun runsOnlyWhileVisibleAndLoops() {
        val activity = start()
        StoryView.animationsEnabled = { true }
        var loops = 0
        val view = TestCore.onMain { HowToView(activity, HowToScenes.get("power")).apply { onLoop = { loops++ } } }
        attach(activity, view)
        Views.waitUntil { view.isRunning }
        Views.waitUntil(7_000) { loops > 0 }
        TestCore.onMain { view.visibility = View.GONE }
        Views.waitUntil { !view.isRunning }
        val hidden = picture(view)
        val seen = TestCore.onMain { loops }
        Thread.sleep(300)
        assertEquals(seen, TestCore.onMain { loops })
        assertTrue(hidden.sameAs(picture(view)))
        TestCore.onMain { view.visibility = View.VISIBLE }
        Views.waitUntil { view.isRunning }
        top = Views.start(RadioActivity::class.java)
        Views.waitUntil { !view.isRunning }
        val covered = picture(view)
        Thread.sleep(300)
        assertTrue(covered.sameAs(picture(view)))
        Views.finish(top)
        top = null
        Views.waitUntil { view.isRunning }
    }

    @Test fun aRealScrollViewPausesTimeAndPartsThenResumes() {
        val activity = start()
        StoryView.animationsEnabled = { true }
        lateinit var panel: HowToPanel
        lateinit var view: HowToView
        val scroll = TestCore.onMain {
            panel = HowToPanel(activity, Palette(activity), listOf("power", "close", "search").map { HowToScenes.get(it) },
                sentence = { activity.getString(HowTo.sentence(it.name)) }, canSay = { false }, say = {})
            view = Views.descendants(panel).filterIsInstance<HowToView>().single()
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(panel, LinearLayout.LayoutParams(440, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(View(activity), LinearLayout.LayoutParams(440, 2_000))
            }
            ScrollView(activity).apply {
                addView(content)
                val host = FrameLayout(activity)
                host.addView(this, FrameLayout.LayoutParams(440, 400))
                activity.setContentView(host)
            }
        }
        Views.waitUntil { view.isRunning }
        Thread.sleep(1_300)
        TestCore.onMain { scroll.scrollTo(0, 900) }
        Views.waitUntil { !view.getGlobalVisibleRect(Rect()) }
        assertFalse("off-screen must remove frame callbacks, not just skip drawing", TestCore.onMain { view.isRunning })
        val paused = picture(view)
        val part = TestCore.onMain { panel.part }
        Thread.sleep(5_300)
        assertEquals("off-screen loops cannot advance parts", part, TestCore.onMain { panel.part })
        assertTrue("off-screen elapsed time is preserved", paused.sameAs(picture(view)))
        TestCore.onMain { scroll.scrollTo(0, 0) }
        Views.waitUntil { view.isRunning }
        // The old part has ~3.7 s left, not a restarted 5 s loop.
        Views.waitUntil(4_600) { panel.part != part }
    }

    @Test fun aLoopCallbackCanDetachWithoutReposting() {
        val activity = start()
        StoryView.animationsEnabled = { true }
        var loops = 0
        val view = TestCore.onMain { HowToView(activity, HowToScenes.get("power")) }
        val host = attach(activity, view)
        TestCore.onMain { view.onLoop = { loops++; host.removeView(view) } }
        Views.waitUntil(7_000) { loops == 1 }
        assertFalse(TestCore.onMain { view.isAttachedToWindow || view.isRunning })
        val stopped = picture(view)
        Thread.sleep(300)
        assertEquals(1, TestCore.onMain { loops })
        assertTrue(stopped.sameAs(picture(view)))
        TestCore.onMain { host.addView(view, FrameLayout.LayoutParams(440, 220)) }
        Views.waitUntil { view.isRunning }
        Thread.sleep(300)
        assertFalse("reattached view resumes", stopped.sameAs(picture(view)))
    }

    @Test fun freezingStopsAndNullResumesTheVisiblePicture() {
        val activity = start()
        StoryView.animationsEnabled = { true }
        val view = TestCore.onMain { HowToView(activity, HowToScenes.get("hello")) }
        attach(activity, view)
        Views.waitUntil { view.isRunning }
        TestCore.onMain { view.freezeAt(.6f) }
        assertFalse(TestCore.onMain { view.isRunning })
        val frozen = picture(view)
        Thread.sleep(300)
        assertTrue(frozen.sameAs(picture(view)))
        TestCore.onMain { view.freezeAt(null) }
        Views.waitUntil { view.isRunning }
        Thread.sleep(300)
        assertFalse(frozen.sameAs(picture(view)))
    }
}

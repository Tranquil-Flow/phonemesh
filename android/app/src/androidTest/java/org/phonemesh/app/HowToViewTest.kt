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

    @Test fun lockDrawsASeparateTapOnTheStopSquareAfterRelease() {
        val view = TestCore.onMain { laidOut("lock") }
        val export = InstrumentationRegistry.getArguments().getString("howtoExport") == "true"
        listOf("slide" to .52f, "locked" to .6f, "release" to .76f,
            "approach" to .82f, "tap" to .86f, "lift" to .9f, "retreat" to .96f).forEach { (label, time) ->
            TestCore.onMain { view.freezeAt(time) }
            val bitmap = picture(view)
            // The button is centred at (110,86) in the drawing's 220 x 110 units.
            // Its lower square is white after release; the returning fingertip covers it on tap.
            if (label in listOf("release", "retreat"))
                assertEquals("stop square uncovered at $label", HowToKit.C.WHITE, bitmap.getPixel(220, 182))
            if (label == "tap")
                assertEquals("finger taps the visible stop square", HowToKit.C.SKIN, bitmap.getPixel(220, 182))
            if (export) {
                val file = File(inst.targetContext.getExternalFilesDir(null), "howto-setup/lock-$label.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            bitmap.recycle()
        }
    }

    /** The draft bar as the chat shows it, in the drawing's 220 x 110 units at twice the size. */
    @Test fun theListenPictureShowsTheRealDraftBarAndTapsListenThenSend() {
        val view = TestCore.onMain { laidOut("listen").apply { freezeAt(0f) } }
        val apart = picture(view)
        assertEquals("Clear is the light button on the left", HowToKit.C.BUBBLE, apart.getPixel(36, 144))
        assertEquals("Listen is the round yellow button in the middle", HowToKit.C.RADIO, apart.getPixel(220, 136))
        assertEquals("with a dark triangle", HowToKit.C.INK, apart.getPixel(218, 160))
        assertEquals("Send is the dark button on the right", HowToKit.C.PHONE, apart.getPixel(400, 140))
        TestCore.onMain { view.freezeAt(view.scene.still) }
        assertEquals("the finger is on Listen first", HowToKit.C.SKIN, picture(view).getPixel(220, 172))
        TestCore.onMain { view.freezeAt(.66f) }
        val sending = picture(view)
        assertEquals("then on Send", HowToKit.C.SKIN, sending.getPixel(342, 180))
        assertEquals("and no longer on Listen", HowToKit.C.INK, sending.getPixel(218, 160))
    }

    @Test fun aLimitedHeightMakesItSmallerNotCut() {
        val view = HowToView(inst.targetContext, HowToScenes.get("listen"))
        TestCore.onMain {
            view.measure(View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST))
            assertEquals(200, view.measuredWidth)
            assertEquals(100, view.measuredHeight)
            view.measure(View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST))
            assertEquals(440, view.measuredWidth)
            assertEquals(220, view.measuredHeight)
        }
    }

    @Test fun cancelChevronsFollowTheMockupsPositiveAnimationDelays() {
        val view = TestCore.onMain { laidOut("cancel").apply { freezeAt(.2f) } }
        val bitmap = picture(view)
        val first = android.graphics.Color.red(bitmap.getPixel(186, 150))
        val second = android.graphics.Color.red(bitmap.getPixel(222, 150))
        val third = android.graphics.Color.red(bitmap.getPixel(258, 150))
        assertTrue("later chevrons have not brightened yet", first < second && second < third)
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
        // scrollTo() flips geometric visibility immediately, but the scroll change reaches the
        // tree observer (and the next frame callback) on a later traversal, which is what calls
        // updateRunning() to drop the frame callbacks. Wait for that stop with a bound that still
        // fails if a frame callback survives off-screen.
        Views.waitUntil(1_000) { !view.isRunning }
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

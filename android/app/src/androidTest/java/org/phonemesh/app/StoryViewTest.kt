package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.StoryTimeline

@RunWith(AndroidJUnit4::class)
class StoryViewTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var view: StoryView? = null
    private var activity: LanguageActivity? = null

    @After fun tearDown() {
        view?.let { v -> TestCore.onMain { v.stop() } }
        Views.finish(activity)
        if (activity != null) TestCore.uninstall()
        StoryView.animationsEnabled = StoryView.systemAnimations
    }

    private fun measured(): StoryView = TestCore.onMain {
        StoryView(inst.targetContext).apply {
            measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }.also { view = it }

    private fun picture(v: StoryView): Bitmap = TestCore.onMain {
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    @Test fun keepsTheMockupProportions() = assertEquals(340, measured().measuredHeight)

    @Test fun drawsEveryInstantWithTheWaxPrintSky() {
        val v = measured()
        val bitmap = Bitmap.createBitmap(600, 340, Bitmap.Config.ARGB_8888)
        TestCore.onMain {
            val canvas = Canvas(bitmap)
            for (i in 0..200) { v.freezeAt(i / 200f); v.draw(canvas) }
            v.freezeAt(0f)
            v.draw(canvas)
        }
        val sky = bitmap.getPixel(20, 20)
        assertTrue("sky is orange: ${Integer.toHexString(sky)}", Color.red(sky) > 0xE0 && Color.blue(sky) < 0x80)
    }

    @Test fun theBubbleUsesTheMockupsHeavyRoboto() {
        val actual = Bitmap.createBitmap(900, 510, Bitmap.Config.ARGB_8888)
        val expectedInk = Bitmap.createBitmap(900, 510, Bitmap.Config.ARGB_8888)
        TestCore.onMain {
            Canvas(actual).apply {
                scale(3f, 3f)
                StoryScene().draw(this, StoryTimeline.at(.375f))
            }
            Canvas(expectedInk).apply {
                // Use the scene's transform, not a larger font size: hinting differs between them.
                scale(3f, 3f)
                translate(84f, 40f)
                drawText(StoryTimeline.WORD, 0f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 13f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                })
            }
        }
        var solidPixels = 0
        for (y in 90 until 150) for (x in 170 until 335) {
            if (Color.alpha(expectedInk.getPixel(x, y)) == 255) {
                solidPixels++
                assertEquals("heavy Roboto ink at $x,$y", 0xFF2B1A10.toInt(), actual.getPixel(x, y))
            }
        }
        assertTrue("the reference really contains ink", solidPixels > 50)
    }

    @Test fun reducedMotionShowsTheStillFrameAndNeverTicks() {
        StoryView.animationsEnabled = { false }
        val v = measured()
        val moments = mutableListOf<Int>()
        TestCore.onMain { v.onMoment = { moments += it }; v.start() }
        val still = picture(v)
        Thread.sleep(300)
        inst.waitForIdleSync()
        assertFalse(TestCore.onMain { v.isRunning })
        assertEquals(listOf(StoryTimeline.moment(StoryTimeline.STILL)), TestCore.onMain { moments.toList() })
        assertEquals(2, TestCore.onMain { v.moment })
        assertTrue("reduced motion stays on the same picture", still.sameAs(picture(v)))
    }

    @Test fun runsBetweenStartAndStopOnly() {
        StoryView.animationsEnabled = { true }
        val v = measured()
        val moments = mutableListOf<Int>()
        TestCore.onMain { v.onMoment = { moments += it }; v.start() }
        assertTrue(TestCore.onMain { v.isRunning })
        Views.waitUntil(3_000) { moments.contains(0) }
        TestCore.onMain { v.stop() }
        val seen = TestCore.onMain { moments.size }
        val stopped = picture(v)
        Thread.sleep(3_000)
        assertFalse(TestCore.onMain { v.isRunning })
        assertEquals(seen, TestCore.onMain { moments.size })
        assertTrue("stopped frames do not change", stopped.sameAs(picture(v)))
    }

    @Test fun aFrozenPictureStaysFrozenWhenStarted() {
        StoryView.animationsEnabled = { true }
        val v = measured()
        TestCore.onMain { v.freezeAt(.85f); v.start() }
        val frozen = picture(v)
        Thread.sleep(300)
        assertFalse(TestCore.onMain { v.isRunning })
        assertEquals(4, TestCore.onMain { v.moment })
        assertTrue(frozen.sameAs(picture(v)))
    }

    @Test fun startsWithOpeningAndReportsOnlyMomentChanges() {
        val v = measured()
        assertEquals(StoryTimeline.OPENING, TestCore.onMain { v.moment })
        val moments = mutableListOf<Int>()
        TestCore.onMain {
            v.onMoment = { now ->
                assertEquals("state is published before observers", now, v.moment)
                moments += now
            }
            v.freezeAt(.2f)
            v.freezeAt(.25f)
            v.freezeAt(.54f)
            v.freezeAt(.85f)
            v.freezeAt(1f)
        }
        assertEquals(listOf(0, 2, 4, StoryTimeline.OPENING), moments)
    }

    @Test fun stoppedPictureResumesWithoutRestarting() {
        StoryView.animationsEnabled = { true }
        val v = measured()
        TestCore.onMain { v.freezeAt(.449f); v.freezeAt(null); v.start(); v.stop() }
        val stopped = picture(v)
        Thread.sleep(300)
        TestCore.onMain { v.start(); v.start() }
        Views.waitUntil(1_500) { v.moment == 2 }
        assertTrue(TestCore.onMain { v.isRunning })
        assertFalse("resume advances rather than staying frozen", stopped.sameAs(picture(v)))
    }

    @Test fun freezingARunningPictureStopsItAndNullResumes() {
        StoryView.animationsEnabled = { true }
        val v = measured()
        TestCore.onMain { v.start(); v.freezeAt(.449f) }
        assertFalse(TestCore.onMain { v.isRunning })
        assertEquals(1, TestCore.onMain { v.moment })
        val frozen = picture(v)
        Thread.sleep(300)
        assertTrue(frozen.sameAs(picture(v)))
        TestCore.onMain { v.freezeAt(null); v.start() }
        Views.waitUntil(1_500) { v.moment == 2 }
    }

    @Test fun aMomentObserverCanStopTheLoop() {
        StoryView.animationsEnabled = { true }
        val v = measured()
        val moments = mutableListOf<Int>()
        TestCore.onMain {
            v.freezeAt(.449f)
            v.freezeAt(null)
            v.onMoment = { now ->
                assertEquals(now, v.moment)
                moments += now
                v.stop()
            }
            v.start()
        }
        Views.waitUntil(1_500) { moments.isNotEmpty() }
        assertFalse(TestCore.onMain { v.isRunning })
        Thread.sleep(300)
        assertEquals(listOf(2), TestCore.onMain { moments.toList() })
    }

    @Test fun detachingStopsTheLoop() {
        TestCore.install(language = null)
        activity = Views.start(LanguageActivity::class.java)
        StoryView.animationsEnabled = { true }
        val v = measured()
        val host = TestCore.onMain {
            FrameLayout(activity!!).apply {
                addView(v)
                activity!!.setContentView(this)
                v.start()
            }
        }
        assertTrue(TestCore.onMain { v.isAttachedToWindow && v.isRunning })
        TestCore.onMain { host.removeView(v) }
        assertFalse(TestCore.onMain { v.isRunning })
        val stopped = picture(v)
        Thread.sleep(300)
        assertTrue(stopped.sameAs(picture(v)))
    }
}

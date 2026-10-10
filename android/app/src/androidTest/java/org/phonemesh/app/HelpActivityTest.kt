package org.phonemesh.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.HowToScenes
import java.io.File

/** Fake speech and radio only; bitmap exports never change FLAG_SECURE. */
@RunWith(AndroidJUnit4::class)
class HelpActivityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var activity: Activity? = null
    private var top: Activity? = null
    private class Loops { var count = 0 }

    @After fun tearDown() {
        TestCore.onMain { StoryView.animationsEnabled = { false } }
        Views.finish(top)
        Views.finish(activity)
        TestCore.uninstall()
    }

    private fun start() = Views.start(HelpActivity::class.java).also { activity = it }

    /** An animated page need not become idle; still require the actual bounded state transition. */
    private fun awaitMotion(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (!TestCore.onMain(condition)) {
            check(android.os.SystemClock.uptimeMillis() < end) { "Motion condition not met in $timeoutMs ms" }
            Thread.sleep(50)
        }
    }

    private fun rows(screen: Activity): List<String> = TestCore.onMain {
        Views.descendants(screen.window.decorView).mapNotNull { view ->
            (view.tag as? String)?.takeIf { it.startsWith("help:") }?.removePrefix("help:")
        }
    }

    private fun picture(screen: Activity, scene: String): HowToView = TestCore.onMain {
        Views.descendants(rowOnMain(screen, scene)).filterIsInstance<HowToView>().single()
    }

    private fun rowOnMain(screen: Activity, scene: String): View =
        Views.descendants(screen.window.decorView).first { it.tag == "help:$scene" }

    private fun bitmap(view: View): Bitmap = TestCore.onMain {
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    @Test fun everyPictureIsThereOnceInItsGroup() {
        TestCore.install()
        val screen = start()
        val expected = HowToScenes.groups.flatten().filter { it != "pair" || HowTo.showPair }
        assertEquals(expected.toSet(), rows(screen).toSet())
        assertEquals(rows(screen).size, rows(screen).toSet().size)
        val groups = listOf(
            listOf("download", "friend", "power", "close", "search", "pair", "hello"),
            listOf("cancel", "lock", "listen", "delivery", "failed", "arrive"),
            listOf("lost", "bt", "short", "notheard", "mic"),
            listOf("allow", "dlfail", "damaged", "refused", "older", "space", "wait", "toolong", "playfail"),
        )
        assertEquals(groups, HelpActivity.GROUPS.map { it.second })
        val order = TestCore.onMain {
            Views.descendants(screen.window.decorView).mapNotNull { it.tag as? String }
                .filter { it.startsWith("help:") || it.startsWith("help_group:") }
        }
        assertEquals(groups.flatMapIndexed { index, scenes ->
            listOf("help_group:$index") + scenes.filter { it != "pair" || HowTo.showPair }.map { "help:$it" }
        }, order)
        assertNotNull(Views.withText(screen, "Problèmes rares"))
        expected.forEach { scene ->
            assertEquals(screen.getString(HowTo.sentence(scene)), TestCore.onMain {
                Views.descendants(rowOnMain(screen, scene)).filterIsInstance<android.widget.TextView>()
                    .single { it.tag == "howto_sentence" }.text.toString()
            })
        }
    }

    @Test fun pairingWaitsForTheRealRadioTest() {
        assertFalse("turn on only after the V4 is seen asking for a code", HowTo.showPair)
        TestCore.install()
        assertFalse("pair" in rows(start()))
    }

    @Test fun aRowReadsItsSentence() {
        TestCore.install()
        val screen = start()
        TestCore.onMain { speakerOnMain(screen, "power").performClick() }
        assertEquals(listOf("Allumez la radio."), TestCore.speech.spoken)
        assertTrue("no actual audio", TestCore.speech.clips.isEmpty())
    }

    private fun speakerOnMain(screen: Activity, scene: String): View =
        Views.descendants(rowOnMain(screen, scene)).first { it.tag == "howto_say" }

    @Test fun beforeAnyVoiceOnlyRecordedRowsHaveSpeakers() {
        TestCore.install(voices = emptyList())
        val screen = start()
        rows(screen).forEach { scene ->
            assertEquals(scene, if (scene in listOf("download", "friend")) View.VISIBLE else View.GONE,
                TestCore.onMain { speakerOnMain(screen, scene).visibility })
        }
        listOf("download", "friend").forEach { scene ->
            TestCore.onMain { speakerOnMain(screen, scene).performClick() }
        }
        val language = checkNotNull(LanguageRegistry.byTag("fr"))
        assertEquals(listOf(language.howToClips.getValue("download"), language.howToClips.getValue("friend")), TestCore.speech.clips)
        assertTrue(TestCore.speech.spoken.isEmpty())
    }

    @Test fun anArrivingVoiceRefreshesSpeakersWithoutReplacingPictures() {
        TestCore.install(voices = emptyList())
        val screen = start()
        val before = picture(screen, "power")
        assertEquals(View.GONE, TestCore.onMain { speakerOnMain(screen, "power").visibility })
        TestCore.onMain {
            Fixtures.installReady(inst.targetContext)
            screen.onCoreChange(AppCore.Change.PACKS)
        }
        assertSame(before, picture(screen, "power"))
        assertEquals(View.VISIBLE, TestCore.onMain { speakerOnMain(screen, "power").visibility })
        TestCore.onMain { speakerOnMain(screen, "power").performClick() }
        assertEquals(listOf("Allumez la radio."), TestCore.speech.spoken)
    }

    @Test fun backReturns() {
        TestCore.install()
        val screen = start()
        assertEquals("Retour", Views.tagged(screen, "back")!!.contentDescription)
        Views.tap(screen, "back")
        Views.waitUntil { screen.isFinishing }
        assertTrue(screen.isFinishing)
    }

    @Test fun scrollingAwayPausesAndReturningResumesTheSameLoop() {
        TestCore.install()
        val screen = start()
        val view = picture(screen, "download")
        val loops = Loops()
        val scroll = TestCore.onMain {
            view.onLoop = { loops.count++ }
            Views.descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        }
        TestCore.onMain { StoryView.animationsEnabled = { true }; view.requestLayout() }
        awaitMotion { view.isRunning }
        Thread.sleep(1_300)
        TestCore.onMain { scroll.scrollTo(0, scroll.getChildAt(0).height) }
        awaitMotion(1_000) { !view.getGlobalVisibleRect(Rect()) && !view.isRunning }
        val paused = bitmap(view)
        val seen = TestCore.onMain { loops.count }
        try {
            Thread.sleep(5_300)
            assertEquals(seen, TestCore.onMain { loops.count })
            val hidden = bitmap(view)
            try { assertTrue("off-screen time must stay paused", paused.sameAs(hidden)) } finally { hidden.recycle() }
            TestCore.onMain { scroll.scrollTo(0, 0) }
            awaitMotion { view.isRunning }
            assertSame(view, picture(screen, "download"))
            awaitMotion(4_600) { loops.count > seen }
            // Every running picture must actually intersect the viewport, including lower groups.
            assertTrue(TestCore.onMain {
                Views.descendants(screen.window.decorView).filterIsInstance<HowToView>()
                    .all { !it.isRunning || it.getGlobalVisibleRect(Rect()) }
            })
        } finally { paused.recycle() }
    }

    @Test fun aCoveredPageStopsAndReturningKeepsItsPictures() {
        TestCore.install()
        val screen = start()
        val view = picture(screen, "download")
        TestCore.onMain { StoryView.animationsEnabled = { true }; view.requestLayout() }
        awaitMotion { view.isRunning }
        top = Views.start(SettingsActivity::class.java)
        awaitMotion { !view.isRunning }
        val paused = bitmap(view)
        try {
            Thread.sleep(300)
            val hidden = bitmap(view)
            try { assertTrue(paused.sameAs(hidden)) } finally { hidden.recycle() }
            TestCore.onMain { top!!.finish() }
            top = null
            awaitMotion { view.isRunning }
            assertSame(view, picture(screen, "download"))
        } finally { paused.recycle() }
    }

    @Test fun reducedMotionShowsStillsAndNeverLoopsEvenAfterScrolling() {
        TestCore.install()
        val screen = start()
        val views = TestCore.onMain { Views.descendants(screen.window.decorView).filterIsInstance<HowToView>() }
        val loops = Loops()
        TestCore.onMain { views.forEach { it.onLoop = { loops.count++ } } }
        val view = picture(screen, "download")
        val still = bitmap(view)
        val expected = TestCore.onMain {
            HowToView(screen, view.scene).apply {
                measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                layout(0, 0, measuredWidth, measuredHeight)
                freezeAt(scene.still)
            }
        }
        val reference = bitmap(expected)
        try {
            assertTrue("the selected still shows the action", still.sameAs(reference))
            val scroll = TestCore.onMain { Views.descendants(screen.window.decorView).filterIsInstance<ScrollView>().single() }
            TestCore.onMain { scroll.scrollTo(0, scroll.getChildAt(0).height) }
            Thread.sleep(300)
            TestCore.onMain { scroll.scrollTo(0, 0) }
            Thread.sleep(5_300)
            assertTrue(TestCore.onMain { views.none { it.isRunning } })
            assertEquals(0, TestCore.onMain { loops.count })
            val later = bitmap(view)
            try { assertTrue(still.sameAs(later)) } finally { later.recycle() }
        } finally { still.recycle(); reference.recycle() }
    }

    @Test fun bothLanguagesAndThemesAreReadableAndSecure() {
        listOf("fr", "en-US").forEach { language ->
            listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
                TestCore.install(language = language, voices = listOf(language))
                TestCore.onMain { TestCore.core.settings.theme = theme }
                assertTrue("$language fixture voice must be ready", TestCore.onMain {
                    TestCore.core.readiness() is org.phonemesh.core.Readiness.Ready
                })
                val screen = start()
                val scan = A11yCheck.scan(screen)
                assertEquals("$language $theme", emptyList<String>(), scan.problems)
                assertTrue("$language $theme: ${scan.targets} targets for ${rows(screen).size} rows",
                    scan.targets >= rows(screen).size + 1)
                assertNotNull(Views.withText(screen, if (language == "fr") "Aide" else "Help"))
                assertEquals(if (theme == ThemeMode.DARK) 0xFF121212.toInt() else android.graphics.Color.WHITE,
                    TestCore.onMain { Palette(screen).background })
                assertTrue(TestCore.onMain { screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 })
                assertEquals(false, inst.targetContext.packageManager.getActivityInfo(screen.componentName, 0).exported)
                if (InstrumentationRegistry.getArguments().getString("helpExport") == "true") {
                    val image = bitmap(screen.window.decorView)
                    try {
                        val file = File(inst.targetContext.getExternalFilesDir(null), "task18/help-$language-${theme.name.lowercase()}.png")
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { image.recycle() }
                }
                Views.finish(screen)
                activity = null
                TestCore.uninstall()
            }
        }
    }
}

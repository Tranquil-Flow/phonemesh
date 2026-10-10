package org.phonemesh.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The every-screen checks for the radio region cards and lists, in light and dark: the checks of ScreenChecksTest,
 * and that no word is cut, no control is cut at the sides, and the picture is whole with its flag.
 */
@RunWith(AndroidJUnit4::class)
class RegionScreensTest {
    private var activity: Activity? = null

    @After fun tearDown() {
        ScreenStates.finish(activity)
        TestCore.uninstall()
    }

    @Test fun everyRegionStateHasACapture() {
        assertEquals(names, ScreenStates.region.map { it.first })
        assertEquals(names.map { "$it-dark" }, ScreenStates.regionDark.map { it.first })
        assertTrue("no name is used twice", (ScreenStates.all + ScreenStates.region).map { it.first }.toSet().size == ScreenStates.all.size + names.size)
    }

    @Test fun everyRegionScreenPassesInLight() = checkEveryScreen(ScreenStates.region, 0xFFFFFFFF.toInt())
    @Test fun everyRegionScreenPassesInDark() = checkEveryScreen(ScreenStates.regionDark, 0xFF121212.toInt())

    private fun checkEveryScreen(states: List<Pair<String, () -> Activity>>, background: Int) {
        for ((name, state) in states) {
            activity = state()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val shown = activity!!
            val scan = A11yCheck.scan(shown)
            assertEquals(name, emptyList<String>(), scan.problems)
            // Nothing to tap while the radio restarts or says it is ready: only the screen's own bar is left.
            val minTargets = if ("working" in name || "done" in name) 2 else 3
            assertTrue("$name targets ${scan.targets} < $minTargets", scan.targets >= minTargets)
            if (shown is RegionActivity) assertTrue("$name rows ${scan.rows} < 3", scan.rows >= 3)
            assertEquals(name, background, TestCore.onMain { Palette(shown).background })
            assertEquals("theme switch restored after $name", ThemeMode.AUTO, TestCore.nextTheme)
            // The normal build hides every screen from captures; only the screenshot build does not.
            val flags = TestCore.onMain { shown.window.attributes.flags }
            assertEquals("$name FLAG_SECURE", BuildConfig.SECURE_SCREENS, flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val card = Views.tagged(shown, "card_region")
            assertEquals("$name region card", shown !is RegionActivity, card != null)
            TestCore.onMain { wordsAndPicture(name, shown, card) }
            ScreenStates.finish(activity)
            activity = null
        }
    }

    private fun wordsAndPicture(name: String, shown: Activity, card: View?) {
        val content = shown.findViewById<View>(android.R.id.content)
        // The whole list screen; on the other screens the card around the region card (chat rows may shorten their lines).
        val scope = if (card == null) content
        else generateSequence(card) { it.parent as? View }.firstOrNull { it.tag == "card_radio" } ?: card.parent as View
        val texts = Views.descendants(scope).filter { it.isShown }.filterIsInstance<TextView>()
            .filter { it !is EditText && it.text.isNotBlank() }
        assertTrue("$name shows words", texts.isNotEmpty())
        for (words in texts) {
            val what = "$name \"${words.text}\""
            val layout = checkNotNull(words.layout) { "$what has no layout" }
            assertEquals("$what is cut short", words.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) assertEquals("$what line $line ends in dots", 0, layout.getEllipsisCount(line))
            val needed = layout.getLineBottom(layout.lineCount - 1) + words.compoundPaddingTop + words.compoundPaddingBottom
            assertTrue("$what is clipped: height=${words.height}, needed=$needed", words.height >= needed)
            val widest = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
            val room = words.width - words.compoundPaddingLeft - words.compoundPaddingRight
            assertTrue("$what is wider than its room: $widest > $room", widest <= room + 1f)
            val visible = Rect()
            if (words.getGlobalVisibleRect(visible)) assertEquals("$what is cut at the sides", words.width, visible.width())
        }
        val picture = Views.descendants(content).firstOrNull { it.tag == "region_picture" && it.isShown }
        assertEquals("$name region picture", card != null, picture != null)
        if (picture == null) return
        assertTrue("$name picture is empty", picture.width > 0 && picture.height > 0)
        assertEquals("$name picture shape", picture.width / 2, picture.height)
        val visible = Rect()
        assertTrue("$name picture is off screen", picture.getGlobalVisibleRect(visible))
        assertEquals("$name picture is cut at the sides", picture.width, visible.width())
        assertEquals("$name picture is cut at top or bottom", picture.height, visible.height())
        assertFalse("$name: with animations off the picture holds its still frame", (picture as RegionPicture).isRunning)
        val bitmap = Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
        picture.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        assertTrue("$name radio body", count(pixels, 0xFFFFCA28) > 100)
        if ("set-up-de" in name) {
            assertTrue("$name German red", count(pixels, 0xFFDD0000) > 20)
            assertTrue("$name German gold", count(pixels, 0xFFFFCE00) > 20)
        }
        if ("set-up-tg" in name) {
            assertTrue("$name Togo green", count(pixels, 0xFF006A4E) > 20)
            assertTrue("$name Togo red", count(pixels, 0xFFD21034) > 5)
        }
    }

    private fun count(pixels: IntArray, colour: Long) = pixels.count { it == colour.toInt() }

    private companion object {
        val names = listOf(
            "12-region-set-up-de", "12-region-set-up-tg", "12-region-problem", "12-region-change",
            "12-region-choose-country", "12-region-helper", "12-region-not-listed", "12-region-working",
            "12-region-done", "12-region-no-return", "12-region-not-taken", "12-region-not-changed",
            "13-region-blocked-send", "14-region-countries", "15-region-helpers", "15-region-helpers-chosen",
        )
    }
}

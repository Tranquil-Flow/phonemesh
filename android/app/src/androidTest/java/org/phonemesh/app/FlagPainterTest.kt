package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.CountryRegions
import org.phonemesh.core.FlagSpec

/** Flags are drawn from their description; no picture files. A 100 x 60 flag, read back pixel by pixel. */
@RunWith(AndroidJUnit4::class)
class FlagPainterTest {
    private val countries = CountryRegions.bundled()

    private fun paint(flag: FlagSpec?, alpha: Int = 255): Bitmap {
        val bitmap = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        FlagPainter.draw(Canvas(bitmap), flag, RectF(0f, 0f, 100f, 60f), alpha)
        return bitmap
    }

    private fun assertColour(expected: Long, bitmap: Bitmap, x: Int, y: Int) =
        assertEquals("pixel $x,$y", java.lang.Long.toHexString(expected), java.lang.Long.toHexString(bitmap.getPixel(x, y).toLong() and 0xFFFFFFFFL))

    @Test fun horizontalStripesRunTopToBottom() {
        val germany = paint(countries.flagOf("DE"))
        assertColour(0xFF000000, germany, 50, 10)
        assertColour(0xFFDD0000, germany, 50, 30)
        assertColour(0xFFFFCE00, germany, 50, 50)
    }

    @Test fun verticalStripesStartAtThePole() {
        val flag = paint(FlagSpec.parse("v:ff0000,00ff00,0000ff"))
        assertColour(0xFFFF0000, flag, 16, 30)
        assertColour(0xFF00FF00, flag, 50, 30)
        assertColour(0xFF0000FF, flag, 84, 30)
    }

    @Test fun theCantonIsASquareWithItsStarInTheMiddle() {
        val togo = paint(countries.flagOf("TG"))
        assertColour(0xFFD21034, togo, 3, 3)
        assertColour(0xFFD21034, togo, 33, 33)
        assertColour(0xFFFFFFFF, togo, 18, 18)
        // Outside the 36-pixel square the five stripes show.
        assertColour(0xFF006A4E, togo, 60, 5)
        assertColour(0xFFFFCE00, togo, 60, 18)
        assertColour(0xFF006A4E, togo, 20, 54)
    }

    /** Benin: a green band down the pole side, 40 % of the width, over the yellow and red stripes. */
    @Test fun theHoistBandCoversFortyPercentOfTheWidth() {
        val benin = paint(countries.flagOf("BJ"))
        assertColour(0xFF008751, benin, 2, 5)
        assertColour(0xFF008751, benin, 20, 55)
        assertColour(0xFF008751, benin, 38, 30)
        assertColour(0xFFFCD116, benin, 42, 5)
        assertColour(0xFFFCD116, benin, 95, 25)
        assertColour(0xFFE8112D, benin, 42, 55)
        assertColour(0xFFE8112D, benin, 95, 35)
    }

    @Test fun aStarSitsInTheHoistBandWhenThereIsOne() {
        val flag = paint(FlagSpec.parse("h:ff0000,ff0000; hoist:008751; star:ffffff"))
        assertColour(0xFFFFFFFF, flag, 20, 30)
        assertColour(0xFF008751, flag, 3, 3)
        assertColour(0xFFFF0000, flag, 70, 30)
    }

    @Test fun aStarWithoutCantonOrHoistIsCentred() {
        val flag = paint(FlagSpec.parse("h:ff0000,ff0000; star:ffffff"))
        assertColour(0xFFFFFFFF, flag, 50, 30)
        assertColour(0xFFFF0000, flag, 5, 5)
    }

    @Test fun theStarRingIsDrawnAroundTheMiddle() {
        val europe = paint(countries.regions.getValue("EU_868").flag)
        assertColour(0xFF003399, europe, 50, 30)
        assertColour(0xFF003399, europe, 2, 2)
        val stars = (0 until 100).sumOf { x -> (0 until 60).count { y -> europe.getPixel(x, y) == 0xFFFFCC00.toInt() } }
        assertTrue("star pixels: $stars", stars > 10)
    }

    @Test fun noFlagDrawsAGlobeAndLeavesTheCornersAlone() {
        val globe = paint(null)
        assertColour(0xFF4FC3F7, globe, 66, 18)
        assertColour(0x00000000, globe, 1, 1)
        assertColour(0x00000000, globe, 98, 58)
    }

    @Test fun aFlagFadesWithItsOpacity() {
        val half = paint(countries.flagOf("DE"), alpha = 128)
        assertEquals(128, half.getPixel(50, 30) ushr 24)
    }
}

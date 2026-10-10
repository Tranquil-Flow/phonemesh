package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class FlagSpecTest {
    @Test fun horizontalStripesWithCantonAndStar() {
        val flag = FlagSpec.parse("h:006a4e,ffce00,006a4e; canton:d21034; star:ffffff")
        assertEquals(listOf(0xFF006A4E.toInt(), 0xFFFFCE00.toInt(), 0xFF006A4E.toInt()), flag.stripes)
        assertFalse(flag.vertical)
        assertEquals(0xFFD21034.toInt(), flag.canton)
        assertEquals(0xFFFFFFFF.toInt(), flag.star)
        assertNull(flag.starRing)
    }

    @Test fun verticalWithARingOfStars() {
        val flag = FlagSpec.parse("v:003399; stars:12:ffcc00")
        assertTrue(flag.vertical)
        assertEquals(listOf(0xFF003399.toInt()), flag.stripes)
        assertEquals(StarRing(12, 0xFFFFCC00.toInt()), flag.starRing)
    }

    @Test fun badFlagsAreRefused() {
        listOf("", "canton:ffffff", "h:00000", "h:000000; v:ffffff", "h:000000; stars:0:ffffff", "h:000000; moon:ffffff", "h:gg0000")
            .forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { FlagSpec.parse(text) } }
    }

    // Owner choice: simple flag shapes. `hoist:` is a band on the pole side over horizontal stripes.
    @Test fun hoistBandOverHorizontalStripes() {
        val flag = FlagSpec.parse("h:fcd116,e8112d; hoist:008751")
        assertFalse(flag.vertical)
        assertEquals(listOf(0xFFFCD116.toInt(), 0xFFE8112D.toInt()), flag.stripes)
        assertEquals(0xFF008751.toInt(), flag.hoist)
        assertNull(flag.canton)
        assertNull(flag.star)
    }

    @Test fun hoistBandMayCarryAStar() =
        assertEquals(0xFFFFFFFF.toInt(), FlagSpec.parse("h:000000,ffffff; hoist:d21034; star:ffffff").star)

    @Test fun flagsWithoutAHoistBandHaveNone() {
        assertNull(FlagSpec.parse("h:000000,dd0000,ffce00").hoist)
        assertNull(FlagSpec.parse("v:003399; stars:12:ffcc00").hoist)
    }

    @Test fun badHoistBandsAreRefused() {
        listOf("hoist:008751", "v:ffffff; hoist:008751", "h:ffffff; hoist:00875", "h:ffffff; hoist:", "h:ffffff; canton:000000; hoist:008751")
            .forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { FlagSpec.parse(text) } }
    }

    @Test fun aPartWrittenTwiceIsRefused() {
        listOf("h:000000; canton:ffffff; canton:dd0000", "h:000000; star:ffffff; star:dd0000", "h:000000; hoist:ffffff; hoist:dd0000", "v:000000; stars:3:ffffff; stars:4:ffffff")
            .forEach { text -> assertThrows(text, IllegalArgumentException::class.java) { FlagSpec.parse(text) } }
    }
}

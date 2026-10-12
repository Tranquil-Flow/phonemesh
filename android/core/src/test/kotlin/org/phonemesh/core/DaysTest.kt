package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DaysTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(paris).toInstant().toEpochMilli()

    @Test fun todayYesterdayThenADate() {
        val now = at(2026, 10, 12, 9)
        assertEquals(DayName.TODAY, Days.name(at(2026, 10, 12, 0, 1), now, paris))
        assertEquals(DayName.YESTERDAY, Days.name(at(2026, 10, 11, 23, 59), now, paris))
        assertEquals(DayName.DATE, Days.name(at(2026, 10, 10), now, paris))
    }

    @Test fun yesterdayCrossesTheNewYear() {
        assertEquals(DayName.YESTERDAY, Days.name(at(2025, 12, 31), at(2026, 1, 1, 8), paris))
    }

    @Test fun aWrongClockInTheFutureShowsItsDate() {
        assertEquals(DayName.DATE, Days.name(at(2026, 10, 13), at(2026, 10, 12), paris))
    }

    @Test fun aDividerStartsEachNewDayOnly() {
        assertTrue("the first message", Days.startsDay(null, at(2026, 10, 12), paris))
        assertFalse(Days.startsDay(at(2026, 10, 12, 0, 5), at(2026, 10, 12, 23, 55), paris))
        assertTrue(Days.startsDay(at(2026, 10, 11, 23, 55), at(2026, 10, 12, 0, 5), paris))
    }
}

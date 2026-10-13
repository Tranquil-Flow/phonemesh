package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PlanWindowTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, s: Int = 0) =
        LocalDateTime.of(y, m, d, h, min, s).atZone(paris).toInstant().toEpochMilli()

    @Test fun opensOneHourAheadRoundedUpToAQuarter() {
        assertEquals(at(2026, 10, 12, 17, 45), PlanWindow.opening(at(2026, 10, 12, 16, 40), paris))
        assertEquals("already on a quarter", at(2026, 10, 12, 17, 45), PlanWindow.opening(at(2026, 10, 12, 16, 45), paris))
        assertEquals("seconds past a quarter round up", at(2026, 10, 12, 18, 0), PlanWindow.opening(at(2026, 10, 12, 16, 45, 1), paris))
        assertEquals("late evening crosses midnight", at(2026, 10, 13, 0, 0), PlanWindow.opening(at(2026, 10, 12, 22, 50), paris))
    }

    @Test fun minusStopsAtTheFirstQuarterAfterNow() {
        val now = at(2026, 10, 12, 16, 40)
        assertEquals(at(2026, 10, 12, 16, 45), PlanWindow.earliest(now, paris))
        assertEquals(at(2026, 10, 12, 17, 0), PlanWindow.earliest(at(2026, 10, 12, 16, 45), paris))
        assertEquals(at(2026, 10, 12, 16, 45), PlanWindow.step(at(2026, 10, 12, 17, 0), forward = false, now, paris))
        assertNull(PlanWindow.step(at(2026, 10, 12, 16, 45), forward = false, now, paris))
        assertEquals(at(2026, 10, 12, 17, 0), PlanWindow.step(at(2026, 10, 12, 16, 45), forward = true, now, paris))
    }

    @Test fun plusStopsAfterTheLastDayAYearAhead() {
        val now = at(2026, 10, 12, 16, 40)
        assertEquals(LocalDate.of(2027, 10, 12), PlanWindow.lastDay(now, paris))
        assertEquals(at(2027, 10, 12, 23, 45), PlanWindow.step(at(2027, 10, 12, 23, 30), forward = true, now, paris))
        assertNull(PlanWindow.step(at(2027, 10, 12, 23, 45), forward = true, now, paris))
    }

    @Test fun aTimeAtOrBeforeNowIsPast() {
        val now = at(2026, 10, 12, 16, 40)
        assertTrue(PlanWindow.isPast(at(2026, 10, 12, 8, 0), now))
        assertTrue(PlanWindow.isPast(now, now))
        assertFalse(PlanWindow.isPast(now + 1, now))
    }

    @Test fun changingTheDayKeepsTheWallClockTimeAcrossTheClockChange() {
        // 25 October 2026: the clocks go back in Paris.
        val friday = at(2026, 10, 23, 8, 0)
        assertEquals(at(2026, 10, 26, 8, 0), PlanWindow.onDay(friday, LocalDate.of(2026, 10, 26), paris))
        assertEquals(at(2026, 10, 25, 8, 0), PlanWindow.onDay(friday, LocalDate.of(2026, 10, 25), paris))
        assertEquals(at(2026, 10, 23, 19, 30), PlanWindow.atTime(friday, 19, 30, paris))
    }

    @Test fun aTimeTheClockChangeSkipsMovesForward() {
        // 28 March 2027: 02:00 to 03:00 does not exist in Paris.
        val skipped = PlanWindow.onDay(at(2027, 3, 27, 2, 30), LocalDate.of(2027, 3, 28), paris)
        assertEquals(at(2027, 3, 28, 3, 30), skipped)
    }

    @Test fun namesTheDayAsTheButtonsDo() {
        val now = at(2026, 10, 12, 23, 50)
        assertEquals(PlanDay.TODAY, PlanWindow.day(at(2026, 10, 12, 23, 55), now, paris))
        assertEquals(PlanDay.TOMORROW, PlanWindow.day(at(2026, 10, 13, 0, 0), now, paris))
        assertEquals(PlanDay.OTHER, PlanWindow.day(at(2026, 10, 14, 8, 0), now, paris))
        assertEquals("month end", PlanDay.TOMORROW, PlanWindow.day(at(2026, 11, 1, 8, 0), at(2026, 10, 31, 22, 0), paris))
    }

    @Test fun theMoonShowsFromSevenInTheEveningUntilSixInTheMorning() {
        assertFalse(PlanWindow.isNight(at(2026, 10, 12, 18, 59), paris))
        assertTrue(PlanWindow.isNight(at(2026, 10, 12, 19, 0), paris))
        assertTrue(PlanWindow.isNight(at(2026, 10, 13, 5, 59), paris))
        assertFalse(PlanWindow.isNight(at(2026, 10, 13, 6, 0), paris))
    }
}

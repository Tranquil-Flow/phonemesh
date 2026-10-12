package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PlanTimesTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(paris).toInstant().toEpochMilli()

    @Test fun inTheAfternoonAllThreeAreOffered() {
        val now = at(2026, 10, 12, 14, 30)
        assertEquals(listOf(
            PlanChoice.IN_ONE_HOUR to at(2026, 10, 12, 15, 30),
            PlanChoice.THIS_EVENING to at(2026, 10, 12, 19),
            PlanChoice.TOMORROW_MORNING to at(2026, 10, 13, 8),
        ), PlanTimes.choices(now, paris))
    }

    @Test fun fromSixInTheEveningThisEveningIsNotOffered() {
        assertEquals(listOf(PlanChoice.IN_ONE_HOUR, PlanChoice.TOMORROW_MORNING),
            PlanTimes.choices(at(2026, 10, 12, 18), paris).map { it.first })
        assertEquals(listOf(PlanChoice.IN_ONE_HOUR, PlanChoice.THIS_EVENING, PlanChoice.TOMORROW_MORNING),
            PlanTimes.choices(at(2026, 10, 12, 17, 59), paris).map { it.first })
    }

    @Test fun tomorrowMorningCrossesTheMonthAndTheClockChange() {
        // 25 October 2026: the clocks go back in Paris, so that day is 25 hours long.
        assertEquals(at(2026, 11, 1, 8), PlanTimes.choices(at(2026, 10, 31, 22), paris).last().second)
        assertEquals(at(2026, 10, 25, 8), PlanTimes.choices(at(2026, 10, 24, 23), paris).last().second)
    }

    @Test fun justAfterMidnightTomorrowMorningIsTheNextDay() {
        assertEquals(at(2026, 10, 13, 8), PlanTimes.choices(at(2026, 10, 12, 0, 5), paris).last().second)
    }
}

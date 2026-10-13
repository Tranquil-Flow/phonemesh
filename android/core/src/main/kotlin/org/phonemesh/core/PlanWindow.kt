package org.phonemesh.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Which day a planned time falls on, as the window's day buttons show it. */
enum class PlanDay { TODAY, TOMORROW, OTHER }

/**
 * The rules of the "Send later" window (owner decision, 2026-10-12): any day from today to a year ahead, any time,
 * moved by quarter hours with − and +. Times are kept as wall-clock times, so a day change keeps "08:00" across the
 * clock change.
 */
object PlanWindow {
    const val STEP_MINUTES = 15L
    const val YEARS_AHEAD = 1L
    /** The moon shows from this hour until [MORNING_HOUR]. */
    const val NIGHT_HOUR = 19
    const val MORNING_HOUR = 6

    /** Where the window opens: one hour from now, rounded up to the next quarter hour. */
    fun opening(now: Long, zone: ZoneId): Long = nextQuarter(at(now, zone).plusHours(1)).millis()

    /** The earliest time − can reach: the first quarter hour after now. */
    fun earliest(now: Long, zone: ZoneId): Long = nextQuarter(at(now, zone).plusSeconds(1)).millis()

    /** The last day the calendar offers. */
    fun lastDay(now: Long, zone: ZoneId): LocalDate = at(now, zone).toLocalDate().plusYears(YEARS_AHEAD)

    fun isPast(due: Long, now: Long): Boolean = due <= now

    /** − ([forward] false) or + by a quarter hour; null where the window stops (before [earliest], after [lastDay]). */
    fun step(due: Long, forward: Boolean, now: Long, zone: ZoneId): Long? {
        val moved = due + (if (forward) 1 else -1) * STEP_MINUTES * 60_000
        return when {
            !forward && moved < earliest(now, zone) -> null
            forward && at(moved, zone).toLocalDate().isAfter(lastDay(now, zone)) -> null
            else -> moved
        }
    }

    /** The same wall-clock time on [day]. A time the clock change skips moves forward, as Java does. */
    fun onDay(due: Long, day: LocalDate, zone: ZoneId): Long =
        LocalDateTime.of(day, at(due, zone).toLocalTime()).atZone(zone).millis()

    /** [hour]:[minute] on the same day. */
    fun atTime(due: Long, hour: Int, minute: Int, zone: ZoneId): Long =
        at(due, zone).toLocalDate().atTime(hour, minute).atZone(zone).millis()

    fun day(due: Long, now: Long, zone: ZoneId): PlanDay {
        val days = ChronoUnit.DAYS.between(at(now, zone).toLocalDate(), at(due, zone).toLocalDate())
        return when (days) {
            0L -> PlanDay.TODAY
            1L -> PlanDay.TOMORROW
            else -> PlanDay.OTHER
        }
    }

    fun isNight(due: Long, zone: ZoneId): Boolean = at(due, zone).hour.let { it >= NIGHT_HOUR || it < MORNING_HOUR }

    private fun at(millis: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)

    private fun nextQuarter(time: ZonedDateTime): ZonedDateTime {
        val whole = time.truncatedTo(ChronoUnit.MINUTES)
        val extra = (STEP_MINUTES - whole.minute % STEP_MINUTES) % STEP_MINUTES
        val rounded = whole.plusMinutes(extra)
        return if (rounded.isBefore(time)) rounded.plusMinutes(STEP_MINUTES) else rounded
    }

    private fun ZonedDateTime.millis(): Long = toInstant().toEpochMilli()
}

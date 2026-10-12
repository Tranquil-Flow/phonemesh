package org.phonemesh.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DayName { TODAY, YESTERDAY, DATE }

/** The day dividers between messages. A time from a phone with a wrong clock, even in the future, shows its date. */
object Days {
    fun date(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    fun name(time: Long, now: Long, zone: ZoneId): DayName {
        val day = date(time, zone)
        val today = date(now, zone)
        return when (day) {
            today -> DayName.TODAY
            today.minusDays(1) -> DayName.YESTERDAY
            else -> DayName.DATE
        }
    }

    /** A divider goes above the first message and above each message on another day than the one before it. */
    fun startsDay(previous: Long?, time: Long, zone: ZoneId): Boolean = previous == null || date(previous, zone) != date(time, zone)
}

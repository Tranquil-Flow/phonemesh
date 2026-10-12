package org.phonemesh.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class PlanChoice { IN_ONE_HOUR, THIS_EVENING, TOMORROW_MORNING }

/** When a planned message can go: chosen with pictures, never with a clock. */
object PlanTimes {
    val EVENING: LocalTime = LocalTime.of(19, 0)
    val MORNING: LocalTime = LocalTime.of(8, 0)

    /** "This evening" is offered only before 18:00, so it is always at least an hour away and never earlier than "in 1 hour". */
    val EVENING_OFFERED_UNTIL: LocalTime = LocalTime.of(18, 0)

    fun choices(now: Long, zone: ZoneId): List<Pair<PlanChoice, Long>> {
        val here = Instant.ofEpochMilli(now).atZone(zone)
        return listOfNotNull(
            PlanChoice.IN_ONE_HOUR to now + HOUR_MS,
            if (here.toLocalTime().isBefore(EVENING_OFFERED_UNTIL)) {
                PlanChoice.THIS_EVENING to here.toLocalDate().atTime(EVENING).atZone(zone).toInstant().toEpochMilli()
            } else {
                null
            },
            PlanChoice.TOMORROW_MORNING to here.toLocalDate().plusDays(1).atTime(MORNING).atZone(zone).toInstant().toEpochMilli(),
        )
    }

    private const val HOUR_MS = 60 * 60 * 1000L
}

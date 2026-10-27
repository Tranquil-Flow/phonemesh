package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioAllowanceTest {
    @Test fun aPhoneSendsAtMost180SecondsAnHour() {
        val a = RadioAllowance()
        var now = 0L
        var sent = 0.0
        while (now < 3_600_000) {
            if (a.allows(now, 2_116.0)) { a.record(now, 2_116.0); sent += 2_116.0 }
            now += 1_000
        }
        assertTrue("within 180 s: $sent", sent <= 180_000.0)
        assertTrue("and close to it: $sent", sent > 180_000.0 - 2_116.0)
    }

    @Test fun roomComesBackAsTheHourRolls() {
        val a = RadioAllowance()
        a.record(0, 179_000.0)
        assertFalse(a.allows(10_000, 2_000.0))
        assertEquals("room again when the first airing is an hour old", 3_600_001L, a.nextRoom(10_000, 2_000.0))
        assertTrue(a.allows(3_600_001, 2_000.0))
        assertNull("room now", RadioAllowance().nextRoom(0, 2_000.0))
    }

    @Test fun aRestartKeepsWhatWasUsed() {
        val a = RadioAllowance()
        a.record(1_000, 100_000.0); a.record(2_000, 79_000.0)
        val b = RadioAllowance.decode(a.encode())
        assertFalse("a restart does not reset the hour", b.allows(3_000, 2_000.0))
        assertEquals(179_000.0, b.used(3_000), 0.001)
        assertEquals("bad text starts empty", 0.0, RadioAllowance.decode("x;1:2:3").used(0), 0.001)
        assertEquals(0.0, RadioAllowance.decode(null).used(0), 0.001)
    }

    @Test fun keepsOnlyTheLastHourAndABoundedList() {
        val a = RadioAllowance()
        repeat(5_000) { a.record(it * 1_000L, 1.0) }
        assertTrue(a.encode().split(';').size <= RadioAllowance.MAX_ENTRIES)
        val clockBack = RadioAllowance.decode(a.encode())
        assertTrue("a clock set back still counts as used", clockBack.used(0) > 0)
    }

    @Test fun oneAiringLongerThanTheWholeAllowanceStillGoesWhenNothingElseDid() {
        assertTrue(RadioAllowance(budgetMs = 1_000.0).allows(0, 5_000.0))
    }
}

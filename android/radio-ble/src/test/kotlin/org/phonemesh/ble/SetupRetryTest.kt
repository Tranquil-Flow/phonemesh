package org.phonemesh.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRetryTest {
    private val scheduled = mutableListOf<Pair<Runnable, Long>>()
    private var opened = 0
    private val retry = SetupRetry(
        later = { task, delay -> scheduled += task to delay },
        cancel = { task -> scheduled.removeAll { it.first === task } },
        again = { opened++ },
    )

    @Test fun twoMoreTriesThenTheAttemptEnds() {
        retry.begin()
        assertTrue(retry.failed())
        assertEquals(1_000L, scheduled.single().second)
        scheduled.removeAt(0).first.run()
        assertEquals(1, opened)
        assertTrue(retry.failed())
        assertEquals(3_000L, scheduled.single().second)
        scheduled.removeAt(0).first.run()
        assertEquals(2, opened)
        assertFalse("a third failure ends it", retry.failed())
        assertTrue(scheduled.isEmpty())
    }

    @Test fun aNewAttemptHasEveryTryAgain() {
        retry.begin(); retry.failed(); retry.failed()
        assertFalse(retry.failed())
        retry.begin()
        assertTrue("begin cancels what was waiting", scheduled.isEmpty())
        assertTrue(retry.failed())
    }

    @Test fun endingTheAttemptCancelsTheWaitingTry() {
        retry.begin()
        retry.failed()
        assertTrue(retry.waiting)
        retry.end()
        assertFalse(retry.waiting)
        assertTrue(scheduled.isEmpty())
        assertEquals(0, opened)
    }
}

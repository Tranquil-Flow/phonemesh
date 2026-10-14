package org.phonemesh.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeGattQueueTest {
    @Test fun anOperationCanHaveItsOwnShorterTimeout() {
        val timers = mutableListOf<Pair<Runnable, Long>>()
        val failures = mutableListOf<String>()
        val queue = SafeGattQueue({ r, d -> timers += r to d }, { r -> timers.removeAll { it.first === r } }, { failures += it })
        queue.enqueue("requestMtu", timeoutMs = 4_000L) { true }
        assertEquals(4_000L, timers.single().second)
        queue.complete("requestMtu")
        queue.enqueue("discoverServices") { true }
        assertEquals("the queue's own timeout otherwise", 10_000L, timers.single().second)
        timers.single().first.run()
        assertEquals(listOf("GATT operation timed out: discoverServices"), failures)
    }
}

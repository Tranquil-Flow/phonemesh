package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class ProgressInputStreamTest {
    private fun readAll(size: Int, chunk: Int, step: Long): List<Long> {
        val reports = mutableListOf<Long>()
        ProgressInputStream(ByteArrayInputStream(ByteArray(size)), step) { reports += it }.use { input ->
            val buffer = ByteArray(chunk)
            while (input.read(buffer) >= 0) Unit
        }
        return reports
    }

    @Test fun reportsOncePerStepCrossed() {
        val reports = readAll(12_000_000, 65536, 5_000_000)
        assertEquals(2, reports.size)
        assertEquals(listOf(5L, 10L), reports.map { it / 1_000_000 })
    }

    @Test fun oneReadCrossingSeveralStepsReportsOnce() =
        assertEquals(listOf(11_000_000L), readAll(11_000_000, 11_000_000, 5_000_000))

    @Test fun nothingIsReportedBelowTheFirstStep() = assertEquals(emptyList<Long>(), readAll(4_999_999, 4096, 5_000_000))

    @Test fun singleByteReadsCount() {
        val reports = mutableListOf<Long>()
        ProgressInputStream(ByteArrayInputStream(ByteArray(10)), 4) { reports += it }.use { input -> while (input.read() >= 0) Unit }
        assertEquals(listOf(4L, 8L), reports)
    }
}

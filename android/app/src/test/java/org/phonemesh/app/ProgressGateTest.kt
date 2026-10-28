package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B4-5: a copy of half a million messages must not post a million tasks to the screen's thread. */
class ProgressGateTest {
    @Test fun aTenThousandRecordJobPostsAtMostAHundredAndOneTimes() {
        var now = 0L
        val posts = ArrayList<Int>()
        val gate = ProgressGate(clock = { now }) { posts += it }
        val total = 10_000L
        for (i in 1..total) { now += 1; gate.update(i, total) }
        assertTrue("posts: ${posts.size}", posts.size <= 101)
        assertEquals("the end is always shown", 100, posts.last())
        assertEquals(posts.sorted(), posts)
        assertEquals("no percent twice", posts.distinct(), posts)
    }

    @Test fun twoPostsAreAtLeastAGapApartExceptTheEnd() {
        var now = 0L
        val times = ArrayList<Long>()
        val percents = ArrayList<Int>()
        val gate = ProgressGate(minGapMs = 100, clock = { now }) { times += now; percents += it }
        for (i in 1..2_000) { now += 1; gate.update(i.toLong(), 2_000) }
        for (k in 1 until times.size - 1) assertTrue("gap ${times[k] - times[k - 1]}", times[k] - times[k - 1] >= 100)
        assertEquals(100, percents.last())
    }

    @Test fun aRepeatedPercentIsNotPostedAndAPartOfTheJobMapsIntoItsShare() {
        var now = 0L
        val posts = ArrayList<Int>()
        val gate = ProgressGate(minGapMs = 0, clock = { now }) { posts += it }
        repeat(5) { gate.update(40) }
        assertEquals(listOf(40), posts)
        gate.share(40, 60, 50, 100)
        assertEquals(50, posts.last())
        gate.share(85, 100, 0, 0)
        assertEquals(85, posts.last())
    }
}

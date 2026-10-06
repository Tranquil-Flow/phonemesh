package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.RecordGesture.Phase.*

/** Density 2: 2 px per dp. Up and left are negative. */
class RecordGestureTest {
    private val d = 2f

    @Test fun smallMovesKeepHolding() = assertEquals(HOLDING, RecordGesture.onMove(-20f, -20f, d))
    @Test fun slidingUpLocks() = assertEquals(LOCKED, RecordGesture.onMove(-10f, -170f, d))
    @Test fun slidingLeftCancels() = assertEquals(CANCELLED, RecordGesture.onMove(-210f, -30f, d))
    @Test fun aDiagonalFollowsTheLargerMove() {
        assertEquals(CANCELLED, RecordGesture.onMove(-260f, -200f, d))
        assertEquals(LOCKED, RecordGesture.onMove(-200f, -260f, d))
    }
    @Test fun rightOrDownNeverLocksOrCancels() = assertEquals(HOLDING, RecordGesture.onMove(400f, 400f, d))
    @Test fun releasingAfterHoldingFinishes() = assertEquals(FINISHED, RecordGesture.onRelease(0f, 0f, d, 2_000))
    @Test fun aQuickStillTapLocks() = assertEquals(LOCKED, RecordGesture.onRelease(4f, 4f, d, 150))
    @Test fun aQuickMovedReleaseFinishes() = assertEquals(FINISHED, RecordGesture.onRelease(60f, 0f, d, 150))
    @Test fun releasingPastCancelCancels() = assertEquals(CANCELLED, RecordGesture.onRelease(-220f, 0f, d, 900))
    @Test fun cancelProgressIsClamped() {
        assertEquals(0f, RecordGesture.cancelProgress(0f, d), 0.001f)
        assertEquals(0.5f, RecordGesture.cancelProgress(-100f, d), 0.001f)
        assertEquals(1f, RecordGesture.cancelProgress(-400f, d), 0.001f)
        assertEquals(0f, RecordGesture.cancelProgress(80f, d), 0.001f)
    }
    @Test fun theRingFillsOverOneRadioPart() {
        assertEquals(0f, RecordGesture.ringFraction(0), 0.001f)
        assertEquals(0.5f, RecordGesture.ringFraction(9_000), 0.001f)
        assertEquals(1f, RecordGesture.ringFraction(40_000), 0.001f)
    }
}

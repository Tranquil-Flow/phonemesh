package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class DownloadPlanTest {
    private val e = CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "v.zip", 7_000_000, "0".repeat(64))
    @Test fun enoughSpaceStarts() = assertEquals(DownloadDecision.Start, DownloadPlan.decide(e, 200_000_000))
    @Test fun tooLittleSpaceIsReported() {
        val d = DownloadPlan.decide(e, 60_000_000) as DownloadDecision.NotEnoughSpace
        assertEquals(7_000_000L * 3 + 50_000_000L, d.neededBytes)
    }
    @Test fun sizeMustMatchExactly() {
        assertTrue(DownloadPlan.sizeMatches(e, 7_000_000)); assertFalse(DownloadPlan.sizeMatches(e, 7_000_001))
    }
}

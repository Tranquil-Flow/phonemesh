package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class RegionTimelineTest {
    @Test fun setShowsTheFlagThenTheLightThenRings() {
        assertEquals(0f, RegionTimeline.at(RegionScene.SET, 0.1f).newFlag)
        assertEquals(1f, RegionTimeline.at(RegionScene.SET, 0.45f).newFlag)
        assertFalse(RegionTimeline.at(RegionScene.SET, 0.45f).ledOn)
        assertTrue(RegionTimeline.at(RegionScene.SET, 0.6f).ledOn)
        assertTrue(RegionTimeline.at(RegionScene.SET, 0.6f).rings.isNotEmpty())
        assertTrue(RegionTimeline.at(RegionScene.SET, 0.4f).rings.isEmpty())
    }

    @Test fun changeSwapsFlags() {
        val before = RegionTimeline.at(RegionScene.CHANGE, 0.2f)
        val after = RegionTimeline.at(RegionScene.CHANGE, 0.6f)
        assertEquals(1f, before.oldFlag); assertEquals(0f, before.newFlag)
        assertEquals(0f, after.oldFlag); assertEquals(1f, after.newFlag)
    }

    @Test fun workingBlinksOffAndOn() {
        assertTrue(RegionTimeline.at(RegionScene.WORKING, 0.1f).ledOn)
        assertFalse(RegionTimeline.at(RegionScene.WORKING, 0.3f).ledOn)
        assertTrue(RegionTimeline.at(RegionScene.WORKING, 0.9f).ledOn)
    }

    @Test fun helperShowsQuestionAndCross() {
        val frame = RegionTimeline.at(RegionScene.HELPER, RegionTimeline.still(RegionScene.HELPER))
        assertTrue(frame.question > 0f); assertEquals(1f, frame.cross); assertFalse(frame.ledOn)
    }

    @Test fun everySceneLoopsAndItsStillFrameShowsTheAction() = RegionScene.values().forEach { scene ->
        assertEquals(scene.name, RegionTimeline.at(scene, 0f), RegionTimeline.at(scene, 1f))
        val still = RegionTimeline.at(scene, RegionTimeline.still(scene))
        when (scene) {
            RegionScene.SET, RegionScene.CHANGE -> { assertEquals(1f, still.newFlag); assertTrue(still.ledOn) }
            RegionScene.HELPER -> assertEquals(1f, still.cross)
            RegionScene.WORKING -> assertFalse(still.ledOn)
        }
    }

    @Test fun ringsStayInsideTheirLimit() = (0..100).forEach { i ->
        RegionTimeline.at(RegionScene.SET, i / 100f).rings.forEach { assertTrue(it in 0f..1f) }
    }
}

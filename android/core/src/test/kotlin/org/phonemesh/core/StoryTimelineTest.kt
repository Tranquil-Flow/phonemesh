package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class StoryTimelineTest {
    private val steps = (0..1000).map { it / 1000f }

    @Test fun momentsStartAndEndAtTheListedTimes() {
        val expected = listOf(
            0f to -1, .059f to -1, .06f to 0, .299f to 0, .30f to 1, .459f to 1, .46f to 2, .619f to 2,
            .62f to 3, .719f to 3, .72f to 4, .949f to 4, .95f to -1, 1f to -1,
        )
        expected.forEach { (t, moment) -> assertEquals("t=$t", moment, StoryTimeline.moment(t)) }
        assertEquals(5, StoryTimeline.MOMENTS)
    }

    @Test fun theBubbleIsHiddenOnlyOutsideItsWindow() {
        steps.forEach { t ->
            val alpha = StoryTimeline.at(t).bubbleAlpha
            if (t <= .28f || t >= .76f) assertEquals("t=$t", 0f, alpha, 1e-4f)
            if (t >= .31f && t <= .72f) assertEquals("t=$t", 1f, alpha, 1e-4f)
        }
    }

    @Test fun lettersNeverExceedTheWord() {
        steps.forEach { t -> assertTrue("t=$t", StoryTimeline.at(t).letters in 0..StoryTimeline.WORD.length) }
        assertEquals(0, StoryTimeline.at(.2f).letters)
        assertEquals(7, StoryTimeline.at(.5f).letters)
        assertEquals(0, StoryTimeline.at(.8f).letters)
        assertTrue(StoryTimeline.at(.32f).letters in 1..6) // typing
    }

    @Test fun ringsStayWithinTheirLimit() {
        steps.forEach { t ->
            val frame = StoryTimeline.at(t)
            (frame.sendRings + frame.receiveRings).forEach { ring ->
                assertTrue("t=$t r=${ring.radius}", ring.radius in 0f..StoryTimeline.RING_MAX)
                assertTrue("t=$t a=${ring.alpha}", ring.alpha in 0f..1f)
            }
        }
        assertEquals(3, StoryTimeline.at(.5f).sendRings.size)
        assertTrue(StoryTimeline.at(.3f).sendRings.all { it.alpha == 0f })
        assertTrue(StoryTimeline.at(.5f).sendRings.any { it.alpha > 0f })
        assertTrue(StoryTimeline.at(.62f).receiveRings.any { it.alpha > 0f })
    }

    @Test fun theCameraEndsWhereItStarts() {
        val start = StoryTimeline.at(0f)
        val end = StoryTimeline.at(1f)
        assertEquals(listOf(0f, 0f, 300f), listOf(start.camX, start.camY, start.camW))
        assertEquals(listOf(start.camX, start.camY, start.camW), listOf(end.camX, end.camY, end.camW))
        assertEquals(170f, StoryTimeline.at(.2f).camW, 1e-3f) // moved in on her
        assertEquals(130f, StoryTimeline.at(.8f).camX, 1e-3f) // moved to him
    }

    @Test fun lightsBlinkOnlyWhileSendingAndReceiving() {
        steps.forEach { t ->
            val frame = StoryTimeline.at(t)
            if (frame.herLight) assertTrue("t=$t", t > .44f && t < .62f)
            if (frame.hisLight) assertTrue("t=$t", t > .58f && t < .68f)
        }
        assertTrue(steps.any { StoryTimeline.at(it).herLight })
        assertTrue(steps.any { StoryTimeline.at(it).hisLight })
    }

    @Test fun theStillFrameShowsTheBubbleBetweenTheRadios() {
        val still = StoryTimeline.at(StoryTimeline.STILL)
        assertEquals(2, still.moment)
        assertEquals(1f, still.bubbleAlpha, 1e-4f)
        assertTrue(still.bubbleX in 112f..188f)
        assertTrue(still.bubbleY < 80f)
        assertEquals(7, still.letters)
    }

    @Test fun herMouthMovesOnlyWhileSheSpeaks() {
        assertEquals(.8f, StoryTimeline.at(.05f).mouthRy, 1e-4f)
        assertEquals(.8f, StoryTimeline.at(.5f).mouthRy, 1e-4f)
        assertTrue((130..290).map { StoryTimeline.at(it / 1000f).mouthRy }.distinct().size > 10)
    }

    @Test fun timesOutsideTheLoopAreClamped() {
        assertEquals(StoryTimeline.at(0f), StoryTimeline.at(-1f))
        assertEquals(StoryTimeline.at(1f), StoryTimeline.at(2f))
    }
}

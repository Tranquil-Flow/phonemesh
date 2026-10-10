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

    // Pacing: the same action with rests, so every numbered step and the un-numbered caption stay for STEP_MS.

    private val loop = 0L until StoryTimeline.LOOP_MS
    private val originalMs = listOf(2_400L, 1_600L, 1_600L, 1_000L, 2_300L)

    /** What the un-numbered caption's action lasts: the opening and the closing. */
    private val openingMs = 600L
    private val closingMs = 500L
    private val openingRestMs = StoryTimeline.STEP_MS - openingMs - closingMs

    /** The five numbered steps' rests, in order; the loop starts with one more, under the un-numbered caption. */
    private fun rests(): List<LongRange> = allRests().also {
        assertEquals("the loop begins with the opening's rest", 0L, it.first().first)
        assertEquals(StoryTimeline.OPENING, StoryTimeline.paced(it.first().last).moment)
    }.drop(1)

    private fun openingRest(): LongRange = allRests().first()

    /** Every rest as (first ms, last ms), in order. */
    private fun allRests(): List<LongRange> {
        val found = ArrayList<LongRange>()
        var start = -1L
        for (ms in loop) {
            val resting = StoryTimeline.beat(ms).resting
            if (resting && start < 0) start = ms
            if (!resting && start >= 0) { found += start until ms; start = -1 }
        }
        assertEquals("no rest runs into the end of the loop", -1L, start)
        return found
    }

    /** The frame with everything a rest may move put back to the held frame's values. */
    private fun StoryFrame.withMovingPartsOf(held: StoryFrame) = copy(
        herPhoneDy = held.herPhoneDy, hisPhoneDy = held.hisPhoneDy, mouthRx = held.mouthRx, mouthRy = held.mouthRy,
        barScales = held.barScales, bubbleY = held.bubbleY, sendRings = held.sendRings, receiveRings = held.receiveRings,
        herLight = held.herLight, hisLight = held.hisLight, wavesAlpha = held.wavesAlpha,
    )

    @Test fun everyNumberedStepStaysOnScreenForFourSeconds() {
        assertEquals("one number to change", 4_000L, StoryTimeline.STEP_MS)
        val runs = ArrayList<Pair<Int, Long>>()
        for (ms in loop) {
            val moment = StoryTimeline.paced(ms).moment
            if (runs.isNotEmpty() && runs.last().first == moment) runs[runs.size - 1] = moment to runs.last().second + 1
            else runs += moment to 1L
        }
        assertEquals(listOf(-1, 0, 1, 2, 3, 4, -1), runs.map { it.first })
        assertEquals("the opening's rest, then its action at its own length", openingRestMs + openingMs, runs.first().second)
        assertEquals("the closing keeps its length", closingMs, runs.last().second)
        (0 until StoryTimeline.MOMENTS).forEach { step ->
            val shown = runs[step + 1].second
            assertTrue("step ${step + 1} is shown $shown ms", shown >= StoryTimeline.STEP_MS)
            assertEquals("step ${step + 1}", maxOf(StoryTimeline.STEP_MS, originalMs[step]), shown)
        }
    }

    @Test fun theLoopIsTheOriginalStoryPlusItsRests() {
        val resting = openingRestMs + originalMs.sumOf { maxOf(0L, StoryTimeline.STEP_MS - it) }
        assertEquals(StoryTimeline.DURATION_MS + resting, StoryTimeline.LOOP_MS)
        assertEquals(24_000L, StoryTimeline.LOOP_MS)
        assertEquals("six captions of STEP_MS each", (StoryTimeline.MOMENTS + 1) * StoryTimeline.STEP_MS, StoryTimeline.LOOP_MS)
        assertEquals(resting, allRests().sumOf { it.last - it.first + 1 })
        assertEquals(originalMs.map { StoryTimeline.STEP_MS - it }, rests().map { it.last - it.first + 1 })
    }

    @Test fun theActionPlaysAtItsOriginalSpeed() {
        var played = 0L
        for (ms in loop) {
            val beat = StoryTimeline.beat(ms)
            if (beat.resting) continue
            assertEquals("ms=$ms", played / StoryTimeline.DURATION_MS.toFloat(), beat.t, 0f)
            assertEquals("ms=$ms", StoryTimeline.at(beat.t), StoryTimeline.paced(ms))
            played++
        }
        assertEquals("every original instant is played once", StoryTimeline.DURATION_MS, played)
    }

    @Test fun aRestHoldsItsStepAndOnlyBreathes() {
        val rests = rests()
        assertEquals(StoryTimeline.MOMENTS, rests.size)
        rests.forEachIndexed { step, rest ->
            val t = StoryTimeline.beat(rest.first).t
            val held = StoryTimeline.at(t)
            assertEquals(step, held.moment)
            val seen = HashSet<StoryFrame>()
            for (ms in rest) {
                val beat = StoryTimeline.beat(ms)
                assertEquals("ms=$ms", t, beat.t, 0f)
                assertEquals("ms=$ms", ms - rest.first, beat.restMs)
                assertEquals("ms=$ms", rest.last - rest.first + 1, beat.restLength)
                val frame = StoryTimeline.paced(ms)
                seen += frame
                assertEquals("ms=$ms: only the idle parts move", held, frame.withMovingPartsOf(held))
                assertTrue("ms=$ms", held.herPhoneDy - frame.herPhoneDy in 0f..StoryTimeline.PHONE_BOB)
                assertTrue("ms=$ms", held.hisPhoneDy - frame.hisPhoneDy in 0f..StoryTimeline.PHONE_BOB)
                assertTrue("ms=$ms", held.bubbleY - frame.bubbleY in 0f..StoryTimeline.BUBBLE_BOB)
                assertTrue("ms=$ms", frame.mouthRy in .8f..held.mouthRy && frame.mouthRx in held.mouthRx..2.6f)
                frame.barScales.zip(held.barScales).forEach { (now, was) -> assertTrue("ms=$ms", now in was * (1 - StoryTimeline.PULSE)..was) }
                frame.wavesAlpha.zip(held.wavesAlpha).forEach { (now, was) -> assertTrue("ms=$ms", now in was * (1 - StoryTimeline.PULSE)..was) }
                (frame.sendRings + frame.receiveRings).zip(held.sendRings + held.receiveRings).forEach { (now, was) ->
                    assertEquals("ms=$ms", was.alpha, now.alpha, 0f)
                    assertTrue("ms=$ms", now.radius in was.radius * (1 - StoryTimeline.RING_PULSE)..was.radius)
                }
                // A light may only blink where the held picture has it on its radio.
                if (frame.herLight) assertTrue("ms=$ms", t > .44f && t < .62f)
                if (frame.hisLight) assertTrue("ms=$ms", t > .58f && t < .68f)
            }
            assertTrue("rest ${step + 1} is not frozen: ${seen.size} pictures", seen.size > 100)
        }
    }

    @Test fun aRestStartsAndEndsOnTheHeldPicture() {
        rests().forEach { rest ->
            val held = StoryTimeline.at(StoryTimeline.beat(rest.first).t)
            assertEquals("the rest begins on the action's last picture", held, StoryTimeline.paced(rest.first))
            assertEquals("the action goes on from the same picture", held, StoryTimeline.paced(rest.last + 1))
            assertFalse(StoryTimeline.beat(rest.last + 1).resting)
            // One frame (16 ms) before the end the idle motion is all but back.
            val last = StoryTimeline.paced(rest.last - 16)
            assertEquals(held.herPhoneDy, last.herPhoneDy, .02f)
            assertEquals(held.hisPhoneDy, last.hisPhoneDy, .02f)
            assertEquals(held.bubbleY, last.bubbleY, .02f)
            assertEquals(held.mouthRy, last.mouthRy, .02f)
            last.barScales.zip(held.barScales).forEach { (now, was) -> assertEquals(was, now, .02f) }
            last.wavesAlpha.zip(held.wavesAlpha).forEach { (now, was) -> assertEquals(was, now, .02f) }
            (last.sendRings + last.receiveRings).zip(held.sendRings + held.receiveRings).forEach { (now, was) -> assertEquals(was.radius, now.radius, .02f) }
        }
    }

    @Test fun theIdleMotionNeverJumps() {
        for (ms in 1L until StoryTimeline.LOOP_MS) {
            if (!StoryTimeline.beat(ms).resting || !StoryTimeline.beat(ms - 1).resting) continue
            val a = StoryTimeline.paced(ms - 1)
            val b = StoryTimeline.paced(ms)
            assertEquals("ms=$ms", a.herPhoneDy, b.herPhoneDy, .01f)
            assertEquals("ms=$ms", a.bubbleY, b.bubbleY, .01f)
            assertEquals("ms=$ms", a.mouthRy, b.mouthRy, .01f)
        }
    }

    @Test fun eachRestHoldsAFinishedPicture() {
        val held = rests().map { StoryTimeline.at(StoryTimeline.beat(it.first).t) }
        assertEquals(listOf(.28f, .37f, .60f, .70f, .89f), rests().map { StoryTimeline.beat(it.first).t })
        // 1: phone up, she is speaking, no text yet.
        assertEquals(0f, held[0].herPhoneDy, 1e-4f)
        assertEquals(1f, held[0].barsAlpha, 1e-4f)
        assertTrue(held[0].mouthRy > .8f)
        assertEquals(0f, held[0].bubbleAlpha, 1e-4f)
        assertEquals(170f, held[0].camW, 1e-3f)
        // 2: the whole word, the sound bars gone, the bubble not yet on its way to the radio.
        assertEquals(StoryTimeline.WORD.length, held[1].letters)
        assertEquals(1f, held[1].bubbleAlpha, 1e-4f)
        assertEquals(0f, held[1].barsAlpha, 1e-4f)
        assertEquals(listOf(84f, 40f, 1f), listOf(held[1].bubbleX, held[1].bubbleY, held[1].bubbleScale))
        assertEquals("the camera has barely begun to pull back", 170f, held[1].camW, 1.5f)
        // 3: both radios in view, the bubble at his radio, rings closing in on it.
        assertEquals(listOf(30f, 6f, 240f), listOf(held[2].camX, held[2].camY, held[2].camW))
        assertEquals(188f, held[2].bubbleX, 1f)
        assertEquals(80f, held[2].bubbleY, 1.5f)
        assertEquals(1f, held[2].arcAlpha, 1e-4f)
        assertTrue(held[2].receiveRings.any { it.alpha > 0f })
        // 4: the camera on him, the whole word at his raised phone.
        assertEquals(listOf(130f, 22f, 170f), listOf(held[3].camX, held[3].camY, held[3].camW))
        assertEquals(listOf(224f, 38f), listOf(held[3].bubbleX, held[3].bubbleY))
        assertEquals(1f, held[3].bubbleScale, 1e-4f)
        assertEquals(StoryTimeline.WORD.length, held[3].letters)
        assertEquals(0f, held[3].hisPhoneDy, 1e-4f)
        // 5: the bubble gone, the sound waves at full strength, the camera still on him.
        assertEquals(0f, held[4].bubbleAlpha, 1e-4f)
        assertTrue(held[4].wavesAlpha.all { it >= .35f })
        assertEquals(listOf(130f, 22f, 170f), listOf(held[4].camX, held[4].camY, held[4].camW))
        assertEquals(0f, held[4].hisPhoneDy, 1e-4f)
    }

    @Test fun theRadioLightsKeepBlinkingThroughTheRadioRest() {
        val rest = rests()[2]
        val frames = rest.map { StoryTimeline.paced(it) }
        listOf<(StoryFrame) -> Boolean>({ it.herLight }, { it.hisLight }).forEach { light ->
            val changes = frames.zipWithNext().count { (a, b) -> light(a) != light(b) }
            assertTrue("a light blinks on and off through the rest: $changes changes", changes >= 16)
        }
        // No other rest has a radio light on.
        (rests() - setOf(rest)).forEach { other -> assertTrue(other.none { StoryTimeline.paced(it).herLight || StoryTimeline.paced(it).hisLight }) }
    }

    @Test fun theLoopWrapsWithoutAJump() {
        assertEquals(StoryTimeline.paced(0), StoryTimeline.paced(StoryTimeline.LOOP_MS))
        assertEquals(StoryTimeline.paced(250), StoryTimeline.paced(-StoryTimeline.LOOP_MS + 250))
        assertEquals(StoryTimeline.at(0f), StoryTimeline.paced(0))
        // The last picture of the loop is the first one but for a hair of camera: nothing else is on screen.
        val last = StoryTimeline.paced(StoryTimeline.LOOP_MS - 1)
        val first = StoryTimeline.paced(0)
        assertEquals(first.moment, last.moment)
        assertEquals(first.camX, last.camX, .01f)
        assertEquals(first.camY, last.camY, .01f)
        assertEquals(first.camW, last.camW, .01f)
        assertEquals(listOf(first.herPhoneDx, first.herPhoneDy, first.herPhoneAngle), listOf(last.herPhoneDx, last.herPhoneDy, last.herPhoneAngle))
        assertEquals(listOf(first.hisPhoneDx, first.hisPhoneDy, first.hisPhoneAngle), listOf(last.hisPhoneDx, last.hisPhoneDy, last.hisPhoneAngle))
        assertEquals(listOf(first.mouthRx, first.mouthRy), listOf(last.mouthRx, last.mouthRy))
        listOf(first, last).forEach { frame ->
            assertEquals(listOf(0f, 0f, 0f), listOf(frame.barsAlpha, frame.bubbleAlpha, frame.arcAlpha))
            assertTrue((frame.sendRings + frame.receiveRings).all { it.alpha == 0f } && frame.wavesAlpha.all { it == 0f })
            assertFalse(frame.herLight || frame.hisLight)
        }
    }

    @Test fun aStoryTimeMapsToWhereItsActionPlays() {
        for (k in 0 until 1000) {
            val t = k / 1000f
            val ms = StoryTimeline.msOf(t)
            assertEquals("t=$t", t, StoryTimeline.beat(ms).t, 1e-6f)
            assertEquals("t=$t: a resumed picture begins without idle motion", StoryTimeline.at(StoryTimeline.beat(ms).t), StoryTimeline.paced(ms))
        }
        assertEquals("the first picture maps to the start of the opening's rest", 0L, StoryTimeline.msOf(0f))
        assertEquals("the opening's action plays after its rest", openingRestMs + 1L, StoryTimeline.msOf(.0001f))
        assertEquals("before the first step's rest only the opening's is added", openingRestMs + 2_000L, StoryTimeline.msOf(.2f))
        assertEquals("a held time maps to the start of its rest", openingRestMs + 2_800L, StoryTimeline.msOf(.28f))
        assertEquals("the still frame: after three rests", openingRestMs + 5_400L + 1_600L + 2_400L, StoryTimeline.msOf(StoryTimeline.STILL))
        assertEquals(StoryTimeline.LOOP_MS, StoryTimeline.msOf(1f))
    }

    // The un-numbered caption rests too: at the loop's seam, before the opening's action.

    /** What the eye can tell apart: parts drawn with no opacity do not count. */
    private fun StoryFrame.visible() = listOf<Any>(
        moment, camX, camY, camW, herPhoneDx, herPhoneDy, herPhoneAngle, hisPhoneDx, hisPhoneDy, hisPhoneAngle, mouthRx, mouthRy,
        barsAlpha, bubbleAlpha, arcAlpha, sendRings.map { it.alpha }, receiveRings.map { it.alpha }, herLight, hisLight, wavesAlpha,
    )

    @Test fun theUnNumberedCaptionStaysOnScreenAsLongAsAStep() {
        assertEquals(2_900L, openingRestMs)
        val shown = loop.count { StoryTimeline.paced(it).moment == StoryTimeline.OPENING }.toLong()
        assertEquals("the closing, the rest and the opening together", StoryTimeline.STEP_MS, shown)
        val rest = openingRest()
        assertEquals(0L..openingRestMs - 1, rest.first..rest.last)
        // Across the seam the caption is unbroken: the closing, then the rest, then the opening.
        val seam = (StoryTimeline.LOOP_MS - closingMs until StoryTimeline.LOOP_MS + openingRestMs + openingMs)
        assertTrue(seam.all { StoryTimeline.paced(it).moment == StoryTimeline.OPENING })
        assertEquals(StoryTimeline.STEP_MS, seam.last - seam.first + 1)
        assertEquals("step 5 before it", StoryTimeline.MOMENTS - 1, StoryTimeline.paced(seam.first - 1).moment)
        assertEquals("step 1 after it", 0, StoryTimeline.paced(seam.last + 1).moment)
    }

    @Test fun theFirstLaunchBeginsWithTheRestOnTheFirstPicture() {
        assertEquals(StoryBeat(0f, 0, openingRestMs), StoryTimeline.beat(0))
        assertEquals(StoryTimeline.at(0f), StoryTimeline.paced(0))
        for (ms in openingRest()) {
            assertEquals("ms=$ms", StoryBeat(0f, ms, openingRestMs), StoryTimeline.beat(ms))
            assertEquals("ms=$ms", StoryTimeline.OPENING, StoryTimeline.paced(ms).moment)
        }
        // Then the opening's action, from its first instant and at its own speed.
        assertEquals(StoryBeat(0f, 0, 0), StoryTimeline.beat(openingRestMs))
        assertEquals(StoryTimeline.at(0f), StoryTimeline.paced(openingRestMs))
        assertEquals(StoryTimeline.at(.03f), StoryTimeline.paced(openingRestMs + 300))
        assertEquals(0, StoryTimeline.paced(openingRestMs + openingMs).moment)
    }

    @Test fun theSeamShowsTheSamePictureOnBothSides() {
        // The story's last picture and its first differ only in parts that are not drawn (the hidden bubble's place, hidden bars and rings).
        assertEquals(StoryTimeline.at(0f).visible(), StoryTimeline.at(1f).visible())
        val first = StoryTimeline.at(0f)
        assertEquals(listOf(0f, 0f, 0f), listOf(first.barsAlpha, first.bubbleAlpha, first.arcAlpha))
        assertTrue((first.sendRings + first.receiveRings).all { it.alpha == 0f } && first.wavesAlpha.all { it == 0f })
        // The rest begins and ends on that picture, so neither the loop's wrap nor the opening's action jumps.
        val rest = openingRest()
        assertEquals(first, StoryTimeline.paced(rest.first))
        assertEquals(first, StoryTimeline.paced(rest.last + 1))
        assertEquals(first, StoryTimeline.paced(StoryTimeline.LOOP_MS))
        val before = StoryTimeline.paced(rest.last - 16)
        assertEquals(first.herPhoneDy, before.herPhoneDy, .02f)
        assertEquals(first.hisPhoneDy, before.hisPhoneDy, .02f)
        for (ms in rest.first + 1..rest.last + 1) {
            assertEquals("ms=$ms", StoryTimeline.paced(ms - 1).herPhoneDy, StoryTimeline.paced(ms).herPhoneDy, .01f)
            assertEquals("ms=$ms", StoryTimeline.paced(ms - 1).hisPhoneDy, StoryTimeline.paced(ms).hisPhoneDy, .01f)
        }
    }

    @Test fun theOpeningRestOnlyLiftsTheTwoLoweredPhones() {
        val held = StoryTimeline.at(0f)
        val frames = openingRest().map { StoryTimeline.paced(it) }
        frames.forEach { frame ->
            // The bars, the bubble and the rings are not drawn here (no opacity), so their breath is not seen.
            assertEquals("nothing but the two phones is seen to move", held.visible(),
                frame.copy(herPhoneDy = held.herPhoneDy, hisPhoneDy = held.hisPhoneDy).visible())
            assertEquals(held, frame.withMovingPartsOf(held))
            assertTrue(held.herPhoneDy - frame.herPhoneDy in 0f..StoryTimeline.PHONE_BOB)
            assertEquals("both phones move together", held.herPhoneDy - frame.herPhoneDy, held.hisPhoneDy - frame.hisPhoneDy, 1e-4f)
            assertFalse("no radio light comes on", frame.herLight || frame.hisLight)
        }
        assertTrue("the rest is not frozen: ${frames.toSet().size} pictures", frames.toSet().size > 100)
        assertEquals("the phones reach their full lift", StoryTimeline.PHONE_BOB, frames.maxOf { held.herPhoneDy - it.herPhoneDy }, .01f)
        // Mid-rest, between its two breaths, the picture is the held one again.
        assertEquals(held.herPhoneDy, frames[frames.size / 2].herPhoneDy, .01f)
        assertTrue(held.herPhoneDy - frames[frames.size / 4].herPhoneDy > StoryTimeline.PHONE_BOB * .9f)
    }

    @Test fun theStepRestsAreWhereTheyWere() {
        // Each numbered step's rest starts the same time after its caption appears as before the opening rested.
        val captionAt = (0 until StoryTimeline.MOMENTS).map { step -> loop.first { StoryTimeline.paced(it).moment == step } }
        assertEquals(listOf(2_200L, 700L, 1_400L, 800L, 1_700L), rests().mapIndexed { step, rest -> rest.first - captionAt[step] })
        assertEquals((0 until StoryTimeline.MOMENTS).map { openingRestMs + openingMs + it * StoryTimeline.STEP_MS }, captionAt)
    }
}

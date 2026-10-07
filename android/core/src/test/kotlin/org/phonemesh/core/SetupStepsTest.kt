package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.SetupStep.HELLO
import org.phonemesh.core.SetupStep.RADIO
import org.phonemesh.core.SetupStep.VOICE

class SetupStepsTest {
    private fun of(voice: Boolean, radio: Boolean, sent: Boolean, finished: Boolean) = SetupSteps.of(voice, radio, sent, finished)

    @Test fun beforeSetupIsFinishedTheFirstMissingStepIsCurrent() {
        assertEquals(SetupView.Guide(VOICE, emptySet()), of(false, false, false, false))
        assertEquals(SetupView.Guide(VOICE, setOf(RADIO)), of(false, true, false, false))
        assertEquals(SetupView.Guide(VOICE, setOf(HELLO)), of(false, false, true, false))
        assertEquals(SetupView.Guide(VOICE, setOf(RADIO, HELLO)), of(false, true, true, false))
        assertEquals(SetupView.Guide(RADIO, setOf(VOICE)), of(true, false, false, false))
        assertEquals(SetupView.Guide(RADIO, setOf(VOICE, HELLO)), of(true, false, true, false))
        assertEquals(SetupView.Guide(HELLO, setOf(VOICE, RADIO)), of(true, true, false, false))
    }

    @Test fun allThreeDoneCompletesTheGuide() = assertEquals(SetupView.Complete, of(true, true, true, false))

    @Test fun afterSetupOnlyALostVoiceOrRadioShowsAndHelloNeverReturns() {
        assertEquals(SetupView.Hidden, of(true, true, true, true))
        assertEquals(SetupView.Hidden, of(true, true, false, true))
        assertEquals(SetupView.Problems(listOf(VOICE)), of(false, true, true, true))
        assertEquals(SetupView.Problems(listOf(VOICE)), of(false, true, false, true))
        assertEquals(SetupView.Problems(listOf(RADIO)), of(true, false, true, true))
        assertEquals(SetupView.Problems(listOf(RADIO)), of(true, false, false, true))
        assertEquals(SetupView.Problems(listOf(VOICE, RADIO)), of(false, false, true, true))
        assertEquals(SetupView.Problems(listOf(VOICE, RADIO)), of(false, false, false, true))
    }

    @Test fun stepsAreNumberedOneToThree() {
        assertEquals(listOf(VOICE, RADIO, HELLO), SetupSteps.ORDER)
        assertEquals(listOf(1, 2, 3), SetupSteps.ORDER.map { SetupSteps.number(it) })
    }
}
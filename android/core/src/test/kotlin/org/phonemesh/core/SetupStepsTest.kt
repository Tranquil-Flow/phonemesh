package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.SetupStep.HELLO
import org.phonemesh.core.SetupStep.NAME
import org.phonemesh.core.SetupStep.RADIO
import org.phonemesh.core.SetupStep.VOICE

class SetupStepsTest {
    private fun of(voice: Boolean, radio: Boolean, sent: Boolean, finished: Boolean, named: Boolean = true) =
        SetupSteps.of(voice, named, radio, sent, finished)

    @Test fun beforeSetupIsFinishedTheFirstMissingStepIsCurrent() {
        assertEquals(SetupView.Guide(VOICE, setOf(NAME)), of(false, false, false, false))
        assertEquals(SetupView.Guide(VOICE, setOf(NAME, RADIO)), of(false, true, false, false))
        assertEquals(SetupView.Guide(VOICE, setOf(NAME, HELLO)), of(false, false, true, false))
        assertEquals(SetupView.Guide(VOICE, setOf(NAME, RADIO, HELLO)), of(false, true, true, false))
        assertEquals(SetupView.Guide(RADIO, setOf(VOICE, NAME)), of(true, false, false, false))
        assertEquals(SetupView.Guide(RADIO, setOf(VOICE, NAME, HELLO)), of(true, false, true, false))
        assertEquals(SetupView.Guide(HELLO, setOf(VOICE, NAME, RADIO)), of(true, true, false, false))
    }

    @Test fun theNameComesRightAfterTheVoice() {
        assertEquals(SetupView.Guide(VOICE, emptySet()), of(false, false, false, false, named = false))
        assertEquals(SetupView.Guide(NAME, setOf(VOICE)), of(true, false, false, false, named = false))
        assertEquals(SetupView.Guide(NAME, setOf(VOICE, RADIO, HELLO)), of(true, true, true, false, named = false))
    }

    @Test fun allFourDoneCompletesTheGuide() = assertEquals(SetupView.Complete, of(true, true, true, false))

    @Test fun afterSetupOnlyALostVoiceOrRadioOrAMissingNameShowsAndHelloNeverReturns() {
        assertEquals(SetupView.Hidden, of(true, true, true, true))
        assertEquals(SetupView.Hidden, of(true, true, false, true))
        assertEquals(SetupView.Problems(listOf(VOICE)), of(false, true, true, true))
        assertEquals(SetupView.Problems(listOf(VOICE)), of(false, true, false, true))
        assertEquals(SetupView.Problems(listOf(RADIO)), of(true, false, true, true))
        assertEquals(SetupView.Problems(listOf(RADIO)), of(true, false, false, true))
        assertEquals(SetupView.Problems(listOf(VOICE, RADIO)), of(false, false, true, true))
        assertEquals(SetupView.Problems(listOf(VOICE, RADIO)), of(false, false, false, true))
        assertEquals("finished before names existed", SetupView.Problems(listOf(NAME)), of(true, true, true, true, named = false))
        assertEquals(SetupView.Problems(listOf(VOICE, NAME, RADIO)), of(false, false, true, true, named = false))
    }

    @Test fun stepsAreNumberedOneToFour() {
        assertEquals(listOf(VOICE, NAME, RADIO, HELLO), SetupSteps.ORDER)
        assertEquals(listOf(1, 2, 3, 4), SetupSteps.ORDER.map { SetupSteps.number(it) })
    }
}

package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.SetupStep.ADD
import org.phonemesh.core.SetupStep.HELLO
import org.phonemesh.core.SetupStep.NAME
import org.phonemesh.core.SetupStep.RADIO
import org.phonemesh.core.SetupStep.VOICE

class SetupStepsTest {
    private fun of(voice: Boolean, radio: Boolean, sent: Boolean, finished: Boolean, named: Boolean = true, added: Boolean = true) =
        SetupSteps.of(voice, named, radio, sent, finished, added)

    @Test fun beforeSetupIsFinishedTheFirstMissingStepIsCurrent() {
        assertEquals(SetupView.Guide(VOICE, setOf(NAME, ADD)), of(false, false, false, false))
        assertEquals(SetupView.Guide(VOICE, setOf(NAME, RADIO, ADD)), of(false, true, false, false))
        assertEquals(SetupView.Guide(VOICE, setOf(NAME, HELLO, ADD)), of(false, false, true, false))
        assertEquals(SetupView.Guide(VOICE, setOf(NAME, RADIO, HELLO, ADD)), of(false, true, true, false))
        assertEquals(SetupView.Guide(RADIO, setOf(VOICE, NAME, ADD)), of(true, false, false, false))
        assertEquals(SetupView.Guide(RADIO, setOf(VOICE, NAME, HELLO, ADD)), of(true, false, true, false))
        assertEquals(SetupView.Guide(HELLO, setOf(VOICE, NAME, RADIO, ADD)), of(true, true, false, false))
    }

    @Test fun theNameComesRightAfterTheVoice() {
        assertEquals(SetupView.Guide(VOICE, emptySet()), of(false, false, false, false, named = false, added = false))
        assertEquals(SetupView.Guide(NAME, setOf(VOICE)), of(true, false, false, false, named = false, added = false))
        assertEquals(SetupView.Guide(NAME, setOf(VOICE, RADIO, HELLO)), of(true, true, true, false, named = false, added = false))
    }

    @Test fun allFiveDoneCompletesTheGuide() = assertEquals(SetupView.Complete, of(true, true, true, false))

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

    @Test fun addSomeoneComesBeforeHelloEvenIfAMessageWasAlreadySent() {
        assertEquals(SetupView.Guide(ADD, setOf(VOICE, NAME, RADIO)), of(true, true, false, false, added = false))
        assertEquals(SetupView.Guide(ADD, setOf(VOICE, NAME, RADIO, HELLO)), of(true, true, true, false, added = false))
    }

    @Test fun oldFinishedPhonesGetOnlyTheMissingAccountsCardNotTheGuide() {
        assertEquals(SetupView.Problems(listOf(ADD)), of(true, true, true, true, added = false))
        assertEquals(SetupView.Problems(listOf(ADD)), of(true, true, false, true, added = false))
        assertEquals(SetupView.Problems(listOf(VOICE, NAME, RADIO, ADD)),
            of(false, false, false, true, named = false, added = false))
    }

    @Test fun stepsAreNumberedOneToFive() {
        assertEquals(listOf(VOICE, NAME, RADIO, ADD, HELLO), SetupSteps.ORDER)
        assertEquals(listOf(1, 2, 3, 4, 5), SetupSteps.ORDER.map { SetupSteps.number(it) })
    }
}

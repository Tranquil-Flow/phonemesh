package org.phonemesh.core

/** The numbered setup steps, in order. */
/** NAME: the person's own name and colour (accounts spec, "First launch"); it needs the voice to hear the name. */
enum class SetupStep { VOICE, NAME, RADIO, ADD, HELLO }

/** What the chats screen shows above the chats. */
sealed interface SetupView {
    /** The numbered guide: [current] is the large card; every other step is a slim row, ✓ when in [done]. */
    data class Guide(val current: SetupStep, val done: Set<SetupStep>) : SetupView

    /** Every step is done: show the green state for two seconds and remember that setup is finished. */
    data object Complete : SetupView

    /** After setup: slim cards for lost resources or newly introduced steps, without restarting the guide. */
    data class Problems(val lost: List<SetupStep>) : SetupView

    data object Hidden : SetupView
}

/** The setup guide's rule (spec "Chats: guided setup"). Once finished, the guide never returns; Hello never comes back. */
object SetupSteps {
    val ORDER = listOf(SetupStep.VOICE, SetupStep.NAME, SetupStep.RADIO, SetupStep.ADD, SetupStep.HELLO)

    fun number(step: SetupStep): Int = ORDER.indexOf(step) + 1

    /**
     * [named]: the person saved a name. A phone that finished setup before names existed shows the name as a slim card
     * until one is saved. [personAdded] similarly introduces accounts to an existing installation.
     */
    fun of(voiceReady: Boolean, named: Boolean, radioConnected: Boolean, messageSent: Boolean, finished: Boolean, personAdded: Boolean): SetupView {
        val done = buildSet {
            if (voiceReady) add(SetupStep.VOICE)
            if (named) add(SetupStep.NAME)
            if (radioConnected) add(SetupStep.RADIO)
            if (personAdded) add(SetupStep.ADD)
            if (messageSent) add(SetupStep.HELLO)
        }
        if (finished) {
            val lost = ORDER.filter { it != SetupStep.HELLO && it !in done }
            return if (lost.isEmpty()) SetupView.Hidden else SetupView.Problems(lost)
        }
        val current = ORDER.firstOrNull { it !in done } ?: return SetupView.Complete
        return SetupView.Guide(current, done)
    }
}
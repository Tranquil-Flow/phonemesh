package org.phonemesh.core

/** The three numbered setup steps, in order. */
enum class SetupStep { VOICE, RADIO, HELLO }

/** What the chats screen shows above the chats. */
sealed interface SetupView {
    /** The numbered guide: [current] is the large card; every other step is a slim row, ✓ when in [done]. */
    data class Guide(val current: SetupStep, val done: Set<SetupStep>) : SetupView

    /** Every step is done: show the green state for two seconds and remember that setup is finished. */
    data object Complete : SetupView

    /** After setup: one slim card per lost voice or radio, voice first, without step numbers. */
    data class Problems(val lost: List<SetupStep>) : SetupView

    data object Hidden : SetupView
}

/** The setup guide's rule (spec "Chats: guided setup"). Once finished, the guide never returns; Hello never comes back. */
object SetupSteps {
    val ORDER = listOf(SetupStep.VOICE, SetupStep.RADIO, SetupStep.HELLO)

    fun number(step: SetupStep): Int = ORDER.indexOf(step) + 1

    fun of(voiceReady: Boolean, radioConnected: Boolean, messageSent: Boolean, finished: Boolean): SetupView {
        val done = buildSet {
            if (voiceReady) add(SetupStep.VOICE)
            if (radioConnected) add(SetupStep.RADIO)
            if (messageSent) add(SetupStep.HELLO)
        }
        if (finished) {
            val lost = listOf(SetupStep.VOICE, SetupStep.RADIO).filter { it !in done }
            return if (lost.isEmpty()) SetupView.Hidden else SetupView.Problems(lost)
        }
        val current = ORDER.firstOrNull { it !in done } ?: return SetupView.Complete
        return SetupView.Guide(current, done)
    }
}
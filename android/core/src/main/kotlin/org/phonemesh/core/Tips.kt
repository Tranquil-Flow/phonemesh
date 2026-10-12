package org.phonemesh.core

/** The moment a tip belongs to. */
enum class TipPlace { RECORDING, DRAFT, CHAT }

/** Priority order within each place; keys are the talking pictures. */
enum class Tip(val key: String, val place: TipPlace) {
    CANCEL("cancel", TipPlace.RECORDING),
    LOCK("lock", TipPlace.RECORDING),
    LISTEN("listen", TipPlace.DRAFT),
    FAILED("failed", TipPlace.CHAT),
    ARRIVE("arrive", TipPlace.CHAT),
    DELIVERY("delivery", TipPlace.CHAT),
}

/**
 * What has happened in the open chat. [direct] is a private chat: only there can a message reach "Delivered".
 * [held] is a finger kept on the microphone past a tap: a recording started by a tap is locked, so neither sliding
 * left nor sliding up does anything.
 */
data class TipFacts(val sent: Boolean, val failed: Boolean, val received: Boolean, val direct: Boolean, val held: Boolean = true)

object Tips {
    /** A tip left unused is offered again, in this many moments at most. */
    const val MAX_SHOWINGS = 3

    /**
     * The tip for a moment: not [learned] (its action was done, or it was closed), its moment has happened, and of
     * those the one shown in the fewest moments so far ([showings]); the first in priority order when equal.
     */
    fun next(place: TipPlace, facts: TipFacts, learned: Set<String>, showings: Map<String, Int> = emptyMap()): Tip? =
        Tip.entries
            .filter { it.place == place && it.key !in learned && (showings[it.key] ?: 0) < MAX_SHOWINGS && due(it, facts) }
            .minByOrNull { showings[it.key] ?: 0 }

    private fun due(tip: Tip, facts: TipFacts): Boolean = when (tip) {
        Tip.CANCEL, Tip.LOCK -> facts.held
        Tip.LISTEN -> true
        Tip.FAILED -> facts.failed
        Tip.ARRIVE -> facts.received
        // A group message ends at "Sent", so the picture that ends on "Delivered" belongs to private chats.
        Tip.DELIVERY -> facts.sent && facts.direct
    }
}
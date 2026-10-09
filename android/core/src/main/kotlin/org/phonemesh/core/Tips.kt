package org.phonemesh.core

/** The moment a one-time tip belongs to. */
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

data class TipFacts(val sent: Boolean, val failed: Boolean, val received: Boolean)

object Tips {
    /** The first unseen tip whose moment has happened. */
    fun next(place: TipPlace, facts: TipFacts, shown: Set<String>): Tip? =
        Tip.entries.firstOrNull { it.place == place && it.key !in shown && due(it, facts) }

    private fun due(tip: Tip, facts: TipFacts): Boolean = when (tip) {
        Tip.CANCEL, Tip.LOCK, Tip.LISTEN -> true
        Tip.FAILED -> facts.failed
        Tip.ARRIVE -> facts.received
        Tip.DELIVERY -> facts.sent
    }
}
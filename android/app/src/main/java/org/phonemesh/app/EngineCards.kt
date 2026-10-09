package org.phonemesh.app

import org.phonemesh.core.SetupPlan

/** A speech choice with plain names, levels out of three, catalog size and available actions. */
data class EngineCard(
    val id: String,
    val title: Int,
    val description: Int,
    val speed: Int,
    val accuracy: Int,
    val megabytes: Int,
    val state: State,
) {
    sealed interface State {
        data object InUse : State
        data class Installed(val removable: Boolean) : State
        data object Download : State
        data class Progress(val percent: Int) : State
    }
}

/** Tiny is Fast, Base is Accurate; other engines remain in the plain pack list. */
object EngineCards {
    private class Profile(val title: Int, val description: Int, val speed: Int, val accuracy: Int)
    private val profiles = linkedMapOf(
        "engine-whisper-tiny" to Profile(R.string.engine_fast, R.string.engine_fast_detail, 3, 2),
        "engine-whisper-base" to Profile(R.string.engine_accurate, R.string.engine_accurate_detail, 1, 3),
    )

    val ids: Set<String> get() = profiles.keys

    /** [rows] come from PackRows; [inUse] is the engine selected by PackStore's fallback rules. */
    fun build(rows: List<PackRow>, inUse: String?): List<EngineCard> = profiles.mapNotNull { (id, profile) ->
        val row = rows.firstOrNull { it.id == id } ?: return@mapNotNull null
        val entry = row.entry ?: return@mapNotNull null
        val state = when (val action = row.action) {
            is PackRow.Action.Progress -> EngineCard.State.Progress(action.percent)
            PackRow.Action.Download -> EngineCard.State.Download
            else -> if (id == inUse) EngineCard.State.InUse else EngineCard.State.Installed(action == PackRow.Action.Remove)
        }
        EngineCard(id, profile.title, profile.description, profile.speed, profile.accuracy,
            SetupPlan.megabytes(listOf(entry)), state)
    }
}

package org.phonemesh.core

import java.util.Locale

/** What the region check asks the person (spec "What the person sees"). */
sealed interface RegionStep {
    data object Ready : RegionStep
    data class AskSet(val country: String) : RegionStep
    data class AskChange(val country: String, val radioRegion: String) : RegionStep
    data object ChooseCountry : RegionStep
    /** [country] is null when the person chose "My country is not here". */
    data class AskHelper(val country: String?) : RegionStep
}

class RegionCheck(private val countries: CountryRegions) {
    /**
     * [radioRegion] is null before the handshake; [country] is an ISO code in any case, null or blank when unknown,
     * or [NOT_LISTED]; [kept] holds the countries Keep was chosen for on this radio.
     */
    fun decide(radioRegion: String?, country: String?, kept: Set<String>, helperSet: Boolean): RegionStep {
        if (radioRegion == null) return RegionStep.Ready
        val code = country?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val line = countries.country(code)
        if (radioRegion == UNSET) return when {
            code == null -> RegionStep.ChooseCountry
            code == NOT_LISTED -> RegionStep.AskHelper(null)
            line == null -> RegionStep.AskHelper(code)
            else -> RegionStep.AskSet(line.code)
        }
        if (helperSet || line == null || line.region == radioRegion || line.code in kept) return RegionStep.Ready
        return RegionStep.AskChange(line.code, radioRegion)
    }

    companion object {
        const val UNSET = "UNSET"
        /** ISO 3166 user-assigned code, used for "My country is not here". Never a real country. */
        const val NOT_LISTED = "ZZ"
    }
}

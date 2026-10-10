package org.phonemesh.core

import java.util.Locale

/** What a radio's flag picture and sentence show (spec "Which flag a radio shows"). */
sealed interface RadioLook {
    /** A country, named in the sentence; [flag] null draws the globe. */
    data class Country(val code: String, val flag: FlagSpec?) : RadioLook
    /** A region shared by many countries ("Europe"). */
    data class Area(val name: String, val flag: FlagSpec) : RadioLook
    /** Neither: the globe and "another part of the world". */
    data class Elsewhere(val region: String) : RadioLook
}

object RadioLooks {
    /**
     * [storedCountry] is the country this phone set the radio up for. It counts only while it still explains
     * [radioRegion]: its listed region is the radio's, or a helper chose the region ([helperSet]).
     */
    fun of(countries: CountryRegions, radioRegion: String, storedCountry: String?, helperSet: Boolean): RadioLook {
        val stored = storedCountry?.uppercase(Locale.ROOT)
        if (stored != null && (helperSet || countries.country(stored)?.region == radioRegion)) {
            return RadioLook.Country(stored, countries.flagOf(stored))
        }
        val line = countries.regions[radioRegion] ?: return RadioLook.Elsewhere(radioRegion)
        line.flagOf?.let { return RadioLook.Country(it, countries.flagOf(it)) }
        val flag = line.flag
        return if (flag != null && line.name != null) RadioLook.Area(line.name, flag) else RadioLook.Elsewhere(radioRegion)
    }
}

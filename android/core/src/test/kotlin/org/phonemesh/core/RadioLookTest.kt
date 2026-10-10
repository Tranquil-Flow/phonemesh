package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioLookTest {
    private val countries = CountryRegions.load(
        """
        [countries]
        TG | EU_868 | h:006a4e | src
        [regions]
        US | country:US |
        EU_868 | flag:v:003399; stars:12:ffcc00 | Europe
        [flags]
        US | h:b22234,ffffff
        """.trimIndent(),
    )
    private val eu = FlagSpec.parse("v:003399; stars:12:ffcc00")

    @Test fun storedCountryWinsWhileItMatches() =
        assertEquals(RadioLook.Country("TG", FlagSpec.parse("h:006a4e")), RadioLooks.of(countries, "EU_868", "TG", false))

    @Test fun staleStoredCountryIsIgnored() =
        assertEquals(RadioLook.Country("US", FlagSpec.parse("h:b22234,ffffff")), RadioLooks.of(countries, "US", "TG", false))

    @Test fun helperSetCountryIsKeptEvenUnlisted() =
        assertEquals(RadioLook.Country("BJ", null), RadioLooks.of(countries, "EU_868", "BJ", true))

    @Test fun sharedRegionShowsItsArea() = assertEquals(RadioLook.Area("Europe", eu), RadioLooks.of(countries, "EU_868", null, false))

    @Test fun unknownRegionShowsTheGlobe() {
        assertEquals(RadioLook.Elsewhere("UNRECOGNIZED"), RadioLooks.of(countries, "UNRECOGNIZED", null, false))
        assertEquals(RadioLook.Elsewhere("KR"), RadioLooks.of(countries, "KR", null, false))
    }

    @Test fun storedCountryCodeMayBeLowerCase() =
        assertEquals(RadioLook.Country("TG", FlagSpec.parse("h:006a4e")), RadioLooks.of(countries, "EU_868", "tg", false))

    @Test fun unlistedStoredCountryWithoutAHelperIsIgnored() =
        assertEquals(RadioLook.Area("Europe", eu), RadioLooks.of(countries, "EU_868", "BJ", false))
}

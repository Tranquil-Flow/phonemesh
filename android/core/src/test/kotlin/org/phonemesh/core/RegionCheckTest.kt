package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionCheckTest {
    private val check = RegionCheck(CountryRegions.load("[countries]\nTG | EU_868 | h:006a4e | src\nUS | US | h:b22234 | src\n"))
    private fun decide(region: String?, country: String?, kept: Set<String> = emptySet(), helper: Boolean = false) =
        check.decide(region, country, kept, helper)

    @Test fun matchingRegionIsReady() = assertEquals(RegionStep.Ready, decide("EU_868", "TG"))
    @Test fun unsetSupportedAsksToSet() = assertEquals(RegionStep.AskSet("TG"), decide("UNSET", "tg"))
    @Test fun otherRegionAsksToChange() = assertEquals(RegionStep.AskChange("TG", "US"), decide("US", "TG"))
    @Test fun keptCountryIsNotAskedAgain() = assertEquals(RegionStep.Ready, decide("US", "TG", kept = setOf("TG")))
    @Test fun keepIsPerCountry() = assertEquals(RegionStep.AskChange("US", "EU_868"), decide("EU_868", "US", kept = setOf("TG")))
    @Test fun unsetUnknownCountryOpensTheList() = assertEquals(RegionStep.ChooseCountry, decide("UNSET", null))
    @Test fun blankCountryIsUnknown() = assertEquals(RegionStep.ChooseCountry, decide("UNSET", " "))
    @Test fun unsetUnlistedCountryAsksAHelper() = assertEquals(RegionStep.AskHelper("BJ"), decide("UNSET", "BJ"))
    @Test fun notListedChoiceAsksAHelperWithoutACountry() = assertEquals(RegionStep.AskHelper(null), decide("UNSET", RegionCheck.NOT_LISTED))
    @Test fun setRegionWithUnknownCountryIsLeftAlone() = assertEquals(RegionStep.Ready, decide("US", null))
    @Test fun setRegionWithUnlistedCountryIsLeftAlone() = assertEquals(RegionStep.Ready, decide("US", "BJ"))
    @Test fun setRegionAfterNotListedIsLeftAlone() = assertEquals(RegionStep.Ready, decide("US", RegionCheck.NOT_LISTED))
    @Test fun helperSetRadioIsNeverAskedToChange() = assertEquals(RegionStep.Ready, decide("US", "TG", helper = true))
    @Test fun helperSetButResetRadioIsAskedAgain() = assertEquals(RegionStep.AskSet("TG"), decide("UNSET", "TG", helper = true))
    @Test fun regionNotYetKnownIsReady() = assertEquals(RegionStep.Ready, decide(null, "TG"))
    @Test fun unknownFirmwareRegionIsTreatedAsSet() = assertEquals(RegionStep.AskChange("TG", "UNRECOGNIZED"), decide("UNRECOGNIZED", "TG"))
}

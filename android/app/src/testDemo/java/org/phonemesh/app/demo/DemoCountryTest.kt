package org.phonemesh.app.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DemoCountryTest {
    @Test fun theButtonCyclesFromThePhonesOwnCountryThroughTogoFranceGhanaAndBack() {
        assertEquals("tg", DemoCountry.next(null))
        assertEquals("fr", DemoCountry.next("tg"))
        assertEquals("gh", DemoCountry.next("fr"))
        assertNull(DemoCountry.next("gh"))
        assertEquals("tg", DemoCountry.next("zz")) // a stale saved value starts the cycle again
    }

    @Test fun aPretendCountryWinsOverThePhonesOwn() {
        assertEquals("tg", DemoCountry.effective(pretend = "tg", phone = "us"))
        assertEquals("us", DemoCountry.effective(pretend = null, phone = "us"))
        assertNull(DemoCountry.effective(pretend = null, phone = null))
    }
}

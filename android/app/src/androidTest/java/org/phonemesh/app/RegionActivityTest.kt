package org.phonemesh.app

import android.app.Activity
import android.widget.CheckBox
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.RadioRegions
import org.phonemesh.core.RegionCheck
import org.phonemesh.core.RegionStep

/** The country list and the For helpers list, with the fake radio. Only Set writes a region. */
@RunWith(AndroidJUnit4::class)
class RegionActivityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: Activity? = null
    private val radio get() = TestCore.radio

    @After fun tearDown() {
        Views.finish(Views.resumed().takeIf { it !== activity })
        Views.finish(activity)
        TestCore.uninstall()
    }

    /** A remembered radio with no region connects, with the phone in [country]. */
    private fun unset(country: String?, theme: ThemeMode = ThemeMode.AUTO) {
        TestCore.nextTheme = theme
        TestCore.install(connected = false)
        TestCore.access.country = country
        TestCore.onMain {
            TestCore.core.settings.radioAddress = ADDRESS
            radio.region = "UNSET"
            radio.connected(true)
        }
    }

    private fun countries() = Views.launch<RegionActivity>(RegionActivity.countries(context)).also { activity = it }
    private fun helpers() = Views.launch<RegionActivity>(RegionActivity.helpers(context)).also { activity = it }

    private fun resumedNow(): Activity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()

    /** Every item of a list, shown or not. */
    private fun items(screen: Activity, tag: String): List<String?> {
        val list = Views.tagged(screen, tag) as ListView
        return TestCore.onMain { (0 until list.adapter.count).map { list.adapter.getItem(it) as String? } }
    }

    private fun enabled(screen: Activity, tag: String): Boolean = Views.tagged(screen, tag)!!.let { TestCore.onMain { it.isEnabled } }
    private fun selected(screen: Activity, tag: String): Boolean = Views.tagged(screen, tag)!!.let { TestCore.onMain { it.isActivated } }

    @Test fun countryListShowsSupportedCountriesThenNotHere() {
        unset(null)
        val screen = countries()
        assertNotNull(Views.tagged(screen, "country:TG"))
        assertNotNull(Views.tagged(screen, "country:DE"))
        assertNotNull(Views.tagged(screen, "say:TG"))
        assertNotNull(Views.tagged(screen, "country_not_here"))
        Views.tap(screen, "country:TG")
        assertEquals(RegionStep.AskSet("TG"), TestCore.onMain { TestCore.core.region.step() })
        assertTrue(screen.isFinishing)
        assertTrue("choosing a country writes nothing", radio.regionWrites.isEmpty())
    }

    /** Only countries with a checked source are offered: Germany and Togo, by name, then "not here". */
    @Test fun onlyCountriesFromTheFileAreListed() {
        unset(null)
        val screen = countries()
        // French names: Allemagne before Togo.
        assertEquals(listOf("DE", "TG", null), items(screen, "countries"))
        assertEquals(TestCore.core.countries.countries.keys, items(screen, "countries").filterNotNull().toSet())
        listOf("US", "BJ", "ZZ").forEach { assertNull("picture-only $it", Views.tagged(screen, "country:$it")) }
    }

    @Test fun theSpeakerSaysTheCountryNameWithoutChoosingIt() {
        unset(null)
        val screen = countries()
        Views.tap(screen, "say:DE")
        assertEquals(AppCore.LINE_PLAYBACK, TestCore.onMain { TestCore.core.playingId })
        assertFalse(screen.isFinishing)
        assertEquals(RegionStep.ChooseCountry, TestCore.onMain { TestCore.core.region.step() })
    }

    @Test fun notHereLeadsToTheHelperCard() {
        unset(null)
        val screen = countries()
        Views.tap(screen, "country_not_here")
        assertEquals(RegionStep.AskHelper(null), TestCore.onMain { TestCore.core.region.step() })
        assertEquals(RegionCheck.NOT_LISTED, TestCore.onMain { TestCore.core.region.country() })
        assertTrue(radio.regionWrites.isEmpty())
    }

    /** No country known: the chats screen opens the list by itself once, then leaves a card that reopens it. */
    @Test fun anUnknownCountryOpensTheListOnceAndTheCardReopensIt() {
        unset(null)
        val chats = Views.start(ChatsActivity::class.java).also { activity = it }
        Views.waitUntil { resumedNow() is RegionActivity }
        assertNotNull(Views.tagged(Views.resumed()!!, "countries"))
        Views.finish(Views.resumed())
        Views.waitUntil { resumedNow() === chats }
        // A render does not open it again.
        TestCore.onMain { radio.setPeers(emptyList()) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertSame(chats, Views.resumed())
        assertNotNull(Views.tagged(chats, "card_region"))
        assertEquals(emptyList<String>(), A11yCheck.problems(chats))
        Views.tap(chats, "region_countries")
        Views.waitUntil { resumedNow() is RegionActivity }
        Views.tap(Views.resumed()!!, "country:TG")
        Views.waitUntil { resumedNow() === chats }
        assertNotNull(Views.tagged(chats, "region_yes"))
        assertTrue("nothing is written before Yes", radio.regionWrites.isEmpty())
    }

    @Test fun otherCountryOnTheSetUpCardOpensTheList() {
        unset("TG")
        val chats = Views.start(ChatsActivity::class.java).also { activity = it }
        Views.tap(chats, "region_other")
        Views.waitUntil { resumedNow() is RegionActivity }
        assertNotNull(Views.tagged(Views.resumed()!!, "country_not_here"))
    }

    @Test fun forHelpersOnTheHelperCardOpensTheRegionList() {
        unset("BJ")
        val chats = Views.start(ChatsActivity::class.java).also { activity = it }
        Views.tap(chats, "region_helpers")
        Views.waitUntil { resumedNow() is RegionActivity }
        assertNotNull(Views.tagged(Views.resumed()!!, "helper_set"))
        assertTrue(radio.regionWrites.isEmpty())
    }

    @Test fun setStaysOffUntilARegionAndTheTick() {
        unset("BJ")
        val screen = helpers()
        val set = Views.tagged(screen, "helper_set")!!
        assertFalse(TestCore.onMain { set.isEnabled })
        Views.tap(screen, "region:EU_868")
        assertTrue(selected(screen, "region:EU_868"))
        assertFalse(TestCore.onMain { set.isEnabled })
        Views.tap(screen, "helper_tick")
        assertTrue(TestCore.onMain { set.isEnabled })
        assertTrue("choosing and ticking write nothing", radio.regionWrites.isEmpty())
        Views.tap(screen, "helper_set")
        assertEquals(listOf("EU_868"), radio.regionWrites)
        assertEquals(RegionPhase.Working("EU_868", "BJ", true), TestCore.onMain { TestCore.core.region.phase })
        assertTrue(screen.isFinishing)
    }

    @Test fun tickAloneDoesNotEnableSet() {
        unset("BJ")
        val screen = helpers()
        Views.tap(screen, "helper_tick")
        assertFalse(enabled(screen, "helper_set"))
        // A disabled Set that is pressed anyway still writes nothing.
        Views.tap(screen, "helper_set")
        assertTrue(radio.regionWrites.isEmpty())
        assertFalse(screen.isFinishing)
    }

    @Test fun theTickNamesTheCountryOrThisPlace() {
        unset("BJ")
        val named = helpers()
        val benin = java.util.Locale("", "BJ").getDisplayCountry(java.util.Locale.FRENCH)
        assertEquals(named.getString(R.string.helper_tick, benin), Views.textOf(Views.tagged(named, "helper_tick")!!))
        Views.finish(named)
        TestCore.onMain { TestCore.core.region.choose(RegionCheck.NOT_LISTED) }
        val here = helpers()
        assertEquals(here.getString(R.string.helper_tick_here), Views.textOf(Views.tagged(here, "helper_tick")!!))
    }

    @Test fun helperListHasEveryWritableRegion() {
        unset("BJ")
        val screen = helpers()
        assertEquals(RadioRegions.all, items(screen, "regions"))
        assertEquals("EU 868", Views.descendants(Views.tagged(screen, "region:EU_868")!!).filterIsInstance<android.widget.TextView>().single().let { Views.textOf(it) })
    }

    /** Owner choice: licensed amateur bands, UNSET and UNRECOGNIZED are never offered. */
    @Test fun helperListNeverOffersAmateurBandsOrNoRegion() {
        unset("BJ")
        val screen = helpers()
        val offered = items(screen, "regions")
        val refused = listOf("ITU1_2M", "ITU2_2M", "ITU3_2M", "ITU1_70CM", "ITU2_70CM", "ITU3_70CM", "ITU2_125CM", "UNSET", "UNRECOGNIZED")
        refused.forEach { name ->
            assertFalse(name, name in offered)
            assertNull(name, Views.tagged(screen, "region:$name"))
        }
        assertTrue("no amateur band under another name", offered.none { it!!.startsWith("ITU") })
        // Positive control: the list is there, long, and holds ordinary regions, the last of the proto included.
        assertEquals(30, offered.size)
        listOf("US", "EU_433", "EU_868", "EU_N_868").forEach { assertTrue(it, it in offered) }
        assertNotNull(Views.tagged(screen, "region:EU_868"))
        // The end of the list, scrolled into view, shows no refused name either.
        (Views.tagged(screen, "regions") as ListView).let { TestCore.onMain { it.setSelection(offered.size - 1) } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNotNull(Views.tagged(screen, "region:EU_N_868"))
        val shown = Views.all(screen).mapNotNull { (it.tag as? String)?.takeIf { tag -> tag.startsWith("region:") }?.removePrefix("region:") }
        assertTrue(shown.toString(), shown.isNotEmpty() && shown.all { it in RadioRegions.all })
    }

    @Test fun theChoiceAndTheTickSurviveARecreate() {
        unset("BJ")
        val first = helpers()
        Views.tap(first, "region:EU_868")
        Views.tap(first, "helper_tick")
        TestCore.onMain { first.recreate() }
        Views.waitUntil { resumedNow().let { it is RegionActivity && it !== first } }
        val second = Views.resumed()!!.also { activity = it }
        assertTrue(selected(second, "region:EU_868"))
        assertTrue((Views.tagged(second, "helper_tick") as CheckBox).let { TestCore.onMain { it.isChecked } })
        assertTrue(enabled(second, "helper_set"))
        assertTrue(radio.regionWrites.isEmpty())
    }

    @Test fun withoutARadioTheScreenCloses() {
        unset("BJ")
        val screen = helpers()
        TestCore.onMain { radio.connected(false) }
        Views.waitUntil { screen.isFinishing }
        assertTrue(radio.regionWrites.isEmpty())
    }

    @Test fun bothListsPassTheScreenChecksInLightAndDark() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            unset("BJ", theme)
            val list = A11yCheck.scan(countries())
            assertEquals(theme.name, emptyList<String>(), list.problems)
            assertTrue("rows ${list.rows}", list.rows >= 3)
            Views.finish(activity)
            val regions = helpers()
            Views.tap(regions, "region:EU_868")
            val scan = A11yCheck.scan(regions)
            assertEquals(theme.name, emptyList<String>(), scan.problems)
            assertTrue("rows ${scan.rows}", scan.rows >= 4)
            Views.finish(activity)
            TestCore.uninstall()
        }
    }

    private companion object { const val ADDRESS = "AA:BB:CC:DD:EE:01" }
}

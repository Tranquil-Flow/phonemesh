package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake radio only: no scan, connection or transmission reaches Bluetooth. */
@RunWith(AndroidJUnit4::class)
class RadioActivityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var activity: RadioActivity? = null

    @After fun tearDown() {
        Views.finish(activity)
        TestCore.uninstall()
    }

    @Test fun theBackArrowLeavesTheScreen() {
        TestCore.install()
        activity = Views.start(RadioActivity::class.java)
        assertEquals("Retour", Views.tagged(activity!!, "back")!!.contentDescription)
        Views.tap(activity!!, "back")
        // System back asks Android to finish asynchronously.
        Views.waitUntil { activity!!.isFinishing }
        assertTrue(activity!!.isFinishing)
    }

    @Test fun searchStartsAndNearbyRadiosShowWithSignalBars() {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        assertEquals(1, TestCore.radio.scans)
        assertNotNull(Views.tagged(activity!!, "searching"))
        TestCore.onMain {
            TestCore.radio.found("AA:BB:CC:DD:EE:01", "Kofi", -65)
            TestCore.radio.found("AA:BB:CC:DD:EE:02", "Ama", -90)
        }
        inst.waitForIdleSync()
        assertEquals("Signal 3 sur 4", Views.tagged(activity!!, "bars:AA:BB:CC:DD:EE:01")!!.contentDescription)
        assertEquals("Signal 1 sur 4", Views.tagged(activity!!, "bars:AA:BB:CC:DD:EE:02")!!.contentDescription)
        assertEquals("Pas trouvée ? Allumez la radio. Approchez le téléphone.", Views.textOf(Views.tagged(activity!!, "radio_help")!!))
    }

    @Test fun tappingARadioConnectsRemembersItAndReturns() {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        TestCore.onMain { TestCore.radio.found("AA:BB:CC:DD:EE:01", "Kofi", -60) }
        inst.waitForIdleSync()
        Views.clickRow(activity!!, "radio:AA:BB:CC:DD:EE:01")
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), TestCore.radio.connects)
        assertEquals("AA:BB:CC:DD:EE:01", TestCore.core.settings.radioAddress)
        assertEquals("Kofi", TestCore.core.settings.radioName)
        assertFalse(TestCore.core.scanning)
        assertNotNull(Views.tagged(activity!!, "connecting"))
        TestCore.onMain { TestCore.radio.connected(true) }
        assertTrue(activity!!.isFinishing)
    }

    @Test fun leavingTheScreenStopsTheSearch() {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        assertTrue(TestCore.core.scanning)
        Views.finish(activity)
        // onStop may arrive after finish() returns, so wait for it instead of asserting at once.
        Views.waitUntil(5_000) { !TestCore.core.scanning }
        assertFalse(TestCore.core.scanning)
        assertTrue(TestCore.radio.stopScans >= 1)
    }

    @Test fun aConnectedRadioCanBeDisconnectedAndIsForgotten() {
        TestCore.install(connected = true)
        TestCore.onMain { TestCore.core.settings.radioAddress = "AA:BB:CC:DD:EE:01"; TestCore.core.settings.radioName = "Kofi" }
        activity = Views.start(RadioActivity::class.java)
        assertEquals("Connectée : Kofi", Views.textOf(Views.tagged(activity!!, "radio_state")!!))
        Views.tap(activity!!, "radio_disconnect")
        assertEquals(1, TestCore.radio.disconnects)
        assertNull(TestCore.core.settings.radioAddress)
    }

    @Test fun tappingAnotherRadioWhileConnectedWaitsForTheNewConnection() {
        TestCore.install(connected = true)
        activity = Views.start(RadioActivity::class.java)
        TestCore.onMain { TestCore.radio.found("AA:BB:CC:DD:EE:02", "Ama", -60) }
        inst.waitForIdleSync()
        Views.clickRow(activity!!, "radio:AA:BB:CC:DD:EE:02")
        assertFalse(activity!!.isFinishing)
        assertNotNull(Views.tagged(activity!!, "connecting"))
        TestCore.onMain { TestCore.radio.connected(false) } // the first radio drops
        assertFalse(activity!!.isFinishing)
        TestCore.onMain { TestCore.radio.connected(true) }
        assertTrue(activity!!.isFinishing)
    }

    @Test fun anUnnamedRadioShowsRadioAndItsLastFourDigits() {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        TestCore.onMain { TestCore.radio.found("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:01", -70) }
        inst.waitForIdleSync()
        assertEquals("Radio EE01", Views.tagged(activity!!, "radio:AA:BB:CC:DD:EE:01")!!.contentDescription)
        Views.clickRow(activity!!, "radio:AA:BB:CC:DD:EE:01")
        assertEquals("Radio EE01", TestCore.core.settings.radioName)
    }

    @Test fun aScanThatFailsShowsSearchAgainWithoutASpinner() {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        assertEquals(1, TestCore.radio.scans)
        TestCore.onMain { TestCore.radio.failScan() }
        inst.waitForIdleSync()
        assertNull(Views.tagged(activity!!, "searching"))
        val again = checkNotNull(Views.tagged(activity!!, "radio_search_again"))
        assertEquals("Chercher", again.contentDescription)
        assertTrue(TestCore.onMain { again.height } >= Ui.dp(inst.targetContext, 48))
        Views.tap(activity!!, "radio_search_again")
        assertEquals(2, TestCore.radio.scans)
        assertNotNull(Views.tagged(activity!!, "searching"))
        assertNull(Views.tagged(activity!!, "radio_search_again"))
    }
}

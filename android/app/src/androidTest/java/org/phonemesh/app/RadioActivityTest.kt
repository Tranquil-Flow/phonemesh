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

    @Test fun pairingPictureStaysOffEvenWhileAFakeRadioConnects() {
        assertFalse("real V4 pairing evidence is still required", HowTo.showPair)
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        TestCore.onMain { TestCore.radio.found("AA:BB:CC:DD:EE:01", "Kofi", -60) }
        inst.waitForIdleSync()
        Views.clickRow(activity!!, "radio:AA:BB:CC:DD:EE:01")
        assertTrue(TestCore.onMain { TestCore.core.isAttempting("AA:BB:CC:DD:EE:01") })
        assertTrue(TestCore.onMain { Views.descendants(activity!!.window.decorView).none { it.tag == "howto:pair" } })
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

    @Test fun oneTapIsEnoughWhileTheRadioIsBeingPaired() {
        TestCore.install(connected = false)
        TestCore.onMain { TestCore.core.connectWaitMs = 100 }
        activity = Views.start(RadioActivity::class.java)
        TestCore.onMain { TestCore.radio.found("AA:BB:CC:DD:EE:01", "Kofi", -60) }
        inst.waitForIdleSync()
        Views.clickRow(activity!!, "radio:AA:BB:CC:DD:EE:01")
        TestCore.onMain { TestCore.radio.pairing(true) }
        Thread.sleep(500)
        inst.waitForIdleSync()
        assertNotNull("still shown as connecting while the code is typed", Views.tagged(activity!!, "connecting"))
        assertFalse(activity!!.isFinishing)
        TestCore.onMain { TestCore.radio.pairing(false) }
        inst.waitForIdleSync()
        assertNotNull(Views.tagged(activity!!, "connecting"))
        TestCore.onMain { TestCore.radio.connected(true) }
        assertTrue("connected from the one tap", activity!!.isFinishing)
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), TestCore.radio.connects)
    }

    @Test fun refusedPairingEndsConnectingAndTheRadioCanBeTappedAgain() {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        TestCore.onMain { TestCore.radio.found("AA:BB:CC:DD:EE:01", "Kofi", -60) }
        inst.waitForIdleSync()
        Views.clickRow(activity!!, "radio:AA:BB:CC:DD:EE:01")
        TestCore.onMain { TestCore.radio.pairing(true) }
        inst.waitForIdleSync()
        assertNotNull(Views.tagged(activity!!, "connecting"))
        TestCore.onMain { TestCore.radio.pairing(false); TestCore.radio.failConnect() }
        inst.waitForIdleSync()
        assertNull(Views.tagged(activity!!, "connecting"))
        assertFalse(activity!!.isFinishing)
        Views.clickRow(activity!!, "radio:AA:BB:CC:DD:EE:01")
        assertEquals(2, TestCore.radio.connects.size)
        assertNotNull(Views.tagged(activity!!, "connecting"))
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

    /** Finds a radio by its Bluetooth name, taps it, and lets it connect announcing [ownName]; the screen then closes. */
    private fun connectOnce(ownName: String?) {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        TestCore.onMain { TestCore.radio.found("AA:BB:CC:DD:8D:88", "Nova_8d88", -60) }
        inst.waitForIdleSync()
        assertEquals("never connected: its Bluetooth name", "Nova_8d88", Views.tagged(activity!!, "radio:AA:BB:CC:DD:8D:88")!!.contentDescription)
        Views.clickRow(activity!!, "radio:AA:BB:CC:DD:8D:88")
        TestCore.onMain {
            TestCore.radio.ownName = ownName
            TestCore.radio.connected(true)
        }
        assertTrue(activity!!.isFinishing)
        // Its onStop ends the search; it must not arrive after the next screen has begun its own.
        Views.waitUntil { activity!!.isDestroyed }
        activity = Views.start(RadioActivity::class.java)
        TestCore.onMain {
            TestCore.radio.found("AA:BB:CC:DD:8D:88", "Nova_8d88", -60)
            TestCore.radio.found("AA:BB:CC:DD:EE:02", "Nova_ee02", -70)
        }
        inst.waitForIdleSync()
    }

    private fun rowName(address: String): String {
        val row = Views.tagged(activity!!, "radio:$address")!!
        val shown = TestCore.onMain { Views.descendants(row).filterIsInstance<android.widget.TextView>().first().text.toString() }
        assertEquals("read aloud as it is shown", shown, row.contentDescription.toString())
        return shown
    }

    @Test fun aConnectedRadioShowsTheNameOtherPeopleSee() {
        connectOnce("Meshtastic 8d88")
        assertEquals("Connectée : Meshtastic 8d88", Views.textOf(Views.tagged(activity!!, "radio_state")!!))
        assertEquals("Meshtastic 8d88", rowName("AA:BB:CC:DD:8D:88"))
        assertEquals("a radio never connected keeps its Bluetooth name", "Nova_ee02", rowName("AA:BB:CC:DD:EE:02"))
    }

    @Test fun theNameIsKeptAfterTheRadioIsLetGoAndFollowsARename() {
        connectOnce("Meshtastic 8d88")
        TestCore.onMain {
            TestCore.radio.ownName = "Kofi"
            TestCore.radio.setPeers(emptyList()) // the radio reports its own record again
        }
        inst.waitForIdleSync()
        assertEquals("Kofi", rowName("AA:BB:CC:DD:8D:88"))
        Views.tap(activity!!, "radio_disconnect")
        TestCore.onMain { TestCore.radio.connected(false) }
        inst.waitForIdleSync()
        assertEquals("Kofi", rowName("AA:BB:CC:DD:8D:88"))
    }

    @Test fun aRadioThatAnnouncesNoNameKeepsItsBluetoothName() {
        connectOnce("  ")
        assertEquals("Connectée : Nova_8d88", Views.textOf(Views.tagged(activity!!, "radio_state")!!))
        assertEquals("Nova_8d88", rowName("AA:BB:CC:DD:8D:88"))
    }

    @Suppress("DEPRECATION")
    @Test fun bluetoothOffShowsTheSwitchPicture() {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        TestCore.access.bluetooth = false
        // Exercise the refused enable-result callback without opening or changing system settings.
        val result = RadioActivity::class.java.getDeclaredMethod("onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.content.Intent::class.java).apply { isAccessible = true }
        TestCore.onMain { result.invoke(activity!!, 2, android.app.Activity.RESULT_CANCELED, null) }
        assertNotNull(Views.tagged(activity!!, "howto:bt"))
        assertEquals("Allumez le Bluetooth.", Views.textOf(Views.tagged(activity!!, ScreenActivity.NOTE_TAG)!!))
        assertEquals("no real scan or settings change", 1, TestCore.radio.scans)
    }

    @Test fun aScanThatFailsShowsSearchAgainWithoutASpinner() {
        TestCore.install(connected = false)
        activity = Views.start(RadioActivity::class.java)
        assertEquals(1, TestCore.radio.scans)
        TestCore.onMain { TestCore.radio.failScan() }
        inst.waitForIdleSync()
        assertNull(Views.tagged(activity!!, "searching"))
        val again = checkNotNull(Views.tagged(activity!!, "radio_search_again"))
        // The canonical setup_search label is already "Trouver la radio" at HEAD.
        assertEquals("Trouver la radio", again.contentDescription)
        assertTrue(TestCore.onMain { again.height } >= Ui.dp(inst.targetContext, 48))
        Views.tap(activity!!, "radio_search_again")
        assertEquals(2, TestCore.radio.scans)
        assertNotNull(Views.tagged(activity!!, "searching"))
        assertNull(Views.tagged(activity!!, "radio_search_again"))
    }
}

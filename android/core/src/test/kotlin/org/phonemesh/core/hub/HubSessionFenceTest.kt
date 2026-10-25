package org.phonemesh.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.SocketFactory

/**
 * EXISTING-API-ONLY tests (HubSession, HubClient with a SocketFactory, MailPaths.order as they are in the frozen input):
 * they compile before any of the Bluetooth sources are applied, and pin down the generation fence that BleHubLink reuses
 * unchanged. Nothing here touches the network (the one client connects to a closed local port). NOT RUN when staged.
 */
class HubSessionFenceTest {
    private val pin = "A".repeat(43)

    private fun client() = HubClient("127.0.0.1", 9, pin, SocketFactory.getDefault(), connectMillis = 300, sendMillis = 500)

    @Test fun aStaleCallbackBuildsNothing() {
        val session = HubSession()
        val first = session.begin()
        val second = session.begin() // a new join or a replaced card
        var built = false
        assertFalse(session.available(first) { built = true; client() })
        assertFalse("a stale callback is not even asked to build", built)
        assertNull(session.wire())
        assertTrue(session.available(second) { client() })
        assertNotNull(session.wire())
    }

    @Test fun leavingClosesThePublishedWireSoNothingStaysBlockedOnIt() {
        val session = HubSession()
        val gen = session.begin()
        session.available(gen) { client() }
        val wire = session.wire()!!
        session.end()
        assertNull(session.wire())
        val failure = runCatching { wire.latest() }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
        assertEquals("this hub link is closed", failure!!.message)
        assertFalse("a late 'available' of the old join publishes nothing", session.available(gen) { client() })
        assertFalse("a late 'lost' of the old join changes nothing", session.lost(gen))
    }

    @Test fun twoLinksToOneHubAreFencedIndependently() {
        val wifi = HubSession()
        val bluetooth = HubSession()
        val w = wifi.begin()
        val b = bluetooth.begin()
        wifi.available(w) { client() }
        bluetooth.available(b) { client() }
        wifi.end() // Wi-Fi left; Bluetooth stays up
        assertNull(wifi.wire())
        assertNotNull(bluetooth.wire())
        assertFalse(wifi.available(w) { client() })
    }

    @Test fun theExistingOrderOfWaysIsWifiBluetoothRadio() {
        assertEquals(
            listOf(MailPaths.Path.WIFI_HUB, MailPaths.Path.BLUETOOTH_HUB, MailPaths.Path.STOCK_RADIO),
            MailPaths.order(wifiHub = true, bluetoothHub = true, radio = true, foreground = true, roaming = false),
        )
        assertEquals(
            listOf(MailPaths.Path.STOCK_RADIO),
            MailPaths.order(wifiHub = true, bluetoothHub = true, radio = true, foreground = false, roaming = false),
        )
    }
}

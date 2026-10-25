package org.phonemesh.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/**
 * NEW-API test (HubRoutes) on top of the EXISTING MailPaths.order: Wi-Fi hub first, then Bluetooth hub, then the stock radio
 * (null here), with no extra slot and no always-off switch. NOT RUN when staged.
 */
class HubRoutesTest {
    private class Wire(val name: String) : HubWire {
        override fun send(port: Int, packet: ByteArray): HubClient.Sent = throw IOException(name)
        override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<HubClient.Mail> = throw IOException(name)
        override fun latest(): Long = throw IOException(name)
    }

    private val wifi = Wire("wifi")
    private val ble = Wire("bluetooth")

    private fun pick(w: HubWire?, b: HubWire?, radio: Boolean = true, foreground: Boolean = true, roaming: Boolean = false, rested: Set<MailPaths.Path> = emptySet()) =
        HubRoutes.pick(w, b, radio, foreground, roaming, rested)

    @Test fun wifiComesFirstThenBluetoothThenTheRadio() {
        assertSame(wifi, pick(wifi, ble)!!.wire)
        assertEquals(MailPaths.Path.WIFI_HUB, pick(wifi, ble)!!.path)
        assertSame(ble, pick(null, ble)!!.wire)
        assertEquals(MailPaths.Path.BLUETOOTH_HUB, pick(null, ble)!!.path)
        assertNull("no hub wire: the stock radio is the way", pick(null, null))
    }

    @Test fun switchingWifiToBluetoothToStockAndBack() {
        val steps = listOf<Triple<HubWire?, HubWire?, MailPaths.Path?>>(
            Triple(wifi, null, MailPaths.Path.WIFI_HUB),
            Triple(null, ble, MailPaths.Path.BLUETOOTH_HUB), // Wi-Fi lost
            Triple(null, null, null), // Bluetooth lost too: stock radio
            Triple(null, ble, MailPaths.Path.BLUETOOTH_HUB),
            Triple(wifi, ble, MailPaths.Path.WIFI_HUB), // Wi-Fi is back and wins
        )
        for ((w, b, expected) in steps) assertEquals(expected, pick(w, b)?.path)
    }

    @Test fun aHubIsOnlyUsedWhileTheAppIsOpenUnlessRoamingIsOn() {
        assertNull(pick(wifi, ble, foreground = false, roaming = false))
        assertEquals(MailPaths.Path.WIFI_HUB, pick(wifi, ble, foreground = false, roaming = true)!!.path)
        assertEquals(MailPaths.Path.BLUETOOTH_HUB, pick(null, ble, foreground = false, roaming = true)!!.path)
    }

    @Test fun aWayThatJustFailedLetsTheNextWayTakeOver() {
        assertEquals(MailPaths.Path.BLUETOOTH_HUB, pick(wifi, ble, rested = setOf(MailPaths.Path.WIFI_HUB))!!.path)
        assertNull(pick(wifi, ble, rested = setOf(MailPaths.Path.WIFI_HUB, MailPaths.Path.BLUETOOTH_HUB)))
        assertEquals(MailPaths.Path.WIFI_HUB, pick(wifi, ble, rested = setOf(MailPaths.Path.BLUETOOTH_HUB))!!.path)
    }

    @Test fun theRadioFlagNeverAddsAHub() {
        assertNull(pick(null, null, radio = false))
        assertNull(pick(null, null, radio = true))
    }
}

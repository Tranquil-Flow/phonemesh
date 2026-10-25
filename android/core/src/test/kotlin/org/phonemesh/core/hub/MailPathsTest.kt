package org.phonemesh.core.hub

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.hub.MailPaths.Path.BLUETOOTH_HUB
import org.phonemesh.core.hub.MailPaths.Path.STOCK_RADIO
import org.phonemesh.core.hub.MailPaths.Path.WIFI_HUB

/** Accounts plan step 16: Wi-Fi hub, then Bluetooth hub, then the stock radio; hubs only while the app is open unless roaming. */
class MailPathsTest {
    private fun order(wifi: Boolean = true, ble: Boolean = true, radio: Boolean = true, foreground: Boolean = true, roaming: Boolean = false) =
        MailPaths.order(wifiHub = wifi, bluetoothHub = ble, radio = radio, foreground = foreground, roaming = roaming)

    @Test fun wifiHubThenBluetoothHubThenStockRadio() {
        assertEquals(listOf(WIFI_HUB, BLUETOOTH_HUB, STOCK_RADIO), order())
    }

    @Test fun aPathThatIsNotThereIsSkippedAndTheRestKeepTheirOrder() {
        assertEquals(listOf(BLUETOOTH_HUB, STOCK_RADIO), order(wifi = false))
        assertEquals(listOf(WIFI_HUB, STOCK_RADIO), order(ble = false))
        assertEquals(listOf(WIFI_HUB, BLUETOOTH_HUB), order(radio = false))
        assertEquals(listOf(STOCK_RADIO), order(wifi = false, ble = false))
    }

    @Test fun inTheBackgroundOnlyRoamingKeepsTheHubs() {
        assertEquals(listOf(STOCK_RADIO), order(foreground = false))
        assertEquals(listOf(WIFI_HUB, BLUETOOTH_HUB, STOCK_RADIO), order(foreground = false, roaming = true))
    }

    @Test fun roamingNeverInventsAPath() {
        assertEquals(emptyList<MailPaths.Path>(), order(wifi = false, ble = false, radio = false, foreground = false, roaming = true))
    }
}

package org.phonemesh.core.hub

/**
 * Which ways a private packet may take, best first (accounts plan step 16; spec "Which way a phone sends"): the hub over
 * Wi-Fi, then the hub over Bluetooth, then the stock radio. A hub is only used while the app is open, unless the person
 * turned roaming on (decision 9). The stock radio is not gated here: the app already lets it go a minute after its last
 * screen closes. A way that is missing is skipped and the others keep their order; roaming never adds a way.
 */
object MailPaths {
    enum class Path { WIFI_HUB, BLUETOOTH_HUB, STOCK_RADIO }

    fun order(wifiHub: Boolean, bluetoothHub: Boolean, radio: Boolean, foreground: Boolean, roaming: Boolean): List<Path> {
        val hubs = foreground || roaming
        return listOfNotNull(
            Path.WIFI_HUB.takeIf { wifiHub && hubs },
            Path.BLUETOOTH_HUB.takeIf { bluetoothHub && hubs },
            Path.STOCK_RADIO.takeIf { radio },
        )
    }
}

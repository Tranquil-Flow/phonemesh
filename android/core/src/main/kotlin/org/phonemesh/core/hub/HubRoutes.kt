package org.phonemesh.core.hub

/**
 * Which hub wire a private packet or a poll uses right now (accounts decision 19; spec "Which way a phone sends"): the
 * hub over Wi-Fi, else the hub over Bluetooth, else none, which means the stock radio. Built on [MailPaths.order], so
 * there is no extra slot and no switch that is always off: a way is "present" exactly when its link has a wire. A way
 * that just failed ([rested]) is skipped for a while, and the next way in the order takes over. Pure; the app calls it.
 */
object HubRoutes {
    class Route(val path: MailPaths.Path, val wire: HubWire)

    fun pick(
        wifi: HubWire?,
        bluetooth: HubWire?,
        radio: Boolean,
        foreground: Boolean,
        roaming: Boolean,
        rested: Set<MailPaths.Path> = emptySet(),
    ): Route? {
        val order = MailPaths.order(
            wifiHub = wifi != null && MailPaths.Path.WIFI_HUB !in rested,
            bluetoothHub = bluetooth != null && MailPaths.Path.BLUETOOTH_HUB !in rested,
            radio = radio,
            foreground = foreground,
            roaming = roaming,
        )
        return when (order.firstOrNull()) {
            MailPaths.Path.WIFI_HUB -> wifi?.let { Route(MailPaths.Path.WIFI_HUB, it) }
            MailPaths.Path.BLUETOOTH_HUB -> bluetooth?.let { Route(MailPaths.Path.BLUETOOTH_HUB, it) }
            MailPaths.Path.STOCK_RADIO, null -> null
        }
    }
}

package org.phonemesh.core

/** Bluetooth signal in dBm as one to four bars; non-negative values mean "unknown" and show one bar. */
object SignalBars {
    fun of(rssi: Int): Int = when {
        rssi >= 0 -> 1
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
}

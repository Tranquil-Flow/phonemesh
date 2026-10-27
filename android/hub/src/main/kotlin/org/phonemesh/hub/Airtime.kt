package org.phonemesh.hub

import kotlin.math.ceil
import kotlin.math.max

/**
 * LoRa time on air (Semtech AN1200.13) for one Meshtastic packet, and the duty-cycle budget the hub keeps. Defaults are
 * Meshtastic's LongFast preset (SF11, 250 kHz, 4/5, preamble 16), the village setting.
 */
class Airtime(private val sf: Int = 11, private val bandwidthHz: Int = 250_000, private val cr: Int = 1, private val preamble: Int = 16) {
    /**
     * Milliseconds on air for [payload] bytes on ports 300-303: the 16-byte mesh header and the Data fields firmware
     * 2.7.26 writes (7 bytes below 128 payload bytes, 8 from 128), as the phone's airtime meter counts them.
     */
    fun millis(payload: Int): Double {
        val onAir = MESH_HEADER + (if (payload < 128) DATA_OVERHEAD else DATA_OVERHEAD + 1) + payload
        val symbol = (1 shl sf).toDouble() / bandwidthHz
        val de = if (symbol > 0.016) 1 else 0
        val n = 8 + max(ceil((8.0 * onAir - 4 * sf + 28 + 16) / (4 * (sf - 2 * de))).toInt() * (cr + 4), 0)
        return ((preamble + 4.25) * symbol + n * symbol) * 1000
    }

    companion object {
        const val MESH_HEADER = 16
        const val DATA_OVERHEAD = 7
    }
}

/** Radio time used in the last hour, against [percent] of it (EU 868: 10 %). */
class DutyCycle(percent: Int, val windowMillis: Long = 3_600_000) {
    private val budget = windowMillis * percent / 100.0
    private val sent = ArrayDeque<Pair<Long, Double>>()

    fun used(now: Long): Double {
        while (sent.isNotEmpty() && sent.first().first <= now - windowMillis) sent.removeFirst()
        return sent.sumOf { it.second }
    }

    fun allows(now: Long, millis: Double): Boolean = used(now) + millis <= budget

    fun record(now: Long, millis: Double) { sent.addLast(now to millis) }
}

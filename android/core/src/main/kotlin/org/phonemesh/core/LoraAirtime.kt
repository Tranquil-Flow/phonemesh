package org.phonemesh.core

import kotlin.math.ceil
import kotlin.math.max

/** A LoRa modulation: spreading factor, bandwidth in kHz, coding rate denominator (5..8 for 4/5..4/8). */
data class LoraShape(val sf: Int, val bandwidthKhz: Double, val codingRate: Int)

/**
 * Estimated time on air (Semtech AN1200.13: explicit header, CRC on; low-data-rate optimisation when a symbol
 * lasts 16 ms or more, as Meshtastic sets it). An estimate for one hop: relays and retries are extra.
 */
object LoraAirtime {
    /** Meshtastic uses a 16-symbol preamble. */
    const val MESHTASTIC_PREAMBLE = 16

    /** Bytes on air besides our payload: 16 B mesh header, about 5 B of Data protobuf, 12 B for PKI direct messages. */
    const val OVERHEAD_BYTES = 16 + 5 + 12

    /** A routing acknowledgement coming back, as an estimate. */
    const val ACK_BYTES = 16 + 8 + 12

    /** Firmware 2.7.26 presets (src/mesh/MeshRadio.h), without the 500 kHz ones that EU 868 refuses. */
    private val presets = mapOf(
        "SHORT_FAST" to LoraShape(7, 250.0, 5),
        "SHORT_SLOW" to LoraShape(8, 250.0, 5),
        "MEDIUM_FAST" to LoraShape(9, 250.0, 5),
        "MEDIUM_SLOW" to LoraShape(10, 250.0, 5),
        "LONG_FAST" to LoraShape(11, 250.0, 5),
        "LONG_MODERATE" to LoraShape(11, 125.0, 8),
        "LONG_SLOW" to LoraShape(12, 125.0, 8),
    )

    val DEFAULT: LoraShape = presets.getValue("LONG_FAST")

    fun preset(name: String): LoraShape? = presets[name]

    fun seconds(bytes: Int, shape: LoraShape, preamble: Int = MESHTASTIC_PREAMBLE): Double {
        val symbol = (1 shl shape.sf) / (shape.bandwidthKhz * 1000.0)
        val lowRate = if ((1 shl shape.sf) / shape.bandwidthKhz >= 16.0) 1 else 0
        val numerator = 8 * bytes - 4 * shape.sf + 28 + 16
        val payloadSymbols = 8 + max(ceil(numerator / (4.0 * (shape.sf - 2 * lowRate))).toInt() * shape.codingRate, 0)
        return (preamble + 4.25) * symbol + payloadSymbols * symbol
    }

    /** Every packet of a picture ([payloads] are our bytes per packet) plus one acknowledgement each. */
    fun pictureSeconds(payloads: List<Int>, shape: LoraShape): Double =
        payloads.sumOf { seconds(it + OVERHEAD_BYTES, shape) + seconds(ACK_BYTES, shape) }
}

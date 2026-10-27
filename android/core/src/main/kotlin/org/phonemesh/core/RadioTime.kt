package org.phonemesh.core

import kotlin.math.ceil

/**
 * Radio time (airtime meter spec, AM1-AM3 A): the seconds one radio spends sending, once, on one hop. Not a delivery
 * time: relays, retries, waiting for the allowed radio time and the other phone are all extra. Priced from the bytes
 * the app actually sends ([payloads]), plus what the firmware adds around each on ports 300-303.
 */
object RadioTime {
    /** The app's private ports (300-303); old tagged texts use Meshtastic's text port 1. */
    const val PRIVATE_PORT = 301
    const val TEXT_PORT = 1

    /**
     * Bytes on air for [payload] application bytes on [port]: the 16-byte mesh header and the Data protobuf firmware
     * 2.7.26 writes (port, payload, bitfield, each with its field tag). Ports 300-303 add 23 bytes below 128 payload
     * bytes and 24 from 128 (the length takes two bytes); port 1 adds one byte less.
     */
    fun onAir(payload: Int, port: Int = PRIVATE_PORT): Int {
        require(payload >= 0) { "a payload has no negative size" }
        require(port in 1..511) { "port $port" }
        return MESH_HEADER + 1 + varint(port) + 1 + varint(payload) + payload + BITFIELD
    }

    /** Seconds on the radio for these packets on [port], each sent once. */
    fun seconds(payloads: List<Int>, shape: LoraShape, port: Int = PRIVATE_PORT): Double =
        payloads.sumOf { LoraAirtime.seconds(onAir(it, port), shape) }

    private const val MESH_HEADER = 16
    private const val BITFIELD = 2
    private fun varint(n: Int) = if (n < 128) 1 else 2

    /** What a person reads: whole seconds (rounded up) below a minute, whole minutes (rounded up) from one minute. */
    sealed class Shown {
        data class Seconds(val n: Int) : Shown()
        data class Minutes(val n: Int) : Shown()
        object Unknown : Shown() { override fun toString() = "Unknown" }
    }

    /** [seconds] for people; [Shown.Unknown] when the radio's setting is not known (never a guessed LongFast). */
    fun shown(seconds: Double?): Shown = when {
        seconds == null || seconds.isNaN() || seconds < 0 -> Shown.Unknown
        seconds < 60.0 -> Shown.Seconds(maxOf(1, ceil(seconds).toInt()))
        else -> Shown.Minutes(ceil(seconds / 60.0).toInt())
    }
}

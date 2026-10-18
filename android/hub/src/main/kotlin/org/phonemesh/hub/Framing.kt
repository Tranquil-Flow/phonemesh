package org.phonemesh.hub

import java.io.ByteArrayOutputStream

/**
 * Meshtastic's serial (stream) API framing: `0x94 0xC3 | length (2, big-endian) | protobuf`. ToRadio goes to the
 * radio, FromRadio comes back. Bytes outside frames are the radio's debug log, kept apart as text.
 */
object Framing {
    const val START1 = 0x94
    const val START2 = 0xC3
    const val MAX = 512

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size in 1..MAX) { "a frame holds 1..$MAX bytes" }
        return byteArrayOf(START1.toByte(), START2.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
    }

    /** Sent before the first request: wakes a radio whose serial port sleeps (Meshtastic's client libraries do this). */
    val WAKE: ByteArray = ByteArray(32) { START2.toByte() }

    /** Reassembles frames from reads of any size; a bad length makes it look for the next start. */
    class Reader {
        private var state = 0
        private var length = 0
        private val body = ByteArrayOutputStream()
        private val log = ByteArrayOutputStream()

        /** The frames completed by [bytes], and any finished log lines. */
        fun feed(bytes: ByteArray, count: Int = bytes.size, frames: (ByteArray) -> Unit, lines: (String) -> Unit = {}) {
            for (i in 0 until count) {
                val b = bytes[i].toInt() and 0xff
                when (state) {
                    0 -> if (b == START1) state = 1 else logByte(b, lines)
                    1 -> state = if (b == START2) 2 else { logByte(START1, lines); logByte(b, lines); 0 }
                    2 -> { length = b shl 8; state = 3 }
                    3 -> {
                        length = length or b
                        state = if (length in 1..MAX) { body.reset(); 4 } else 0
                    }
                    4 -> {
                        body.write(b)
                        if (body.size() == length) { frames(body.toByteArray()); state = 0 }
                    }
                }
            }
        }

        private fun logByte(b: Int, lines: (String) -> Unit) {
            if (b == '\n'.code) {
                if (log.size() > 0) lines(log.toString(Charsets.UTF_8).trimEnd('\r'))
                log.reset()
            } else if (log.size() < 4096) log.write(b)
        }
    }
}

package org.phonemesh.core.wire

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.P256

/**
 * Wire v2 packets on port 301 (accounts spec, "Message format"):
 * `byte 0 | recipient code (8) | [masked ratchet key and previous chain length (35)] | AES-GCM body with its tag`.
 * Byte 0 = version (4 bits) | key type (2 bits) | ratchet key included (1 bit) | hello (1 bit). The header, as sent,
 * is the GCM associated data. The 35 key bytes are masked with a key only the two phones hold, so an outsider cannot
 * link a turn's packets by them (review F7). A packet is at most [MAX_PACKET] bytes: what a phone can hand its radio
 * in one Bluetooth write. Hellos (hello bit set) have their own layout, see Start.
 */
object WireV2 {
    const val PORT = 301
    const val VERSION = 1
    const val KEY_P256 = 1
    const val MAX_PACKET = 228
    const val CODE = 8
    const val RATCHET = P256.POINT + 2
    const val PLAIN_HEADER = 1 + CODE
    const val KEYED_HEADER = PLAIN_HEADER + RATCHET
    private const val FLAG_KEY = 0x02
    private const val FLAG_HELLO = 0x01

    /** Byte 0 of a hello. */
    val HELLO_BYTE: Byte = ((VERSION shl 4) or (KEY_P256 shl 2) or FLAG_HELLO).toByte()

    class Header(code: ByteArray, masked: ByteArray?) {
        val code: ByteArray = code.copyOf()
        /** The masked ratchet key and previous chain length, or null. */
        val masked: ByteArray? = masked?.copyOf()

        init {
            require(code.size == CODE) { "a code is $CODE bytes" }
            require(masked == null || masked.size == RATCHET) { "the key field is $RATCHET bytes" }
        }

        fun encode(): ByteArray {
            val first = ((VERSION shl 4) or (KEY_P256 shl 2) or (if (masked != null) FLAG_KEY else 0)).toByte()
            return byteArrayOf(first) + code + (masked ?: ByteArray(0))
        }
    }

    /** A packet split into its header, the header's bytes (the associated data) and the sealed body. */
    class Parsed(val header: Header, val associated: ByteArray, val sealed: ByteArray)

    /** The largest body (kind byte included) that fits a packet, with or without the ratchet key. */
    fun maxBody(withKey: Boolean): Int = MAX_PACKET - (if (withKey) KEYED_HEADER else PLAIN_HEADER) - Aead.TAG

    /** A message packet from the radio; null unless byte 0 is version 1, P-256, not a hello, and the sizes fit. */
    fun parse(packet: ByteArray): Parsed? {
        if (packet.size > MAX_PACKET || packet.size < PLAIN_HEADER + Aead.TAG + 1) return null
        val first = packet[0].toInt() and 0xff
        if (first ushr 4 != VERSION || (first ushr 2) and 0x03 != KEY_P256 || first and FLAG_HELLO != 0) return null
        val keyed = first and FLAG_KEY != 0
        val headerSize = if (keyed) KEYED_HEADER else PLAIN_HEADER
        if (packet.size < headerSize + Aead.TAG + 1) return null
        val header = Header(packet.copyOfRange(1, 1 + CODE), if (keyed) packet.copyOfRange(PLAIN_HEADER, KEYED_HEADER) else null)
        return Parsed(header, packet.copyOfRange(0, headerSize), packet.copyOfRange(headerSize, packet.size))
    }
}

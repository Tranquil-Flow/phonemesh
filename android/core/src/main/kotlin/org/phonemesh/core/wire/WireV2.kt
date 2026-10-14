package org.phonemesh.core.wire

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.P256

/**
 * Wire v2 packets on port 301 (accounts spec, "Message format"):
 * `byte 0 | recipient code (8) | [ratchet key (33) | previous chain length (2)] | AES-GCM body with its 16-byte tag`.
 * Byte 0 = version (4 bits) | key type (2 bits) | ratchet key included (1 bit) | spare 0. The header is the GCM
 * associated data. A packet is at most [MAX_PACKET] bytes: what a phone can hand its radio in one Bluetooth write.
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

    class Header(code: ByteArray, ratchetKey: ByteArray?, val previousLength: Int) {
        val code: ByteArray = code.copyOf()
        val ratchetKey: ByteArray? = ratchetKey?.copyOf()

        init {
            require(code.size == CODE) { "a code is $CODE bytes" }
            require(ratchetKey == null || ratchetKey.size == P256.POINT) { "a ratchet key is ${P256.POINT} bytes" }
            require(previousLength in 0..0xffff) { "previous chain length is 2 bytes" }
            require(ratchetKey != null || previousLength == 0) { "a previous length only rides with a ratchet key" }
        }

        val size: Int get() = if (ratchetKey == null) PLAIN_HEADER else KEYED_HEADER

        fun encode(): ByteArray {
            val first = ((VERSION shl 4) or (KEY_P256 shl 2) or (if (ratchetKey != null) FLAG_KEY else 0)).toByte()
            val base = byteArrayOf(first) + code
            return if (ratchetKey == null) base
            else base + ratchetKey + byteArrayOf((previousLength ushr 8).toByte(), previousLength.toByte())
        }
    }

    /** A packet split into its header, the header's bytes (the associated data) and the sealed body. */
    class Parsed(val header: Header, val associated: ByteArray, val sealed: ByteArray)

    /** The largest body (kind byte included) that fits a packet, with or without the ratchet key. */
    fun maxBody(withKey: Boolean): Int = MAX_PACKET - (if (withKey) KEYED_HEADER else PLAIN_HEADER) - Aead.TAG

    /**
     * A packet from the radio; null unless byte 0 is version 1, P-256, spare bit clear, and the sizes fit. The ratchet
     * key is not checked for being on the curve here: the ratchet does that before using it.
     */
    fun parse(packet: ByteArray): Parsed? {
        if (packet.size > MAX_PACKET || packet.size < PLAIN_HEADER + Aead.TAG + 1) return null
        val first = packet[0].toInt() and 0xff
        if (first ushr 4 != VERSION || (first ushr 2) and 0x03 != KEY_P256 || first and 0x01 != 0) return null
        val keyed = first and FLAG_KEY != 0
        val headerSize = if (keyed) KEYED_HEADER else PLAIN_HEADER
        if (packet.size < headerSize + Aead.TAG + 1) return null
        val code = packet.copyOfRange(1, 1 + CODE)
        val header = if (!keyed) Header(code, null, 0) else Header(
            code,
            packet.copyOfRange(PLAIN_HEADER, PLAIN_HEADER + P256.POINT),
            ((packet[KEYED_HEADER - 2].toInt() and 0xff) shl 8) or (packet[KEYED_HEADER - 1].toInt() and 0xff),
        )
        return Parsed(header, packet.copyOfRange(0, headerSize), packet.copyOfRange(headerSize, packet.size))
    }
}

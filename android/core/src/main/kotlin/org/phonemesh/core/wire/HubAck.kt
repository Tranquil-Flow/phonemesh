package org.phonemesh.core.wire

/**
 * Port 302: the hub's "reached the hub" acknowledgement, several codes per packet to save radio time:
 * `version | count (1) | count x recipient code (8)`.
 */
object HubAck {
    const val PORT = 302
    const val VERSION = 1
    const val MAX_CODES = (WireV2.MAX_PACKET - 2) / WireV2.CODE

    fun encode(codes: List<ByteArray>): ByteArray {
        require(codes.size in 1..MAX_CODES) { "1..$MAX_CODES codes per acknowledgement" }
        require(codes.all { it.size == WireV2.CODE }) { "a code is ${WireV2.CODE} bytes" }
        return byteArrayOf(VERSION.toByte(), codes.size.toByte()) + codes.fold(ByteArray(0)) { all, c -> all + c }
    }

    fun decode(bytes: ByteArray): List<ByteArray>? {
        if (bytes.size < 2 || bytes[0].toInt() != VERSION) return null
        val count = bytes[1].toInt() and 0xff
        if (count !in 1..MAX_CODES || bytes.size != 2 + count * WireV2.CODE) return null
        return List(count) { bytes.copyOfRange(2 + it * WireV2.CODE, 2 + (it + 1) * WireV2.CODE) }
    }
}

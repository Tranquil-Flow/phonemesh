package org.phonemesh.core

import java.nio.ByteBuffer

/**
 * Hardware test T1 (accounts spec): does a full-size broadcast on the accounts port reach the other radio's phone,
 * intact? Probe builds only. Layout: "PMT1" | run (4) | sequence (4) | sent at, ms (8) | 213 bytes that depend on all
 * three, so any changed byte after the header is caught.
 */
object T1Probe {
    /** Meshtastic's payload limit on air. A phone's Bluetooth link to the radio allows less (252-byte writes: 228). */
    const val MAX_SIZE = 233
    private const val HEADER = 20
    const val MIN_SIZE = HEADER + 1
    private val MAGIC = "PMT1".toByteArray(Charsets.US_ASCII)

    data class Got(val run: Int, val seq: Int, val sentAtMillis: Long)

    fun packet(run: Int, seq: Int, sentAtMillis: Long, size: Int = MAX_SIZE): ByteArray {
        require(size in MIN_SIZE..MAX_SIZE) { "size $size is not in $MIN_SIZE..$MAX_SIZE" }
        val buffer = ByteBuffer.allocate(size).put(MAGIC).putInt(run).putInt(seq).putLong(sentAtMillis)
        for (i in 0 until size - HEADER) buffer.put(filler(run, seq, sentAtMillis, i))
        return buffer.array()
    }

    fun read(payload: ByteArray): Got? {
        if (payload.size !in MIN_SIZE..MAX_SIZE || !payload.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val buffer = ByteBuffer.wrap(payload, 4, HEADER - 4)
        val got = Got(buffer.int, buffer.int, buffer.long)
        for (i in 0 until payload.size - HEADER) if (payload[HEADER + i] != filler(got.run, got.seq, got.sentAtMillis, i)) return null
        return got
    }

    /** Every bit of the header changes every filler byte's value (MurmurHash3's 64-bit finaliser). */
    private fun filler(run: Int, seq: Int, sentAt: Long, i: Int): Byte {
        var x = ((run.toLong() shl 32) or (seq.toLong() and 0xffffffffL)) * -0x61c8864680b583ebL xor sentAt
        x += i * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 33)) * -0xae502812aa7333L
        x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
        return (x xor (x ushr 33)).toByte()
    }
}

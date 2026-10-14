package org.phonemesh.core.account

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The safety picture both people compare instead of a 60-digit number: six icons out of a fixed list of 64 (36 bits),
 * so both phones show the same six (owner decision after crypto review F4, 2026-10-14).
 *
 * The icons come from PBKDF2-HMAC-SHA256 over the two identity keys in byte order, [ROUNDS] rounds: someone swapping a
 * key in a link must try about 2^36 keys at 2^16 hashes each (about 2^52 work) to find one showing the same picture.
 */
object SafetyPicture {
    const val ICONS = 64
    const val SHOWN = 6
    const val ROUNDS = 65_536
    private val SALT = "Phonemesh safety picture v1".toByteArray(Charsets.US_ASCII)

    /** Takes a moment (about 65,000 hashes): compute it off the main thread, once per contact. */
    fun icons(mine: ByteArray, theirs: ByteArray): IntArray {
        val (first, second) = if (compare(mine, theirs) <= 0) mine to theirs else theirs to mine
        val key = pbkdf2(first + second, SALT, ROUNDS)
        var bits = 0L
        for (i in 0 until 6) bits = (bits shl 8) or (key[i].toLong() and 0xff)
        return IntArray(SHOWN) { ((bits ushr (42 - 6 * it)) and 63).toInt() }
    }

    /** PBKDF2-HMAC-SHA256, one 32-byte block (RFC 8018). The JCA's own takes a char[] password, not key bytes. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(rounds - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size - b.size
    }
}

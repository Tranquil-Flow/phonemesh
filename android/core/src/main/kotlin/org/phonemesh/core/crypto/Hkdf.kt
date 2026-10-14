package org.phonemesh.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF with SHA-256, RFC 5869. An empty salt means 32 zero bytes, as the RFC says. */
object Hkdf {
    private const val HASH = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(if (salt.isEmpty()) ByteArray(HASH) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH) { "length $length is not in 1..${255 * HASH}" }
        require(prk.size >= HASH) { "PRK must be at least $HASH bytes" }
        val out = ByteArray(length)
        var block = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            val take = minOf(HASH, length - filled)
            block.copyInto(out, filled, 0, take)
            filled += take
            counter++
        }
        return out
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray = expand(extract(salt, ikm), info, length)

    /** The spec's info strings are ASCII. */
    fun derive(salt: ByteArray, ikm: ByteArray, info: String, length: Int): ByteArray =
        derive(salt, ikm, info.toByteArray(Charsets.US_ASCII), length)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}

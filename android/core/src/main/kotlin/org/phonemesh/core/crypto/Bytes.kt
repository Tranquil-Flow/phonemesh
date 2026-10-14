package org.phonemesh.core.crypto

import java.math.BigInteger
import java.security.MessageDigest

/** Byte helpers for the accounts crypto (docs/specs/2026-10-14-accounts-design.md). */
object Bytes {
    /** Equality that takes the same time whatever the contents: for tags, codes and anything secret. */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun u32(value: Long): ByteArray {
        require(value in 0..0xffffffffL) { "$value does not fit in 4 bytes" }
        return byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    }

    /** [value] as exactly [size] big-endian bytes; throws if it does not fit. */
    fun fixed(value: BigInteger, size: Int): ByteArray {
        require(value.signum() >= 0) { "negative value" }
        val raw = value.toByteArray()
        val start = raw.indexOfFirst { it != 0.toByte() }.let { if (it < 0) raw.size else it }
        val digits = raw.size - start
        require(digits <= size) { "value needs $digits bytes, more than $size" }
        return ByteArray(size).also { raw.copyInto(it, size - digits, start, raw.size) }
    }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()
}

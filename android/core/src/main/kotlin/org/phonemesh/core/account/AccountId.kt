package org.phonemesh.core.account

import org.phonemesh.core.crypto.Bytes

/** An account's id: the first 8 bytes of SHA-256 of its identity key, compressed. */
class AccountId private constructor(private val bytes: ByteArray) : Comparable<AccountId> {
    fun toBytes(): ByteArray = bytes.copyOf()

    /** Unsigned, byte by byte: the order both phones use to tell L from H. */
    override fun compareTo(other: AccountId): Int {
        for (i in 0 until SIZE) {
            val c = (bytes[i].toInt() and 0xff) - (other.bytes[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?) = other is AccountId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val SIZE = 8
        fun of(identityKey: ByteArray): AccountId = AccountId(Bytes.sha256(identityKey).copyOf(SIZE))
        fun fromBytes(bytes: ByteArray): AccountId {
            require(bytes.size == SIZE) { "an account id is $SIZE bytes" }
            return AccountId(bytes.copyOf())
        }
    }
}

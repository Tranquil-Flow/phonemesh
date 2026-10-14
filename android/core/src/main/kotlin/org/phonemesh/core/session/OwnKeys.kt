package org.phonemesh.core.session

import org.phonemesh.core.crypto.P256
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * This phone's long-term keys: the identity key (IK) and the signed prekey (SPK). On the phone the identity key may
 * live in the Android Keystore, so the private halves are reached only through these operations.
 */
interface OwnKeys {
    /** IK, compressed (33 bytes). */
    val identity: ByteArray
    /** SPK, compressed (33 bytes). */
    val prekey: ByteArray
    /** ECDH of the identity key with [peer]; null when [peer] is not a valid key. */
    fun agreeIdentity(peer: ByteArray): ByteArray?
    /** ECDH of the signed prekey with [peer]; null when [peer] is not a valid key. */
    fun agreePrekey(peer: ByteArray): ByteArray?
    /** ECDSA with the identity key, raw 64 bytes. */
    fun signIdentity(message: ByteArray): ByteArray
}

/** A key pair as the ratchet stores it: the 32-byte scalar and the compressed public key. */
class KeyPairBytes(val scalar: ByteArray, val public: ByteArray) {
    init {
        require(scalar.size == 32 && public.size == P256.POINT) { "a scalar is 32 bytes, a public key 33" }
    }

    companion object {
        /** A fresh pair from the platform's generator. */
        fun generate(): KeyPairBytes {
            val pair = P256.generate()
            val scalar = org.phonemesh.core.crypto.Bytes.fixed((pair.private as ECPrivateKey).s, 32)
            return KeyPairBytes(scalar, P256.compress(pair.public as ECPublicKey))
        }
    }
}

/** Long-term keys held as plain scalars: tests, and phones without a Keystore that can agree keys (Android < 12). */
class SoftKeys(private val identityPair: KeyPairBytes, private val prekeyPair: KeyPairBytes) : OwnKeys {
    private val identityKey: PrivateKey = P256.privateKey(identityPair.scalar)
    private val prekeyKey: PrivateKey = P256.privateKey(prekeyPair.scalar)
    override val identity: ByteArray get() = identityPair.public.copyOf()
    override val prekey: ByteArray get() = prekeyPair.public.copyOf()
    override fun agreeIdentity(peer: ByteArray) = P256.agree(identityKey, peer)
    override fun agreePrekey(peer: ByteArray) = P256.agree(prekeyKey, peer)
    override fun signIdentity(message: ByteArray) = P256.sign(identityKey, message)

    companion object {
        fun generate() = SoftKeys(KeyPairBytes.generate(), KeyPairBytes.generate())
    }
}

package org.phonemesh.core.session

import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * This phone's long-term keys: the identity key (IK) and its signed prekeys (SPK). On the phone the identity key may
 * live in the Android Keystore, so the private halves are reached only through these operations. Prekeys rotate;
 * older ones stay in [prekeys] while links and starts may still name them (review F11).
 */
interface OwnKeys {
    /** IK, compressed (33 bytes). */
    val identity: ByteArray
    /** The live signed prekeys, compressed, the current one first. */
    val prekeys: List<ByteArray>
    /** The current signed prekey: the one new cards carry. */
    val prekey: ByteArray get() = prekeys.first()
    /** ECDH of the identity key with [peer]; null when [peer] is not a valid key. */
    fun agreeIdentity(peer: ByteArray): ByteArray?
    /** ECDH of the live prekey [prekey] with [peer]; null when this phone no longer holds it or [peer] is invalid. */
    fun agreePrekey(prekey: ByteArray, peer: ByteArray): ByteArray?
    /** ECDSA with the identity key, raw 64 bytes. */
    fun signIdentity(message: ByteArray): ByteArray
}

/** A key pair as the ratchet stores it: the 32-byte scalar and the compressed public key. */
class KeyPairBytes(scalar: ByteArray, public: ByteArray) {
    val scalar: ByteArray = scalar.copyOf()
    val public: ByteArray = public.copyOf()

    init {
        require(scalar.size == 32 && public.size == P256.POINT) { "a scalar is 32 bytes, a public key 33" }
    }

    /** Whether [public] really is this scalar's key, and both are in range (review F15). */
    fun consistent(): Boolean = try {
        val x = P256.agree(P256.privateKey(scalar), P256.generator)
        x != null && P256.decompress(public) != null && Bytes.equal(x, public.copyOfRange(1, P256.POINT)) &&
            P256.compress(P256.decompress(public)!!).contentEquals(public)
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        /** A fresh pair from the platform's generator. */
        fun generate(): KeyPairBytes {
            val pair = P256.generate()
            return KeyPairBytes(Bytes.fixed((pair.private as ECPrivateKey).s, 32), P256.compress(pair.public as ECPublicKey))
        }
    }
}

/** Long-term keys held as plain scalars: tests, and phones whose Keystore cannot agree keys (Android < 12). */
class SoftKeys(private val identityPair: KeyPairBytes, private val prekeyPairs: List<KeyPairBytes>) : OwnKeys {
    constructor(identityPair: KeyPairBytes, prekeyPair: KeyPairBytes) : this(identityPair, listOf(prekeyPair))

    init {
        require(prekeyPairs.isNotEmpty()) { "at least one prekey" }
    }

    private val identityKey: PrivateKey = P256.privateKey(identityPair.scalar)
    private val prekeyKeys = prekeyPairs.map { it.public to P256.privateKey(it.scalar) }
    override val identity: ByteArray get() = identityPair.public.copyOf()
    override val prekeys: List<ByteArray> get() = prekeyPairs.map { it.public.copyOf() }
    override fun agreeIdentity(peer: ByteArray) = P256.agree(identityKey, peer)
    override fun agreePrekey(prekey: ByteArray, peer: ByteArray): ByteArray? =
        prekeyKeys.firstOrNull { it.first.contentEquals(prekey) }?.let { P256.agree(it.second, peer) }
    override fun signIdentity(message: ByteArray) = P256.sign(identityKey, message)

    /** The same identity with a new current prekey, keeping up to [keep] older ones. */
    fun rotated(newPrekey: KeyPairBytes = KeyPairBytes.generate(), keep: Int = 1) =
        SoftKeys(identityPair, listOf(newPrekey) + prekeyPairs.take(keep))

    companion object {
        fun generate() = SoftKeys(KeyPairBytes.generate(), KeyPairBytes.generate())
    }
}

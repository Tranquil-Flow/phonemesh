package org.phonemesh.core.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a 16-byte tag. The nonce is never sent: both sides derive it with the key from the message key
 * (spec, "Chain step"), so a key and nonce pair must never be used twice. [sealed] lets tests watch every pair used.
 */
object Aead {
    const val KEY = 32
    const val NONCE = 12
    const val TAG = 16

    /** Test hook, visible to this module's tests only: told of every key and nonce [seal] uses (review F20). */
    @Volatile internal var sealed: ((key: ByteArray, nonce: ByteArray) -> Unit)? = null

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        sealed?.invoke(key.copyOf(), nonce.copyOf())
        return cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)
    }

    /** The plaintext, or null when [sealed] is too short or anything in it, the [aad], key or nonce is not as sealed. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG) return null
        return try {
            cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == KEY) { "key must be $KEY bytes" }
        require(nonce.size == NONCE) { "nonce must be $NONCE bytes" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
            updateAAD(aad)
        }
    }
}

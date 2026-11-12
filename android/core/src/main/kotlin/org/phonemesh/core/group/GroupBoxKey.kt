package org.phonemesh.core.group

import org.phonemesh.core.crypto.P256

/**
 * Op 9, the announced group box key (v0.1.3 G3b; relayed group control design, "New group ops" and "Box keys";
 * accounts decisions 79 and 80). A member makes a P-256 key pair for one group, keeps the private half sealed by the
 * Vault (never in a chat backup, decision 75) and announces the public half on its own radio chain, so an admin that
 * has not added it can still reach it: the admin seals the next epoch's key to that box key in an envelope (op 8).
 *
 * Body (35 bytes): op 9 | version 1 | the 33-byte compressed public key. Like every group packet the body is signed
 * with the chain owner's group signing key, so the announcement is bound to that member's list entry and to nobody
 * outside the group. The private half never leaves the phone; the public half is public by design.
 *
 * The body carries no identity of its own: the announcing member is the radio chain it travels on, so a reader takes
 * the key as the chain owner's own (see [GroupBoxKeys]). Op 9 has no cryptography of its own.
 */
object GroupBoxKey {
    const val OP = 9
    const val VERSION = 1
    /** The body after the op byte: version 1 and the 33-byte compressed public key. */
    const val BODY = 1 + P256.POINT
    /** The whole body: op 9 | version 1 | the key (35 bytes, as the design note says). */
    const val SIZE = 1 + BODY

    /** The body announcing [public]: op 9, version 1 and the compressed public key. */
    fun encode(public: ByteArray): ByteArray {
        require(public.size == P256.POINT && P256.decompress(public) != null) { "not a box key" }
        return byteArrayOf(OP.toByte(), VERSION.toByte()) + public
    }

    /** The public key in [body], or null when [body] is not a well-formed announcement. Never throws. */
    fun decode(body: ByteArray): ByteArray? {
        if (body.size != SIZE || body[0] != OP.toByte() || body[1] != VERSION.toByte()) return null
        val key = body.copyOfRange(2, SIZE)
        return if (P256.decompress(key) != null) key else null
    }
}

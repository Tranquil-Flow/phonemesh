package org.phonemesh.core.group

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.Hkdf
import org.phonemesh.core.crypto.P256
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey

/**
 * Op 8, the relayed group envelope (v0.1.3 G3a; docs/specs/2026-11-06-relayed-group-control-design.md, "The envelope";
 * accounts decisions 79 and 80). An admin that is not a member's contact seals the new epoch's key to that member's
 * group-only box key and sends it as a group packet on its own radio chain of the epoch the member holds. That packet's
 * group signature shows the envelope is the admin's; this body binds the group, the new epoch, the admin, the
 * recipient, the box key and the ephemeral key, so a copy on another admin's chain, for another member, group or epoch
 * does not open. A member being removed reads the outer packet but holds no box key the envelope was sealed to, and the
 * new secret is never protected by the old group key alone.
 *
 * Body (120 bytes): op 8 | version 1 | ephemeral key E (33) | AES-256-GCM(key, nonce, aad = op || version || E,
 * u32(epoch) || secret 32 || list digest 16 || parent digest 16 || list part count 1), where key || nonce =
 * HKDF-SHA256(salt = "phonemesh group envelope v1" || group || u32(epoch), ikm = ECDH(eph, B),
 * info = admin identity 33 || recipient identity 33 || B || E). Each envelope has its own ephemeral key, so each key and
 * nonce is used once. No recipient field and no padding (decision 80): members can still tell whom envelopes reached
 * and how many there were (the design note says how); nothing here hides that.
 *
 * Only one body is sealed or opened here. Which members get envelopes, box keys and their announcements, chain
 * positions, retries, Ask and saved state are later G3 slices; nothing in GroupPost sends or reads op 8 yet.
 * Private halves held by JCA keys cannot be wiped from here; derived bytes are overwritten best-effort.
 */
object GroupEnvelope {
    const val OP = 8
    const val VERSION = 1
    private const val PLAIN = 4 + GroupCipher.SECRET + GroupRoster.DIGEST + GroupRoster.DIGEST + 1
    const val SEALED = PLAIN + Aead.TAG
    const val SIZE = 2 + P256.POINT + SEALED

    private val SALT = "phonemesh group envelope v1".toByteArray()
    private val HEAD = byteArrayOf(OP.toByte(), VERSION.toByte())

    /** One of a member's own box keys: its private half and the 33-byte compressed public key it announced. */
    class BoxKey(val private: PrivateKey, public: ByteArray) {
        val public: ByteArray = public.copyOf()

        init {
            require(public.size == P256.POINT && P256.decompress(public) != null) { "not a box key" }
        }
    }

    private fun derive(group: ByteArray, epoch: Long, z: ByteArray, admin: ByteArray, recipient: ByteArray, box: ByteArray, e: ByteArray) =
        Hkdf.derive(SALT + group + Bytes.u32(epoch), z, admin + recipient + box + e, Aead.KEY + Aead.NONCE)

    /**
     * The envelope giving [key] (the epoch the admin is about to take) to the member with identity [recipient], sealed
     * to the box key [box] that member announced, from the admin with identity [admin]. [ephemeral] is made fresh for
     * each envelope; tests pass a fixed one to compare with the vectors.
     */
    fun seal(key: GroupKey, admin: ByteArray, recipient: ByteArray, box: ByteArray, ephemeral: KeyPair = P256.generate()): ByteArray {
        require(admin.size == P256.POINT && recipient.size == P256.POINT) { "identity keys are ${P256.POINT} bytes" }
        require(P256.decompress(box) != null) { "not a box key" }
        val e = P256.compress(ephemeral.public as ECPublicKey)
        val z = checkNotNull(P256.agree(ephemeral.private, box))
        val okm = derive(key.group, key.epoch, z, admin, recipient, box, e)
        val aesKey = okm.copyOfRange(0, Aead.KEY)
        val nonce = okm.copyOfRange(Aead.KEY, okm.size)
        val plain = Bytes.u32(key.epoch) + key.secret + key.digest + key.parent + byteArrayOf(key.parts.toByte())
        try {
            return HEAD + e + Aead.seal(aesKey, nonce, HEAD + e, plain)
        } finally {
            z.fill(0); okm.fill(0); aesKey.fill(0); nonce.fill(0); plain.fill(0)
        }
    }

    /**
     * The key in [body], or null. [group] and [epoch] are the group and the next epoch (one after the epoch the
     * receiver holds, whose chain the packet opened on); [admin] is the chain owner's identity and [signer] that
     * admin's group signing key from the list held (the caller checks that the owner is an admin of that list);
     * [recipient] is the receiver's own identity and [boxes] its kept box keys, newest first. Trial opening: a failed tag
     * under every kept key is "not for me". Anything malformed is null, never an exception. The result still has to
     * pass the unchanged `receiveKey` rules (next epoch only, parent = the list held, waiting limits).
     */
    fun open(body: ByteArray, group: ByteArray, epoch: Long, admin: ByteArray, signer: ByteArray, recipient: ByteArray,
             boxes: List<BoxKey>): GroupKey? {
        if (body.size != SIZE || body[0] != OP.toByte() || body[1] != VERSION.toByte()) return null
        if (group.size != GroupCipher.GROUP_ID || epoch !in 1 until 0xFFFF_FFFFL) return null
        if (admin.size != P256.POINT || recipient.size != P256.POINT) return null
        val e = body.copyOfRange(2, 2 + P256.POINT)
        val sealed = body.copyOfRange(2 + P256.POINT, SIZE)
        for (box in boxes) {
            val z = P256.agree(box.private, e) ?: return null
            val okm = derive(group, epoch, z, admin, recipient, box.public, e)
            val aesKey = okm.copyOfRange(0, Aead.KEY)
            val nonce = okm.copyOfRange(Aead.KEY, okm.size)
            val plain = Aead.open(aesKey, nonce, HEAD + e, sealed)
            z.fill(0); okm.fill(0); aesKey.fill(0); nonce.fill(0)
            if (plain == null) continue
            val secret = plain.copyOfRange(4, 4 + GroupCipher.SECRET)
            try {
                if ((ByteBuffer.wrap(plain, 0, 4).int.toLong() and 0xFFFF_FFFFL) != epoch) return null
                val at = 4 + GroupCipher.SECRET
                return GroupKey(group, epoch, secret, plain.copyOfRange(at, at + GroupRoster.DIGEST),
                    plain.copyOfRange(at + GroupRoster.DIGEST, at + 2 * GroupRoster.DIGEST), plain[PLAIN - 1].toInt() and 0xFF, signer)
            } catch (_: RuntimeException) {
                return null
            } finally {
                secret.fill(0); plain.fill(0)
            }
        }
        return null
    }
}

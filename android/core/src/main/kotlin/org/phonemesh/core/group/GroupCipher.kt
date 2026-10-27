package org.phonemesh.core.group

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.Hkdf
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.WireV2

/**
 * Private group packets (decision 47, draft GK3; independent crypto review OPEN). One epoch secret per membership,
 * renewed on every join and leave and given to each member over their own private session; from it each member
 * has a sending chain bound to their identity key. A message key, nonce and on-air code come from one chain step and
 * are used once.
 *
 * Every packet is signed by its sender's **group signing key** (decision 66): a key made for this group, never signed
 * by an identity key, that other members learn only through an admin over private sessions. So members cannot write
 * as each other, and a leaked packet proves nothing to an outsider. The signature is sealed inside the body (review
 * C1): nobody without the epoch key sees it, so a removed member cannot test later packets against keys.
 *
 * Packet: WireV2 plain header (version byte, 8-byte code: hubs store and serve it like any private mail) | AES-256-GCM,
 * with the header as associated data, of the body followed by a raw low-S P-256 signature (64) over the domain, group
 * id, epoch, sequence, code, sender identity and body. Nothing names the group or the sender on air.
 */
object GroupCipher {
    const val GROUP_ID = 16
    const val SECRET = 32
    const val SIGNATURE = P256.SIGNATURE
    /** The largest body that fits one radio packet: 228 - 9 - 16 - 64. */
    const val MAX_BODY = WireV2.MAX_PACKET - WireV2.PLAIN_HEADER - Aead.TAG - SIGNATURE

    private val DOMAIN = "phonemesh group v1".toByteArray()
    private val CHAIN_INFO = "phonemesh group chain".toByteArray()
    private val HUB_CHAIN_INFO = "phonemesh group hub chain".toByteArray()
    private val MESSAGE_INFO = "phonemesh group message".toByteArray()
    private val SIG_DOMAIN = "phonemesh group sig v2".toByteArray()
    private const val MIN_PACKET = WireV2.PLAIN_HEADER + Aead.TAG + 1 + SIGNATURE

    /** One chain position: the message key, nonce and on-air code for it, and the next position. */
    class Step(val key: ByteArray, val nonce: ByteArray, val code: ByteArray, val next: ByteArray)

    /**
     * The first chain key of the member with [identity] (33-byte compressed P-256) in this group epoch. [hub] gives
     * their second chain, for what goes only through a hub (full pictures, photos, recent messages; draft GK4), so
     * people who only hear the radio never see gaps in the first chain.
     */
    fun chain0(group: ByteArray, epoch: Long, secret: ByteArray, identity: ByteArray, hub: Boolean = false): ByteArray {
        require(group.size == GROUP_ID && secret.size == SECRET && identity.size == P256.POINT)
        require(epoch in 1..0xFFFF_FFFFL)
        return Hkdf.derive(DOMAIN + group + Bytes.u32(epoch), secret, (if (hub) HUB_CHAIN_INFO else CHAIN_INFO) + identity, 32)
    }

    fun step(chain: ByteArray): Step {
        val m = Hkdf.expand(Hkdf.hmac(chain, byteArrayOf(1)), MESSAGE_INFO, Aead.KEY + Aead.NONCE + WireV2.CODE)
        return Step(m.copyOfRange(0, 32), m.copyOfRange(32, 44), m.copyOfRange(44, 52), Hkdf.hmac(chain, byteArrayOf(2)))
    }

    private fun signed(group: ByteArray, epoch: Long, seq: Long, code: ByteArray, identity: ByteArray, body: ByteArray) =
        SIG_DOMAIN + group + Bytes.u32(epoch) + Bytes.u32(seq) + code + identity + body

    /**
     * The packet for [body] at [step] (chain position [seq]) from the member with [identity]; [sign] signs with their
     * group signing key. The signature goes inside the sealed part.
     */
    fun seal(
        group: ByteArray, epoch: Long, seq: Long, step: Step, identity: ByteArray, body: ByteArray, sign: (ByteArray) -> ByteArray,
    ): ByteArray {
        require(body.isNotEmpty() && body.size <= MAX_BODY) { "a group body is 1..$MAX_BODY bytes" }
        val header = WireV2.Header(step.code, null).encode()
        val signature = P256.lowS(sign(signed(group, epoch, seq, step.code, identity, body)))
        check(signature.size == SIGNATURE) { "a raw P-256 signature" }
        return header + Aead.seal(step.key, step.nonce, header, body + signature)
    }

    /**
     * The body, or null unless [packet] carries [step]'s code, opens with its key and holds a low-S signature by
     * [signer] (the sender's group signing key, 33-byte compressed) over this group, epoch, position and sender.
     */
    fun open(
        group: ByteArray, epoch: Long, seq: Long, step: Step, identity: ByteArray, signer: ByteArray, packet: ByteArray,
    ): ByteArray? {
        if (packet.size > WireV2.MAX_PACKET || packet.size < MIN_PACKET) return null
        val header = packet.copyOfRange(0, WireV2.PLAIN_HEADER)
        if (!Bytes.equal(header, WireV2.Header(step.code, null).encode())) return null
        val plain = Aead.open(step.key, step.nonce, header, packet.copyOfRange(WireV2.PLAIN_HEADER, packet.size)) ?: return null
        val body = plain.copyOfRange(0, plain.size - SIGNATURE)
        val signature = plain.copyOfRange(plain.size - SIGNATURE, plain.size)
        // Low S only (review C5); a member who knows the epoch key still cannot pass off a packet as another's.
        if (!P256.isLowS(signature) || !P256.verify(signer, signed(group, epoch, seq, step.code, identity, body), signature)) return null
        return body
    }

    /** The on-air code of a packet, or null when it is not a plain WireV2 packet long enough to be a group one. */
    fun codeOf(packet: ByteArray): ByteArray? {
        if (packet.size < MIN_PACKET || packet.size > WireV2.MAX_PACKET) return null
        val parsed = WireV2.parse(packet) ?: return null
        return if (parsed.header.masked == null) parsed.header.code else null
    }
}

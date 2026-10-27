package org.phonemesh.core.group

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.Hkdf
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.WireV2

/**
 * Private group packets (decision 47, draft GK1; independent crypto review OPEN). One epoch secret per membership,
 * renewed on every join and leave and given to each member over their own private session; from it each member
 * has a sending chain bound to their identity key. A message key, nonce and on-air code come from one chain step and
 * are used once; every packet is signed by its sender's identity key, so members cannot write as each other.
 *
 * Packet: WireV2 plain header (version byte, 8-byte code: hubs store and serve it like any private mail) | AES-256-GCM
 * of the body with the header as associated data | raw P-256 signature (64) over the domain, group id, epoch,
 * sequence, header and sealed body. Nothing names the group or the sender on air; a member finds both from the code.
 */
object GroupCipher {
    const val GROUP_ID = 16
    const val SECRET = 32
    const val SIGNATURE = P256.SIGNATURE
    /** The largest body that fits one radio packet: 228 - 9 - 16 - 64. */
    const val MAX_BODY = WireV2.MAX_PACKET - WireV2.PLAIN_HEADER - Aead.TAG - SIGNATURE

    private val DOMAIN = "phonemesh group v1".toByteArray()
    private val CHAIN_INFO = "phonemesh group chain".toByteArray()
    private val MESSAGE_INFO = "phonemesh group message".toByteArray()
    private val SIG_DOMAIN = "phonemesh group sig v1".toByteArray()

    /** One chain position: the message key, nonce and on-air code for it, and the next position. */
    class Step(val key: ByteArray, val nonce: ByteArray, val code: ByteArray, val next: ByteArray)

    /** The first chain key of the member with [identity] (33-byte compressed P-256) in this group epoch. */
    fun chain0(group: ByteArray, epoch: Long, secret: ByteArray, identity: ByteArray): ByteArray {
        require(group.size == GROUP_ID && secret.size == SECRET && identity.size == P256.POINT)
        require(epoch in 1..0xFFFF_FFFFL)
        return Hkdf.derive(DOMAIN + group + Bytes.u32(epoch), secret, CHAIN_INFO + identity, 32)
    }

    fun step(chain: ByteArray): Step {
        val m = Hkdf.expand(Hkdf.hmac(chain, byteArrayOf(1)), MESSAGE_INFO, Aead.KEY + Aead.NONCE + WireV2.CODE)
        return Step(m.copyOfRange(0, 32), m.copyOfRange(32, 44), m.copyOfRange(44, 52), Hkdf.hmac(chain, byteArrayOf(2)))
    }

    private fun signed(group: ByteArray, epoch: Long, seq: Long, header: ByteArray, sealed: ByteArray) =
        SIG_DOMAIN + group + Bytes.u32(epoch) + Bytes.u32(seq) + header + sealed

    /** The packet for [body] at [step] (chain position [seq]); [sign] signs with the sender's identity key. */
    fun seal(group: ByteArray, epoch: Long, seq: Long, step: Step, body: ByteArray, sign: (ByteArray) -> ByteArray): ByteArray {
        require(body.isNotEmpty() && body.size <= MAX_BODY) { "a group body is 1..$MAX_BODY bytes" }
        val header = WireV2.Header(step.code, null).encode()
        val sealed = Aead.seal(step.key, step.nonce, header, body)
        val signature = sign(signed(group, epoch, seq, header, sealed))
        check(signature.size == SIGNATURE) { "a raw P-256 signature" }
        return header + sealed + signature
    }

    /** The body, or null unless [packet] carries [step]'s code, opens with its key and is signed by [identity]. */
    fun open(group: ByteArray, epoch: Long, seq: Long, step: Step, identity: ByteArray, packet: ByteArray): ByteArray? {
        if (packet.size > WireV2.MAX_PACKET || packet.size < WireV2.PLAIN_HEADER + Aead.TAG + 1 + SIGNATURE) return null
        val header = packet.copyOfRange(0, WireV2.PLAIN_HEADER)
        if (!Bytes.equal(header, WireV2.Header(step.code, null).encode())) return null
        val sealed = packet.copyOfRange(WireV2.PLAIN_HEADER, packet.size - SIGNATURE)
        val signature = packet.copyOfRange(packet.size - SIGNATURE, packet.size)
        // Signature first: a member who knows the group key still cannot pass off a packet as someone else's.
        if (!P256.verify(identity, signed(group, epoch, seq, header, sealed), signature)) return null
        return Aead.open(step.key, step.nonce, header, sealed)
    }

    /** The on-air code of a packet, or null when it is not a plain WireV2 packet long enough to be a group one. */
    fun codeOf(packet: ByteArray): ByteArray? {
        if (packet.size < WireV2.PLAIN_HEADER + Aead.TAG + 1 + SIGNATURE || packet.size > WireV2.MAX_PACKET) return null
        val parsed = WireV2.parse(packet) ?: return null
        return if (parsed.header.masked == null) parsed.header.code else null
    }
}

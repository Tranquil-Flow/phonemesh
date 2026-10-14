package org.phonemesh.core.session

import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.Hkdf
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.WireV2

/**
 * Starting a conversation (accounts spec, "Starting a conversation" and "Starting after a QR scan"; build plan,
 * "Binding contracts"): after both people scanned each other's QR code, or when one opens the other's link and sends
 * a hello.
 */
object Start {
    internal const val INFO_X3DH = "Phonemesh X3DH v1"
    internal const val INFO_QR = "Phonemesh QR v1"
    internal const val INFO_HELLO = "Phonemesh hello v1"
    private const val HELLO_KEYS = 2 * P256.POINT

    /** QR start: either phone may write first. Null when a key agreement fails or the card is this phone's own. */
    fun qr(own: OwnKeys, their: ContactCard): SessionState? {
        val mine = org.phonemesh.core.account.AccountId.of(own.identity)
        if (mine == their.accountId) return null
        val iAmL = mine < their.accountId
        val dh1: ByteArray?; val dh2: ByteArray?
        if (iAmL) { dh1 = own.agreeIdentity(their.prekey); dh2 = own.agreePrekey(their.identity) }
        else { dh1 = own.agreePrekey(their.identity); dh2 = own.agreeIdentity(their.prekey) }
        val dh3 = own.agreePrekey(their.prekey)
        if (dh1 == null || dh2 == null || dh3 == null) return null
        return initial(secret(dh1, dh2, dh3, INFO_QR), iAmL, their.prekey)
    }

    class LinkOpened(val state: SessionState, val hello: ByteArray)

    /**
     * Link start by the person who opened [owner]'s link (A, who is L): the session and the hello packet to send.
     * [ephemeral] is used for this hello only. The hello is sealed once, under chain LH's first key: send these exact
     * bytes again if needed, never seal another hello in this session.
     */
    fun link(own: OwnKeys, name: String, colour: Int, owner: ContactCard, ephemeral: KeyPairBytes, day: Long): LinkOpened? {
        if (org.phonemesh.core.account.AccountId.of(own.identity) == owner.accountId) return null
        val ek = P256.privateKey(ephemeral.scalar)
        val dh1 = own.agreeIdentity(owner.prekey) ?: return null
        val dh2 = P256.agree(ek, owner.identity) ?: return null
        val dh3 = P256.agree(ek, owner.prekey) ?: return null
        val state = initial(secret(dh1, dh2, dh3, INFO_X3DH), true, owner.prekey)
        val associated = WireV2.Header(helloCode(owner.prekey, day), null, 0).encode() + own.identity + ephemeral.public
        val mk = Ratchet.messageKey(state.cks)
        state.cks = Ratchet.nextChain(state.cks)
        state.ns = 1
        return LinkOpened(state, associated + Ratchet.sealWith(mk, associated, Body.encode(Body.Hello(name, colour))))
    }

    class HelloReceived(val state: SessionState, identity: ByteArray, val name: String, val colour: Int) {
        val identity: ByteArray = identity.copyOf()
    }

    /** A hello for this phone's link (its code for [today], the day before or after); null for anything else. */
    fun hello(own: OwnKeys, packet: ByteArray, today: Long): HelloReceived? {
        val parsed = WireV2.parse(packet) ?: return null
        if (parsed.header.ratchetKey != null || parsed.sealed.size < HELLO_KEYS + 16 + 1) return null
        if ((today - 1..today + 1).none { it >= 0 && Bytes.equal(helloCode(own.prekey, it), parsed.header.code) }) return null
        val identity = parsed.sealed.copyOfRange(0, P256.POINT)
        val ephemeral = parsed.sealed.copyOfRange(P256.POINT, HELLO_KEYS)
        if (Bytes.equal(identity, own.identity)) return null
        val dh1 = own.agreePrekey(identity) ?: return null
        val dh2 = own.agreeIdentity(ephemeral) ?: return null
        val dh3 = own.agreePrekey(ephemeral) ?: return null
        val state = initial(secret(dh1, dh2, dh3, INFO_X3DH), false, null)
        val mk = Ratchet.messageKey(state.ckr)
        state.ckr = Ratchet.nextChain(state.ckr)
        state.nr = 1
        val associated = parsed.associated + identity + ephemeral
        val plain = Ratchet.openWith(mk, associated, parsed.sealed.copyOfRange(HELLO_KEYS, parsed.sealed.size)) ?: return null
        val body = Body.decode(plain) as? Body.Hello ?: return null
        state.heard = true
        return HelloReceived(state, identity, body.name, body.colour)
    }

    /** The code a hello to the owner of [prekey] carries on [day] (days since 1970-01-01, UTC). */
    fun helloCode(prekey: ByteArray, day: Long): ByteArray {
        require(day in 0..0xffffffffL) { "day out of range" }
        return Hkdf.hmac(Hkdf.derive(ByteArray(0), prekey, INFO_HELLO, 32), Bytes.u32(day)).copyOf(WireV2.CODE)
    }

    private fun secret(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, info: String): ByteArray =
        Hkdf.derive(ByteArray(32), ByteArray(32) { -1 } + dh1 + dh2 + dh3, info, 32)

    /** The 192 bytes from the shared secret: RK, CK_LH, CK_HL, HK_LH, HK_HL, NHK. L's first step comes first. */
    private fun initial(sk: ByteArray, iAmL: Boolean, theirPrekey: ByteArray?): SessionState {
        val out = Hkdf.derive(ByteArray(0), sk, Ratchet.INFO_ROOT, 192)
        fun part(i: Int) = out.copyOfRange(i * 32, (i + 1) * 32)
        val (rk, ckLH, ckHL, hkLH, hkHL, nhk) = listOf(part(0), part(1), part(2), part(3), part(4), part(5)).let { Six(it) }
        return if (iAmL) SessionState(true, rk, null, theirPrekey, true, false, ckLH, hkLH, 0, 0, false, ckHL, hkHL, 0, nhk)
        else SessionState(false, rk, null, theirPrekey, false, false, ckHL, hkHL, 0, 0, false, ckLH, hkLH, 0, nhk)
    }

    private class Six(private val v: List<ByteArray>) {
        operator fun component1() = v[0]; operator fun component2() = v[1]; operator fun component3() = v[2]
        operator fun component4() = v[3]; operator fun component5() = v[4]; operator fun component6() = v[5]
    }
}

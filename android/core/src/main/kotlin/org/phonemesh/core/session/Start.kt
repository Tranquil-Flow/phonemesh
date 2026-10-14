package org.phonemesh.core.session

import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.Hkdf
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.WireV2

/**
 * Starting a conversation (accounts spec, "Starting a conversation" and "Starting after a QR scan"; build plan,
 * "Binding contracts"; crypto review fixes F1, F2, F8, F10, F11, F18, F22, F23).
 *
 * - **QR start:** both people scanned each other's code. Each code carries a one-time key made for that showing, so a
 *   second scan gives new keys. L is the smaller account id and takes the first root step.
 * - **Link start:** A opened B's link and sends one hello. B takes the first root step, with a fresh key, before its
 *   first packet, so a replayed hello can never make B reuse a key. The hello hides A's identity key and carries no
 *   date or code: B finds hellos for itself by trying them.
 */
object Start {
    internal const val INFO_X3DH = "Phonemesh X3DH v1"
    internal const val INFO_QR = "Phonemesh QR v1"
    internal const val INFO_HELLO = "Phonemesh hello v1"
    private const val SEALED_IDENTITY = P256.POINT + Aead.TAG
    private const val HELLO_FIXED = 1 + P256.POINT + SEALED_IDENTITY

    /**
     * QR start. [mine] is the code this phone showed and [myOneTime] the key pair behind it (forget it after this);
     * [their] the code it scanned. Null when a key agreement fails, the codes do not match their keys, or [their] is
     * this phone's own.
     */
    fun qr(own: OwnKeys, mine: QrCode, myOneTime: KeyPairBytes, their: QrCode): SessionState? {
        if (!myOneTime.public.contentEquals(mine.oneTime) || !mine.card.identity.contentEquals(own.identity)) return null
        val me = AccountId.of(own.identity)
        if (me == their.card.accountId) return null
        val iAmL = me < their.card.accountId
        val ek = P256.privateKey(myOneTime.scalar)
        val myPrekey = mine.card.prekey
        val dh1: ByteArray?; val dh2: ByteArray?
        if (iAmL) { dh1 = own.agreeIdentity(their.card.prekey); dh2 = own.agreePrekey(myPrekey, their.card.identity) }
        else { dh1 = own.agreePrekey(myPrekey, their.card.identity); dh2 = own.agreeIdentity(their.card.prekey) }
        val dh3 = own.agreePrekey(myPrekey, their.card.prekey)
        val dh4 = P256.agree(ek, their.oneTime)
        if (dh1 == null || dh2 == null || dh3 == null || dh4 == null) return null
        val (ikL, ikH) = if (iAmL) own.identity to their.card.identity else their.card.identity to own.identity
        val sk = secret(INFO_QR, ikL, ikH, dh1, dh2, dh3, dh4)
        return initial(sk, iAmL, firstStepper = iAmL, mustStep = false, mine = myOneTime, theirs = their.oneTime)
    }

    class LinkOpened(val state: SessionState, val hello: ByteArray)

    /**
     * Link start by the person who opened [owner]'s link (A, who is L): the session and the hello to send. [ephemeral]
     * is made for this hello and kept in the session as A's first ratchet key. Send the same hello bytes again to
     * retry; to try again much later, start afresh with a new [ephemeral] (a new session, nothing reused).
     */
    fun link(own: OwnKeys, name: String, colour: Int, owner: ContactCard, ephemeral: KeyPairBytes): LinkOpened? {
        if (AccountId.of(own.identity) == owner.accountId) return null
        val ek = P256.privateKey(ephemeral.scalar)
        val first = byteArrayOf(WireV2.HELLO_BYTE)
        val hide = P256.agree(ek, owner.prekey) ?: return null
        val sealedIdentity = sealIdentity(hide, first + ephemeral.public, own.identity)
        val dh1 = own.agreeIdentity(owner.prekey) ?: return null
        val dh2 = P256.agree(ek, owner.identity) ?: return null
        val sk = secret(INFO_X3DH, own.identity, owner.identity, dh1, dh2, hide)
        val state = initial(sk, iAmL = true, firstStepper = false, mustStep = false, mine = ephemeral, theirs = null)
        val associated = first + ephemeral.public + sealedIdentity
        val hello = associated + Ratchet.sealWith(Ratchet.messageKey(state.cks), associated, Body.encode(Body.Hello(name, colour)))
        return LinkOpened(state.with(cks = Ratchet.nextChain(state.cks), ns = 1), hello)
    }

    class HelloReceived(val state: SessionState, identity: ByteArray, val name: String, val colour: Int, id: ByteArray) {
        val identity: ByteArray = identity.copyOf()
        /** Remember it: a hello with an id already seen, or from an identity that has a session, is a replay (F2). */
        val id: ByteArray = id.copyOf()
    }

    /** Whether [packet] has a hello's shape: worth trying with [hello]. */
    fun isHello(packet: ByteArray): Boolean = packet.size in HELLO_FIXED + 1 + Aead.TAG..WireV2.MAX_PACKET && packet[0] == WireV2.HELLO_BYTE

    /** A hello sent to one of this phone's live prekeys; null for anything else. About one ECDH per prekey. */
    fun hello(own: OwnKeys, packet: ByteArray): HelloReceived? {
        if (!isHello(packet)) return null
        val ephemeral = packet.copyOfRange(1, 1 + P256.POINT)
        if (P256.decompress(ephemeral) == null) return null
        val sealedIdentity = packet.copyOfRange(1 + P256.POINT, HELLO_FIXED)
        for (prekey in own.prekeys) {
            val hide = own.agreePrekey(prekey, ephemeral) ?: continue
            val identity = openIdentity(hide, packet.copyOf(1 + P256.POINT), sealedIdentity) ?: continue
            if (P256.decompress(identity) == null || Bytes.equal(identity, own.identity)) return null
            val dh1 = own.agreePrekey(prekey, identity) ?: return null
            val dh2 = own.agreeIdentity(ephemeral) ?: return null
            val sk = secret(INFO_X3DH, identity, own.identity, dh1, dh2, hide)
            // The owner's first chain comes from SK alone, which a replayed hello rebuilds: it steps before sending.
            val state = initial(sk, iAmL = false, firstStepper = true, mustStep = true, mine = null, theirs = ephemeral)
            val associated = packet.copyOf(HELLO_FIXED)
            val plain = Ratchet.openWith(Ratchet.messageKey(state.ckr), associated, packet.copyOfRange(HELLO_FIXED, packet.size)) ?: return null
            val body = Body.decode(plain) as? Body.Hello ?: return null
            val received = state.with(ckr = Ratchet.nextChain(state.ckr), nr = 1, heard = true)
            return HelloReceived(received, identity, body.name, body.colour, Bytes.sha256(ephemeral).copyOf(16))
        }
        return null
    }

    private fun identityKeys(shared: ByteArray): ByteArray = Hkdf.derive(ByteArray(0), shared, INFO_HELLO, Aead.KEY + Aead.NONCE)

    private fun sealIdentity(shared: ByteArray, associated: ByteArray, identity: ByteArray): ByteArray {
        val keys = identityKeys(shared)
        return Aead.seal(keys.copyOf(Aead.KEY), keys.copyOfRange(Aead.KEY, Aead.KEY + Aead.NONCE), associated, identity)
    }

    private fun openIdentity(shared: ByteArray, associated: ByteArray, sealed: ByteArray): ByteArray? {
        val keys = identityKeys(shared)
        return Aead.open(keys.copyOf(Aead.KEY), keys.copyOfRange(Aead.KEY, Aead.KEY + Aead.NONCE), associated, sealed)
    }

    /** SK = HKDF(salt 32 x 00, 32 x FF || DH..., info || IK_L || IK_H): the identity keys are bound in (F23). */
    private fun secret(info: String, ikL: ByteArray, ikH: ByteArray, vararg dh: ByteArray): ByteArray =
        Hkdf.derive(ByteArray(32), dh.fold(ByteArray(32) { -1 }) { all, d -> all + d },
            info.toByteArray(Charsets.US_ASCII) + ikL + ikH, 32)

    /** The 192 bytes from SK: RK, CK_LH, CK_HL, HK_LH, HK_HL, NHK. NHK names the first stepper's first new chain. */
    private fun initial(sk: ByteArray, iAmL: Boolean, firstStepper: Boolean, mustStep: Boolean, mine: KeyPairBytes?, theirs: ByteArray?): SessionState {
        val out = Hkdf.derive(ByteArray(0), sk, Ratchet.INFO_INIT, 192)
        fun part(i: Int) = out.copyOfRange(i * 32, (i + 1) * 32)
        val (ckOut, hkOut, ckIn, hkIn) = if (iAmL) listOf(part(1), part(3), part(2), part(4)) else listOf(part(2), part(4), part(1), part(3))
        return SessionState(iAmL, part(0), mine, theirs, firstStepper, false, mustStep, ckOut, hkOut, 0, 0, false,
            ckIn, hkIn, 0, part(5), 0, emptyMap())
    }
}

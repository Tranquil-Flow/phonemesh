package org.phonemesh.core.session

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.Hkdf
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.WireV2

/**
 * The Double Ratchet with recipient codes (accounts spec, "Double Ratchet" and "Recipient codes"; build plan,
 * "Binding contracts"). Root steps alternate between the two phones, L first; each step's new ratchet key rides on
 * every packet of the new chain that fits it, until the other phone's own step shows it arrived. Counters are never
 * sent: the 8-byte recipient code names the chain and counter. Every message key is used once and then deleted.
 */
object Ratchet {
    /** Codes looked ahead in a receiving chain, and in a new chain under the next header key. */
    const val WINDOW = 20
    /** Message keys kept for late packets, per conversation; the oldest go first. */
    const val MAX_SKIPPED = 50
    /** The most keys derived to reach the end of an old chain (a bound on work, not on what is kept). */
    const val MAX_SKIP_SCAN = 1000

    internal const val INFO_ROOT = "Phonemesh root v1"
    internal const val INFO_CHAIN = "Phonemesh chain v1"

    class Sealed(val packet: ByteArray, val code: ByteArray, val counter: Long, val stepped: Boolean, val withKey: Boolean)

    sealed class Opened {
        class Delivered(val body: ByteArray, val code: ByteArray, val stepped: Boolean) : Opened()
        /** A packet of the other phone's new chain that arrived before any packet carrying its ratchet key; try again later. */
        object Held : Opened()
        /** Not for this conversation, a replay, or not authentic. */
        object NotMine : Opened()
    }

    /**
     * Seals [body] (kind byte first) as the next packet of [state]. Makes the root step when it is this phone's turn
     * and the other phone spoke, unless [body] is too long to ride with the ratchet key: then the step waits for the
     * next packet. [newPair] is called only for a step.
     */
    fun seal(state: SessionState, body: ByteArray, newPair: () -> KeyPairBytes = KeyPairBytes::generate): Sealed {
        require(body.isNotEmpty()) { "a body starts with its kind" }
        val stepDue = state.mayStep && state.heard
        val stepNow = stepDue && body.size <= WireV2.maxBody(true)
        if (stepNow) stepSending(state, newPair())
        val withKey = state.announcing && body.size <= WireV2.maxBody(true)
        require(body.size <= WireV2.maxBody(withKey)) { "body of ${body.size} bytes does not fit a packet" }
        val n = state.ns
        require(n < 0xffffffffL) { "chain exhausted" }
        val mk = messageKey(state.cks)
        state.cks = nextChain(state.cks)
        state.ns = n + 1
        val code = code(state.hks, n)
        val header = WireV2.Header(code, if (withKey) state.mine!!.public else null, if (withKey) state.pn else 0).encode()
        val packet = header + sealWith(mk, header, body)
        if (!(stepDue && !stepNow)) state.heard = false
        return Sealed(packet, code, n, stepNow, withKey)
    }

    /** Opens [packet] if it belongs to [state]; [state] changes only when it is [Opened.Delivered]. */
    fun open(state: SessionState, packet: ByteArray, own: OwnKeys): Opened {
        val parsed = WireV2.parse(packet) ?: return Opened.NotMine
        val code = parsed.header.code
        val codeHex = SessionState.hexOf(code)
        val work = state.copy()

        work.skipped.remove(codeHex)?.let { mk ->
            val body = openWith(mk, parsed) ?: return Opened.NotMine
            work.heard = true
            state.assign(work)
            return Opened.Delivered(body, code, false)
        }

        for (i in 0 until WINDOW) {
            val n = work.nr + i
            if (n > 0xffffffffL) break
            if (!Bytes.equal(code(work.hkr, n), code)) continue
            val key = parsed.header.ratchetKey
            if (key != null && (work.theirs == null || !Bytes.equal(key, work.theirs!!))) return Opened.NotMine
            val mk = advanceReceiving(work, n)
            val body = openWith(mk, parsed) ?: return Opened.NotMine
            work.heard = true
            state.assign(work)
            return Opened.Delivered(body, code, false)
        }

        if (!work.mayStep) for (n in 0 until WINDOW) {
            if (!Bytes.equal(code(work.nhk, n.toLong()), code)) continue
            val key = parsed.header.ratchetKey ?: return Opened.Held
            val mine = work.mine
            val dh = (if (mine == null) own.agreePrekey(key) else P256.agree(P256.privateKey(mine.scalar), key)) ?: return Opened.NotMine
            val (rk, ck, nextHeader) = rootStep(work.rk, dh)
            val oldCk = work.ckr; val oldHk = work.hkr; val oldNr = work.nr
            work.rk = rk; work.theirs = key.copyOf(); work.hkr = work.nhk; work.nhk = nextHeader
            work.ckr = ck; work.nr = 0
            val mk = advanceReceiving(work, n.toLong())
            val body = openWith(mk, parsed) ?: return Opened.NotMine
            // Authentic: keep the old chain's last keys for its late packets, then hand the turn to this phone.
            skipOldChain(work, oldCk, oldHk, oldNr, parsed.header.previousLength.toLong())
            work.mayStep = true
            work.heard = true
            work.announcing = false
            state.assign(work)
            return Opened.Delivered(body, code, true)
        }
        return Opened.NotMine
    }

    /** Every code that would be recognised now, as hex: for a quick look before trying [open]. */
    fun codes(state: SessionState): Set<String> {
        val codes = HashSet<String>(state.skipped.keys)
        for (i in 0 until WINDOW) if (state.nr + i <= 0xffffffffL) codes += SessionState.hexOf(code(state.hkr, state.nr + i))
        if (!state.mayStep) for (n in 0 until WINDOW) codes += SessionState.hexOf(code(state.nhk, n.toLong()))
        return codes
    }

    /** A message's reference for replies and reactions: SHA-256(recipient code)[0..4]. */
    fun reference(code: ByteArray): ByteArray = Bytes.sha256(code).copyOf(4)

    // ---- Binding contracts

    internal fun code(headerKey: ByteArray, counter: Long): ByteArray = Hkdf.hmac(headerKey, Bytes.u32(counter)).copyOf(WireV2.CODE)
    internal fun messageKey(chainKey: ByteArray): ByteArray = Hkdf.hmac(chainKey, byteArrayOf(1))
    internal fun nextChain(chainKey: ByteArray): ByteArray = Hkdf.hmac(chainKey, byteArrayOf(2))

    internal fun rootStep(rk: ByteArray, dh: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val out = Hkdf.derive(rk, dh, INFO_ROOT, 96)
        return Triple(out.copyOfRange(0, 32), out.copyOfRange(32, 64), out.copyOfRange(64, 96))
    }

    internal fun sealWith(mk: ByteArray, associated: ByteArray, body: ByteArray): ByteArray {
        val keys = Hkdf.derive(ByteArray(0), mk, INFO_CHAIN, Aead.KEY + Aead.NONCE)
        return Aead.seal(keys.copyOf(Aead.KEY), keys.copyOfRange(Aead.KEY, Aead.KEY + Aead.NONCE), associated, body)
    }

    internal fun openWith(mk: ByteArray, associated: ByteArray, sealed: ByteArray): ByteArray? {
        val keys = Hkdf.derive(ByteArray(0), mk, INFO_CHAIN, Aead.KEY + Aead.NONCE)
        return Aead.open(keys.copyOf(Aead.KEY), keys.copyOfRange(Aead.KEY, Aead.KEY + Aead.NONCE), associated, sealed)
    }

    private fun openWith(mk: ByteArray, parsed: WireV2.Parsed) = openWith(mk, parsed.associated, parsed.sealed)

    // ---- Steps

    private fun stepSending(state: SessionState, pair: KeyPairBytes) {
        val theirs = checkNotNull(state.theirs) { "no ratchet key from the other phone yet" }
        val dh = checkNotNull(P256.agree(P256.privateKey(pair.scalar), theirs)) { "the other phone's ratchet key is invalid" }
        val (rk, ck, nextHeader) = rootStep(state.rk, dh)
        state.pn = state.ns.coerceAtMost(0xffff).toInt()
        state.ns = 0
        state.rk = rk
        state.cks = ck
        state.hks = state.nhk
        state.nhk = nextHeader
        state.mine = pair
        state.announcing = true
        state.mayStep = false
    }

    /** Moves the receiving chain to counter [n], keeping the keys it passes, and returns key [n]. */
    private fun advanceReceiving(state: SessionState, n: Long): ByteArray {
        var ck = state.ckr
        var at = state.nr
        while (at < n) {
            keep(state, code(state.hkr, at), messageKey(ck))
            ck = nextChain(ck)
            at++
        }
        val mk = messageKey(ck)
        state.ckr = nextChain(ck)
        state.nr = n + 1
        return mk
    }

    private fun skipOldChain(state: SessionState, ck: ByteArray, hk: ByteArray, from: Long, until: Long) {
        var key = ck
        var at = from
        val end = minOf(until, from + MAX_SKIP_SCAN)
        while (at < end) {
            keep(state, code(hk, at), messageKey(key))
            key = nextChain(key)
            at++
        }
    }

    private fun keep(state: SessionState, code: ByteArray, mk: ByteArray) {
        state.skipped[SessionState.hexOf(code)] = mk
        while (state.skipped.size > MAX_SKIPPED) state.skipped.remove(state.skipped.keys.first())
    }
}

package org.phonemesh.core.session

import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.Hkdf
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.Ack
import org.phonemesh.core.wire.WireV2
import java.security.GeneralSecurityException

/**
 * The Double Ratchet with recipient codes (accounts spec, "Double Ratchet", "Recipient codes" and "How the code
 * settles the ratchet"; build plan, "Binding contracts"). Root steps alternate between the two phones; each step's new
 * ratchet key rides, masked, on every packet of the new chain that fits it, until the other phone's own step shows it
 * arrived. Counters are never sent: the 8-byte recipient code names the chain and counter. Every message key is used
 * once. [seal] and [open] never change the state they are given; they return the next one (review F3).
 */
object Ratchet {
    /** Codes looked ahead in a receiving chain, and in a new chain under the next header key. */
    const val WINDOW = 20
    /** Message keys kept for late packets, per conversation; the oldest go first. */
    const val MAX_SKIPPED = 50
    /** The most keys derived to reach the end of an old chain (a bound on work, not on what is kept). */
    const val MAX_SKIP_SCAN = 1000

    internal const val INFO_INIT = "Phonemesh init v1"
    internal const val INFO_ROOT = "Phonemesh root v1"
    internal const val INFO_CHAIN = "Phonemesh chain v1"
    internal const val INFO_MASK = "Phonemesh header mask v1"

    /**
     * A sealed packet and the state after it. **Store [state] with [packet] before sending it**; send the stored bytes
     * again to retry. [chainTag] and [counter] name it in the other phone's [Ack]s.
     */
    class Sealed(
        val packet: ByteArray, val code: ByteArray, val counter: Long, val stepped: Boolean, val withKey: Boolean,
        val chainTag: ByteArray, val state: SessionState,
    )

    sealed class Opened {
        /** [counter] and [chainTag] are -1 and empty for a late packet opened with a kept key. */
        class Delivered(val body: ByteArray, val code: ByteArray, val counter: Long, val stepped: Boolean, val chainTag: ByteArray, val state: SessionState) : Opened()
        /**
         * A packet of the other phone's new chain that came before any packet carrying its ratchet key; try again after
         * a step. Not authenticated: anyone can make these, so keep few (review F16).
         */
        object Held : Opened()
        /** Not for this conversation, a replay, not authentic, or a state that cannot be used. */
        object NotMine : Opened()
    }

    /**
     * Seals [body] (kind byte first) as the next packet after [state]. Steps the ratchet when it is this phone's turn
     * and the other phone spoke, unless [body] is too long to ride with the ratchet key: then the step waits for the
     * next packet. A link's owner always steps before its first packet, even a long one (that packet then goes without
     * the key, and its receiver holds it until a keyed one arrives). [newPair] is called only for a step.
     */
    fun seal(state: SessionState, body: ByteArray, newPair: () -> KeyPairBytes = KeyPairBytes::generate): Sealed {
        require(body.isNotEmpty()) { "a body starts with its kind" }
        val fits = body.size <= WireV2.maxBody(true)
        val stepDue = state.mayStep && (state.heard || state.mustStep)
        val stepNow = stepDue && (fits || state.mustStep)
        val s = if (stepNow) stepSending(state, newPair()) else state
        check(!s.mustStep) { "this chain is not fresh" }
        val withKey = s.announcing && fits
        require(body.size <= WireV2.maxBody(withKey)) { "body of ${body.size} bytes does not fit a packet" }
        val n = s.ns
        require(n < 0xffffffffL) { "chain exhausted" }
        val code = code(s.hks, n)
        val masked = if (withKey) mask(s.hks, n, s.mine!!.public + byteArrayOf((s.pn ushr 8).toByte(), s.pn.toByte())) else null
        val header = WireV2.Header(code, masked).encode()
        val packet = header + sealWith(messageKey(s.cks), header, body)
        val next = s.with(cks = nextChain(s.cks), ns = n + 1, heard = if (stepDue && !stepNow) s.heard else false)
        return Sealed(packet, code, n, stepNow, withKey, tag(s.hks), next)
    }

    /** Opens [packet] if it belongs to [state]; [now] (seconds) dates any keys kept for late packets. */
    fun open(state: SessionState, packet: ByteArray, own: OwnKeys, now: Long = 0): Opened = try {
        openChecked(state, packet, now)
    } catch (_: IllegalArgumentException) {
        Opened.NotMine
    } catch (_: IllegalStateException) {
        Opened.NotMine
    } catch (_: GeneralSecurityException) {
        Opened.NotMine
    }

    private fun openChecked(state: SessionState, packet: ByteArray, now: Long): Opened {
        val parsed = WireV2.parse(packet) ?: return Opened.NotMine
        val code = parsed.header.code
        val codeHex = SessionState.hexOf(code)

        state.skipped[codeHex]?.let { entry ->
            val body = openWith(entry.key, parsed) ?: return Opened.NotMine
            return Opened.Delivered(body, code, -1, false, ByteArray(0), state.with(skipped = state.skipped - codeHex, heard = true))
        }

        for (i in 0 until WINDOW) {
            val n = state.nr + i
            if (n > 0xffffffffL) break
            if (!Bytes.equal(code(state.hkr, n), code)) continue
            parsed.header.masked?.let { masked ->
                val key = unmask(state.hkr, n, masked).copyOf(P256.POINT)
                if (state.theirs == null || !Bytes.equal(key, state.theirs)) return Opened.NotMine
            }
            val walk = walk(state.ckr, state.hkr, state.nr, n)
            val body = openWith(walk.key, parsed) ?: return Opened.NotMine
            val next = state.with(ckr = walk.next, nr = n + 1, heard = true, skipped = keep(state.skipped, walk.passed, now))
            return Opened.Delivered(body, code, n, false, tag(state.hkr), next)
        }

        if (!state.mayStep) for (n in 0 until WINDOW) {
            if (!Bytes.equal(code(state.nhk, n.toLong()), code)) continue
            val masked = parsed.header.masked ?: return Opened.Held
            val plain = unmask(state.nhk, n.toLong(), masked)
            val key = plain.copyOf(P256.POINT)
            val previous = ((plain[P256.POINT].toInt() and 0xff) shl 8) or (plain[P256.POINT + 1].toInt() and 0xff)
            val mine = state.mine ?: return Opened.NotMine
            val dh = P256.agree(P256.privateKey(mine.scalar), key) ?: return Opened.NotMine
            val (rk, ck, nextHeader) = rootStep(state.rk, dh)
            val walk = walk(ck, state.nhk, 0, n.toLong())
            val body = openWith(walk.key, parsed) ?: return Opened.NotMine
            // Authentic. Keep the old chain's last keys first, then the new chain's, so neither crowds out the other's
            // newest (review F14).
            val old = walk(state.ckr, state.hkr, state.nr, minOf(previous.toLong(), state.nr + MAX_SKIP_SCAN)).passed
            val next = state.with(
                rk = rk, theirs = key, hkr = state.nhk, nhk = nextHeader, ckr = walk.next, nr = n + 1L,
                mayStep = true, heard = true, announcing = false, skipped = keep(keep(state.skipped, old, now), walk.passed, now),
            )
            return Opened.Delivered(body, code, n.toLong(), true, tag(state.nhk), next)
        }
        return Opened.NotMine
    }

    /** "Delivered to their phone" for the chain this phone receives now; null before anything arrived in it. */
    fun ack(state: SessionState): Ack? {
        if (state.nr == 0L) return null
        val highest = state.nr - 1
        var missing = 0
        for (i in 0 until 32) {
            val n = highest - 1 - i
            if (n < 0) break
            if (SessionState.hexOf(code(state.hkr, n)) in state.skipped) missing = missing or (1 shl i)
        }
        return Ack(tag(state.hkr), highest, missing)
    }

    /**
     * Flow control (review F5): whether a new packet may be sealed, given the newest [ack] from the other phone (null:
     * none yet). The other phone looks [WINDOW] codes past what it acknowledged, so this phone never seals more than
     * WINDOW packets past that in its current chain; past that, it resends its oldest unacknowledged stored packet.
     */
    fun maySealNew(state: SessionState, ack: Ack?): Boolean {
        val acked = if (ack != null && Bytes.equal(ack.tag, tag(state.hks))) ack.highest + 1 else 0L
        return state.ns - acked < WINDOW
    }

    /** The tag the other phone's [Ack]s use for this phone's current sending chain. */
    fun sendingTag(state: SessionState): ByteArray = tag(state.hks)

    /** [state] without kept keys older than [before] (seconds): late packets past that are given up (review F24). */
    fun forget(state: SessionState, before: Long): SessionState =
        if (state.skipped.values.none { it.at < before }) state else state.with(skipped = state.skipped.filterValues { it.at >= before })

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
    internal fun tag(headerKey: ByteArray): ByteArray = Bytes.sha256(headerKey).copyOf(Ack.TAG)

    internal fun rootStep(rk: ByteArray, dh: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val out = Hkdf.derive(rk, dh, INFO_ROOT, 96)
        return Triple(out.copyOfRange(0, 32), out.copyOfRange(32, 64), out.copyOfRange(64, 96))
    }

    /** The 35 key bytes XOR a pad from the chain's header key and the counter (review F7). Its own inverse. */
    internal fun mask(headerKey: ByteArray, counter: Long, bytes: ByteArray): ByteArray {
        val pad = Hkdf.derive(ByteArray(0), headerKey, INFO_MASK.toByteArray(Charsets.US_ASCII) + Bytes.u32(counter), WireV2.RATCHET)
        return ByteArray(WireV2.RATCHET) { (bytes[it].toInt() xor pad[it].toInt()).toByte() }
    }

    private fun unmask(headerKey: ByteArray, counter: Long, masked: ByteArray) = mask(headerKey, counter, masked)

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

    private fun stepSending(state: SessionState, pair: KeyPairBytes): SessionState {
        val theirs = checkNotNull(state.theirs) { "no ratchet key from the other phone yet" }
        val dh = checkNotNull(P256.agree(P256.privateKey(pair.scalar), theirs)) { "the other phone's ratchet key is invalid" }
        val (rk, ck, nextHeader) = rootStep(state.rk, dh)
        return state.with(
            pn = state.ns.coerceAtMost(0xffff).toInt(), ns = 0, rk = rk, cks = ck, hks = state.nhk, nhk = nextHeader,
            mine = pair, announcing = true, mayStep = false, mustStep = false,
        )
    }

    private class Walk(val key: ByteArray, val next: ByteArray, val passed: List<Pair<ByteArray, ByteArray>>)

    /** From chain key [ck] at counter [from], to counter [to]: key [to], the chain key after it, and the keys passed. */
    private fun walk(ck: ByteArray, hk: ByteArray, from: Long, to: Long): Walk {
        var chain = ck
        var at = from
        val passed = ArrayList<Pair<ByteArray, ByteArray>>()
        while (at < to) {
            passed += code(hk, at) to messageKey(chain)
            chain = nextChain(chain)
            at++
        }
        return Walk(messageKey(chain), nextChain(chain), passed)
    }

    private fun keep(skipped: Map<String, SessionState.Skipped>, keys: List<Pair<ByteArray, ByteArray>>, now: Long): Map<String, SessionState.Skipped> {
        if (keys.isEmpty()) return skipped
        val out = LinkedHashMap(skipped)
        for ((code, key) in keys) out[SessionState.hexOf(code)] = SessionState.Skipped(key, now)
        while (out.size > MAX_SKIPPED) out.remove(out.keys.first())
        return out
    }
}

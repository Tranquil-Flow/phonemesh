package org.phonemesh.core.session

import org.phonemesh.core.crypto.P256
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * One conversation's ratchet state (accounts spec, "Double Ratchet"; build plan, "Binding contracts"). Immutable to
 * callers: [Ratchet] and [Start] return new states. **Store the new state (and any packet to send) in one transaction
 * before the packet leaves the phone, and resend stored bytes, never reseal** (review F3).
 *
 * - [iAmL]: this phone is L (QR start: the smaller account id; link start: the person who opened the link).
 * - [mine]: this phone's current ratchet key pair (at the start, its one-time key; null for a link's owner, who steps
 *   before it sends). [theirs]: the other phone's current ratchet key, or null when not known yet.
 * - [mayStep]: the next root step is this phone's; the steps alternate. [heard]: the other phone spoke since this one
 *   last sent. [mustStep]: this phone's sending chain is not fresh (a link owner's first chain) and is never used.
 * - [nhk]: the header key of the next new chain, whichever side starts it; both phones hold the same one.
 * - [announcing]: this phone's latest chain began with a step the other phone has not answered yet.
 * - [serial]: how many times this state moved forward. Keep the highest stored and refuse an older state (F6).
 * - [skipped]: message keys kept for late packets, by code, with the time each was kept (F24).
 */
class SessionState internal constructor(
    val iAmL: Boolean,
    internal val rk: ByteArray,
    internal val mine: KeyPairBytes?,
    internal val theirs: ByteArray?,
    val mayStep: Boolean,
    val heard: Boolean,
    internal val mustStep: Boolean,
    internal val cks: ByteArray,
    internal val hks: ByteArray,
    val ns: Long,
    internal val pn: Int,
    val announcing: Boolean,
    internal val ckr: ByteArray,
    internal val hkr: ByteArray,
    val nr: Long,
    internal val nhk: ByteArray,
    val serial: Long,
    internal val skipped: Map<String, Skipped>,
) {
    class Skipped(key: ByteArray, val at: Long) {
        internal val key: ByteArray = key.copyOf()
    }

    internal fun with(
        rk: ByteArray = this.rk, mine: KeyPairBytes? = this.mine, theirs: ByteArray? = this.theirs, mayStep: Boolean = this.mayStep,
        heard: Boolean = this.heard, mustStep: Boolean = this.mustStep, cks: ByteArray = this.cks, hks: ByteArray = this.hks,
        ns: Long = this.ns, pn: Int = this.pn, announcing: Boolean = this.announcing, ckr: ByteArray = this.ckr,
        hkr: ByteArray = this.hkr, nr: Long = this.nr, nhk: ByteArray = this.nhk, skipped: Map<String, Skipped> = this.skipped,
    ) = SessionState(iAmL, rk, mine, theirs, mayStep, heard, mustStep, cks, hks, ns, pn, announcing, ckr, hkr, nr, nhk, serial + 1, skipped)

    /** How many skipped keys are held. */
    val skippedCount: Int get() = skipped.size

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).run {
            writeByte(VERSION)
            var flags = 0
            if (iAmL) flags = flags or 1
            if (mayStep) flags = flags or 2
            if (heard) flags = flags or 4
            if (announcing) flags = flags or 8
            if (mine != null) flags = flags or 16
            if (theirs != null) flags = flags or 32
            if (mustStep) flags = flags or 64
            writeByte(flags)
            write(rk)
            mine?.let { write(it.scalar); write(it.public) }
            theirs?.let { write(it) }
            write(cks); write(hks); writeInt(ns.toInt()); writeShort(pn)
            write(ckr); write(hkr); writeInt(nr.toInt())
            write(nhk)
            writeLong(serial)
            writeByte(skipped.size)
            for ((code, entry) in skipped) { write(hex(code)); write(entry.key); writeLong(entry.at) }
        }
        return out.toByteArray()
    }

    companion object {
        const val VERSION = 1

        /** A stored state; null when the bytes are not one this version wrote, or its keys are not valid (F15). */
        fun decode(bytes: ByteArray): SessionState? = try {
            DataInputStream(bytes.inputStream()).run {
                if (readUnsignedByte() != VERSION) return null
                val flags = readUnsignedByte()
                if (flags and 0x80 != 0) return null
                fun bytes(n: Int) = ByteArray(n).also { readFully(it) }
                val rk = bytes(32)
                val mine = if (flags and 16 != 0) KeyPairBytes(bytes(32), bytes(33)) else null
                val theirs = if (flags and 32 != 0) bytes(33) else null
                val cks = bytes(32); val hks = bytes(32); val ns = readInt().toLong() and 0xffffffffL; val pn = readUnsignedShort()
                val ckr = bytes(32); val hkr = bytes(32); val nr = readInt().toLong() and 0xffffffffL
                val nhk = bytes(32)
                val serial = readLong()
                val count = readUnsignedByte()
                if (count > Ratchet.MAX_SKIPPED || serial < 0) return null
                val skipped = LinkedHashMap<String, Skipped>()
                repeat(count) { skipped[hexOf(bytes(8))] = Skipped(bytes(32), readLong()) }
                if (read() != -1) return null
                if (mine != null && !mine.consistent()) return null
                if (theirs != null && P256.decompress(theirs) == null) return null
                SessionState(flags and 1 != 0, rk, mine, theirs, flags and 2 != 0, flags and 4 != 0, flags and 64 != 0,
                    cks, hks, ns, pn, flags and 8 != 0, ckr, hkr, nr, nhk, serial, skipped)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        internal fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

package org.phonemesh.core.session

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * One conversation's ratchet state (accounts spec, "Double Ratchet"; build plan, "Binding contracts"). Mutated only by
 * [Ratchet] and [Start]. Stored as bytes ([encode]), versioned. Holds no message key once that key has been used.
 *
 * - [iAmL]: this phone is L (QR start: the smaller account id; link start: the person who opened the link).
 * - [mine]: this phone's current ratchet key pair; null means its signed prekey (before its first step).
 * - [theirs]: the other phone's current ratchet key; at the start their signed prekey, or null when unknown.
 * - [mayStep]: the next root step is this phone's (the steps alternate, L first). [heard]: the other phone spoke since
 *   this one last sent; a step happens on the next send when both are true.
 * - [nhk]: the header key of the next new chain, whichever side starts it; both phones hold the same one.
 * - [announcing]: this phone's latest chain began with a step the other phone has not yet answered, so its ratchet
 *   key rides on every packet that fits it.
 */
class SessionState internal constructor(
    var iAmL: Boolean,
    var rk: ByteArray,
    var mine: KeyPairBytes?,
    var theirs: ByteArray?,
    var mayStep: Boolean,
    var heard: Boolean,
    var cks: ByteArray,
    var hks: ByteArray,
    var ns: Long,
    var pn: Int,
    var announcing: Boolean,
    var ckr: ByteArray,
    var hkr: ByteArray,
    var nr: Long,
    var nhk: ByteArray,
    val skipped: LinkedHashMap<String, ByteArray> = LinkedHashMap(),
) {
    fun copy(): SessionState = SessionState(
        iAmL, rk.copyOf(), mine?.let { KeyPairBytes(it.scalar.copyOf(), it.public.copyOf()) }, theirs?.copyOf(), mayStep,
        heard, cks.copyOf(), hks.copyOf(), ns, pn, announcing, ckr.copyOf(), hkr.copyOf(), nr, nhk.copyOf(),
        LinkedHashMap(skipped.mapValues { it.value.copyOf() }),
    )

    internal fun assign(other: SessionState) {
        iAmL = other.iAmL; rk = other.rk; mine = other.mine; theirs = other.theirs; mayStep = other.mayStep
        heard = other.heard; cks = other.cks; hks = other.hks; ns = other.ns; pn = other.pn; announcing = other.announcing
        ckr = other.ckr; hkr = other.hkr; nr = other.nr; nhk = other.nhk
        skipped.clear(); skipped.putAll(other.skipped)
    }

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
            writeByte(flags)
            write(rk)
            mine?.let { write(it.scalar); write(it.public) }
            theirs?.let { write(it) }
            write(cks); write(hks); writeInt(ns.toInt()); writeShort(pn)
            write(ckr); write(hkr); writeInt(nr.toInt())
            write(nhk)
            writeByte(skipped.size)
            for ((code, key) in skipped) { write(hex(code)); write(key) }
        }
        return out.toByteArray()
    }

    companion object {
        const val VERSION = 1

        /** A stored state; null when the bytes are not one this version wrote. */
        fun decode(bytes: ByteArray): SessionState? = try {
            DataInputStream(bytes.inputStream()).run {
                if (readUnsignedByte() != VERSION) return null
                val flags = readUnsignedByte()
                if (flags and 0xc0 != 0) return null
                fun bytes(n: Int) = ByteArray(n).also { readFully(it) }
                val rk = bytes(32)
                val mine = if (flags and 16 != 0) KeyPairBytes(bytes(32), bytes(33)) else null
                val theirs = if (flags and 32 != 0) bytes(33) else null
                val cks = bytes(32); val hks = bytes(32); val ns = readInt().toLong() and 0xffffffffL; val pn = readUnsignedShort()
                val ckr = bytes(32); val hkr = bytes(32); val nr = readInt().toLong() and 0xffffffffL
                val nhk = bytes(32)
                val count = readUnsignedByte()
                if (count > Ratchet.MAX_SKIPPED) return null
                val skipped = LinkedHashMap<String, ByteArray>()
                repeat(count) { skipped[hexOf(bytes(8))] = bytes(32) }
                if (read() != -1) return null
                SessionState(flags and 1 != 0, rk, mine, theirs, flags and 2 != 0, flags and 4 != 0, cks, hks, ns, pn,
                    flags and 8 != 0, ckr, hkr, nr, nhk, skipped)
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

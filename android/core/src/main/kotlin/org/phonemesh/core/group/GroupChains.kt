package org.phonemesh.core.group

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * One member's sending chain as others read it (draft GK3): the chain key at the next unread position, codes to ask
 * a hub for, and at most [MAX_SKIPPED] keys kept for packets that arrive out of order. A used or skipped-past chain
 * key is gone; only one-use message keys of missed packets stay, so reading a packet twice is refused. The window's
 * steps are worked out once per move of the chain, not for every packet (review C10).
 */
class GroupReader(
    private val group: ByteArray, private val epoch: Long, val identity: ByteArray, val signer: ByteArray,
    private var chain: ByteArray, next: Long = 0,
) {
    var next: Long = next
        private set
    /** The chain position of the packet [open] last returned (not saved). */
    var lastOpened: Long = -1
        private set
    private val skipped = LinkedHashMap<Long, GroupCipher.Step>()
    private var window: List<GroupCipher.Step>? = null

    private fun window(): List<GroupCipher.Step> = window ?: run {
        var c = chain
        List(WINDOW) { GroupCipher.step(c).also { c = it.next } }.also { window = it }
    }

    /** Codes of the next [WINDOW] packets and of every kept missed one: what to listen and ask for. */
    fun expectedCodes(): List<ByteArray> = skipped.values.map { it.code } + window().map { it.code }

    /** The body when [packet] is this member's, in order or late within the window; null otherwise (state unchanged). */
    fun open(packet: ByteArray): ByteArray? {
        val code = GroupCipher.codeOf(packet) ?: return null
        skipped.entries.firstOrNull { it.value.code.contentEquals(code) }?.let { (seq, step) ->
            val body = GroupCipher.open(group, epoch, seq, step, identity, signer, packet) ?: return null
            skipped.remove(seq)
            lastOpened = seq
            return body
        }
        val steps = window()
        val i = steps.indexOfFirst { it.code.contentEquals(code) }
        if (i < 0) return null
        val body = GroupCipher.open(group, epoch, next + i, steps[i], identity, signer, packet) ?: return null
        // Only a genuine packet moves the chain: a forged code cannot make us skip or forget keys.
        lastOpened = next + i
        for (j in 0 until i) skipped[next + j] = steps[j]
        while (skipped.size > MAX_SKIPPED) skipped.remove(skipped.keys.first())
        chain = steps[i].next
        next += i + 1
        window = null
        return body
    }

    /** Missed packets still readable, oldest first. */
    fun missed(): List<Long> = skipped.keys.toList()

    /** Positions read so far as a receipt says them: the highest read (-1: none) and which of the 32 below are missing. */
    fun received(): Pair<Long, Long> {
        val highest = next - 1
        var missing = 0L
        for (i in 0 until 32) { val seq = highest - 1 - i; if (seq >= 0 && skipped.containsKey(seq)) missing = missing or (1L shl i) }
        return highest to missing
    }

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(identity); out.write(signer); out.write(chain); out.write(u32(next)); out.write(skipped.size)
        for ((seq, s) in skipped) { out.write(u32(seq)); out.write(s.key); out.write(s.nonce); out.write(s.code); out.write(s.next) }
        return out.toByteArray()
    }

    companion object {
        /** How far ahead a reader looks: a sender's packets lost in a row beyond this are not read. */
        const val WINDOW = 20
        const val MAX_SKIPPED = 64

        internal fun u32(n: Long) = ByteBuffer.allocate(4).putInt(n.toInt()).array()

        /** A reader saved with [encode]; [b] is positioned at its start and left after it. */
        internal fun decode(group: ByteArray, epoch: Long, b: ByteBuffer): GroupReader {
            fun raw(n: Int) = ByteArray(n).also { b.get(it) }
            val r = GroupReader(group, epoch, raw(33), raw(33), raw(32), b.int.toLong() and 0xFFFF_FFFFL)
            repeat(b.get().toInt() and 0xFF) {
                val seq = b.int.toLong() and 0xFFFF_FFFFL
                r.skipped[seq] = GroupCipher.Step(raw(32), raw(12), raw(8), raw(32))
            }
            require(r.skipped.size <= MAX_SKIPPED)
            return r
        }
    }
}

/**
 * This phone's own sending chain in one group epoch. Each [seal] uses a position once and forgets its key: the app
 * stores the member's state ([GroupMember.encode]) after sealing and before the packet leaves (review C8).
 */
class GroupWriter(
    private val group: ByteArray, private val epoch: Long, private val identity: ByteArray, private var chain: ByteArray,
    next: Long = 0, private val sign: (ByteArray) -> ByteArray,
) {
    var next: Long = next
        private set

    /** This phone's own next codes: asked for like everyone else's, so a hub cannot tell the sender (review C6). */
    fun expectedCodes(): List<ByteArray> {
        var c = chain
        return List(GroupReader.WINDOW) { GroupCipher.step(c).also { c = it.next }.code }
    }

    fun seal(body: ByteArray): ByteArray {
        require(next < 0xFFFF_FFFFL) { "this chain is used up: renew the group key" }
        val step = GroupCipher.step(chain)
        val packet = GroupCipher.seal(group, epoch, next, step, identity, body, sign)
        chain = step.next
        next++
        return packet
    }

    fun encode(): ByteArray = chain + GroupReader.u32(next)
}

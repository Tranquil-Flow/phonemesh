package org.phonemesh.core.group

/**
 * One member's sending chain as others read it (draft GK1): the chain key at the next unread position, codes to ask
 * a hub for, and at most [MAX_SKIPPED] keys kept for packets that arrive out of order. A used or skipped-past chain
 * key is gone; only one-use message keys of missed packets stay, so reading a packet twice is refused.
 */
class GroupReader(
    private val group: ByteArray, private val epoch: Long, private val identity: ByteArray,
    private var chain: ByteArray, next: Long = 0,
) {
    var next: Long = next
        private set
    private val skipped = LinkedHashMap<Long, GroupCipher.Step>()

    /** Codes of the next [window] packets and of every kept missed one: what to listen and ask for. */
    fun expectedCodes(window: Int = WINDOW): List<ByteArray> {
        val out = skipped.values.map { it.code }.toMutableList()
        var c = chain
        repeat(window) { val s = GroupCipher.step(c); out += s.code; c = s.next }
        return out
    }

    /** The body when [packet] is this member's, in order or late within the window; null otherwise (state unchanged). */
    fun open(packet: ByteArray): ByteArray? {
        val code = GroupCipher.codeOf(packet) ?: return null
        skipped.entries.firstOrNull { it.value.code.contentEquals(code) }?.let { (seq, step) ->
            val body = GroupCipher.open(group, epoch, seq, step, identity, packet) ?: return null
            skipped.remove(seq)
            return body
        }
        var c = chain
        val passed = ArrayList<Pair<Long, GroupCipher.Step>>()
        for (i in 0 until WINDOW) {
            val s = GroupCipher.step(c)
            val seq = next + i
            if (s.code.contentEquals(code)) {
                val body = GroupCipher.open(group, epoch, seq, s, identity, packet) ?: return null
                // Only a genuine packet moves the chain: a forged code cannot make us skip or forget keys.
                for ((n, step) in passed) skipped[n] = step
                while (skipped.size > MAX_SKIPPED) skipped.remove(skipped.keys.first())
                chain = s.next
                next = seq + 1
                return body
            }
            passed += seq to s
            c = s.next
        }
        return null
    }

    /** Missed packets still readable, oldest first (for tests and storage). */
    fun missed(): List<Long> = skipped.keys.toList()

    companion object {
        /** How far ahead a reader looks: a sender's packets lost in a row beyond this are not read. */
        const val WINDOW = 20
        const val MAX_SKIPPED = 64
    }
}

/** This phone's own sending chain in one group epoch. Each [seal] uses a position once and forgets its key. */
class GroupWriter(
    private val group: ByteArray, private val epoch: Long, private var chain: ByteArray, next: Long = 0,
    private val sign: (ByteArray) -> ByteArray,
) {
    var next: Long = next
        private set

    /** The chain key to store after the next [seal]: keep it durably before the packet leaves (no reuse after a crash). */
    fun chainAfterNext(): ByteArray = GroupCipher.step(chain).next

    fun seal(body: ByteArray): ByteArray {
        require(next < 0xFFFF_FFFFL) { "this chain is used up: renew the group key" }
        val step = GroupCipher.step(chain)
        val packet = GroupCipher.seal(group, epoch, next, step, body, sign)
        chain = step.next
        next++
        return packet
    }
}

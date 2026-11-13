package org.phonemesh.core.group

import org.phonemesh.core.crypto.P256

/**
 * The box keys a member keeps (v0.1.3 G3b; relayed group control design, "Box keys"; accounts decisions 79 and 80).
 *
 * Every member keeps, per member of the list held, the newest box key that member announced on its own radio chain
 * (op 9, [GroupBoxKey]). The announcing member is the chain the body travels on, signed with that member's group
 * signing key, so a key is bound to the member that announced it and to nobody outside the group.
 *
 * A key belongs to one member. A member that announces a key another member already announced is refused: two members
 * with one key would let one of them open the other's envelopes, and a removed member that still holds the private half
 * could open an envelope meant for whoever claimed its key. So a claim stays with its member for as long as the group
 * lives, even after that member rotates to a new key or leaves the list.
 *
 * "Newest" is the chain position the announcement was read at, not the order the packets arrived in: a reader may open
 * a later position before an earlier one inside its reading window, and the later key is the one that counts. A newer
 * epoch is newer than any position of the older one.
 *
 * The private half never comes here: it is sealed by the Vault on the phone that made the key. Only public keys are
 * kept, and they are saved with the group state (v0.1.3 G3d), never in a chat backup (decision 75).
 */
class GroupBoxKeys(private val onList: (ByteArray) -> Boolean) {
    private class Held(val key: ByteArray, val epoch: Long, val seq: Long)

    /** The newest key each member announced, with the epoch and chain position it was read at. */
    private val held = LinkedHashMap<List<Byte>, Held>()

    /** Which member each announced key belongs to: a key is never claimed twice. */
    private val claimed = LinkedHashMap<List<Byte>, List<Byte>>()

    /**
     * The key [owner] announced in [body], read at chain position [seq] of [epoch]. True when the key was taken (and
     * is now the newest that member announced), false when it was refused: the owner is not on the list held, the body
     * is malformed, the key belongs to another member, or it is older than the key that member already announced.
     */
    fun learn(owner: ByteArray, body: ByteArray, epoch: Long, seq: Long): Boolean {
        if (!onList(owner)) return false
        val key = GroupBoxKey.decode(body) ?: return false
        val o = owner.toList()
        val k = key.toList()
        val was = claimed[k]
        if (was != null && was != o) return false
        val mine = held[o]
        if (mine != null && !newer(epoch, seq, mine)) return false
        held[o] = Held(key.copyOf(), epoch, seq)
        claimed[k] = o
        return true
    }

    /** Whether a key read at [epoch]/[seq] is newer than the one held at [other]'s epoch and position. */
    private fun newer(epoch: Long, seq: Long, other: Held): Boolean =
        epoch > other.epoch || (epoch == other.epoch && seq > other.seq)

    /** The newest key [owner] announced, or null when it announced none. */
    fun keyOf(owner: ByteArray): ByteArray? = held[owner.toList()]?.key?.copyOf()

    /** The epoch the key [owner] announced was read in, or 0 when it announced none. */
    fun epochOf(owner: ByteArray): Long = held[owner.toList()]?.epoch ?: 0L

    /** [owner] left the list: its key is dropped. The claim stays, so no other member may take that key. */
    fun forget(owner: ByteArray) {
        held.remove(owner.toList())
    }

    /** The group is gone: nothing is kept, and no key is claimed. */
    fun clear() {
        held.clear()
        claimed.clear()
    }

    /** The members that announced a key, in the order they first did. */
    val owners: List<ByteArray> get() = held.keys.map { it.toByteArray() }

    /**
     * The keys as bytes, to be saved with the group state (v0.1.3 G3d): the key each member announced, with the epoch
     * and chain position it was read at, and which member each key belongs to. The claims are saved too: a key a member
     * announced and then rotated away is still that member's, so nobody else may take it.
     */
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u32(n: Long) = out.write(java.nio.ByteBuffer.allocate(4).putInt(n.toInt()).array())
        out.write(VERSION)
        out.write(held.size)
        for ((owner, h) in held) {
            out.write(owner.toByteArray())
            out.write(h.key)
            u32(h.epoch)
            u32(h.seq)
        }
        out.write(claimed.size)
        for ((key, owner) in claimed) {
            out.write(key.toByteArray())
            out.write(owner.toByteArray())
        }
        return out.toByteArray()
    }

    /**
     * The keys of [other], for loading a saved state into this one. A key of a member that is not on the list held is
     * dropped: only a member of the list held has one. This table's own list check is the authority, not the one the
     * saved bytes were written under. The claims are all kept: a key a member announced and then rotated away, or took
     * with it when it left, is still that member's, so nobody else may take it.
     */
    internal fun restore(other: GroupBoxKeys) {
        held.clear()
        for ((owner, h) in other.held) if (onList(owner.toByteArray())) held[owner] = h
        claimed.clear()
        claimed.putAll(other.claimed)
    }

    companion object {
        /** The saved form of the keys (v0.1.3 G3d). */
        const val VERSION = 1

        /**
         * The keys saved in [bytes], or null when they are not a saved set of keys. Nothing is filtered here: the saved
         * bytes are read as they were written. Which members are on the list held is decided when they are loaded (see
         * [restore]), by the list held at that time.
         */
        fun decode(bytes: ByteArray): GroupBoxKeys? = try {
            var at = 0
            fun raw(n: Int) = bytes.copyOfRange(at, at + n).also { at += n }
            fun u8() = raw(1)[0].toInt() and 0xFF
            fun u32() = java.nio.ByteBuffer.wrap(raw(4)).int.toLong() and 0xFFFF_FFFFL
            require(u8() == VERSION)
            val keys = GroupBoxKeys { true }
            repeat(u8()) {
                val owner = raw(P256.POINT)
                val key = raw(P256.POINT)
                val epoch = u32()
                val seq = u32()
                keys.held[owner.toList()] = Held(key.copyOf(), epoch, seq)
            }
            repeat(u8()) {
                val key = raw(P256.POINT)
                val owner = raw(P256.POINT)
                keys.claimed[key.toList()] = owner.toList()
            }
            require(at == bytes.size)
            keys
        } catch (_: RuntimeException) { null }
    }
}

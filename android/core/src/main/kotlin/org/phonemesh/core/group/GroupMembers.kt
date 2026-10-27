package org.phonemesh.core.group

import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import java.security.SecureRandom

/**
 * Group membership (draft GK2, decision 47; crypto review OPEN). An admin starts every epoch: a fresh secret goes to
 * each member over their own private session ([GroupKey], one small packet each), and the member list goes once to
 * everyone as signed group packets on the admin's new chain ([GroupRoster] in parts). A phone takes a new epoch only
 * from an admin of the epoch it has, only when the list it rebuilds matches the digest in its key, and only if it is
 * still on the list. Members need not know each other: the list carries their identity keys.
 */
class GroupRoster(val name: String, val colour: Int, val members: List<Member>) {
    class Member(identity: ByteArray, val admin: Boolean) {
        val identity: ByteArray = identity.copyOf()
        init { require(identity.size == P256.POINT && P256.decompress(identity) != null) { "an identity key on the curve" } }
    }

    init {
        val n = name.toByteArray(Charsets.UTF_8).size
        require(n in 1..MAX_NAME) { "a group name is 1..$MAX_NAME bytes" }
        require(colour in 0..0xFFFFFF)
        require(members.size in 2..MAX_MEMBERS) { "a group has 2..$MAX_MEMBERS people" }
        require(members.any { it.admin }) { "a group has an admin" }
        require(members.map { it.identity.toList() }.toSet().size == members.size) { "each person once" }
    }

    fun member(identity: ByteArray): Member? = members.firstOrNull { it.identity.contentEquals(identity) }
    fun isAdmin(identity: ByteArray) = member(identity)?.admin == true

    fun encode(): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        out.write(VERSION); out.write(n.size); out.write(n)
        out.write(colour ushr 16); out.write((colour ushr 8) and 0xFF); out.write(colour and 0xFF)
        out.write(members.size)
        for (m in members) { out.write(if (m.admin) 1 else 0); out.write(m.identity) }
        return out.toByteArray()
    }

    /** First 16 bytes of SHA-256 over the domain, group id, epoch and [encode]: what each [GroupKey] promises. */
    fun digest(group: ByteArray, epoch: Long): ByteArray =
        Bytes.sha256("phonemesh group roster v1".toByteArray(), group, Bytes.u32(epoch), encode()).copyOf(16)

    /** [encode] cut into the bodies of roster packets (op [GroupBody.ROSTER]), each fitting one group packet. */
    fun parts(): List<ByteArray> {
        val bytes = encode()
        val chunks = bytes.toList().chunked(PART).map { it.toByteArray() }
        return chunks.mapIndexed { i, c -> byteArrayOf(GroupBody.ROSTER.toByte(), i.toByte(), chunks.size.toByte()) + c }
    }

    companion object {
        const val VERSION = 1
        const val MAX_NAME = 24
        const val MAX_MEMBERS = 20
        /** Roster bytes per packet: the group body limit less op, index and count. */
        const val PART = GroupCipher.MAX_BODY - 3

        fun decode(bytes: ByteArray): GroupRoster? = try {
            var i = 0
            fun u8() = bytes[i++].toInt() and 0xFF
            require(u8() == VERSION)
            val n = u8()
            val name = String(bytes, i, n, Charsets.UTF_8).also { i += n }
            val colour = (u8() shl 16) or (u8() shl 8) or u8()
            val count = u8()
            val members = List(count) {
                val flags = u8()
                require(flags == 0 || flags == 1)
                Member(bytes.copyOfRange(i, i + P256.POINT), flags == 1).also { i += P256.POINT }
            }
            require(i == bytes.size)
            GroupRoster(name, colour, members).takeIf { it.encode().contentEquals(bytes) }
        } catch (_: RuntimeException) { null }
    }
}

/** The first byte of every group packet body: an app message, or a part of the member list. */
object GroupBody {
    const val MESSAGE = 1
    const val ROSTER = 2
}

/** A new epoch's secret for one member, sent over that member's private session (70 bytes). */
class GroupKey(group: ByteArray, val epoch: Long, secret: ByteArray, digest: ByteArray, val parts: Int) {
    val group: ByteArray = group.copyOf()
    val secret: ByteArray = secret.copyOf()
    val digest: ByteArray = digest.copyOf()

    init {
        require(group.size == GroupCipher.GROUP_ID && secret.size == GroupCipher.SECRET && digest.size == 16)
        require(epoch in 1..0xFFFF_FFFFL && parts in 1..MAX_PARTS)
    }

    fun encode(): ByteArray = byteArrayOf(VERSION.toByte()) + group + Bytes.u32(epoch) + secret + digest + byteArrayOf(parts.toByte())

    companion object {
        const val VERSION = 1
        const val SIZE = 1 + 16 + 4 + 32 + 16 + 1
        const val MAX_PARTS = 6

        fun decode(b: ByteArray): GroupKey? = try {
            require(b.size == SIZE && b[0].toInt() == VERSION)
            val epoch = ((b[17].toLong() and 0xFF) shl 24) or ((b[18].toLong() and 0xFF) shl 16) or
                ((b[19].toLong() and 0xFF) shl 8) or (b[20].toLong() and 0xFF)
            GroupKey(b.copyOfRange(1, 17), epoch, b.copyOfRange(21, 53), b.copyOfRange(53, 69), b[69].toInt() and 0xFF)
        } catch (_: RuntimeException) { null }
    }
}

/**
 * One phone's view of one group. [state] tells the app what to show; nothing here sends: the app sends what [start]
 * and [newEpoch] return (keys over private sessions, roster packets as group packets) after storing this state.
 */
class GroupMember(
    private val me: ByteArray,
    private val sign: (ByteArray) -> ByteArray,
    val group: ByteArray,
) {
    enum class State { INVITED, ACTIVE, REMOVED }

    var state: State = State.INVITED
        private set
    var epoch: Long = 0
        private set
    var roster: GroupRoster? = null
        private set
    private var writer: GroupWriter? = null
    private val readers = LinkedHashMap<List<Byte>, GroupReader>()

    private class Pending(val from: ByteArray, val key: GroupKey, val reader: GroupReader) {
        val parts = sortedMapOf<Int, ByteArray>()
    }
    private var pending: Pending? = null

    /** What an admin sends to start an epoch. */
    class Outgoing(val keys: Map<List<Byte>, ByteArray>, val rosterPackets: List<ByteArray>)

    /**
     * As an admin of the current roster (or creating the group): a fresh secret, a [GroupKey] for every other member
     * and the roster packets. This phone takes the epoch at once; a removed person gets no key.
     */
    fun newEpoch(next: GroupRoster, random: SecureRandom = SecureRandom()): Outgoing {
        val current = roster
        require(if (current == null) epoch == 0L else current.isAdmin(me)) { "only an admin starts an epoch" }
        require(next.isAdmin(me)) { "the admin starting an epoch stays an admin in it" }
        val nextEpoch = epoch + 1
        val secret = ByteArray(GroupCipher.SECRET).also(random::nextBytes)
        val parts = next.parts()
        val key = GroupKey(group, nextEpoch, secret, next.digest(group, nextEpoch), parts.size)
        commit(nextEpoch, next, secret)
        val packets = parts.map { writer!!.seal(it) }
        val keys = next.members.filterNot { it.identity.contentEquals(me) }.associate { it.identity.toList() to key.encode() }
        return Outgoing(keys, packets)
    }

    /**
     * A [GroupKey] that arrived over [from]'s private session. Kept until the roster packets complete it. Refused (false)
     * unless [from] is an admin of this group's current roster, or this phone has no epoch yet (an invitation), and
     * the epoch is newer than the one held.
     */
    fun receiveKey(from: ByteArray, key: GroupKey): Boolean {
        if (!key.group.contentEquals(group) || key.epoch <= epoch) return false
        val current = roster
        if (current != null && !current.isAdmin(from)) return false
        pending?.let { if (it.key.epoch > key.epoch) return false }
        val reader = GroupReader(group, key.epoch, from, GroupCipher.chain0(group, key.epoch, key.secret, from))
        pending = Pending(from.copyOf(), key, reader)
        return true
    }

    /** On-air codes of the group packets this phone can read next: what to listen and ask a hub for. */
    fun expectedCodes(): List<ByteArray> =
        readers.values.flatMap { it.expectedCodes() } + (pending?.reader?.expectedCodes() ?: emptyList())

    /** A message from a member: (sender identity, app body). Roster packets are handled here and return null. */
    fun receive(packet: ByteArray): Pair<ByteArray, ByteArray>? {
        pending?.let { p ->
            p.reader.open(packet)?.let { body -> rosterPart(p, body); return null }
        }
        if (state != State.ACTIVE) return null
        for ((id, reader) in readers) {
            val body = reader.open(packet) ?: continue
            if (body.isEmpty()) return null
            return when (body[0].toInt()) {
                GroupBody.MESSAGE -> id.toByteArray() to body.copyOfRange(1, body.size)
                else -> null
            }
        }
        return null
    }

    /** A packet carrying [body] (an app message) to everyone in the group. */
    fun send(body: ByteArray): ByteArray {
        check(state == State.ACTIVE) { "not an active member" }
        return writer!!.seal(byteArrayOf(GroupBody.MESSAGE.toByte()) + body)
    }

    private fun rosterPart(p: Pending, body: ByteArray) {
        if (body.size < 4 || body[0].toInt() != GroupBody.ROSTER) return
        val index = body[1].toInt() and 0xFF
        val total = body[2].toInt() and 0xFF
        if (total != p.key.parts || index >= total) return
        p.parts[index] = body.copyOfRange(3, body.size)
        if (p.parts.size < total) return
        val bytes = p.parts.values.fold(ByteArray(0)) { a, b -> a + b }
        val next = GroupRoster.decode(bytes)
        pending = null
        if (next == null || !next.digest(group, p.key.epoch).contentEquals(p.key.digest)) return
        // The sender must be an admin of the new list too, and for an existing group already was one (receiveKey).
        if (!next.isAdmin(p.from)) return
        if (next.member(me) == null) { removed(); return }
        commit(p.key.epoch, next, p.key.secret)
        // The admin's chain already carried the roster packets: keep reading it from there.
        readers[p.from.toList()] = p.reader
    }

    private fun commit(nextEpoch: Long, next: GroupRoster, secret: ByteArray) {
        readers.clear()
        for (m in next.members) {
            val ck = GroupCipher.chain0(group, nextEpoch, secret, m.identity)
            if (m.identity.contentEquals(me)) writer = GroupWriter(group, nextEpoch, ck, sign = sign)
            else readers[m.identity.toList()] = GroupReader(group, nextEpoch, m.identity, ck)
        }
        // The secret itself is not kept: only the chains derived from it (forward secrecy within the epoch, if every
        // member does the same).
        secret.fill(0)
        epoch = nextEpoch
        roster = next
        state = State.ACTIVE
    }

    private fun removed() {
        readers.clear(); writer = null; state = State.REMOVED
    }
}

package org.phonemesh.core.group

import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * The member list of one group epoch (draft GK3, decisions 47 and 66): a name, a colour, and for each person their
 * identity key, whether they are an admin, and their group signing key. Version 2 bytes: version | name length |
 * UTF-8 name | RGB | count | count x (flags | identity 33 | signing key 33).
 */
class GroupRoster(val name: String, val colour: Int, val members: List<Member>) {
    class Member(identity: ByteArray, val admin: Boolean, signer: ByteArray) {
        val identity: ByteArray = identity.copyOf()
        val signer: ByteArray = signer.copyOf()
        init {
            require(identity.size == P256.POINT && P256.decompress(identity) != null) { "an identity key on the curve" }
            require(signer.size == P256.POINT && P256.decompress(signer) != null) { "a signing key on the curve" }
        }
    }

    init {
        val n = name.toByteArray(Charsets.UTF_8).size
        require(n in 1..MAX_NAME) { "a group name is 1..$MAX_NAME bytes" }
        require(colour in 0..0xFFFFFF)
        require(members.size in 2..MAX_MEMBERS) { "a group has 2..$MAX_MEMBERS people" }
        require(members.any { it.admin }) { "a group has an admin" }
        require(members.map { it.identity.toList() }.toSet().size == members.size) { "each person once" }
        require(members.map { it.signer.toList() }.toSet().size == members.size) { "each signing key once" }
    }

    fun member(identity: ByteArray): Member? = members.firstOrNull { it.identity.contentEquals(identity) }
    fun isAdmin(identity: ByteArray) = member(identity)?.admin == true

    fun encode(): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(VERSION); out.write(n.size); out.write(n)
        out.write(colour ushr 16); out.write((colour ushr 8) and 0xFF); out.write(colour and 0xFF)
        out.write(members.size)
        for (m in members) { out.write(if (m.admin) 1 else 0); out.write(m.identity); out.write(m.signer) }
        return out.toByteArray()
    }

    /**
     * First 16 bytes of SHA-256 over the domain, group id, epoch, the previous list's digest ([parent]; zeros for the
     * first epoch) and [encode]: what each [GroupKey] promises, chained so two lists made at once are seen (review C3).
     */
    fun digest(group: ByteArray, epoch: Long, parent: ByteArray): ByteArray {
        require(parent.size == DIGEST)
        return Bytes.sha256("phonemesh group roster v2".toByteArray(), group, Bytes.u32(epoch), parent, encode()).copyOf(DIGEST)
    }

    /** [encode] cut into the bodies of roster packets (op [GroupBody.ROSTER]), each fitting one group packet. */
    fun parts(): List<ByteArray> {
        val chunks = encode().toList().chunked(PART).map { it.toByteArray() }
        return chunks.mapIndexed { i, c -> byteArrayOf(GroupBody.ROSTER.toByte(), i.toByte(), chunks.size.toByte()) + c }
    }

    companion object {
        const val VERSION = 2
        const val MAX_NAME = 24
        const val MAX_MEMBERS = 20
        const val DIGEST = 16
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
                val id = bytes.copyOfRange(i, i + P256.POINT).also { i += P256.POINT }
                val signer = bytes.copyOfRange(i, i + P256.POINT).also { i += P256.POINT }
                Member(id, flags == 1, signer)
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

/**
 * A new epoch's secret for one member, sent over that member's private session (body kind 10, 119 bytes): version 2 |
 * group 16 | epoch u32 | secret 32 | list digest 16 | parent digest 16 | list parts | the admin's signing key 33.
 */
class GroupKey(group: ByteArray, val epoch: Long, secret: ByteArray, digest: ByteArray, parent: ByteArray, val parts: Int, signer: ByteArray) {
    val group: ByteArray = group.copyOf()
    val secret: ByteArray = secret.copyOf()
    val digest: ByteArray = digest.copyOf()
    val parent: ByteArray = parent.copyOf()
    val signer: ByteArray = signer.copyOf()

    init {
        require(group.size == GroupCipher.GROUP_ID && secret.size == GroupCipher.SECRET)
        require(digest.size == GroupRoster.DIGEST && parent.size == GroupRoster.DIGEST)
        require(epoch in 1 until 0xFFFF_FFFFL && parts in 1..MAX_PARTS)
        require(signer.size == P256.POINT && P256.decompress(signer) != null)
    }

    fun encode(): ByteArray = byteArrayOf(VERSION.toByte()) + group + Bytes.u32(epoch) + secret + digest + parent +
        byteArrayOf(parts.toByte()) + signer

    /** Best-effort wipe of the secret held here (review C7). */
    fun wipe() = secret.fill(0)

    companion object {
        const val VERSION = 2
        const val SIZE = 1 + 16 + 4 + 32 + 16 + 16 + 1 + 33
        /** Twenty people with signing keys: 1370 list bytes in parts of 136. */
        const val MAX_PARTS = 11

        fun decode(b: ByteArray): GroupKey? = try {
            require(b.size == SIZE && b[0].toInt() == VERSION)
            val epoch = ByteBuffer.wrap(b, 17, 4).int.toLong() and 0xFFFF_FFFFL
            GroupKey(b.copyOfRange(1, 17), epoch, b.copyOfRange(21, 53), b.copyOfRange(53, 69), b.copyOfRange(69, 85),
                b[85].toInt() and 0xFF, b.copyOfRange(86, 119))
        } catch (_: RuntimeException) { null }
    }
}

/**
 * The other private group bodies (kinds 11-13), all sent over one person's private session:
 * - [Signer]: "my signing key for this group" (version 1 | group 16 | key 33). An admin's one is the invitation; a
 *   member's one, sent back to that admin, lets it put the member on the list (decision 66).
 * - [Removed]: "you are not on the list of epoch N" (version 1 | group 16 | epoch u32), with no secret (review C4).
 * - [Ask]: "send me epoch N's key and list again" (same layout), to the admin who started it (review C4).
 */
sealed class GroupControl(val kind: Int) {
    abstract val group: ByteArray
    abstract fun encode(): ByteArray

    class Signer(group: ByteArray, key: ByteArray) : GroupControl(SIGNER) {
        override val group: ByteArray = group.copyOf()
        val key: ByteArray = key.copyOf()
        init { require(group.size == GroupCipher.GROUP_ID && key.size == P256.POINT && P256.decompress(key) != null) }
        override fun encode() = byteArrayOf(1) + group + key
    }

    class Removed(group: ByteArray, val epoch: Long) : GroupControl(REMOVED) {
        override val group: ByteArray = group.copyOf()
        init { require(group.size == GroupCipher.GROUP_ID && epoch in 1 until 0xFFFF_FFFFL) }
        override fun encode() = byteArrayOf(1) + group + Bytes.u32(epoch)
    }

    class Ask(group: ByteArray, val epoch: Long) : GroupControl(ASK) {
        override val group: ByteArray = group.copyOf()
        init { require(group.size == GroupCipher.GROUP_ID && epoch in 1 until 0xFFFF_FFFFL) }
        override fun encode() = byteArrayOf(1) + group + Bytes.u32(epoch)
    }

    companion object {
        const val KEY = 10
        const val SIGNER = 11
        const val REMOVED = 12
        const val ASK = 13

        fun decode(kind: Int, b: ByteArray): GroupControl? = try {
            require(b.isNotEmpty() && b[0].toInt() == 1)
            when (kind) {
                SIGNER -> { require(b.size == 50); Signer(b.copyOfRange(1, 17), b.copyOfRange(17, 50)) }
                REMOVED, ASK -> {
                    require(b.size == 21)
                    val epoch = ByteBuffer.wrap(b, 17, 4).int.toLong() and 0xFFFF_FFFFL
                    if (kind == REMOVED) Removed(b.copyOfRange(1, 17), epoch) else Ask(b.copyOfRange(1, 17), epoch)
                }
                else -> null
            }
        } catch (_: RuntimeException) { null }

        /** Whether [b] is a well-formed private group body of [kind] (10-13). */
        fun valid(kind: Int, b: ByteArray): Boolean = if (kind == KEY) GroupKey.decode(b) != null else decode(kind, b) != null
    }
}

/**
 * One phone's view of one group (draft GK3). Nothing here sends: the app sends what [newEpoch], [introduce], [ask]
 * and [receiveControl] return, after storing [encode] (review C8). [me] is this phone's identity key, [mySigner] its
 * signing key for this group (public, 33 bytes) and [sign] signs with the private half, kept in the phone's vault.
 *
 * Rules (review C2-C4): a member takes only the next epoch (epoch + 1), whose key names the list it holds as parent,
 * from an admin of that list; one waiting key per admin, dropped after [KEEP_MS]. Two different lists for one epoch
 * put the group in [State.CONFLICT]: sending stops until an admin of the list held starts the next epoch from either
 * list. While a new key waits for its list, this phone stops sending in the old epoch, keeps what the new epoch's
 * first packets say, and still reads members of the new list in the old epoch for [KEEP_MS], marked as such.
 */
class GroupMember(
    me: ByteArray,
    mySigner: ByteArray,
    private val sign: (ByteArray) -> ByteArray,
    group: ByteArray,
) {
    private val me: ByteArray = me.copyOf()
    val mySigner: ByteArray = mySigner.copyOf()
    val group: ByteArray = group.copyOf()

    enum class State { INVITED, ACTIVE, CONFLICT, REMOVED }

    /** A message read from the group: who wrote it, the app body, and whether it came in the epoch before. */
    class Message(val sender: ByteArray, val body: ByteArray, val oldEpoch: Boolean = false)

    /** What an admin sends to start (or resend) an epoch: keys pairwise, the list as group packets, removal notices. */
    class Outgoing(val keys: Map<List<Byte>, ByteArray>, val rosterPackets: List<ByteArray>, val removed: Map<List<Byte>, ByteArray>)

    var state: State = State.INVITED
        private set
    var epoch: Long = 0
        private set
    var roster: GroupRoster? = null
        private set
    var digest: ByteArray = ByteArray(GroupRoster.DIGEST)
        private set
    private var previous: GroupRoster? = null
    private var previousDigest: ByteArray = ByteArray(GroupRoster.DIGEST)
    private var writer: GroupWriter? = null
    private val readers = LinkedHashMap<List<Byte>, GroupReader>()
    private var oldReaders = LinkedHashMap<List<Byte>, GroupReader>()
    private var oldEpoch = 0L
    private var oldUntil = 0L
    private val conflictDigests = ArrayList<ByteArray>()
    private val signers = LinkedHashMap<List<Byte>, ByteArray>()

    private class Pending(val from: ByteArray, val key: GroupKey, val reader: GroupReader, val at: Long) {
        val parts = sortedMapOf<Int, ByteArray>()
        val buffered = ArrayList<ByteArray>()
    }
    private val pendings = LinkedHashMap<List<Byte>, Pending>()

    /** The key and list packets of the epoch this phone started, kept [KEEP_MS] to answer [GroupControl.Ask]. */
    private class Issued(val key: GroupKey, val packets: List<ByteArray>, val at: Long)
    private var issued: Issued? = null

    /** Whether [send] works now: an active member with no newer key waiting for its list. */
    val canSend: Boolean get() = state == State.ACTIVE && writer != null && pendings.values.none { it.key.epoch > epoch }

    /** This phone's signing key for the group, to send to an admin (invitation reply) or to an invitee. */
    fun introduce(): ByteArray = GroupControl.Signer(group, mySigner).encode()

    /** Signing keys people sent this phone (an admin builds the next list from these). */
    fun signerOf(identity: ByteArray): ByteArray? = signers[identity.toList()]?.copyOf()

    /** "Send me epoch N's key and list again", for the admin whose key this phone is waiting on. */
    fun ask(): Pair<ByteArray, ByteArray>? = pendings.values.firstOrNull()?.let { it.from.copyOf() to GroupControl.Ask(group, it.key.epoch).encode() }

    /** A short code of the list held, to compare between phones (review C3): equal codes, same list. */
    fun safetyCode(): ByteArray =
        Bytes.sha256("phonemesh group safety v1".toByteArray(), group, Bytes.u32(epoch), digest).copyOf(8)

    /**
     * As an admin of the list held (or creating the group): a fresh secret, a [GroupKey] for every other member, the
     * list packets, and a removal notice for each person dropped. This phone takes the epoch at once.
     */
    fun newEpoch(next: GroupRoster, now: Long, random: SecureRandom = SecureRandom()): Outgoing {
        val current = roster
        require(if (current == null) epoch == 0L && state == State.INVITED else (state == State.ACTIVE || state == State.CONFLICT) && current.isAdmin(me)) {
            "only an admin of the list held starts an epoch"
        }
        require(pendings.values.none { it.key.epoch > epoch }) { "a newer key is waiting for its list" }
        require(epoch < 0xFFFF_FFFEL) { "no epoch left" }
        require(next.isAdmin(me) && next.member(me)!!.signer.contentEquals(mySigner)) { "the admin starting an epoch stays an admin in it" }
        val nextEpoch = epoch + 1
        val secret = ByteArray(GroupCipher.SECRET).also(random::nextBytes)
        val parts = next.parts()
        val nextDigest = next.digest(group, nextEpoch, digest)
        val key = GroupKey(group, nextEpoch, secret, nextDigest, digest, parts.size, mySigner)
        val before = current
        commit(nextEpoch, next, nextDigest, secret.copyOf(), now, null)
        val packets = parts.map { writer!!.seal(it) }
        issued = Issued(key, packets, now)
        val others = next.members.filterNot { it.identity.contentEquals(me) }
        val keys = others.associate { it.identity.toList() to key.encode() }
        val dropped = before?.members.orEmpty().filter { next.member(it.identity) == null }
            .associate { it.identity.toList() to GroupControl.Removed(group, nextEpoch).encode() }
        secret.fill(0)
        return Outgoing(keys, packets, dropped)
    }

    /**
     * A [GroupKey] that arrived over [from]'s private session. Waits for its list. Refused (false) unless it is for
     * the next epoch, names the list held as its parent (or, while in conflict, either list) and comes from an admin of
     * that list; an invitation (no epoch yet) may start anywhere. The same key again is accepted and changes nothing.
     */
    fun receiveKey(from: ByteArray, key: GroupKey, now: Long): Boolean {
        if (!key.group.contentEquals(group) || state == State.REMOVED) return false
        expire(now)
        if (key.epoch == epoch && key.digest.contentEquals(digest)) return true
        val current = roster
        if (current != null) {
            // The next epoch, from an admin of the list held, built on it (or, in a conflict, on either list).
            val next = key.epoch == epoch + 1 && current.isAdmin(from) &&
                (key.parent.contentEquals(digest) || (state == State.CONFLICT && conflictDigests.any { it.contentEquals(key.parent) }))
            // A rival list for the epoch held, from an admin of the list before: checked when its list completes.
            val rival = key.epoch == epoch && key.parent.contentEquals(previousDigest) && previous?.isAdmin(from) == true
            if (!next && !rival) return false
        }
        val id = from.toList()
        pendings[id]?.let { if (it.key.epoch == key.epoch && it.key.digest.contentEquals(key.digest)) return true }
        // One waiting key per admin. A second admin's different list for the same epoch waits too: the first to
        // complete is taken, and the other, once its own list completes and checks, shows as a conflict.
        val reader = GroupReader(group, key.epoch, from, key.signer, GroupCipher.chain0(group, key.epoch, key.secret, from))
        pendings[id] = Pending(from.copyOf(), key, reader, now)
        return true
    }

    /**
     * Another private group body from [from]: a signing key (kept), a removal notice from an admin of the list held
     * (this phone leaves), or a request to resend an epoch this phone started (answered when [from] is on its list).
     * Returns false when refused; [resend] is filled for an answered request.
     */
    fun receiveControl(from: ByteArray, control: GroupControl, now: Long): Pair<Boolean, Outgoing?> {
        if (!control.group.contentEquals(group)) return false to null
        expire(now)
        return when (control) {
            is GroupControl.Signer -> { signers[from.toList()] = control.key.copyOf(); true to null }
            is GroupControl.Removed -> {
                val current = roster ?: return false to null
                if (control.epoch != epoch + 1 || !current.isAdmin(from)) return false to null
                removed(); true to null
            }
            is GroupControl.Ask -> {
                val i = issued ?: return false to null
                val list = roster ?: return false to null
                if (control.epoch != i.key.epoch || i.key.epoch != epoch || list.member(from) == null) return false to null
                true to Outgoing(mapOf(from.toList() to i.key.encode()), i.packets, emptyMap())
            }
        }
    }

    /** On-air codes this phone can read next, and its own (review C6): what to listen and ask a hub for. */
    fun expectedCodes(now: Long): List<ByteArray> {
        expire(now)
        return readers.values.flatMap { it.expectedCodes() } + (writer?.expectedCodes() ?: emptyList()) +
            pendings.values.flatMap { it.reader.expectedCodes() } + oldReaders.values.flatMap { it.expectedCodes() }
    }

    /** Messages a packet brings: one message, the messages a just-completed list releases, or none. */
    fun receive(packet: ByteArray, now: Long): List<Message> {
        expire(now)
        for (p in pendings.values.toList()) {
            val body = p.reader.open(packet) ?: continue
            if (body.isNotEmpty() && body[0].toInt() == GroupBody.ROSTER) return rosterPart(p, body, now)
            // The admin's first message may come before the list: keep it for when the list completes (review C4).
            if (body.isNotEmpty() && body[0].toInt() == GroupBody.MESSAGE && p.buffered.size < MAX_BUFFERED) p.buffered += body
            return emptyList()
        }
        if (state != State.ACTIVE && state != State.CONFLICT) return emptyList()
        for ((id, reader) in readers) {
            val body = reader.open(packet) ?: continue
            return message(id, body, false)
        }
        for ((id, reader) in oldReaders) {
            val body = reader.open(packet) ?: continue
            return message(id, body, true)
        }
        return emptyList()
    }

    private fun message(id: List<Byte>, body: ByteArray, old: Boolean): List<Message> =
        if (body.isNotEmpty() && body[0].toInt() == GroupBody.MESSAGE) listOf(Message(id.toByteArray(), body.copyOfRange(1, body.size), old)) else emptyList()

    /** A packet carrying [body] (an app message) to everyone in the group. Store [encode] before it leaves. */
    fun send(body: ByteArray): ByteArray {
        check(canSend) { if (state == State.CONFLICT) "the group has two lists: an admin must start the next epoch" else "not able to send now" }
        return writer!!.seal(byteArrayOf(GroupBody.MESSAGE.toByte()) + body)
    }

    private fun rosterPart(p: Pending, body: ByteArray, now: Long): List<Message> {
        if (body.size < 4) return emptyList()
        val index = body[1].toInt() and 0xFF
        val total = body[2].toInt() and 0xFF
        if (total != p.key.parts || index >= total) return emptyList()
        p.parts[index] = body.copyOfRange(3, body.size)
        if (p.parts.size < total) return emptyList()
        pendings.remove(p.from.toList())
        try {
            if (p.key.epoch < epoch) return emptyList()
            val bytes = p.parts.values.fold(ByteArray(0)) { a, b -> a + b }
            val next = GroupRoster.decode(bytes) ?: return emptyList()
            if (!next.digest(group, p.key.epoch, p.key.parent).contentEquals(p.key.digest)) return emptyList()
            // The sender is an admin of the new list too, under the signing key its key named.
            val admin = next.member(p.from)
            if (admin == null || !admin.admin || !admin.signer.contentEquals(p.key.signer)) return emptyList()
            // A checked rival list for the epoch held: a conflict (review C3), whether or not it keeps this phone.
            if (roster != null && p.key.epoch == epoch) {
                if (!p.key.digest.contentEquals(digest)) {
                    if (conflictDigests.none { it.contentEquals(digest) }) conflictDigests += digest.copyOf()
                    if (conflictDigests.none { it.contentEquals(p.key.digest) }) conflictDigests += p.key.digest.copyOf()
                    state = State.CONFLICT
                }
                return emptyList()
            }
            val mine = next.member(me)
            if (mine == null) { removed(); return emptyList() }
            if (!mine.signer.contentEquals(mySigner)) return emptyList()
            commit(p.key.epoch, next, p.key.digest, p.key.secret.copyOf(), now, p)
            return p.buffered.map { Message(p.from.copyOf(), it.copyOfRange(1, it.size)) }
        } finally {
            p.key.wipe()
        }
    }

    private fun commit(nextEpoch: Long, next: GroupRoster, nextDigest: ByteArray, secret: ByteArray, now: Long, from: Pending?) {
        // Members of the new list may still write in the old epoch for a while: keep reading them, marked old.
        oldReaders = LinkedHashMap(readers.filterKeys { id -> next.member(id.toByteArray()) != null })
        oldEpoch = epoch
        oldUntil = now + KEEP_MS
        readers.clear()
        for (m in next.members) {
            val ck = GroupCipher.chain0(group, nextEpoch, secret, m.identity)
            if (m.identity.contentEquals(me)) writer = GroupWriter(group, nextEpoch, me, ck, sign = sign)
            else readers[m.identity.toList()] = GroupReader(group, nextEpoch, m.identity, m.signer, ck)
        }
        // The admin's chain already carried the list packets: keep reading it from there.
        from?.let { readers[it.from.toList()] = it.reader }
        // Waiting keys for older epochs are spent; rivals for this one stay until their lists check.
        pendings.values.removeAll { (it.key.epoch < nextEpoch).also { old -> if (old) it.key.wipe() } }
        // The secret itself is not kept: only the chains derived from it.
        secret.fill(0)
        previous = roster
        previousDigest = digest
        epoch = nextEpoch
        roster = next
        digest = nextDigest.copyOf()
        conflictDigests.clear()
        if (issued?.key?.epoch != nextEpoch) issued = null
        state = State.ACTIVE
    }

    private fun removed() {
        readers.clear(); oldReaders.clear(); pendings.clear(); writer = null; issued = null; state = State.REMOVED
    }

    private fun expire(now: Long) {
        if (oldReaders.isNotEmpty() && now > oldUntil) oldReaders.clear()
        pendings.values.removeAll { (now - it.at > KEEP_MS).also { old -> if (old) it.key.wipe() } }
        issued?.let { if (now - it.at > KEEP_MS) { it.key.wipe(); issued = null } }
    }

    // ---- saved state (review C8): everything above, so a phone resumes exactly where it was

    fun encode(): ByteArray {
        val o = ByteArrayOutputStream()
        fun u8(n: Int) = o.write(n)
        fun u32(n: Long) = o.write(GroupReader.u32(n))
        fun u64(n: Long) = o.write(ByteBuffer.allocate(8).putLong(n).array())
        fun blob(b: ByteArray?) { val x = b ?: ByteArray(0); o.write(ByteBuffer.allocate(2).putShort(x.size.toShort()).array()); o.write(x) }
        u8(STATE_VERSION); u8(state.ordinal); u32(epoch)
        blob(roster?.encode()); o.write(digest); blob(previous?.encode()); o.write(previousDigest)
        blob(writer?.encode())
        u8(readers.size); for (r in readers.values) blob(r.encode())
        u32(oldEpoch); u64(oldUntil); u8(oldReaders.size); for (r in oldReaders.values) blob(r.encode())
        u8(conflictDigests.size); for (d in conflictDigests) o.write(d)
        u8(signers.size); for ((id, k) in signers) { o.write(id.toByteArray()); o.write(k) }
        u8(pendings.size)
        for (p in pendings.values) {
            o.write(p.from); o.write(p.key.encode()); u64(p.at); blob(p.reader.encode())
            u8(p.parts.size); for ((i, part) in p.parts) { u8(i); blob(part) }
            u8(p.buffered.size); for (b in p.buffered) blob(b)
        }
        val i = issued
        if (i == null) u8(0) else { u8(1); o.write(i.key.encode()); u64(i.at); u8(i.packets.size); for (pk in i.packets) blob(pk) }
        return o.toByteArray()
    }

    companion object {
        /** How long a waiting key, old-epoch reading and a started epoch's resend copy last: the resend horizon. */
        const val KEEP_MS = 72 * 3_600_000L
        const val MAX_BUFFERED = 32
        private const val STATE_VERSION = 1

        /** A member saved with [encode]; null when the bytes are not a saved member of [group]. */
        fun decode(me: ByteArray, mySigner: ByteArray, sign: (ByteArray) -> ByteArray, group: ByteArray, bytes: ByteArray): GroupMember? = try {
            val b = ByteBuffer.wrap(bytes)
            fun raw(n: Int) = ByteArray(n).also { b.get(it) }
            fun u8() = b.get().toInt() and 0xFF
            fun u32() = b.int.toLong() and 0xFFFF_FFFFL
            fun blob(): ByteArray = raw(b.short.toInt() and 0xFFFF)
            require(u8() == STATE_VERSION)
            val m = GroupMember(me, mySigner, sign, group)
            m.state = State.entries[u8()]
            m.epoch = u32()
            m.roster = blob().takeIf { it.isNotEmpty() }?.let { requireNotNull(GroupRoster.decode(it)) }
            m.digest = raw(GroupRoster.DIGEST)
            m.previous = blob().takeIf { it.isNotEmpty() }?.let { requireNotNull(GroupRoster.decode(it)) }
            m.previousDigest = raw(GroupRoster.DIGEST)
            blob().takeIf { it.isNotEmpty() }?.let { w ->
                require(w.size == 36)
                m.writer = GroupWriter(group, m.epoch, me, w.copyOfRange(0, 32), ByteBuffer.wrap(w, 32, 4).int.toLong() and 0xFFFF_FFFFL, sign)
            }
            repeat(u8()) { val r = GroupReader.decode(group, m.epoch, ByteBuffer.wrap(blob())); m.readers[r.identity.toList()] = r }
            m.oldEpoch = u32(); m.oldUntil = b.long
            repeat(u8()) { val r = GroupReader.decode(group, m.oldEpoch, ByteBuffer.wrap(blob())); m.oldReaders[r.identity.toList()] = r }
            repeat(u8()) { m.conflictDigests += raw(GroupRoster.DIGEST) }
            repeat(u8()) { m.signers[raw(P256.POINT).toList()] = raw(P256.POINT) }
            repeat(u8()) {
                val from = raw(P256.POINT)
                val key = requireNotNull(GroupKey.decode(raw(GroupKey.SIZE)))
                val at = b.long
                val p = Pending(from, key, GroupReader.decode(group, key.epoch, ByteBuffer.wrap(blob())), at)
                repeat(u8()) { val i = u8(); p.parts[i] = blob() }
                repeat(u8()) { p.buffered += blob() }
                m.pendings[from.toList()] = p
            }
            if (u8() == 1) {
                val key = requireNotNull(GroupKey.decode(raw(GroupKey.SIZE)))
                val at = b.long
                m.issued = Issued(key, List(u8()) { blob() }, at)
            }
            require(!b.hasRemaining())
            m
        } catch (_: RuntimeException) { null }
    }
}

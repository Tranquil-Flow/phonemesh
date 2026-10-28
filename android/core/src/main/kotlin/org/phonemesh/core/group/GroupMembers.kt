package org.phonemesh.core.group

import org.phonemesh.core.account.Avatar
import org.phonemesh.core.account.EmojiAvatars
import org.phonemesh.core.account.Profile
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/** A group picture as the list and invitations carry it: kind (0 letter, 1 emoji, 2 photo) | length | bytes. */
object GroupAvatar {
    fun encode(avatar: Avatar): ByteArray = when (avatar) {
        Avatar.Letter -> byteArrayOf(Profile.LETTER.toByte(), 0)
        is Avatar.Emoji -> avatar.text.toByteArray(Charsets.UTF_8).let { byteArrayOf(Profile.EMOJI.toByte(), it.size.toByte()) + it }
        is Avatar.Photo -> byteArrayOf(Profile.PHOTO.toByte(), Profile.PHOTO_ID.toByte()) + avatar.id
    }

    /** The avatar at [at] in [b] and the bytes it took; throws on anything else. */
    fun decode(b: ByteArray, at: Int): Pair<Avatar, Int> {
        val kind = b[at].toInt() and 0xFF
        val n = b[at + 1].toInt() and 0xFF
        require(at + 2 + n <= b.size)
        val bytes = b.copyOfRange(at + 2, at + 2 + n)
        val avatar = when (kind) {
            Profile.LETTER -> { require(n == 0); Avatar.Letter }
            Profile.EMOJI -> { require(n in 1..Profile.MAX_AVATAR); Avatar.Emoji(String(bytes, Charsets.UTF_8).also { require(it in EmojiAvatars.all) }) }
            Profile.PHOTO -> { require(n == Profile.PHOTO_ID); Avatar.Photo(bytes) }
            else -> throw IllegalArgumentException("avatar kind")
        }
        require(encode(avatar).contentEquals(b.copyOfRange(at, at + 2 + n)))
        return avatar to 2 + n
    }
}

/**
 * The member list of one group epoch (draft GK4, decisions 47, 66, 69, 72, 74): a name, a colour, a picture, whether
 * new members see recent messages, and for each person their identity key, whether they are an admin, and their group
 * signing key. Version 3 bytes: version | name length | UTF-8 name | RGB | picture kind | picture length | picture |
 * flags (1: new members see recent messages) | count | count x (flags | identity 33 | signing key 33).
 */
class GroupRoster(
    val name: String, val colour: Int, val members: List<Member>,
    val avatar: Avatar = Avatar.Letter, val recentForNew: Boolean = false,
) {
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
    fun indexOf(identity: ByteArray): Int = members.indexOfFirst { it.identity.contentEquals(identity) }
    fun isAdmin(identity: ByteArray) = member(identity)?.admin == true

    fun encode(): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(VERSION); out.write(n.size); out.write(n)
        out.write(colour ushr 16); out.write((colour ushr 8) and 0xFF); out.write(colour and 0xFF)
        out.write(GroupAvatar.encode(avatar))
        out.write(if (recentForNew) 1 else 0)
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
        return Bytes.sha256("phonemesh group roster v3".toByteArray(), group, Bytes.u32(epoch), parent, encode()).copyOf(DIGEST)
    }

    /** [encode] cut into the bodies of roster packets (op [GroupBody.ROSTER]), each fitting one group packet. */
    fun parts(): List<ByteArray> {
        val chunks = encode().toList().chunked(PART).map { it.toByteArray() }
        return chunks.mapIndexed { i, c -> byteArrayOf(GroupBody.ROSTER.toByte(), i.toByte(), chunks.size.toByte()) + c }
    }

    companion object {
        const val VERSION = 3
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
            require(i + n <= bytes.size)
            val name = String(bytes, i, n, Charsets.UTF_8).also { i += n }
            val colour = (u8() shl 16) or (u8() shl 8) or u8()
            val (avatar, used) = GroupAvatar.decode(bytes, i).also { i += it.second }
            check(used > 0)
            val flags = u8()
            require(flags == 0 || flags == 1)
            val count = u8()
            val members = List(count) {
                val f = u8()
                require(f == 0 || f == 1)
                val id = bytes.copyOfRange(i, i + P256.POINT).also { i += P256.POINT }
                val signer = bytes.copyOfRange(i, i + P256.POINT).also { i += P256.POINT }
                Member(id, f == 1, signer)
            }
            require(i == bytes.size)
            GroupRoster(name, colour, members, avatar, flags == 1).takeIf { it.encode().contentEquals(bytes) }
        } catch (_: RuntimeException) { null }
    }
}

/**
 * The first byte of every group packet body (draft GK4). On a member's radio chain: [MESSAGE] (an app body, the same
 * bytes as a private one), [ROSTER] (admins only), [PROFILE] (the member's own name and picture, decision 72),
 * [RECEIPT] ([GroupReceipt]) and [GROUP_PHOTO]'s head. On the hub chain: picture parts, [SHARED] recent messages
 * (decision 74), [MEMBER_PHOTO] and [GROUP_PHOTO] pieces (decisions 64, 69).
 */
object GroupBody {
    const val MESSAGE = 1
    const val ROSTER = 2
    const val PROFILE = 3
    const val RECEIPT = 4
    const val SHARED = 5
    const val MEMBER_PHOTO = 6
    const val GROUP_PHOTO = 7
    const val LAST = GROUP_PHOTO

    /**
     * Whether [op] may travel on the radio chain, or (with [hub]) on the hub chain (draft GK4, review G9-12): a message
     * and the group photo on either (their parts go through a hub), a profile and a receipt on the radio chain only,
     * recent messages and a member's photo on the hub chain only. A list part is made by the engine, never sealed here.
     */
    fun allowed(op: Int, hub: Boolean): Boolean = when (op) {
        MESSAGE, GROUP_PHOTO -> true
        PROFILE, RECEIPT -> !hub
        SHARED, MEMBER_PHOTO -> hub
        else -> false
    }
}

/**
 * "These are the radio-chain packets I have read" (op [GroupBody.RECEIPT], decision 72 e): for each sender, their
 * index in the epoch's list, the highest position read and which of the 32 positions below it are missing. Bytes:
 * count | count x (index | highest u32 | missing u32). One packet holds [MAX_ENTRIES].
 */
class GroupReceipt(val entries: List<Entry>) {
    class Entry(val index: Int, val highest: Long, val missing: Long) {
        init { require(index in 0 until GroupRoster.MAX_MEMBERS && highest in 0..0xFFFF_FFFEL && missing in 0..0xFFFF_FFFFL) }

        /** Whether the sender's packet at [seq] was read: at or below the highest, and not among the missing. */
        fun covers(seq: Long): Boolean {
            if (seq < 0 || seq > highest) return false
            if (seq == highest) return true
            val gap = highest - 1 - seq
            return gap < 32 && (missing ushr gap.toInt()) and 1L == 0L
        }
    }

    init {
        require(entries.size in 1..MAX_ENTRIES)
        require(entries.map { it.index }.toSet().size == entries.size) { "each sender once" }
    }

    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(1 + entries.size * ENTRY)
        b.put(entries.size.toByte())
        for (e in entries) { b.put(e.index.toByte()); b.putInt(e.highest.toInt()); b.putInt(e.missing.toInt()) }
        return b.array()
    }

    companion object {
        private const val ENTRY = 9
        const val MAX_ENTRIES = (GroupCipher.MAX_BODY - 2) / ENTRY

        fun decode(b: ByteArray): GroupReceipt? = try {
            val n = b[0].toInt() and 0xFF
            require(b.size == 1 + n * ENTRY)
            val buf = ByteBuffer.wrap(b, 1, n * ENTRY)
            GroupReceipt(List(n) { Entry(buf.get().toInt() and 0xFF, buf.int.toLong() and 0xFFFF_FFFFL, buf.int.toLong() and 0xFFFF_FFFFL) })
        } catch (_: RuntimeException) { null }
    }
}

/**
 * A recent message an admin shares with people who just joined (op [GroupBody.SHARED], decision 74), on the admin's hub
 * chain: author account id 8 | time in seconds u32 | the message's app body. Shown as shared by that admin.
 */
class GroupShared(author: ByteArray, val atSeconds: Long, body: ByteArray) {
    val author: ByteArray = author.copyOf()
    val body: ByteArray = body.copyOf()
    init {
        require(author.size == AUTHOR && atSeconds in 0..0xFFFF_FFFFL)
        require(body.size in 1..MAX_BODY) { "a shared message is 1..$MAX_BODY bytes" }
    }

    fun encode(): ByteArray = author + Bytes.u32(atSeconds) + body

    companion object {
        const val AUTHOR = 8
        /** The app body that fits: the group body less op, author and time. */
        const val MAX_BODY = GroupCipher.MAX_BODY - 1 - AUTHOR - 4

        fun decode(b: ByteArray): GroupShared? = try {
            GroupShared(b.copyOfRange(0, AUTHOR), ByteBuffer.wrap(b, AUTHOR, 4).int.toLong() and 0xFFFF_FFFFL, b.copyOfRange(AUTHOR + 4, b.size))
        } catch (_: RuntimeException) { null }
    }
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
        /** Twenty people with signing keys and the longest name and picture: at most 1,407 list bytes in parts of 136. */
        const val MAX_PARTS = 11

        fun decode(b: ByteArray): GroupKey? = try {
            require(b.size == SIZE && b[0].toInt() == VERSION)
            val epoch = ByteBuffer.wrap(b, 17, 4).int.toLong() and 0xFFFF_FFFFL
            GroupKey(b.copyOfRange(1, 17), epoch, b.copyOfRange(21, 53), b.copyOfRange(53, 69), b.copyOfRange(69, 85),
                b[85].toInt() and 0xFF, b.copyOfRange(86, 119))
        } catch (_: RuntimeException) { null }

        /** A short tag of an epoch secret, kept instead of it, so two keys with one list but different secrets are seen. */
        internal fun tag(group: ByteArray, epoch: Long, secret: ByteArray): ByteArray =
            Bytes.sha256("phonemesh group secret tag v1".toByteArray(), group, Bytes.u32(epoch), secret).copyOf(16)
    }
}

/**
 * The other private group bodies (kinds 11-15), all sent over one person's private session:
 * - [Signer]: "my signing key for this group" (version 1 | group 16 | key 33): the reply that says Join (decision 66).
 * - [Removed]: "you are not on the list of epoch N" (version 1 | group 16 | epoch u32), with no secret (review C4).
 * - [Ask]: "send me epoch N's key and list again" (same layout), to the admin who started it (review C4).
 * - [Invite]: an admin asks someone to join (version 1 | group 16 | the admin's signing key 33 | people invited u8 |
 *   name length | name | RGB | picture kind | picture length | picture), shown with Join and Ignore (decision 72 c).
 * - [Leave]: "I left" (version 1 | group 16 | epoch u32), to one admin, who starts the next epoch without the sender.
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

    class Invite(group: ByteArray, signer: ByteArray, val people: Int, val name: String, val colour: Int, val avatar: Avatar) : GroupControl(INVITE) {
        override val group: ByteArray = group.copyOf()
        val signer: ByteArray = signer.copyOf()
        init {
            require(group.size == GroupCipher.GROUP_ID && signer.size == P256.POINT && P256.decompress(signer) != null)
            require(people in 2..GroupRoster.MAX_MEMBERS && name.toByteArray(Charsets.UTF_8).size in 1..GroupRoster.MAX_NAME && colour in 0..0xFFFFFF)
        }
        override fun encode(): ByteArray {
            val n = name.toByteArray(Charsets.UTF_8)
            return byteArrayOf(1) + group + signer + byteArrayOf(people.toByte(), n.size.toByte()) + n +
                byteArrayOf((colour ushr 16).toByte(), (colour ushr 8).toByte(), colour.toByte()) + GroupAvatar.encode(avatar)
        }
    }

    class Leave(group: ByteArray, val epoch: Long) : GroupControl(LEAVE) {
        override val group: ByteArray = group.copyOf()
        init { require(group.size == GroupCipher.GROUP_ID && epoch in 1 until 0xFFFF_FFFFL) }
        override fun encode() = byteArrayOf(1) + group + Bytes.u32(epoch)
    }

    companion object {
        const val KEY = 10
        const val SIGNER = 11
        const val REMOVED = 12
        const val ASK = 13
        const val INVITE = 14
        const val LEAVE = 15

        fun decode(kind: Int, b: ByteArray): GroupControl? = try {
            require(b.isNotEmpty() && b[0].toInt() == 1)
            when (kind) {
                SIGNER -> { require(b.size == 50); Signer(b.copyOfRange(1, 17), b.copyOfRange(17, 50)) }
                REMOVED, ASK, LEAVE -> {
                    require(b.size == 21)
                    val epoch = ByteBuffer.wrap(b, 17, 4).int.toLong() and 0xFFFF_FFFFL
                    val g = b.copyOfRange(1, 17)
                    when (kind) { REMOVED -> Removed(g, epoch); ASK -> Ask(g, epoch); else -> Leave(g, epoch) }
                }
                INVITE -> {
                    val people = b[50].toInt() and 0xFF
                    val n = b[51].toInt() and 0xFF
                    val name = String(b, 52, n, Charsets.UTF_8)
                    var i = 52 + n
                    val colour = ((b[i].toInt() and 0xFF) shl 16) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() and 0xFF)
                    i += 3
                    val (avatar, used) = GroupAvatar.decode(b, i)
                    require(i + used == b.size)
                    Invite(b.copyOfRange(1, 17), b.copyOfRange(17, 50), people, name, colour, avatar).takeIf { it.encode().contentEquals(b) }
                }
                else -> null
            }
        } catch (_: RuntimeException) { null }

        /** Whether [b] is a well-formed private group body of [kind] (10-15). */
        fun valid(kind: Int, b: ByteArray): Boolean = if (kind == KEY) GroupKey.decode(b) != null else decode(kind, b) != null
    }
}

/**
 * One phone's view of one group (draft GK4). Nothing here sends: the app sends what [newEpoch], [introduce], [ask],
 * [leave], [seal] and [receiveControl] return, after storing [encode] (review C8). [me] is this phone's identity key,
 * [mySigner] its signing key for this group (public, 33 bytes) and [sign] signs with the private half, kept in the
 * phone's vault. [inviter] is the admin whose invitation this phone joined: the first key is taken only from it.
 *
 * Rules (review C2-C4): a member takes only the next epoch (epoch + 1), whose key names the list it holds as parent,
 * from an admin of that list; one waiting key per admin, dropped after [KEEP_MS]. Two different lists for one epoch,
 * or one list with two different secrets, put the group in [State.CONFLICT]: sending stops until an admin of the list
 * held starts the next epoch. While a new key waits for its list, this phone stops sending in the old epoch, keeps
 * what the new epoch's first packets say, and still reads members of the new list in the old epoch for [KEEP_MS].
 *
 * Each member has two sending chains per epoch: the radio chain, and a hub chain for what goes only through a hub, so
 * hub-only packets never open gaps that radio-only readers would have to skip.
 */
class GroupMember(
    me: ByteArray,
    mySigner: ByteArray,
    private val sign: (ByteArray) -> ByteArray,
    group: ByteArray,
    inviter: ByteArray? = null,
) {
    private val me: ByteArray = me.copyOf()
    val mySigner: ByteArray = mySigner.copyOf()
    val group: ByteArray = group.copyOf()
    /** The admin whose invitation this phone joined; null for the phone that made the group. */
    val inviter: ByteArray? = inviter?.copyOf()

    enum class State { INVITED, ACTIVE, CONFLICT, REMOVED, LEFT }

    /**
     * Something read from the group: who sent it, its op ([GroupBody]) and body, whether it came in the epoch before,
     * whether on the hub chain, and its epoch and chain position (what receipts name).
     */
    class Message(
        val sender: ByteArray, val body: ByteArray, val oldEpoch: Boolean = false,
        val op: Int = GroupBody.MESSAGE, val hub: Boolean = false, val epoch: Long = 0, val seq: Long = -1,
    )

    /** A sealed packet and where it sits: what this phone stores to match receipts against. */
    class Sealed(val packet: ByteArray, val epoch: Long, val seq: Long, val hub: Boolean)

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
    /** The list before the one held (null for the first): who joined in this epoch, and what old receipts name. */
    var previous: GroupRoster? = null
        private set
    private var previousDigest: ByteArray = ByteArray(GroupRoster.DIGEST)
    private var secretTag: ByteArray = ByteArray(16)
    /** When the epoch held was taken: a rival key for it is believed only for [RIVAL_MS] after that (review G9-4). */
    private var committedAt = 0L
    /** The epoch in which this phone first stood on a list: recent messages count only from the admin of that epoch (review G9-9). */
    private var joinedAt = 0L
    /** Recent messages taken so far, at most [GroupPost.RECENT_MAX] (review G9-9). */
    private var sharedTaken = 0
    private var writer: GroupWriter? = null
    private var hubWriter: GroupWriter? = null
    private val readers = LinkedHashMap<List<Byte>, GroupReader>()
    private val hubReaders = LinkedHashMap<List<Byte>, GroupReader>()
    private var oldReaders = LinkedHashMap<List<Byte>, GroupReader>()
    private var oldHubReaders = LinkedHashMap<List<Byte>, GroupReader>()
    private var oldEpoch = 0L
    private var oldUntil = 0L
    private val conflictDigests = ArrayList<ByteArray>()
    private val signers = LinkedHashMap<List<Byte>, ByteArray>()
    /** Senders read on the radio chain since the last [receipt]. */
    private val unreceipted = LinkedHashSet<List<Byte>>()
    /** Members who said they left, for an admin to drop at the next epoch. */
    private val leaving = LinkedHashSet<List<Byte>>()

    private class Pending(val from: ByteArray, val key: GroupKey, val reader: GroupReader, val at: Long) {
        val parts = sortedMapOf<Int, ByteArray>()
        val buffered = ArrayList<Pair<Long, ByteArray>>()
    }
    private val pendings = LinkedHashMap<List<Byte>, Pending>()
    /**
     * Keys that came before the epoch they build on (a later key overtook an earlier list): kept, unchecked, at most
     * [MAX_EARLY], and checked like any key once this phone holds the epoch before theirs.
     */
    private val early = ArrayList<Pending>()

    /** Whether the last [receive] read the packet (so the state changed and must be saved), even with nothing to show. */
    var took: Boolean = false
        private set

    /** The key and list packets of the epoch this phone started, kept [KEEP_MS] to answer [GroupControl.Ask]. */
    private class Issued(val key: GroupKey, val packets: List<ByteArray>, val at: Long) {
        /** Members already answered for this epoch: each is answered once (review G9-11). */
        val asked = LinkedHashSet<List<Byte>>()
    }
    private var issued: Issued? = null

    /** Whether [send] works now: an active member with no newer key waiting for its list. */
    val canSend: Boolean get() = state == State.ACTIVE && writer != null && pendings.values.none { it.key.epoch > epoch }

    /** Whether this phone is new in the epoch held (it was not on the list before): it may get recent messages. */
    val joinedThisEpoch: Boolean get() = roster != null && previous?.member(me) == null

    /** The epoch in which this phone first stood on a list (0 when it does not know). */
    val joinedEpoch: Long get() = joinedAt

    /** Counts one recent message taken; false once [GroupPost.RECENT_MAX] were (the sender's limit, enforced on receiving too). */
    fun takeShared(): Boolean {
        if (sharedTaken >= GroupPost.RECENT_MAX) return false
        sharedTaken++
        return true
    }

    /** People on the list held who were not on the one before: the members a [GroupBody.PROFILE] must reach again. */
    fun added(): List<ByteArray> = roster?.members.orEmpty().filter { previous?.member(it.identity) == null }.map { it.identity.copyOf() }

    /** This phone's signing key for the group, sent to the inviting admin to join. */
    fun introduce(): ByteArray = GroupControl.Signer(group, mySigner).encode()

    /** Signing keys people sent this phone (an admin builds the next list from these). */
    fun signerOf(identity: ByteArray): ByteArray? = signers[identity.toList()]?.copyOf()

    /** "Send me epoch N's key and list again", for the admin whose key this phone is waiting on. */
    fun ask(): Pair<ByteArray, ByteArray>? = pendings.values.firstOrNull()?.let { it.from.copyOf() to GroupControl.Ask(group, it.key.epoch).encode() }

    /** The admin this phone is waiting on, if a newer key waits for its list. */
    fun waitingOn(): ByteArray? = pendings.values.firstOrNull { it.key.epoch > epoch }?.from?.copyOf()

    /** Since when, and for which epoch, this phone waits for a list (null when it waits for none). */
    fun waiting(): Pair<Long, Long>? = pendings.values.filter { it.key.epoch > epoch }.minByOrNull { it.at }?.let { it.at to it.key.epoch }

    /** A short code of the list held, to compare between phones (review C3): equal codes, same list. */
    fun safetyCode(): ByteArray =
        Bytes.sha256("phonemesh group safety v1".toByteArray(), group, Bytes.u32(epoch), digest).copyOf(8)

    /** Members on the list held who said they left (an admin starts the next epoch without them). */
    fun leaving(): List<ByteArray> = leaving.map { it.toByteArray() }.filter { roster?.member(it) != null }

    /**
     * As an admin of the list held (or creating the group): a fresh secret, a [GroupKey] for every other member, the
     * list packets, and a removal notice for each person dropped. This phone takes the epoch at once.
     */
    fun newEpoch(next: GroupRoster, now: Long, random: SecureRandom = SecureRandom(), leaving: Boolean = false): Outgoing {
        val current = roster
        require(if (current == null) epoch == 0L && state == State.INVITED && inviter == null && !leaving else (state == State.ACTIVE || state == State.CONFLICT) && current.isAdmin(me)) {
            "only an admin of the list held starts an epoch"
        }
        require(pendings.values.none { it.key.epoch > epoch }) { "a newer key is waiting for its list" }
        require(epoch < 0xFFFF_FFFEL) { "no epoch left" }
        if (leaving) require(next.member(me) == null) { "an admin leaving is not on the list it starts" }
        else require(next.isAdmin(me) && next.member(me)!!.signer.contentEquals(mySigner)) { "the admin starting an epoch stays an admin in it" }
        val nextEpoch = epoch + 1
        val secret = ByteArray(GroupCipher.SECRET).also(random::nextBytes)
        val parts = next.parts()
        val nextDigest = next.digest(group, nextEpoch, digest)
        val key = GroupKey(group, nextEpoch, secret, nextDigest, digest, parts.size, mySigner)
        val before = current
        val others = next.members.filterNot { it.identity.contentEquals(me) }
        val keys = others.associate { it.identity.toList() to key.encode() }
        val dropped = before?.members.orEmpty().filter { next.member(it.identity) == null && !it.identity.contentEquals(me) }
            .associate { it.identity.toList() to GroupControl.Removed(group, nextEpoch).encode() }
        val packets: List<ByteArray>
        if (leaving) {
            // The last admin hands the group over and leaves in one epoch (decision 72 d): its chain carries the list,
            // and this phone keeps nothing of the new epoch.
            val w = GroupWriter(group, nextEpoch, me, GroupCipher.chain0(group, nextEpoch, secret, me), sign = sign)
            packets = parts.map { w.seal(it) }
            removed(State.LEFT)
        } else {
            commit(nextEpoch, next, nextDigest, secret.copyOf(), now, null)
            packets = parts.map { writer!!.seal(it) }
            issued = Issued(key, packets, now)
        }
        secret.fill(0)
        return Outgoing(keys, packets, dropped)
    }

    /**
     * A [GroupKey] that arrived over [from]'s private session. Waits for its list. Refused (false) unless it is for
     * the next epoch, names the list held as its parent (or, while in conflict, either list) and comes from an admin of
     * that list; the first key only from the [inviter]. The same key again is accepted and changes nothing.
     */
    fun receiveKey(from: ByteArray, key: GroupKey, now: Long): Boolean {
        if (!key.group.contentEquals(group) || state == State.REMOVED || state == State.LEFT) return false
        expire(now)
        if (key.epoch == epoch && key.digest.contentEquals(digest) && GroupKey.tag(group, epoch, key.secret).contentEquals(secretTag)) return true
        val current = roster
        if (current != null) {
            // The next epoch, from an admin of the list held, built on it (or, in a conflict, on either list).
            val next = key.epoch == epoch + 1 && current.isAdmin(from) &&
                (key.parent.contentEquals(digest) || (state == State.CONFLICT && conflictDigests.any { it.contentEquals(key.parent) }))
            // A rival for the epoch held (another list, or this list with another secret), from an admin of the list
            // before: checked when its list completes.
            // Only soon after the epoch was taken: an admin the list removed cannot force a conflict later (review G9-4).
            val rival = key.epoch == epoch && key.parent.contentEquals(previousDigest) && previous?.isAdmin(from) == true &&
                now - committedAt in 0..RIVAL_MS
            if (!next && !rival) {
                // A key from an admin of the list held for an epoch further on: kept until the epochs before it are taken.
                if (key.epoch !in epoch + 2..epoch + MAX_AHEAD || !current.isAdmin(from)) return false
                if (early.none { it.from.contentEquals(from) && it.key.epoch == key.epoch && it.key.secret.contentEquals(key.secret) }) {
                    if (early.size >= MAX_EARLY) early.removeAt(0).key.wipe()
                    early += Pending(from.copyOf(), GroupKey.decode(key.encode())!!, GroupReader(group, key.epoch, from, key.signer,
                        GroupCipher.chain0(group, key.epoch, key.secret, from)), now)
                }
                return true
            }
        } else {
            // An invitation: the first epoch's key, only from the admin who invited this phone.
            val by = inviter ?: return false
            if (!by.contentEquals(from)) return false
        }
        val id = from.toList()
        pendings[id]?.let { if (it.key.epoch == key.epoch && it.key.digest.contentEquals(key.digest) && it.key.secret.contentEquals(key.secret)) return true }
        // One waiting key per admin. A second admin's different list for the same epoch waits too: the first to
        // complete is taken, and the other, once its own list completes and checks, shows as a conflict.
        val reader = GroupReader(group, key.epoch, from, key.signer, GroupCipher.chain0(group, key.epoch, key.secret, from))
        pendings.remove(id)?.key?.wipe()
        pendings[id] = Pending(from.copyOf(), key, reader, now)
        return true
    }

    /**
     * Another private group body from [from]: a signing key (kept), a removal notice from an admin of the list held
     * (this phone leaves), a request to resend an epoch this phone started (answered when [from] is on its list), or
     * a member saying they left (kept for an admin's next epoch). Returns false when refused; the second value is filled
     * for an answered request. [GroupControl.Invite] is the app's to show, never taken here.
     */
    fun receiveControl(from: ByteArray, control: GroupControl, now: Long): Pair<Boolean, Outgoing?> {
        if (!control.group.contentEquals(group)) return false to null
        expire(now)
        return when (control) {
            is GroupControl.Signer -> {
                // A signing key must be new to the group: not this phone's, a member's or another person's (review G9-1).
                val key = control.key
                val who = from.toList()
                if (key.contentEquals(mySigner) || roster?.members?.any { it.signer.contentEquals(key) } == true ||
                    signers.any { (id, k) -> id != who && k.contentEquals(key) }) return false to null
                signers[who] = key.copyOf(); true to null
            }
            is GroupControl.Removed -> {
                val current = roster ?: return false to null
                if (control.epoch != epoch + 1 || !current.isAdmin(from)) return false to null
                removed(State.REMOVED); true to null
            }
            is GroupControl.Ask -> {
                val i = issued ?: return false to null
                val list = roster ?: return false to null
                if (control.epoch != i.key.epoch || i.key.epoch != epoch || list.member(from) == null) return false to null
                if (!i.asked.add(from.toList())) return false to null
                true to Outgoing(mapOf(from.toList() to i.key.encode()), i.packets, emptyMap())
            }
            is GroupControl.Leave -> {
                val list = roster ?: return false to null
                // The notice may name the epoch after the one held (its sender took it first).
                // A notice from long ago would mark a member who came back as leaving (review G9-14).
                if (from.contentEquals(me) || list.member(from) == null || control.epoch > epoch + MAX_AHEAD || control.epoch + MAX_AHEAD < epoch) return false to null
                leaving += from.toList(); true to null
            }
            is GroupControl.Invite -> false to null
        }
    }

    /**
     * This phone leaves: it stops reading and sending at once, and returns the one admin to tell and the [GroupControl.Leave]
     * body (null when there is nobody to tell). The first admin on the list other than this phone that [canTell] (a
     * contact: the notice goes privately) is told, so only one admin acts. The last admin promotes someone with [newEpoch] first (decision 72 d).
     */
    fun leave(canTell: (ByteArray) -> Boolean = { true }): Pair<ByteArray, ByteArray>? {
        val list = roster
        val told = list?.members?.firstOrNull { it.admin && !it.identity.contentEquals(me) && canTell(it.identity) }
        val out = if (list != null && told != null && epoch in 1 until 0xFFFF_FFFFL) told.identity.copyOf() to GroupControl.Leave(group, epoch).encode() else null
        removed(State.LEFT)
        return out
    }

    /**
     * On-air codes this phone can read next, and its own (review C6): what to listen and ask a hub for. [hub] adds the
     * hub chains (asked of a hub; the radio never carries them).
     */
    fun expectedCodes(now: Long, hub: Boolean = true): List<ByteArray> {
        expire(now)
        val radio = readers.values.flatMap { it.expectedCodes() } + (writer?.expectedCodes() ?: emptyList()) +
            pendings.values.flatMap { it.reader.expectedCodes() } + oldReaders.values.flatMap { it.expectedCodes() }
        if (!hub) return radio
        return radio + hubReaders.values.flatMap { it.expectedCodes() } + (hubWriter?.expectedCodes() ?: emptyList()) +
            oldHubReaders.values.flatMap { it.expectedCodes() }
    }

    /** What a packet brings: one item, the messages a just-completed list releases, or none. */
    fun receive(packet: ByteArray, now: Long): List<Message> {
        took = false
        expire(now)
        for (p in pendings.values.toList()) {
            val body = p.reader.open(packet) ?: continue
            took = true
            if (body.isNotEmpty() && body[0].toInt() == GroupBody.ROSTER) return rosterPart(p, body, now)
            // The admin's first packets may come before the list: keep them for when the list completes (review C4).
            if (body.isNotEmpty() && p.buffered.size < MAX_BUFFERED) p.buffered += p.reader.lastOpened to body
            return emptyList()
        }
        if (state != State.ACTIVE && state != State.CONFLICT) return emptyList()
        for ((chains, old, hub) in listOf(Triple(readers, false, false), Triple(hubReaders, false, true),
                Triple(oldReaders, true, false), Triple(oldHubReaders, true, true))) {
            for ((id, reader) in chains) {
                val body = reader.open(packet) ?: continue
                took = true
                return message(id, body, old, hub, if (old) oldEpoch else epoch, reader.lastOpened)
            }
        }
        return emptyList()
    }

    private fun message(id: List<Byte>, body: ByteArray, old: Boolean, hub: Boolean, ep: Long, seq: Long): List<Message> {
        if (body.isEmpty()) return emptyList()
        val op = body[0].toInt() and 0xFF
        if (op == GroupBody.ROSTER || op !in GroupBody.MESSAGE..GroupBody.LAST) return emptyList()
        // An op on a chain it does not belong to is a modified client's, never read (review G9-12).
        if (!GroupBody.allowed(op, hub)) return emptyList()
        // Receipts are for what people say, never for receipts (no echo).
        if (!hub && !old && op != GroupBody.RECEIPT) unreceipted += id
        return listOf(Message(id.toByteArray(), body.copyOfRange(1, body.size), old, op, hub, ep, seq))
    }

    /** Tests only: a packet on any chain with any op, to forge what a modified client could send. */
    internal fun sealUnchecked(op: Int, body: ByteArray, hub: Boolean): Sealed {
        val w = if (hub) hubWriter!! else writer!!
        val seq = w.next
        return Sealed(w.seal(byteArrayOf(op.toByte()) + body), epoch, seq, hub)
    }

    /** Tests only: a signing key kept as if [receiveControl] had taken it, to prove the engine survives one that should not be. */
    internal fun putSignerUnchecked(identity: ByteArray, key: ByteArray) { signers[identity.toList()] = key.copyOf() }

    /** A packet carrying [body] (an app message) to everyone in the group. Store [encode] before it leaves. */
    fun send(body: ByteArray): ByteArray = seal(GroupBody.MESSAGE, body).packet

    /** A packet carrying [body] as [op] on the radio chain or, with [hub], the hub chain. Store [encode] before it leaves. */
    fun seal(op: Int, body: ByteArray, hub: Boolean = false): Sealed {
        check(canSend) { if (state == State.CONFLICT) "the group has two lists: an admin must start the next epoch" else "not able to send now" }
        require(op in GroupBody.MESSAGE..GroupBody.LAST && op != GroupBody.ROSTER) { "not an op the app sends" }
        require(GroupBody.allowed(op, hub)) { "op $op does not travel on that chain" }
        val w = if (hub) hubWriter!! else writer!!
        val seq = w.next
        return Sealed(w.seal(byteArrayOf(op.toByte()) + body), epoch, seq, hub)
    }

    /**
     * The body of a [GroupBody.RECEIPT] for the radio-chain packets read since the last one, or null when nothing new
     * was read. Senders read since then come first; others fill the packet. Store [encode] once it is sealed.
     */
    fun receipt(): ByteArray? {
        val list = roster ?: return null
        if (unreceipted.isEmpty() || !canSend) return null
        val order = unreceipted.toList() + readers.keys.filter { it !in unreceipted }
        val entries = order.mapNotNull { id ->
            val r = readers[id] ?: return@mapNotNull null
            val (highest, missing) = r.received()
            if (highest < 0) null else GroupReceipt.Entry(list.indexOf(id.toByteArray()), highest, missing)
        }.take(GroupReceipt.MAX_ENTRIES)
        unreceipted.clear()
        return if (entries.isEmpty()) null else GroupReceipt(entries).encode()
    }

    /**
     * What a receipt [message] says about this phone's own radio chain: the entry naming this phone in the list of the
     * receipt's epoch, or null. Its [GroupReceipt.Entry.covers] says which of this phone's positions were read.
     */
    fun receiptForMe(message: Message): GroupReceipt.Entry? {
        if (message.op != GroupBody.RECEIPT || message.hub) return null
        val list = when (message.epoch) { epoch -> roster; oldEpoch -> previous; else -> null } ?: return null
        val receipt = GroupReceipt.decode(message.body) ?: return null
        if (receipt.entries.any { it.index >= list.members.size }) return null
        val mine = list.indexOf(me)
        return receipt.entries.firstOrNull { it.index == mine }
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
            // The sender is an admin of the new list under the signing key its key named, or an admin of the list held
            // under that key who leaves with this list (the last admin handing over, decision 72 d).
            val admin = next.member(p.from) ?: roster?.member(p.from)?.takeIf { p.key.epoch == epoch + 1 }
            if (admin == null || !admin.admin || !admin.signer.contentEquals(p.key.signer)) return emptyList()
            // A checked rival for the epoch held (review C3): another list, or this list with another secret, is a
            // conflict, whether or not it keeps this phone.
            if (roster != null && p.key.epoch == epoch) {
                val otherList = !p.key.digest.contentEquals(digest)
                val otherSecret = !GroupKey.tag(group, epoch, p.key.secret).contentEquals(secretTag)
                if (otherList || otherSecret) {
                    if (conflictDigests.none { it.contentEquals(digest) }) conflictDigests += digest.copyOf()
                    if (conflictDigests.none { it.contentEquals(p.key.digest) }) conflictDigests += p.key.digest.copyOf()
                    state = State.CONFLICT
                }
                return emptyList()
            }
            val mine = next.member(me)
            if (mine == null) { removed(State.REMOVED); return emptyList() }
            if (!mine.signer.contentEquals(mySigner)) return emptyList()
            commit(p.key.epoch, next, p.key.digest, p.key.secret.copyOf(), now, p)
            return p.buffered.flatMap { (seq, b) -> message(p.from.toList(), b, false, false, epoch, seq) }
        } finally {
            p.key.wipe()
        }
    }

    private fun commit(nextEpoch: Long, next: GroupRoster, nextDigest: ByteArray, secret: ByteArray, now: Long, from: Pending?) {
        // Members of the new list may still write in the old epoch for a while: keep reading them, marked old.
        oldReaders = LinkedHashMap(readers.filterKeys { id -> next.member(id.toByteArray()) != null })
        oldHubReaders = LinkedHashMap(hubReaders.filterKeys { id -> next.member(id.toByteArray()) != null })
        oldEpoch = epoch
        oldUntil = now + KEEP_MS
        readers.clear(); hubReaders.clear()
        for (m in next.members) {
            val ck = GroupCipher.chain0(group, nextEpoch, secret, m.identity)
            val hk = GroupCipher.chain0(group, nextEpoch, secret, m.identity, hub = true)
            if (m.identity.contentEquals(me)) {
                writer = GroupWriter(group, nextEpoch, me, ck, sign = sign)
                hubWriter = GroupWriter(group, nextEpoch, me, hk, sign = sign)
            } else {
                readers[m.identity.toList()] = GroupReader(group, nextEpoch, m.identity, m.signer, ck)
                hubReaders[m.identity.toList()] = GroupReader(group, nextEpoch, m.identity, m.signer, hk)
            }
        }
        // The admin's chain already carried the list packets: keep reading it from there.
        from?.let { if (next.member(it.from) != null) readers[it.from.toList()] = it.reader }
        // Waiting keys for older epochs are spent; rivals for this one stay until their lists check.
        pendings.values.removeAll { (it.key.epoch < nextEpoch).also { old -> if (old) it.key.wipe() } }
        secretTag = GroupKey.tag(group, nextEpoch, secret)
        // The secret itself is not kept: only the chains derived from it and a tag of it.
        secret.fill(0)
        if (roster?.member(me) == null) joinedAt = nextEpoch
        committedAt = now
        previous = roster
        previousDigest = digest
        epoch = nextEpoch
        roster = next
        digest = nextDigest.copyOf()
        conflictDigests.clear()
        unreceipted.clear()
        leaving.retainAll { next.member(it.toByteArray()) != null }
        if (issued?.key?.epoch != nextEpoch) issued = null
        state = State.ACTIVE
        // Keys that came early: older ones are spent; the next epoch's are checked now like any key.
        early.removeAll { (it.key.epoch <= nextEpoch).also { old -> if (old) it.key.wipe() } }
        for (e in early.filter { it.key.epoch == nextEpoch + 1 }) {
            early.remove(e)
            receiveKey(e.from, e.key, e.at)
            if (pendings.values.none { it.key === e.key }) e.key.wipe()
        }
    }

    private fun removed(to: State) {
        readers.clear(); hubReaders.clear(); oldReaders.clear(); oldHubReaders.clear()
        pendings.values.forEach { it.key.wipe() }; pendings.clear()
        early.forEach { it.key.wipe() }; early.clear()
        writer = null; hubWriter = null; issued?.key?.wipe(); issued = null; unreceipted.clear(); leaving.clear()
        state = to
    }

    private fun expire(now: Long) {
        if ((oldReaders.isNotEmpty() || oldHubReaders.isNotEmpty()) && now > oldUntil) { oldReaders.clear(); oldHubReaders.clear() }
        pendings.values.removeAll { (now - it.at > KEEP_MS).also { old -> if (old) it.key.wipe() } }
        early.removeAll { (now - it.at > KEEP_MS).also { old -> if (old) it.key.wipe() } }
        issued?.let { if (now - it.at > KEEP_MS) { it.key.wipe(); issued = null } }
    }

    // ---- saved state (review C8): everything above, so a phone resumes exactly where it was

    fun encode(): ByteArray {
        val o = ByteArrayOutputStream()
        fun u8(n: Int) = o.write(n)
        fun u32(n: Long) = o.write(GroupReader.u32(n))
        fun u64(n: Long) = o.write(ByteBuffer.allocate(8).putLong(n).array())
        fun blob(b: ByteArray?) { val x = b ?: ByteArray(0); o.write(ByteBuffer.allocate(2).putShort(x.size.toShort()).array()); o.write(x) }
        fun readers(m: Map<List<Byte>, GroupReader>) { u8(m.size); for (r in m.values) blob(r.encode()) }
        fun ids(s: Collection<List<Byte>>) { u8(s.size); for (id in s) o.write(id.toByteArray()) }
        u8(STATE_VERSION); u8(state.ordinal); u32(epoch); blob(inviter)
        blob(roster?.encode()); o.write(digest); blob(previous?.encode()); o.write(previousDigest); o.write(secretTag)
        blob(writer?.encode()); blob(hubWriter?.encode())
        readers(readers); readers(hubReaders)
        u32(oldEpoch); u64(oldUntil); readers(oldReaders); readers(oldHubReaders)
        u8(conflictDigests.size); for (d in conflictDigests) o.write(d)
        u8(signers.size); for ((id, k) in signers) { o.write(id.toByteArray()); o.write(k) }
        ids(unreceipted); ids(leaving)
        u8(pendings.size)
        for (p in pendings.values) {
            o.write(p.from); o.write(p.key.encode()); u64(p.at); blob(p.reader.encode())
            u8(p.parts.size); for ((i, part) in p.parts) { u8(i); blob(part) }
            u8(p.buffered.size); for ((seq, b) in p.buffered) { u32(seq); blob(b) }
        }
        u8(early.size); for (e in early) { o.write(e.from); o.write(e.key.encode()); u64(e.at) }
        val i = issued
        if (i == null) u8(0) else { u8(1); o.write(i.key.encode()); u64(i.at); u8(i.packets.size); for (pk in i.packets) blob(pk) }
        // Added in v0.1.2 (reviews G9-4, G9-9, G9-11); a save from before it ends here and still loads.
        u8(EXT_VERSION); u64(committedAt); u32(joinedAt); u8(sharedTaken)
        val asked = i?.asked.orEmpty()
        u8(asked.size); for (id in asked) o.write(id.toByteArray())
        return o.toByteArray()
    }

    companion object {
        /** How long a waiting key, old-epoch reading and a started epoch's resend copy last: the resend horizon. */
        const val KEEP_MS = 72 * 3_600_000L
        const val MAX_BUFFERED = 32
        /** Keys kept for epochs past the next (a later key overtaking an earlier list), and how far ahead. */
        const val MAX_EARLY = 4
        const val MAX_AHEAD = 4L
        /** How long after taking an epoch a rival key for it is still believed (review G9-4). */
        const val RIVAL_MS = 6 * 3_600_000L
        private const val STATE_VERSION = 2
        private const val EXT_VERSION = 1

        /** A member saved with [encode]; null when the bytes are not a saved member of [group]. */
        fun decode(me: ByteArray, mySigner: ByteArray, sign: (ByteArray) -> ByteArray, group: ByteArray, bytes: ByteArray): GroupMember? = try {
            val b = ByteBuffer.wrap(bytes)
            fun raw(n: Int) = ByteArray(n).also { b.get(it) }
            fun u8() = b.get().toInt() and 0xFF
            fun u32() = b.int.toLong() and 0xFFFF_FFFFL
            fun blob(): ByteArray = raw(b.short.toInt() and 0xFFFF)
            fun writer(w: ByteArray, epoch: Long): GroupWriter {
                require(w.size == 36)
                return GroupWriter(group, epoch, me, w.copyOfRange(0, 32), ByteBuffer.wrap(w, 32, 4).int.toLong() and 0xFFFF_FFFFL, sign)
            }
            fun readers(into: MutableMap<List<Byte>, GroupReader>, epoch: Long) =
                repeat(u8()) { val r = GroupReader.decode(group, epoch, ByteBuffer.wrap(blob())); into[r.identity.toList()] = r }
            require(u8() == STATE_VERSION)
            val state = State.entries[u8()]
            val epoch = u32()
            val inviter = blob().takeIf { it.isNotEmpty() }?.also { require(it.size == P256.POINT) }
            val m = GroupMember(me, mySigner, sign, group, inviter)
            m.state = state
            m.epoch = epoch
            m.roster = blob().takeIf { it.isNotEmpty() }?.let { requireNotNull(GroupRoster.decode(it)) }
            m.digest = raw(GroupRoster.DIGEST)
            m.previous = blob().takeIf { it.isNotEmpty() }?.let { requireNotNull(GroupRoster.decode(it)) }
            m.previousDigest = raw(GroupRoster.DIGEST)
            m.secretTag = raw(16)
            blob().takeIf { it.isNotEmpty() }?.let { m.writer = writer(it, m.epoch) }
            blob().takeIf { it.isNotEmpty() }?.let { m.hubWriter = writer(it, m.epoch) }
            readers(m.readers, m.epoch); readers(m.hubReaders, m.epoch)
            m.oldEpoch = u32(); m.oldUntil = b.long
            readers(m.oldReaders, m.oldEpoch); readers(m.oldHubReaders, m.oldEpoch)
            repeat(u8()) { m.conflictDigests += raw(GroupRoster.DIGEST) }
            repeat(u8()) { m.signers[raw(P256.POINT).toList()] = raw(P256.POINT) }
            repeat(u8()) { m.unreceipted += raw(P256.POINT).toList() }
            repeat(u8()) { m.leaving += raw(P256.POINT).toList() }
            repeat(u8()) {
                val from = raw(P256.POINT)
                val key = requireNotNull(GroupKey.decode(raw(GroupKey.SIZE)))
                val at = b.long
                val p = Pending(from, key, GroupReader.decode(group, key.epoch, ByteBuffer.wrap(blob())), at)
                repeat(u8()) { val i = u8(); p.parts[i] = blob() }
                repeat(u8()) { val seq = u32(); p.buffered += seq to blob() }
                require(p.buffered.size <= MAX_BUFFERED)
                m.pendings[from.toList()] = p
            }
            repeat(u8()) {
                val from = raw(P256.POINT)
                val key = requireNotNull(GroupKey.decode(raw(GroupKey.SIZE)))
                m.early += Pending(from, key, GroupReader(group, key.epoch, from, key.signer, GroupCipher.chain0(group, key.epoch, key.secret, from)), b.long)
            }
            require(m.early.size <= MAX_EARLY)
            if (u8() == 1) {
                val key = requireNotNull(GroupKey.decode(raw(GroupKey.SIZE)))
                val at = b.long
                m.issued = Issued(key, List(u8()) { blob() }, at)
            }
            if (b.hasRemaining()) {
                require(u8() == EXT_VERSION)
                m.committedAt = b.long
                m.joinedAt = u32()
                m.sharedTaken = u8()
                repeat(u8()) { m.issued!!.asked += raw(P256.POINT).toList() }
            } else if (m.roster != null && m.previous?.member(me) == null) m.joinedAt = m.epoch
            require(!b.hasRemaining())
            m
        } catch (_: RuntimeException) { null }
    }
}

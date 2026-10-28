package org.phonemesh.app

import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.PictureWire
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.AvatarPhotos
import org.phonemesh.core.account.Profile
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.group.GroupAvatar
import org.phonemesh.core.group.GroupControl
import org.phonemesh.core.group.GroupMember
import org.phonemesh.core.group.GroupPost
import org.phonemesh.core.group.GroupRoster
import org.phonemesh.core.group.GroupShared
import org.phonemesh.core.group.GroupStore
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.wire.Body
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * [GroupPost]'s storage on the phone (v0.1.2): each group's state and signing key sealed by the [Vault] (labels bind
 * them to their group), private bodies sealed while they wait for the Postman, packets in the group outbox, and every
 * event written into the group's chat, all in one transaction per change (review C8). Main thread only.
 */
class AppGroupStore(
    private val history: History,
    private val accounts: AccountStore,
    private val vault: Vault,
    private val now: () -> Long = System::currentTimeMillis,
) : GroupStore {
    val rows = GroupRows(history)
    private val me: ByteArray get() = accounts.identity
    private val myAccount: String get() = accounts.accountId.toString()
    internal val pictures = GroupPictures(history, rows)
    /** Signing keys made for a group whose first change is not committed yet. */
    private val pendingSigners = HashMap<String, ByteArray>()

    override fun groups(): List<ByteArray> = rows.groups().map { hexBytes(it.groupId) }

    override fun saved(group: ByteArray): GroupStore.Saved? {
        val row = rows.group(hex(group)) ?: return null
        val state = vault.open(row.sealed, stateLabel(row.groupId)) ?: return null
        return GroupStore.Saved(group, state, row.signer, decodeInvited(row.invited), row.receiptDue, row.profileDue,
            row.draft?.let(::decodeDraft), row.adminDue, row.askedEpoch)
    }

    override fun invitations(): List<GroupStore.Invitation> = rows.invitations().mapNotNull { r ->
        val invite = GroupControl.decode(GroupControl.INVITE, r.invite) as? GroupControl.Invite ?: return@mapNotNull null
        GroupStore.Invitation(AppMailStore.idOf(r.fromAccount), r.fromIdentity, invite, r.at)
    }

    override fun newSigner(group: ByteArray): ByteArray {
        val pair = KeyPairBytes.generate()
        pendingSigners[hex(group)] = vault.seal(pair.scalar, signerLabel(hex(group)))
        pair.scalar.fill(0)
        return pair.public
    }

    override fun sign(group: ByteArray, data: ByteArray): ByteArray {
        val g = hex(group)
        val sealed = rows.group(g)?.signerSealed ?: pendingSigners[g] ?: error("no signing key for this group")
        val scalar = checkNotNull(vault.open(sealed, signerLabel(g))) { "the group signing key cannot be opened" }
        return try { P256.sign(P256.privateKey(scalar), data) } finally { scalar.fill(0) }
    }

    override fun identityOf(account: AccountId): ByteArray? = history.contactRow(account.toString())?.let { Accounts.identityOf(it.card) }

    override fun myProfile(): Profile? = accounts.profile()

    override fun myPhotoPieces(): List<ByteArray> = history.ownPhoto()?.let {
        runCatching { AvatarPhotos.pieces(it.webp, it.width, it.height, it.sketch, group = true) }.getOrNull()
    }.orEmpty()

    override fun groupPhotoPieces(group: ByteArray): List<ByteArray> = rows.photo(hex(group), "")?.let {
        runCatching { AvatarPhotos.pieces(it.webp, it.width, it.height, it.sketch, group = true) }.getOrNull()
    }.orEmpty()

    override fun recent(group: ByteArray, sinceMs: Long, max: Int): List<GroupShared> {
        val chat = rows.group(hex(group))?.chatId ?: return emptyList()
        return history.messages(chat, limit = 500).filter { it.kind == MessageKind.TEXT && it.time >= sinceMs }.mapNotNull { m ->
            val language = m.language ?: return@mapNotNull null
            val author = if (m.direction == Direction.OUT) myAccount else (m.senderId ?: return@mapNotNull null)
            val body = runCatching { Body.encode(Body.Text(language, m.text)) }.getOrNull() ?: return@mapNotNull null
            if (body.size > GroupShared.MAX_BODY || author.length != 16) return@mapNotNull null
            GroupShared(hexBytes(author), (m.time / 1000).coerceIn(0, 0xFFFF_FFFFL), body)
        }.takeLast(max)
    }

    override fun commit(change: GroupPost.Change): Boolean = try {
        history.transaction { apply(change); true }
    } catch (e: RuntimeException) {
        if (e is IllegalStateException || e is IllegalArgumentException || e is android.database.SQLException) {
            // Nothing of the change was kept; the engine tries again later. The kind only: never group content.
            android.util.Log.w("PhonemeshGroups", "group change not kept: ${e.javaClass.simpleName}")
            false
        } else throw e
    }

    private fun apply(change: GroupPost.Change) {
        val g = hex(change.group)
        change.invitation?.let { i ->
            rows.putInvitation(GroupRows.InvitationRow(g, i.from.toString(), i.fromIdentity, i.invite.encode(), i.at))
        }
        if (change.dropInvitation) rows.dropInvitation(g)
        if (change.deleted) { rows.deleteGroup(g); pendingSigners.remove(g) }
        change.saved?.let { save(g, it) }
        val chat = rows.group(g)?.chatId
        for ((to, body) in change.private) rows.addPrivate(to.toString(), body.kind, vault.seal(body.bytes, privateLabel(to.toString(), body.kind)), now())
        var named = false
        for (p in change.packets) {
            val id = rows.addOut(g, p.sealed.packet, p.hubOnly, p.messageId, p.sealed.epoch, p.sealed.seq, p.audience, now())
            check(id > 0)
            // The radio head names the bubble: replies and reactions to it point at its reference.
            val messageId = p.messageId
            if (messageId != null && !p.hubOnly && !named) {
                history.attachPacket(messageId, AppMailStore.refNumber(Ratchet.reference(p.sealed.packet.copyOfRange(1, 9))))
                named = true
            }
        }
        for (e in change.events) event(g, chat, e)
    }

    private fun save(g: String, s: GroupStore.Saved) {
        val existing = rows.group(g)
        val member = GroupMember.decode(me, s.signer, { error("not for signing") }, s.group, s.state) ?: error("bad state")
        val list = member.roster
        val looks = list?.let { GroupStore.Draft(it.name, it.colour, it.avatar, it.recentForNew) } ?: s.draft ?: error("a group without a name")
        val chatId = existing?.chatId ?: history.privateGroupChat(g, looks.name).id
        if (existing != null && existing.name != looks.name) history.renameChat(chatId, looks.name)
        val signerSealed = existing?.signerSealed ?: pendingSigners.remove(g) ?: error("no signing key for this group")
        val waiting = member.waitingOn()?.let { AccountId.of(it).toString() }
        rows.putGroup(GroupRows.GroupRow(
            g, chatId, vault.seal(s.state, stateLabel(g)), s.signer, signerSealed, encodeInvited(s.invited), s.receiptDue, s.profileDue,
            s.adminDue, s.askedEpoch, s.draft?.let(::encodeDraft), member.state.name, member.epoch, list?.isAdmin(me) ?: (member.inviter == null),
            looks.name, looks.colour, GroupAvatar.encode(looks.avatar), looks.recentForNew, waiting,
            member.state == GroupMember.State.LEFT || member.state == GroupMember.State.REMOVED,
        ))
        if (existing == null && member.inviter == null) history.addNote(chatId, GroupNotes.MADE, now())
    }

    private fun event(g: String, chatId: Long?, e: GroupPost.Event) {
        val chat = chatId ?: return
        when (e) {
            is GroupPost.Event.Message -> message(g, chat, e)
            is GroupPost.Event.ProfileOf -> rows.setProfile(g, e.member.toString(), e.profile.encode(), e.profile.revision)
            is GroupPost.Event.Delivered -> delivered(g, e)
            is GroupPost.Event.Photo -> photo(g, e)
            is GroupPost.Event.ListChanged -> {
                rows.setMembers(g, e.roster.members.map { AccountId.of(it.identity).toString() to it.admin })
                val mine = e.roster.member(me) != null
                when {
                    mine && e.joined.size == e.roster.members.size - 1 && rows.group(g)?.admin == false && e.epoch >= 1 && isNewHere(g, e) ->
                        history.addNote(chat, GroupNotes.YOU_JOINED, now())
                    e.joined.isNotEmpty() -> history.addNote(chat, GroupNotes.joined(e.joined.map { it.toString() }), now())
                }
                if (e.left.isNotEmpty()) history.addNote(chat, GroupNotes.left(e.left.map { it.toString() }), now())
            }
            is GroupPost.Event.StateChanged -> when (e.state) {
                GroupMember.State.REMOVED -> history.addNote(chat, GroupNotes.REMOVED, now())
                GroupMember.State.LEFT -> history.addNote(chat, GroupNotes.YOU_LEFT, now())
                else -> Unit
            }
            is GroupPost.Event.InvitationArrived -> Unit
        }
    }

    /** This phone's first list in the group: every other person is new to it. */
    private fun isNewHere(g: String, e: GroupPost.Event.ListChanged): Boolean {
        val chat = rows.group(g)?.chatId ?: return false
        return history.messages(chat, 50).none { it.kind == MessageKind.NOTE && it.text == GroupNotes.YOU_JOINED } && e.left.isEmpty()
    }

    private fun message(g: String, chat: Long, e: GroupPost.Event.Message) {
        val body = Body.decode(e.body) ?: return
        val sender = e.sender.toString()
        val shared = e.shared
        val author = shared?.let { hex(it.author) } ?: sender
        val at = shared?.let { it.atSeconds * 1000 } ?: now()
        val ref = if (shared == null) AppMailStore.refNumber(Ratchet.reference(e.code)) else null
        when (body) {
            is Body.Text -> history.addIncoming(chat, author, body.text, body.language, at, shared == null, packetId = ref,
                sharedBy = if (shared != null) sender else null)
            is Body.Reply -> history.addIncoming(chat, author, body.text, body.language, at, shared == null, packetId = ref,
                replyTo = AppMailStore.refNumber(body.ref), sharedBy = if (shared != null) sender else null)
            is Body.Reaction -> if (shared == null) history.addReaction(chat, sender, body.emoji, AppMailStore.refNumber(body.ref), at)
            is Body.PictureHead, is Body.PicturePart -> if (shared == null) pictures.accept(g, chat, sender, body, ref, at)
            else -> Unit
        }
    }

    /**
     * A member's receipt: this phone's messages it covers count once for that member; positions it says are missing go
     * by radio again, a few times at most (selective repair, as AE2 does for private mail).
     */
    private fun delivered(g: String, e: GroupPost.Event.Delivered) {
        val who = e.member.toString()
        val touched = HashSet<Long>()
        for (row in rows.outAt(g, e.epoch)) {
            if (e.entry.covers(row.seq)) {
                row.messageId?.let { rows.delivered(it, who); touched += it }
            } else if (row.seq < e.entry.highest && e.entry.highest - row.seq <= 32 && row.airings < MAX_AIRINGS && row.state != STATE_WAITING) {
                rows.setOutState(row.id, STATE_WAITING)
            }
        }
        for (id in touched) {
            val audience = rows.audience(id) ?: continue
            if (audience > 0 && rows.deliveredCount(id) >= audience) history.setStatus(id, DeliveryStatus.DELIVERED)
        }
    }

    private fun photo(g: String, e: GroupPost.Event.Photo) {
        val who = if (e.ofGroup) "" else e.sender.toString()
        rows.keepPhotoPiece(g, who, e.ofGroup, e.piece, now())
        val pieces = rows.photoPieces(g, who, e.ofGroup)
        // Any run of pieces from one head that makes a whole, checked photo (as for contacts, decision 50).
        for (from in pieces.indices.reversed()) {
            val run = pieces.subList(from, pieces.size)
            val photo = AvatarPhotos.join(run, group = true) ?: continue
            val head = run.mapNotNull { PictureWire.decodeGroup(it) as? PictureWire.Head }.firstOrNull() ?: continue
            rows.setPhoto(g, who, GroupRows.Photo(photo.webp, photo.width, photo.height, head.sketch))
            return
        }
    }

    // ---- what the transport does with the group outbox

    /** The radio took group outbox row [id]. */
    fun aired(id: Long) {
        val row = rows.out(id) ?: return
        if (row.state != STATE_WAITING) return
        rows.setOutState(id, STATE_SENT, row.airings + 1)
        row.messageId?.let { m -> if (history.message(m)?.status == DeliveryStatus.WAITING) history.setStatus(m, DeliveryStatus.SENT) }
    }

    /** A pinned hub took group outbox row [id]: it airs it (unless hub only) and keeps it for phones that ask. */
    fun reachedHub(id: Long) {
        val row = rows.out(id) ?: return
        if (row.state != STATE_WAITING) return
        rows.setOutState(id, STATE_HUB, row.airings + 1)
        row.messageId?.let { m ->
            val status = history.message(m)?.status
            if (status == DeliveryStatus.WAITING || status == DeliveryStatus.SENT) history.setStatus(m, DeliveryStatus.HUB)
        }
    }

    /** The private bodies waiting for the Postman, opened: (row id, contact, body). */
    fun privates(): List<Triple<Long, AccountId, Body.Group>> = rows.privates().mapNotNull { r ->
        val bytes = vault.open(r.sealed, privateLabel(r.accountId, r.kind)) ?: run { rows.dropPrivate(r.id); return@mapNotNull null }
        Triple(r.id, AppMailStore.idOf(r.accountId), Body.Group(r.kind, bytes))
    }

    companion object {
        const val STATE_WAITING = "WAITING"
        const val STATE_SENT = "SENT"
        const val STATE_HUB = "HUB"
        /** How many times one group packet goes by radio at most (first airing plus repairs). */
        const val MAX_AIRINGS = 3

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun hexBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        private fun stateLabel(g: String) = "group-state:$g"
        private fun signerLabel(g: String) = "group-signer:$g"
        private fun privateLabel(account: String, kind: Int) = "group-private:$account:$kind"

        fun encodeInvited(list: List<Pair<AccountId, ByteArray>>): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(list.size)
            for ((a, id) in list) { o.write(a.toBytes()); o.write(id) }
            return o.toByteArray()
        }

        fun decodeInvited(b: ByteArray): List<Pair<AccountId, ByteArray>> {
            val n = b[0].toInt() and 0xFF
            require(b.size == 1 + n * (AccountId.SIZE + P256.POINT))
            return List(n) { i ->
                val at = 1 + i * (AccountId.SIZE + P256.POINT)
                AccountId.fromBytes(b.copyOfRange(at, at + AccountId.SIZE)) to b.copyOfRange(at + AccountId.SIZE, at + AccountId.SIZE + P256.POINT)
            }
        }

        /** name length | name | RGB | picture | recent flag. */
        fun encodeDraft(d: GroupStore.Draft): ByteArray {
            val n = d.name.toByteArray(Charsets.UTF_8)
            return byteArrayOf(n.size.toByte()) + n + byteArrayOf((d.colour ushr 16).toByte(), (d.colour ushr 8).toByte(), d.colour.toByte()) +
                GroupAvatar.encode(d.avatar) + byteArrayOf(if (d.recentForNew) 1 else 0)
        }

        fun decodeDraft(b: ByteArray): GroupStore.Draft? = runCatching {
            val n = b[0].toInt() and 0xFF
            val name = String(b, 1, n, Charsets.UTF_8)
            val c = ByteBuffer.wrap(b, 1 + n, 3).let { ((it.get().toInt() and 0xFF) shl 16) or ((it.get().toInt() and 0xFF) shl 8) or (it.get().toInt() and 0xFF) }
            val (avatar, used) = GroupAvatar.decode(b, 4 + n)
            require(b.size == 4 + n + used + 1)
            GroupStore.Draft(name, c, avatar, b[b.size - 1].toInt() == 1)
        }.getOrNull()
    }
}

/** Codes of the lines the app writes into a group's chat ([MessageKind.NOTE]); the chat screen shows them in words. */
object GroupNotes {
    const val MADE = "made"
    const val YOU_JOINED = "you-joined"
    const val YOU_LEFT = "you-left"
    const val REMOVED = "removed"
    fun joined(accounts: List<String>) = "joined:" + accounts.joinToString(",")
    fun left(accounts: List<String>) = "left:" + accounts.joinToString(",")
}

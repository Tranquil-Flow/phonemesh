package org.phonemesh.core.group

import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.Avatar
import org.phonemesh.core.account.Profile
import org.phonemesh.core.wire.Body

/**
 * What [GroupPost] keeps. The app backs it with the sealed history and the Vault; tests with maps. [commit] applies a
 * whole [GroupPost.Change] in one transaction or nothing, so state is saved before anything leaves (review C8).
 */
interface GroupStore {
    /**
     * A group's name, colour, picture and setting before its first list (the maker's choice, or an invitation's), so
     * a group nobody has joined yet still shows (decision 72).
     */
    class Draft(val name: String, val colour: Int, val avatar: Avatar, val recentForNew: Boolean)

    /** A group's saved state: [state] is [GroupMember.encode]; [signer] this phone's public signing key for it. */
    class Saved(
        group: ByteArray, state: ByteArray, signer: ByteArray,
        /** People an admin invited who have not joined yet (account, identity key). */
        invited: List<Pair<AccountId, ByteArray>> = emptyList(),
        /** When this phone's next receipt goes, if one waits. */
        val receiptDue: Long? = null,
        /** When this phone's profile (and photo) goes to the group again, if it waits. */
        val profileDue: Long? = null,
        val draft: Draft? = null,
        /** When an admin's next epoch for people who joined or left is due (joins are batched), if one waits. */
        val adminDue: Long? = null,
        /** The epoch this phone last asked an admin to send again (asked once by itself). */
        val askedEpoch: Long = 0,
    ) {
        val group: ByteArray = group.copyOf()
        val state: ByteArray = state.copyOf()
        val signer: ByteArray = signer.copyOf()
        val invited: List<Pair<AccountId, ByteArray>> = invited.map { it.first to it.second.copyOf() }
    }

    /** An invitation shown with Join and Ignore (decision 72 c). */
    class Invitation(val from: AccountId, fromIdentity: ByteArray, val invite: GroupControl.Invite, val at: Long) {
        val fromIdentity: ByteArray = fromIdentity.copyOf()
    }

    fun groups(): List<ByteArray>
    fun saved(group: ByteArray): Saved?
    fun invitations(): List<Invitation>
    /** A new private signing key for [group], kept by the app; returns its public key (33 bytes). */
    fun newSigner(group: ByteArray): ByteArray
    /** Signs [data] with this phone's signing key for [group] (raw r || s). */
    fun sign(group: ByteArray, data: ByteArray): ByteArray
    /** A contact's identity key, or null when [account] is not a contact. */
    fun identityOf(account: AccountId): ByteArray?
    /** This phone's profile, if it has one. */
    fun myProfile(): Profile?
    /** This phone's photo as pieces sized for a group packet (head first), when its picture is a photo. */
    fun myPhotoPieces(): List<ByteArray>
    /** [group]'s photo as pieces sized for a group packet (head first), when its picture is a photo this phone has. */
    fun groupPhotoPieces(group: ByteArray): List<ByteArray>
    /** Messages to share with people who just joined (decision 74): newer than [sinceMs], at most [max], oldest first. */
    fun recent(group: ByteArray, sinceMs: Long, max: Int): List<GroupShared>
    /** All of [change] or nothing; false when refused. */
    fun commit(change: GroupPost.Change): Boolean
}

/**
 * The private-group engine (v0.1.2, draft GK4, decisions 47, 66, 69, 72, 74), pure Kotlin like the Postman:
 * invitations, joining, epochs, sending, reading, receipts, profiles, pictures and leaving, on top of [GroupMember].
 * It never sends: each call builds a [Change] that the store commits in one transaction; the app then gives the
 * private bodies to the Postman and the packets to the radio and a hub. Times are milliseconds. One caller at a time.
 */
class GroupPost(
    private val me: ByteArray,
    private val store: GroupStore,
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
    private val quietDelay: () -> Long = { MIN_QUIET_MS + random.nextInt((MAX_QUIET_MS - MIN_QUIET_MS + 1).toInt()) },
    private val profileDelay: () -> Long = { random.nextInt((MAX_PROFILE_DELAY_MS + 1).toInt()).toLong() },
) {
    /**
     * A group packet to send: by radio and to a hub, or ([hubOnly]) only to a hub (decisions 64, 69). [messageId] links
     * a chat message; [audience] is how many others were on the list when it was sealed ("of M").
     */
    class Packet(val group: ByteArray, val sealed: GroupMember.Sealed, val hubOnly: Boolean, val messageId: Long? = null, val audience: Int = 0)

    /** Something the app shows or records, in the same transaction as the state that read it. */
    sealed class Event {
        abstract val group: ByteArray
        /** A message (an app body, as a private one), or a recent message an admin shared ([shared] set; decision 74). */
        class Message(override val group: ByteArray, val sender: AccountId, val body: ByteArray, val epoch: Long, val seq: Long,
                      val oldEpoch: Boolean, val hub: Boolean, val shared: GroupShared? = null,
                      /** The packet's on-air code: the sender names its own bubble by it, so replies and reactions match. */
                      val code: ByteArray = ByteArray(0)) : Event()
        class ProfileOf(override val group: ByteArray, val member: AccountId, val profile: Profile) : Event()
        /** [member]'s receipt for this phone's radio chain in [epoch]: which of its positions they read. */
        class Delivered(override val group: ByteArray, val member: AccountId, val epoch: Long, val entry: GroupReceipt.Entry) : Event()
        /** A piece of [sender]'s photo, or of the group photo ([ofGroup]; only from an admin). */
        class Photo(override val group: ByteArray, val sender: AccountId, val ofGroup: Boolean, val piece: ByteArray) : Event()
        /** A new list: who joined and who left since the list before. */
        class ListChanged(override val group: ByteArray, val epoch: Long, val roster: GroupRoster, val joined: List<AccountId>, val left: List<AccountId>) : Event()
        class StateChanged(override val group: ByteArray, val state: GroupMember.State) : Event()
        class InvitationArrived(override val group: ByteArray, val from: AccountId) : Event()
    }

    /**
     * One transaction: the group's new [saved] state (or [deleted]), an [invitation] to keep or [dropInvitation],
     * private bodies for contacts, group packets and events. Private bodies may go twice after a crash: receivers take
     * each twice without harm.
     */
    class Change(
        val group: ByteArray,
        val saved: GroupStore.Saved? = null,
        val deleted: Boolean = false,
        val invitation: GroupStore.Invitation? = null,
        val dropInvitation: Boolean = false,
        val private: List<Pair<AccountId, Body.Group>> = emptyList(),
        val packets: List<Packet> = emptyList(),
        val events: List<Event> = emptyList(),
    )

    /** One group opened for a change: its member state and what the change will hold. */
    private inner class Open(val saved: GroupStore.Saved, val member: GroupMember) {
        val group: ByteArray get() = saved.group
        var invited = saved.invited.toMutableList()
        var receiptDue = saved.receiptDue
        var profileDue = saved.profileDue
        var draft = saved.draft
        var adminDue = saved.adminDue
        var askedEpoch = saved.askedEpoch
        val private = ArrayList<Pair<AccountId, Body.Group>>()
        val packets = ArrayList<Packet>()
        val events = ArrayList<Event>()
        val epochBefore = member.epoch
        val stateBefore = member.state
        fun change(dropInvitation: Boolean = false) = Change(
            group, GroupStore.Saved(group, member.encode(), saved.signer, invited, receiptDue, profileDue, draft, adminDue, askedEpoch),
            dropInvitation = dropInvitation, private = private, packets = packets, events = events,
        )
        fun send(op: Int, body: ByteArray, hubOnly: Boolean, messageId: Long? = null) {
            val sealed = member.seal(op, body, hub = hubOnly)
            packets += Packet(group, sealed, hubOnly, messageId, (member.roster?.members?.size ?: 1) - 1)
        }
    }

    private fun open(group: ByteArray): Open? {
        val saved = store.saved(group) ?: return null
        val m = GroupMember.decode(me, saved.signer, { store.sign(group, it) }, group, saved.state) ?: return null
        return Open(saved, m)
    }

    private fun account(identity: ByteArray) = AccountId.of(identity)

    /** The name, colour and picture a group shows now: its list's, else its draft's. */
    fun looks(group: ByteArray): GroupStore.Draft? {
        val o = open(group) ?: return null
        return o.member.roster?.let { GroupStore.Draft(it.name, it.colour, it.avatar, it.recentForNew) } ?: o.draft
    }

    /** The member state of [group], for the screens (read only: changes go through this engine). */
    fun member(group: ByteArray): GroupMember? = open(group)?.member

    // ---- making a group, inviting, joining

    /**
     * A new group with this phone as its admin, inviting [people] (contacts, 1..19). Returns its id, or null when the
     * store refused. Nobody is in it until someone joins: the first list is made then (decision 72 c).
     */
    fun create(name: String, colour: Int, avatar: Avatar, recentForNew: Boolean, people: List<AccountId>, now: Long): ByteArray? {
        require(people.isNotEmpty() && people.size < GroupRoster.MAX_MEMBERS && people.distinct().size == people.size)
        require(store.groups().size < MAX_GROUPS) { "too many groups" }
        val identities = people.map { a -> a to requireNotNull(store.identityOf(a)) { "only contacts are invited" } }
        require(identities.none { it.second.contentEquals(me) })
        val group = ByteArray(GroupCipher.GROUP_ID).also(random::nextBytes)
        val signer = store.newSigner(group)
        val member = GroupMember(me, signer, { store.sign(group, it) }, group)
        val draft = GroupStore.Draft(name, colour, avatar, recentForNew)
        val o = Open(GroupStore.Saved(group, member.encode(), signer, identities, draft = draft), member)
        val invite = GroupControl.Invite(group, signer, people.size + 1, name, colour, avatar)
        for (a in people) o.private += a to Body.Group(GroupControl.INVITE, invite.encode())
        return if (store.commit(o.change())) group else null
    }

    /** As an admin: invite more [people] (contacts not on the list or invited yet). */
    fun invite(group: ByteArray, people: List<AccountId>, now: Long): Boolean {
        val o = open(group) ?: return false
        if (!isAdmin(o)) return false
        val list = o.member.roster
        val fresh = people.distinct().filter { a -> o.invited.none { it.first == a } && list?.members?.none { account(it.identity) == a } != false }
        if (fresh.isEmpty()) return false
        val total = (list?.members?.size ?: 1) + o.invited.size + fresh.size
        if (total > GroupRoster.MAX_MEMBERS) return false
        for (a in fresh) o.invited += a to (store.identityOf(a) ?: return false)
        val looks = list?.let { GroupStore.Draft(it.name, it.colour, it.avatar, it.recentForNew) } ?: o.draft ?: return false
        val invite = GroupControl.Invite(group, o.member.mySigner, total, looks.name, looks.colour, looks.avatar)
        for (a in fresh) o.private += a to Body.Group(GroupControl.INVITE, invite.encode())
        return store.commit(o.change())
    }

    /** Join: this phone makes its signing key for the group and sends it to the admin who invited it. */
    fun join(invitation: GroupStore.Invitation, now: Long): Boolean {
        val group = invitation.invite.group
        if (store.saved(group) != null) return store.commit(Change(group, dropInvitation = true))
        if (store.groups().size >= MAX_GROUPS) return false
        val signer = store.newSigner(group)
        val member = GroupMember(me, signer, { store.sign(group, it) }, group, invitation.fromIdentity)
        val i = invitation.invite
        val o = Open(GroupStore.Saved(group, member.encode(), signer, draft = GroupStore.Draft(i.name, i.colour, i.avatar, false)), member)
        o.private += invitation.from to Body.Group(GroupControl.SIGNER, member.introduce())
        return store.commit(o.change(dropInvitation = true))
    }

    /** Ignore: the invitation goes; nothing is sent. */
    fun ignore(invitation: GroupStore.Invitation): Boolean = store.commit(Change(invitation.invite.group, dropInvitation = true))

    // ---- what arrives over private sessions (the Postman's GroupInbox)

    /**
     * A private group body from contact [from]. True when taken (and saved): the Postman then receipts it. A body for
     * a group this phone does not hold is taken and dropped, so the sender's session does not stall on it.
     */
    fun takePrivate(from: AccountId, body: Body.Group, now: Long): Boolean {
        val fromIdentity = store.identityOf(from) ?: return false
        if (body.kind == GroupControl.KEY) {
            val key = GroupKey.decode(body.bytes) ?: return false
            try {
                val o = open(key.group) ?: return true
                o.member.receiveKey(fromIdentity, key, now)
                return finish(o, now)
            } finally { key.wipe() }
        }
        val control = GroupControl.decode(body.kind, body.bytes) ?: return false
        if (control is GroupControl.Invite) {
            if (store.saved(control.group) != null) return true
            val known = store.invitations()
            if (known.size >= MAX_INVITATIONS && known.none { it.invite.group.contentEquals(control.group) }) return true
            return store.commit(Change(control.group, invitation = GroupStore.Invitation(from, fromIdentity, control, now),
                events = listOf(Event.InvitationArrived(control.group, from))))
        }
        val o = open(control.group) ?: return true
        val (taken, resend) = o.member.receiveControl(fromIdentity, control, now)
        if (taken && resend != null) {
            for ((id, k) in resend.keys) o.private += account(id.toByteArray()) to Body.Group(GroupControl.KEY, k)
            for (p in resend.rosterPackets) o.packets += Packet(o.group, GroupMember.Sealed(p, o.member.epoch, -1, false), false)
        }
        // Someone this phone invited said Join, or a member left: as an admin, the next epoch goes soon, with every
        // join and leaving that came meanwhile (one epoch for many: each costs a key per member and the list).
        val forAdmin = (control is GroupControl.Signer && o.invited.any { it.first == from }) || control is GroupControl.Leave
        if (taken && forAdmin && isAdmin(o) && o.adminDue == null) o.adminDue = now + ADMIN_BATCH_MS
        return finish(o, now)
    }

    /** As an admin able to send: one epoch adding every invited person whose Join (signing key) has arrived. */
    private fun addJoined(o: Open, now: Long): Boolean {
        if (!isAdmin(o) || o.member.waitingOn() != null) return false
        val list = o.member.roster
        if (list != null && !o.member.canSend) return false
        val ready = o.invited.mapNotNull { (a, id) -> o.member.signerOf(id)?.let { Triple(a, id, it) } }
            .filter { (_, id, _) -> list?.member(id) == null }
        if (ready.isEmpty()) return false
        val room = GroupRoster.MAX_MEMBERS - (list?.members?.size ?: 1)
        val taken = ready.take(room)
        if (taken.isEmpty()) return false
        val joiners = taken.map { (_, id, key) -> GroupRoster.Member(id, false, key) }
        val next = if (list == null) {
            val d = o.draft ?: return false
            GroupRoster(d.name, d.colour, listOf(GroupRoster.Member(me, true, o.member.mySigner)) + joiners, d.avatar, d.recentForNew)
        } else GroupRoster(list.name, list.colour, list.members + joiners, list.avatar, list.recentForNew)
        o.invited.removeAll { inv -> taken.any { it.first == inv.first } }
        startEpoch(o, next, now)
        return true
    }

    private fun isAdmin(o: Open): Boolean {
        val list = o.member.roster
        return if (list == null) o.member.inviter == null && o.member.state == GroupMember.State.INVITED else
            list.isAdmin(me) && (o.member.state == GroupMember.State.ACTIVE || o.member.state == GroupMember.State.CONFLICT)
    }

    /** As an admin: a new epoch with [next], sent as keys, the list and removal notices, then what new people need. */
    /**
     * Whether this phone can give every other member of [next] the new key: keys go privately, so each must be a
     * contact. An admin who is missing one cannot start the epoch (the screens ask to add them first).
     */
    private fun canReach(next: GroupRoster): Boolean =
        next.members.all { it.identity.contentEquals(me) || store.identityOf(account(it.identity)) != null }

    private fun startEpoch(o: Open, next: GroupRoster, now: Long) {
        val before = o.member.roster
        val out = o.member.newEpoch(next, now, random)
        for ((id, k) in out.keys) o.private += account(id.toByteArray()) to Body.Group(GroupControl.KEY, k)
        // A removed person this phone does not know gets no notice: they find out when keys stop coming.
        for ((id, r) in out.removed) if (store.identityOf(account(id.toByteArray())) != null) o.private += account(id.toByteArray()) to Body.Group(GroupControl.REMOVED, r)
        for (p in out.rosterPackets) o.packets += Packet(o.group, GroupMember.Sealed(p, o.member.epoch, -1, false), false)
        val joined = next.members.filter { before?.member(it.identity) == null && !it.identity.contentEquals(me) }
        // Recent messages for the people who just joined (decision 74): hub only, from this admin.
        if (joined.isNotEmpty() && next.recentForNew && before != null) {
            for (s in store.recent(o.group, now - RECENT_MS, RECENT_MAX)) o.send(GroupBody.SHARED, s.encode(), hubOnly = true)
        }
        // The group photo (decision 69): its sketch by radio, the rest through a hub; again whenever people join.
        val photoChanged = (next.avatar as? Avatar.Photo)?.let { before?.avatar != it } ?: false
        if (next.avatar is Avatar.Photo && (joined.isNotEmpty() || photoChanged)) {
            store.groupPhotoPieces(o.group).forEachIndexed { i, piece -> o.send(GroupBody.GROUP_PHOTO, piece, hubOnly = i > 0) }
        }
    }

    // ---- group packets (radio or hub)

    /** Every on-air code this phone can read in its groups, its own included (review C6): for listening and hubs. */
    fun expectedCodes(now: Long): Set<String> = store.groups().flatMapTo(LinkedHashSet()) { g ->
        open(g)?.member?.expectedCodes(now)?.map { c -> c.joinToString("") { "%02x".format(it) } }.orEmpty()
    }

    /** A packet from the radio or a hub. True when one of this phone's groups read it (and saved what it says). */
    fun receive(packet: ByteArray, now: Long): Boolean {
        val code = GroupCipher.codeOf(packet) ?: return false
        for (g in store.groups()) {
            val o = open(g) ?: continue
            val read = o.member.receive(packet, now)
            if (!o.member.took) continue
            for (m in read) read(o, m, now, code)
            return finish(o, now)
        }
        return false
    }

    private fun read(o: Open, m: GroupMember.Message, now: Long, code: ByteArray) {
        val sender = account(m.sender)
        val list = o.member.roster
        when (m.op) {
            GroupBody.MESSAGE -> {
                if (m.body.isEmpty()) return
                o.events += Event.Message(o.group, sender, m.body, m.epoch, m.seq, m.oldEpoch, m.hub, code = code.copyOf())
                if (!m.hub && !m.oldEpoch && o.receiptDue == null) o.receiptDue = now + quietDelay()
            }
            GroupBody.PROFILE -> Profile.decode(m.body)?.let { o.events += Event.ProfileOf(o.group, sender, it) }
            GroupBody.RECEIPT -> o.member.receiptForMe(m)?.let { o.events += Event.Delivered(o.group, sender, m.epoch, it) }
            GroupBody.SHARED -> {
                // Only for a phone new on this list, only from an admin, only through a hub (decision 74).
                if (!m.hub || m.oldEpoch || !o.member.joinedThisEpoch || list?.isAdmin(m.sender) != true) return
                val s = GroupShared.decode(m.body) ?: return
                o.events += Event.Message(o.group, sender, s.body, m.epoch, m.seq, false, true, s)
            }
            GroupBody.MEMBER_PHOTO -> if (m.hub) o.events += Event.Photo(o.group, sender, false, m.body)
            GroupBody.GROUP_PHOTO -> if (list?.isAdmin(m.sender) == true) o.events += Event.Photo(o.group, sender, true, m.body)
        }
    }

    /** Saves [o] with what changed in it: a new list's news and the profile it calls for, or a new state. */
    private fun finish(o: Open, now: Long): Boolean {
        val m = o.member
        if (m.epoch != o.epochBefore && m.roster != null) {
            val list = m.roster!!
            val before = m.previous
            val joined = list.members.filter { before?.member(it.identity) == null && !it.identity.contentEquals(me) }.map { account(it.identity) }
            val left = before?.members.orEmpty().filter { list.member(it.identity) == null }.map { account(it.identity) }
            o.events += Event.ListChanged(o.group, m.epoch, list, joined, left)
            o.draft = null
            // New people need everyone's name and picture again; a phone new on the list sends its own (decision 72 b).
            if ((joined.isNotEmpty() || m.joinedThisEpoch) && o.profileDue == null) o.profileDue = now + profileDelay()
            // Invited people already on the list are no longer waiting.
            o.invited.removeAll { inv -> list.member(inv.second) != null }
            // A phone that just became an admin acts on joins and leavings that reached it before.
            val pending = m.leaving().isNotEmpty() || o.invited.any { m.signerOf(it.second) != null }
            if (pending && isAdmin(o) && o.adminDue == null) o.adminDue = now + ADMIN_BATCH_MS
        }
        if (m.state != o.stateBefore) {
            o.events += Event.StateChanged(o.group, m.state)
            if (m.state == GroupMember.State.REMOVED || m.state == GroupMember.State.LEFT) { o.receiptDue = null; o.profileDue = null; o.invited.clear() }
        }
        return store.commit(o.change())
    }

    // ---- sending

    /**
     * [bodies] (app bodies, as a private message's) to the whole group, each with whether it goes only through a hub:
     * a text is one radio body; a picture is its head by radio and its parts through a hub (decision 69). Null when
     * this phone cannot send in the group now (not joined, a change waiting, two lists) or the store refused.
     */
    fun send(group: ByteArray, bodies: List<Pair<ByteArray, Boolean>>, messageId: Long?, now: Long): List<Packet>? {
        require(bodies.isNotEmpty())
        val o = open(group) ?: return null
        if (!o.member.canSend || bodies.isEmpty()) return null
        if (bodies.any { it.first.isEmpty() || it.first.size >= GroupCipher.MAX_BODY }) return null
        for ((b, hub) in bodies) o.send(GroupBody.MESSAGE, b, hub, messageId)
        return if (store.commit(o.change())) o.packets.toList() else null
    }

    /**
     * Receipts and profiles that are due now, and an admin's epochs for people who joined or said they left. False
     * when the store refused a change.
     */
    fun tick(now: Long): Boolean {
        var ok = true
        for (g in store.groups()) {
            val o = open(g) ?: continue
            val m = o.member
            var work = false
            val due = o.adminDue
            if (due != null && due <= now && isAdmin(o) && m.waitingOn() == null && (m.roster == null || m.canSend)) {
                if (m.leaving().isNotEmpty()) {
                    val list = m.roster!!
                    val gone = m.leaving()
                    val stay = list.members.filter { mem -> gone.none { it.contentEquals(mem.identity) } }
                    val next = GroupRoster(list.name, list.colour, stay, list.avatar, list.recentForNew)
                    if (stay.size >= 2 && canReach(next)) startEpoch(o, next, now)
                }
                addJoined(o, now)
                o.adminDue = null; work = true
            } else if (due != null && !isAdmin(o)) { o.adminDue = null; work = true }
            // A list that has not come after a while: ask its admin once to send it again (review C4).
            val waiting = m.waiting()
            if (waiting != null && now - waiting.first >= ASK_AFTER_MS && o.askedEpoch != waiting.second) {
                m.ask()?.let { (to, body) -> o.private += account(to) to Body.Group(GroupControl.ASK, body) }
                o.askedEpoch = waiting.second; work = true
            }
            val r = o.receiptDue
            if (r != null && r <= now && m.canSend) {
                m.receipt()?.let { o.send(GroupBody.RECEIPT, it, hubOnly = false) }
                o.receiptDue = null; work = true
            }
            val p = o.profileDue
            if (p != null && p <= now && m.canSend) {
                store.myProfile()?.let { profile ->
                    o.send(GroupBody.PROFILE, profile.encode(), hubOnly = false)
                    // A photo goes only through a hub (decision 64).
                    if (profile.avatar is Avatar.Photo) for (piece in store.myPhotoPieces()) o.send(GroupBody.MEMBER_PHOTO, piece, hubOnly = true)
                }
                o.profileDue = null; work = true
            }
            if (work || m.epoch != o.epochBefore) ok = finish(o, now) && ok
        }
        return ok
    }

    /** When [tick] has work next; null when nothing waits. */
    fun nextDue(): Long? = store.groups().mapNotNull { g ->
        val s = store.saved(g) ?: return@mapNotNull null
        listOfNotNull(s.receiptDue, s.profileDue, s.adminDue).minOrNull()
    }.minOrNull()

    /** This phone's name or picture changed: every group gets the new profile soon. */
    fun profileChanged(now: Long): Boolean = store.groups().all { g ->
        val o = open(g) ?: return@all true
        if (o.member.roster == null || o.member.state == GroupMember.State.REMOVED || o.member.state == GroupMember.State.LEFT) return@all true
        o.profileDue = now + profileDelay()
        store.commit(o.change())
    }

    // ---- admin actions (decision 72): each is a new epoch

    private fun edit(group: ByteArray, now: Long, next: (GroupRoster) -> GroupRoster?): Boolean {
        val o = open(group) ?: return false
        val list = o.member.roster ?: return false
        if (!isAdmin(o) || o.member.waitingOn() != null) return false
        val n = try { next(list) } catch (_: IllegalArgumentException) { null } ?: return false
        if (!canReach(n)) return false
        startEpoch(o, n, now)
        return finish(o, now)
    }

    fun remove(group: ByteArray, person: AccountId, now: Long) = edit(group, now) { l ->
        l.members.filterNot { account(it.identity) == person }.takeIf { it.size < l.members.size && it.size >= 2 }
            ?.let { GroupRoster(l.name, l.colour, it, l.avatar, l.recentForNew) }
    }

    fun makeAdmin(group: ByteArray, person: AccountId, now: Long) = edit(group, now) { l ->
        if (l.members.none { account(it.identity) == person && !it.admin }) null
        else GroupRoster(l.name, l.colour, l.members.map { if (account(it.identity) == person) GroupRoster.Member(it.identity, true, it.signer) else it }, l.avatar, l.recentForNew)
    }

    /** New name, colour, picture or "New members see recent messages" setting. */
    fun restyle(group: ByteArray, name: String, colour: Int, avatar: Avatar, recentForNew: Boolean, now: Long) = edit(group, now) { l ->
        GroupRoster(name, colour, l.members, avatar, recentForNew)
    }

    /** "Fix the group" after two lists at once: the next epoch from the list this admin holds (review C3). */
    fun fix(group: ByteArray, now: Long): Boolean {
        val o = open(group) ?: return false
        if (o.member.state != GroupMember.State.CONFLICT) return false
        return edit(group, now) { it }
    }

    /** "Ask Kofi again": the admin whose key waits for its list is asked to send them again. */
    fun askAgain(group: ByteArray, now: Long): Boolean {
        val o = open(group) ?: return false
        val (to, body) = o.member.ask() ?: return false
        o.private += account(to) to Body.Group(GroupControl.ASK, body)
        return store.commit(o.change())
    }

    /**
     * This phone leaves. The last admin names [nextAdmin] (decision 72 d): one epoch makes them an admin and leaves this
     * phone out. Others tell one admin, who drops them at its next epoch. A group nobody joined yet is simply dropped.
     */
    fun leave(group: ByteArray, nextAdmin: AccountId?, now: Long): Boolean {
        val o = open(group) ?: return false
        val list = o.member.roster
        if (list == null) {
            if (o.member.inviter == null) return store.commit(Change(group, deleted = true))
            o.member.leave()
            return finish(o, now)
        }
        val otherAdmins = list.members.filter { it.admin && !it.identity.contentEquals(me) }
        val rest = list.members.filterNot { it.identity.contentEquals(me) }
        if (list.isAdmin(me) && otherAdmins.isEmpty() && rest.size >= 2) {
            // The last admin names the next one and leaves in the same epoch (decision 72 d).
            val chosen = rest.firstOrNull { account(it.identity) == nextAdmin } ?: return false
            if (!o.member.canSend) return false
            val next = GroupRoster(list.name, list.colour, rest.map { if (it === chosen) GroupRoster.Member(it.identity, true, it.signer) else it },
                list.avatar, list.recentForNew)
            val out = o.member.newEpoch(next, now, random, leaving = true)
            for ((id, k) in out.keys) o.private += account(id.toByteArray()) to Body.Group(GroupControl.KEY, k)
            for (p in out.rosterPackets) o.packets += Packet(o.group, GroupMember.Sealed(p, list.let { o.epochBefore + 1 }, -1, false), false)
            return finish(o, now)
        }
        // The notice goes privately, so to an admin this phone has a contact for (an admin it does not know cannot be told).
        o.member.leave { store.identityOf(account(it)) != null }?.let { (to, body) -> o.private += account(to) to Body.Group(GroupControl.LEAVE, body) }
        return finish(o, now)
    }

    /** Drop a group this phone left or was removed from (its chat is the app's). */
    fun forget(group: ByteArray): Boolean {
        val o = open(group) ?: return false
        if (o.member.state != GroupMember.State.LEFT && o.member.state != GroupMember.State.REMOVED) return false
        return store.commit(Change(group, deleted = true))
    }

    companion object {
        const val MAX_GROUPS = 32
        const val MAX_INVITATIONS = 16
        const val MIN_QUIET_MS = 15_000L
        const val MAX_QUIET_MS = 60_000L
        const val MAX_PROFILE_DELAY_MS = 60_000L
        /** Joins and leavings an admin gathers into one epoch. */
        const val ADMIN_BATCH_MS = 60_000L
        /** How long a phone waits for a list before asking its admin once to send it again. */
        const val ASK_AFTER_MS = 10 * 60_000L
        /** Recent messages for new members (decision 74): the last 7 days, at most 50. */
        const val RECENT_MS = 7 * 86_400_000L
        const val RECENT_MAX = 50
    }
}

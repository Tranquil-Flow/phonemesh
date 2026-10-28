package org.phonemesh.app

import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubDelivery
import org.phonemesh.core.hub.HubRoutes
import org.phonemesh.core.hub.HubWire
import org.phonemesh.core.hub.MailPaths
import org.phonemesh.core.mail.Postman
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.ClaimedSender
import org.phonemesh.core.wire.SharedPost
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.Names
import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.phonemesh.ble.BleMeshTransport
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RadioPeer
import org.phonemesh.ble.RegionResult
import org.phonemesh.ble.TextMark
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.CountryRegions
import org.phonemesh.core.DeliveryRules
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.DraftFit
import org.phonemesh.core.DraftRules
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.MessageCodec
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackStore
import org.phonemesh.core.PeopleHeard
import org.phonemesh.core.LoraAirtime
import org.phonemesh.core.LoraShape
import org.phonemesh.core.RadioAllowance
import org.phonemesh.core.RadioTime
import org.phonemesh.core.PictureAssembler
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureSender
import org.phonemesh.core.PictureWire
import org.phonemesh.core.RadioReport
import org.phonemesh.core.Readiness
import org.phonemesh.core.RecordGesture
import org.phonemesh.core.SetupPlan
import org.phonemesh.core.readiness
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Everything that outlives one screen: radio, speech, packs, history, drafts, recording, playback.
 * One per process, created on first use; screens observe it between onStart and onStop, so rotation
 * never drops the radio. Main thread only; its work thread runs pack installs and never touches History.
 */
class AppCore private constructor(
    private val app: Context,
    radioFactory: (MeshRadio.Listener) -> MeshRadio,
    val speech: Speech,
    val history: History,
    val settings: Settings,
    val access: DeviceAccess,
    private val planAlarm: PlanAlarm,
    private val accountKeys: String = "phonemesh",
) : MeshRadio.Listener {
    enum class Change { RADIO, SCAN, MESSAGES, DELIVERY, PEERS, PACKS, RECORDING, PLAYBACK, NOTE, REGION }
    enum class RecordState { IDLE, RECORDING, TRANSCRIBING }
    fun interface Observer { fun onCoreChange(change: Change) }
    data class FoundRadio(val address: String, val name: String, val rssi: Int)
    /** [colour] and [met]: a person chat's contact (accounts); null for radio chats. [old]: a radio-addressed chat, read-only. */
    data class ChatRow(val chat: Chat, val title: String, val last: Message?, val peopleHeard: Int?, val colour: Int? = null, val met: Boolean? = null, val face: Face? = null) {
        val old: Boolean get() = chat.kind == ChatKind.DIRECT
    }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val observers = LinkedHashSet<Observer>()
    private val radio: MeshRadio = radioFactory(this)
    val countries = CountryRegions.bundled()
    /** The region check for the connected radio. It writes a region only after Yes, Change, helper Set or Try again. */
    val region = RegionSetup(
        countries,
        settings,
        object : RegionLink {
            override val address get() = settings.radioAddress
            override val connected get() = this@AppCore.connected
            override fun region() = radio.region()
            override fun setRegion(region: String, done: (RegionResult) -> Unit) {
                runCatching { radio.setRegion(region, done) }.onFailure {
                    Log.w(TAG, "region write refused", it)
                    done(RegionResult.DROPPED)
                }
            }
            override fun reconnect() = reconnectForRegion()
        },
        object : RegionClock {
            override fun after(ms: Long, task: Runnable) { main.postDelayed(task, ms) }
            override fun cancel(task: Runnable) = main.removeCallbacks(task)
        },
        guess = { BuildConfig.FAKE_COUNTRY.ifEmpty { null } ?: access.countryCode() },
        // A region set or confirmed may free what waits for a radio.
        changed = { changed(Change.REGION); main.post { sendQueued() } },
        log = { warn, line -> if (warn) Log.w(TAG, line) else Log.i(TAG, line) },
        now = { SystemClock.elapsedRealtime() },
    )
    val store = PackStore(File(app.filesDir, "packs"), GeneratedPackCatalog.entries, BuildConfig.ALLOW_UNKNOWN_PACKS)

    var connected = false
        private set
    var scanning = false
        private set
    /** How many times the radio has reported itself ready; a screen compares it to tell a new connection from an old one. */
    var readyReports = 0
        private set
    /** The radio the user last asked to connect to, until it connects. */
    var connectingAddress: String? = null
        private set
    private var connectingSince = 0L
    // The radio whose connection is ready now: its own name is kept under this address.
    private var readyAddress: String? = null
    // Android is pairing with that radio: the wait has no end of its own until the radio reports one.
    private var pairing = false
    // Test hook: tests shorten the connection wait.
    internal var connectWaitMs = CONNECT_WAIT_MS
    private val foundRadios = LinkedHashMap<String, FoundRadio>()
    val found: List<FoundRadio> get() = foundRadios.values.sortedByDescending { it.rssi }
    private var started = 0
    // Test hook: tests shorten the wait for a scan that never reports an end.
    internal var scanWaitMs = SCAN_WAIT_MS
    private val scanExpired = Runnable { stopScan() }
    private val connectExpired = Runnable { changed(Change.RADIO) }
    private val backgroundStop = Runnable { if (started == 0) { if (!roamingActive) leaveRadio(); hubClosed() } }
    // True from the moment the radio is let go in the background until a screen opens again.
    private var radioLetGo = false

    var openChatId: Long? = null
        private set
    private val drafts = HashMap<Long, String>()
    private val cutDrafts = HashSet<Long>()
    private val replyTargets = HashMap<Long, Long>()
    var recordState = RecordState.IDLE
        private set
    var recordingChatId: Long? = null
        private set
    var recordingStartedAt = 0L
        private set
    /** The microphone stays on without a finger (slide up, a quick tap, or a rotation mid-recording). */
    var recordLocked = false
    // Test hook: tests shorten the one-part limit.
    internal var recordLimitMs = RecordGesture.LIMIT_MS
    private var recordEpoch = 0
    private val recordLimit = Runnable { finishRecording() }
    var playingId: Long? = null
        private set
    private var playEpoch = 0

    var importing = false
        private set
    private var collecting = false
    private val poll = Runnable { pollDownloads() }
    private val planCheck = Runnable { sendDue() }
    var lastNote: UiText? = null
        private set

    // ---- Pictures (branch feat/image-messages): see docs/specs/2026-10-12-picture-messages-design.md
    private class Outgoing(val messageId: Long, val address: String, val sender: PictureSender)
    private val outgoing = HashMap<Long, Outgoing>()
    /** Packet id on the radio -> (message id, packet index) for the picture packet in flight. */
    private val picturePackets = HashMap<Long, Pair<Long, Int>>()
    private val assembler = PictureAssembler(now = { SystemClock.elapsedRealtime() })
    private val incoming = HashMap<Pair<String, Int>, Long>()
    private val pictureTick = object : Runnable {
        override fun run() {
            onPictureEvents(assembler.expire())
            if (assembler.pending > 0) main.postDelayed(this, PICTURE_TICK_MS)
        }
    }
    private val random = SecureRandom()
    private val personPictures = PersonPictures(history)
    private var pictureWorkAt: Long? = null
    val picturesDir: File get() = File(app.filesDir, "pictures").apply { mkdirs() }
    private val t1 = if (BuildConfig.T1_PROBE) RadioProbe.load("T1ProbeRun", app) { radio.sendBroadcastData(MeshRadio.ACCOUNT_PORT, it, PRIMARY_CHANNEL) } else null

    init {
        t1?.start()
        if (BuildConfig.T2_PROBE) RadioProbe.load("T2ProbeRun", app)?.start()
        history.expireWaiting()
        history.loseUnfinishedPictures()
        // Pictures received from people finish from their stored pieces, even before a radio is ready.
        main.post { materializePictures() }
        history.groupChat(PRIMARY_CHANNEL)
        // A new process (or a phone that restarted) arms the next planned message again.
        armPlans()
        // Recover local ciphertext immediately, even offline; waiting until expiry would lose it.
        // Post after construction because the mail timers are declared below.
        main.post { runPostman() }
        work.execute { runCatching { downloads().sweep() }.onFailure { Log.w(TAG, "download sweep failed", it) } }
    }

    // ---- Observers and words

    fun observe(observer: Observer) {
        observers += observer
        pollDownloads()
    }

    fun unobserve(observer: Observer) { observers -= observer }

    private fun changed(change: Change) = observers.toList().forEach { it.onCoreChange(change) }

    /** The chosen language's resources (the phone's before the first choice). */
    fun localized(): Context = Locales.wrap(app, settings.language)

    fun text(value: UiText): String = value.resolve(localized())

    fun note(value: UiText) {
        lastNote = value
        changed(Change.NOTE)
    }

    fun currentLanguage(): Language = LanguageRegistry.byTag(settings.language) ?: LanguageRegistry.default

    /** Cheap; download state lives in preferences and DownloadManager, so a new instance per use is fine. */
    fun downloads() = PackDownloads(localized(), store)

    // ---- Screens

    fun activityStarted() {
        started++
        radioLetGo = false
        main.removeCallbacks(backgroundStop)
        if (started == 1) { autoReconnect(); hubOpen() }
    }

    /** No background service in 3a: the radio is let go a minute after the last screen closes. */
    fun activityStopped() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) {
            main.removeCallbacks(hubDiscoveryTick)
            main.postDelayed(backgroundStop, BACKGROUND_GRACE_MS)
        }
    }

    private fun leaveRadio() {
        radioLetGo = true
        cancelRecording()
        stopPlayback()
        stopScan()
        connectingAddress = null
        main.removeCallbacks(connectExpired)
        runCatching { radio.disconnect() }
    }

    // ---- Radio

    fun startScan() {
        foundRadios.clear()
        scanning = true
        main.removeCallbacks(scanExpired)
        main.postDelayed(scanExpired, scanWaitMs)
        changed(Change.SCAN)
        runCatching { radio.scan() }.onFailure { main.removeCallbacks(scanExpired); scanning = false; changed(Change.SCAN) }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(scanExpired)
        runCatching { radio.stopScan() }
        changed(Change.SCAN)
    }

    fun connect(address: String, name: String) {
        settings.radioAddress = address
        settings.radioName = name
        stopScan()
        connectingAddress = address
        connectingSince = SystemClock.elapsedRealtime()
        pairing = false
        main.removeCallbacks(connectExpired)
        runCatching { radio.connect(address) }.onFailure { connectingAddress = null }
        // Nothing else reports the end of the wait, so the screens are told when it runs out.
        if (connectingAddress != null) main.postDelayed(connectExpired, connectWaitMs)
        changed(Change.RADIO)
    }

    /**
     * True while a connection asked for in the last 35 s has not finished (the radio gives up after 30 s), and for
     * as long as Android is pairing with that radio (the radio gives that up after 90 s).
     */
    fun isConnecting(address: String): Boolean = !connected && isAttempting(address)

    /** Like [isConnecting], but also true while another radio is still connected and this one was just asked for. */
    fun isAttempting(address: String): Boolean = connectingAddress == address &&
        (pairing || SystemClock.elapsedRealtime() - connectingSince < connectWaitMs)

    /** The user let the radio go: it is forgotten, so it does not reconnect by itself. */
    fun disconnect() {
        settings.radioAddress = null
        connectingAddress = null
        main.removeCallbacks(connectExpired)
        runCatching { radio.disconnect() }
        changed(Change.RADIO)
    }

    /** Reconnects the remembered radio without asking anything; false when it cannot right now. */
    fun autoReconnect(): Boolean {
        val address = settings.radioAddress ?: return false
        if (connected || isConnecting(address)) return false
        if (access.radioPermissionsMissing().isNotEmpty() || !access.bluetoothOn()) return false
        connect(address, settings.radioName ?: address)
        return true
    }

    /**
     * The region wait asks for the remembered radio again. A new connection ends the one being made, so an
     * attempt still in progress is left to finish (or to fail) first. Nothing reconnects once the radio was
     * let go in the background; the next screen reconnects and the check carries on.
     */
    internal fun reconnectForRegion() {
        val address = settings.radioAddress ?: return
        if (radioLetGo || isConnecting(address)) return
        if (access.radioPermissionsMissing().isNotEmpty() || !access.bluetoothOn()) return
        connect(address, settings.radioName ?: address)
    }

    /** Connected, and the region check has nothing to ask and nothing running. */
    fun radioReady(): Boolean = connected && region.settled

    /** Setup can use a communal radio through an authenticated hub; a linked socket is not enough. */
    fun setupConnectionReady(): Boolean = radioReady() || hubRoute(rest = false, answeredOnly = true) != null

    /** The connected radio's region as its last handshake reported it; null while it is away. */
    fun radioRegionNow(): String? = if (connected) radio.region() else null

    fun peers(): List<RadioPeer> = radio.peers()

    private fun knownName(id: String): String? =
        radio.peers().firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() && it != id }

    /**
     * Keeps the name the connected radio announces for itself, so the radio screen shows the name other people see.
     * A [fresh] connection that announces none forgets an older one.
     */
    private fun rememberOwnName(fresh: Boolean) {
        val address = readyAddress ?: return
        val name = radio.ownName()?.takeIf { it.isNotBlank() }
        if (name != null || fresh) settings.setRadioOwnName(address, name)
    }

    /** The radio ended the scan itself (never started, failed, throttled) or finished stopping one. */
    override fun onScanStopped() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(scanExpired)
        changed(Change.SCAN)
        changed(Change.RADIO)
    }

    override fun onState(connected: Boolean, detail: String) {
        val was = this.connected
        this.connected = connected
        // Diagnostics (scan stopped, ...) repeat the ready flag; only a false to true change is a new connection.
        val becameReady = connected && !was
        if (becameReady) readyReports++
        // Only success ends an attempt here; false is progress too (the radio reports it many times while connecting).
        val attempting = connectingAddress != null
        if (becameReady) endAttempt()
        readyAddress = if (becameReady) settings.radioAddress else readyAddress.takeIf { connected }
        if (becameReady) rememberOwnName(fresh = true)
        // The handshake that made it ready carried its channels: the group takes the primary one's name, or loses it.
        if (becameReady) readyAddress?.let { settings.setRadioChannelName(it, radio.channelName()) }
        // Only a real change of the link reaches the region check: ready means a new handshake has finished.
        if (becameReady) region.onReady()
        if (becameReady) { mailBlocked = false; mailQueue.clear() }
        if (was && !connected) {
            mailFlight = null
            mailQueue.clear()
            main.removeCallbacks(mailStalled)
        }
        if (becameReady) sendDue()
        if (becameReady) { sendQueued(); if (account() != null) main.post { runPostman() } }
        if (was && !connected) region.onLost()
        // The radio went: what was on its way is its own message's business now; the next waits for a radio again.
        if (was && !connected) { draining = null; main.removeCallbacks(drainStalled) }
        if (connected != was || attempting) changed(Change.RADIO)
    }

    /** The radio gave up on the connection asked for: the wait ends now rather than after 35 s. */
    override fun onConnectFailed() {
        if (connectingAddress == null) return
        endAttempt()
        changed(Change.RADIO)
    }

    /** Pairing stops the 35 s clock; when it is over the connection gets its 35 s from then. */
    override fun onPairing(waiting: Boolean) {
        if (connectingAddress == null) return
        pairing = waiting
        main.removeCallbacks(connectExpired)
        if (!waiting) {
            connectingSince = SystemClock.elapsedRealtime()
            main.postDelayed(connectExpired, connectWaitMs)
        }
        changed(Change.RADIO)
    }

    private fun endAttempt() {
        pairing = false
        connectingAddress = null
        main.removeCallbacks(connectExpired)
    }

    override fun onDevice(address: String, name: String, rssi: Int) {
        if (!scanning || (address !in foundRadios && foundRadios.size >= MAX_FOUND)) return
        foundRadios[address] = FoundRadio(address, name, rssi)
        changed(Change.SCAN)
    }

    override fun onPeersChanged() {
        rememberOwnName(fresh = false)
        history.renameAll(radio.peers().filter { it.name.isNotBlank() && it.name != it.id }.associate { it.id to it.name })
        changed(Change.PEERS)
    }

    override fun onText(sender: String, text: String, channel: Int, direct: Boolean) = onText(sender, text, channel, direct, TextMark())

    /**
     * Only the primary channel in 3a. Malformed text is dropped (logged), never shown. A reaction (Meshtastic's emoji mark)
     * is the emoji alone, as the official apps send it, and is kept under the message it answers, not as a bubble.
     */
    override fun onText(sender: String, text: String, channel: Int, direct: Boolean, mark: TextMark) {
        if (channel != PRIMARY_CHANNEL) return
        val received = try {
            MessageCodec.inspectIncoming(text)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "incoming message dropped", e)
            return
        }
        val chat = if (direct) history.directChat(sender, knownName(sender)) else history.groupChat(PRIMARY_CHANNEL)
        if (mark.reaction) {
            val emoji = text.trim()
            if (mark.replyTo == 0L || emoji.toByteArray(Charsets.UTF_8).size > MAX_REACTION_BYTES) return
            history.addReaction(chat.id, sender, emoji, mark.replyTo, System.currentTimeMillis())
            changed(Change.MESSAGES)
            return
        }
        val message = history.addIncoming(
            chat.id, sender, received.text, received.language, System.currentTimeMillis(), countUnread = openChatId != chat.id,
            packetId = mark.packetId.takeIf { it != 0L }, replyTo = mark.replyTo.takeIf { it != 0L },
        )
        changed(Change.MESSAGES)
        // Read aloud only while a screen is open, the microphone is free and nothing else is playing: never cut a voice off.
        if (settings.readAloud && observers.isNotEmpty() && recordState == RecordState.IDLE && playingId == null) play(message.id)
    }

    override fun onDelivery(packetId: Long, state: DeliveryState, detail: String) {
        if (onMailDelivery(packetId, state)) return
        onDrainReport(packetId, state)
        if (picturePackets.containsKey(packetId)) { onPicturePacket(packetId, state); return }
        if (packetId >= REF_FLOOR) return // Shared/account references are never radio acceptance IDs.
        val message = sharedFlight(packetId) ?: history.byPacket(packetId) ?: return
        val chat = history.chat(message.chatId) ?: return
        val current = message.status ?: return
        val next = DeliveryRules.next(current, RadioReport.valueOf(state.name), chat.kind == ChatKind.GROUP)
        if (next != current) {
            history.setStatus(message.id, next)
            changed(Change.DELIVERY)
        }
    }

    // ---- Chats

    /** The group is named after the chosen radio's primary channel when its owner named it, as that radio last reported. */
    fun chatTitle(chat: Chat): String = when {
        chat.kind == ChatKind.GROUP -> settings.radioAddress?.let { settings.radioChannelName(it) } ?: text(UiText(R.string.group_name))
        chat.name.isNotBlank() -> chat.name
        else -> text(unnamedRadio(chat.address))
    }

    /** Group and person chats first; old radio-addressed chats (read-only, plan step 12) after them. */
    fun chatRows(): List<ChatRow> = history.chats().map { chat ->
        val contact = if (chat.kind == ChatKind.PERSON) history.contactRow(chat.address) else null
        ChatRow(
            chat, contact?.name ?: chatTitle(chat), history.lastMessage(chat.id),
            if (chat.kind == ChatKind.GROUP) peopleHeard(chat.id) else null,
            contact?.colour, contact?.met, contact?.let { faceOf(it) } ?: groupFace(chat),
        )
    }.sortedBy { it.old }

    /** The contact colour of a person chat; null otherwise. */
    fun chatColour(chat: Chat): Int? = if (chat.kind == ChatKind.PERSON) history.contactRow(chat.address)?.colour else null

    /** What a person chat's avatar shows; null for other chats. */
    fun chatFace(chat: Chat): Face? = if (chat.kind == ChatKind.PERSON) history.contactRow(chat.address)?.let { faceOf(it) } else groupFace(chat)

    // ---- Private groups: what the screens show and do (decision 72; mockup reviewed 2026-10-27)

    /** A private group as its screens show it. */
    class GroupView(
        val chatId: Long, val hex: String, val name: String, val face: Face, val state: org.phonemesh.core.group.GroupMember.State,
        val listed: Boolean, val admin: Boolean, val canSend: Boolean, val waitingOn: String?, val recentForNew: Boolean,
        val people: Int, val invited: Int, val code: IntArray?,
    ) {
        val conflict get() = state == org.phonemesh.core.group.GroupMember.State.CONFLICT
        val gone get() = state == org.phonemesh.core.group.GroupMember.State.REMOVED || state == org.phonemesh.core.group.GroupMember.State.LEFT
    }

    /** One person in a group's details: on the list ([joined]) or invited and not joined yet. */
    class GroupPerson(val account: String, val name: String, val face: Face, val admin: Boolean, val me: Boolean, val contact: Boolean, val joined: Boolean)

    private fun groupRow(chat: Chat): GroupRows.GroupRow? =
        if (chat.kind == ChatKind.PRIVATE_GROUP && account() != null) runCatching { groupStore.rows.group(chat.address) }.getOrNull() else null

    private fun groupFace(chat: Chat): Face? {
        val row = groupRow(chat) ?: return null
        val avatar = runCatching { org.phonemesh.core.group.GroupAvatar.decode(row.avatar, 0).first }.getOrDefault(org.phonemesh.core.account.Avatar.Letter)
        val photo = (avatar as? org.phonemesh.core.account.Avatar.Photo)?.let { want ->
            groupStore.rows.photo(row.groupId, "")?.webp?.takeIf { org.phonemesh.core.account.AvatarPhotos.id(it).contentEquals(want.id) }?.let { decodeAvatar(it) }
        }
        return Face(row.name, row.colour, avatar, photo)
    }

    fun groupView(chatId: Long): GroupView? {
        val chat = history.chat(chatId) ?: return null
        val row = groupRow(chat) ?: return null
        val member = groups.member(AppGroupStore.hexBytes(row.groupId)) ?: return null
        val list = member.roster
        val code = list?.let {
            val c = member.safetyCode()
            val bits = ((c[0].toInt() and 0xFF) shl 16) or ((c[1].toInt() and 0xFF) shl 8) or (c[2].toInt() and 0xFF)
            IntArray(4) { i -> (bits ushr (18 - 6 * i)) and 63 }
        }
        val waiting = row.waitingOn?.let { personName(row.groupId, it) }
            ?: if (list == null && member.inviter != null) personName(row.groupId, org.phonemesh.core.account.AccountId.of(member.inviter!!).toString()) else null
        return GroupView(chatId, row.groupId, row.name, checkNotNull(groupFace(chat)), member.state, list != null, row.admin, member.canSend,
            waiting, row.recent, list?.members?.size ?: 1, AppGroupStore.decodeInvited(row.invited).size, code)
    }

    /** A group member's name: a contact's own, else the name they sent the group, else "Someone". */
    fun personName(groupHex: String, account: String): String {
        if (account == accounts.accountId.toString()) return accounts.profile()?.name ?: text(UiText(R.string.group_someone))
        history.contactRow(account)?.let { return it.name }
        return groupStore.rows.profile(groupHex, account)?.let { org.phonemesh.core.account.Profile.decode(it)?.name } ?: text(UiText(R.string.group_someone))
    }

    fun personFace(groupHex: String, account: String): Face {
        if (account == accounts.accountId.toString()) return ownFace() ?: Face("?", AccountStore.UNKNOWN_COLOUR, org.phonemesh.core.account.Avatar.Letter)
        history.contactRow(account)?.let { return faceOf(it) }
        val profile = groupStore.rows.profile(groupHex, account)?.let { org.phonemesh.core.account.Profile.decode(it) }
            ?: return Face(text(UiText(R.string.group_someone)), AccountStore.UNKNOWN_COLOUR, org.phonemesh.core.account.Avatar.Letter)
        val photo = (profile.avatar as? org.phonemesh.core.account.Avatar.Photo)?.let { want ->
            groupStore.rows.photo(groupHex, account)?.webp?.takeIf { org.phonemesh.core.account.AvatarPhotos.id(it).contentEquals(want.id) }?.let { decodeAvatar(it) }
        }
        return Face(profile.name, profile.colour, profile.avatar, photo)
    }

    /** Whether [account] is one of this phone's contacts (else the screens say "Not in your contacts"). */
    fun isContact(account: String): Boolean = history.contactRow(account) != null

    fun groupPeople(chatId: Long): List<GroupPerson> {
        val chat = history.chat(chatId) ?: return emptyList()
        val row = groupRow(chat) ?: return emptyList()
        val mine = accounts.accountId.toString()
        val listed = groupStore.rows.members(row.groupId).map { m ->
            GroupPerson(m.accountId, personName(row.groupId, m.accountId), personFace(row.groupId, m.accountId), m.admin, m.accountId == mine,
                isContact(m.accountId), true)
        }.ifEmpty { listOf(GroupPerson(mine, personName(row.groupId, mine), personFace(row.groupId, mine), row.admin, true, false, true)) }
        val invited = AppGroupStore.decodeInvited(row.invited).map { (a, _) ->
            val id = a.toString()
            GroupPerson(id, personName(row.groupId, id), personFace(row.groupId, id), false, false, true, false)
        }
        return listed + invited
    }

    /** Contacts that can be asked into a group (people added in person or by link; never old radio chats). */
    fun groupCandidates(): List<History.ContactRow> = history.contactRows().sortedBy { it.name.lowercase() }

    /** Makes a group and invites [people]; its chat id, or null when it could not be made. */
    fun createGroup(name: String, colour: Int, avatar: org.phonemesh.core.account.Avatar, photo: AvatarDraft?, people: List<String>): Long? {
        var chatId: Long? = null
        mailOperation {
            val now = System.currentTimeMillis()
            val group = groups.create(name, colour, avatar, false, people.map { AppMailStore.idOf(it) }, now) ?: return@mailOperation
            val hex = AppGroupStore.hex(group)
            if (photo != null && avatar is org.phonemesh.core.account.Avatar.Photo) {
                groupStore.rows.setPhoto(hex, "", GroupRows.Photo(photo.webp, photo.width, photo.height, photo.sketch))
            }
            chatId = groupStore.rows.group(hex)?.chatId
            flushGroups()
            schedulePostman()
        }
        changed(Change.MESSAGES)
        return chatId
    }

    /** Invitations shown with Join and Ignore, newest last, with who sent them. */
    fun groupInvitations(): List<Pair<org.phonemesh.core.group.GroupStore.Invitation, String>> =
        if (account() == null) emptyList() else runCatching { groupStore.invitations() }.getOrDefault(emptyList()).map {
            it to (history.contactRow(it.from.toString())?.name ?: text(UiText(R.string.group_someone)))
        }

    fun joinGroup(invitation: org.phonemesh.core.group.GroupStore.Invitation): Long? {
        var chatId: Long? = null
        mailOperation {
            if (groups.join(invitation, System.currentTimeMillis())) chatId = groupStore.rows.group(AppGroupStore.hex(invitation.invite.group))?.chatId
            flushGroups(); schedulePostman()
        }
        changed(Change.MESSAGES)
        return chatId
    }

    fun ignoreGroup(invitation: org.phonemesh.core.group.GroupStore.Invitation) {
        mailOperation { groups.ignore(invitation) }
        changed(Change.MESSAGES)
    }

    /** Runs a group action, then sends what it left; false when the group refused it. */
    private fun groupAction(action: (Long) -> Boolean): Boolean {
        var done = false
        mailOperation { done = action(System.currentTimeMillis()); flushGroups(); schedulePostman() }
        changed(Change.MESSAGES)
        return done
    }

    private fun groupBytes(chatId: Long): ByteArray? = history.chat(chatId)?.takeIf { it.kind == ChatKind.PRIVATE_GROUP }?.let { AppGroupStore.hexBytes(it.address) }

    fun addToGroup(chatId: Long, people: List<String>): Boolean = groupBytes(chatId)?.let { g -> groupAction { groups.invite(g, people.map { AppMailStore.idOf(it) }, it) } } ?: false
    fun removeFromGroup(chatId: Long, account: String): Boolean = groupBytes(chatId)?.let { g -> groupAction { groups.remove(g, AppMailStore.idOf(account), it) } } ?: false
    fun makeGroupAdmin(chatId: Long, account: String): Boolean = groupBytes(chatId)?.let { g -> groupAction { groups.makeAdmin(g, AppMailStore.idOf(account), it) } } ?: false
    fun fixGroup(chatId: Long): Boolean = groupBytes(chatId)?.let { g -> groupAction { groups.fix(g, it) } } ?: false
    fun askGroupAgain(chatId: Long): Boolean = groupBytes(chatId)?.let { g -> groupAction { groups.askAgain(g, it) } } ?: false
    fun leaveGroup(chatId: Long, nextAdmin: String?): Boolean =
        groupBytes(chatId)?.let { g -> groupAction { groups.leave(g, nextAdmin?.let { a -> AppMailStore.idOf(a) }, it) } } ?: false

    /** New name, colour or picture ([photo] for a group photo), or the "New members see recent messages" setting. */
    fun restyleGroup(chatId: Long, name: String, colour: Int, avatar: org.phonemesh.core.account.Avatar, photo: AvatarDraft?, recentForNew: Boolean): Boolean {
        val g = groupBytes(chatId) ?: return false
        return groupAction { now ->
            if (photo != null && avatar is org.phonemesh.core.account.Avatar.Photo) {
                groupStore.rows.setPhoto(AppGroupStore.hex(g), "", GroupRows.Photo(photo.webp, photo.width, photo.height, photo.sketch))
            }
            groups.restyle(g, name, colour, avatar, recentForNew, now)
        }
    }

    fun setGroupRecent(chatId: Long, on: Boolean): Boolean {
        val v = groupView(chatId) ?: return false
        val avatar = runCatching { org.phonemesh.core.group.GroupAvatar.decode(groupStore.rows.group(v.hex)!!.avatar, 0).first }.getOrNull() ?: return false
        return restyleGroup(chatId, v.name, v.face.colour, avatar, null, on)
    }

    /** Drops a group this phone left or was removed from, and its chat. */
    fun forgetGroup(chatId: Long): Boolean = deleteChat(chatId)

    /** "Delivered to N of M" for this phone's own group message: (N, M), or null for any other message. */
    fun groupDelivered(message: Message): Pair<Int, Int>? {
        if (message.direction != Direction.OUT || history.chat(message.chatId)?.kind != ChatKind.PRIVATE_GROUP) return null
        val audience = runCatching { groupStore.rows.audience(message.id) }.getOrNull() ?: return null
        return groupStore.rows.deliveredCount(message.id).coerceAtMost(audience) to audience
    }

    /** A [MessageKind.NOTE]'s words in the current language. */
    fun groupNote(chatId: Long, code: String): String {
        val hex = history.chat(chatId)?.address.orEmpty()
        fun names(list: String) = list.split(",").filter { it.length == 16 }.map { personName(hex, it) }
        return when {
            code == GroupNotes.MADE -> text(UiText(R.string.group_made))
            code == GroupNotes.YOU_JOINED -> text(UiText(R.string.group_you_joined))
            code == GroupNotes.YOU_LEFT -> text(UiText(R.string.group_you_left))
            code == GroupNotes.REMOVED -> text(UiText(R.string.group_removed))
            code.startsWith("joined:") -> names(code.removePrefix("joined:")).joinToString(" ") { text(UiText(R.string.group_joined, listOf(it))) }
            code.startsWith("left:") -> names(code.removePrefix("left:")).joinToString(" ") { text(UiText(R.string.group_left, listOf(it))) }
            code.startsWith("unreachable:") -> names(code.removePrefix("unreachable:")).joinToString(" ") { text(UiText(R.string.group_cannot_reach, listOf(it))) }
            else -> ""
        }
    }

    /** A contact's face, with their photo when this phone holds the one their profile names. */
    fun faceOf(row: History.ContactRow): Face = Face.of(row).let { it.withPhoto(photoOf(row.accountId, it.avatar)) }

    /** What others see for this phone; null before a name is saved. */
    fun ownFace(): Face? = accounts.profile()?.let { Face.of(it).withPhoto(ownPhoto()) }

    // ---- Photo avatars (decision 50 D)

    /** A photo chosen for this phone's picture, made ready: square, at most 64 px and 1 KB, as contacts get it. */
    class AvatarDraft(val webp: ByteArray, val width: Int, val height: Int, val sketch: ByteArray, val preview: android.graphics.Bitmap) {
        val avatar: org.phonemesh.core.account.Avatar.Photo
            get() = org.phonemesh.core.account.Avatar.Photo(org.phonemesh.core.account.AvatarPhotos.id(webp))
    }

    /** Off the main thread: [uri] made into an avatar photo, or null when it cannot be read or made small enough. */
    fun prepareAvatar(uri: android.net.Uri): AvatarDraft? {
        val source = PictureMaker.load(app.contentResolver, uri, AVATAR_LOAD_SIDE) ?: return null
        val side = minOf(source.width, source.height)
        val square = android.graphics.Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        return draftOf(PictureMaker.shrink(square, org.phonemesh.core.account.AvatarPhotos.MAX_SIDE, grey = false))
    }

    /** [small] encoded at the best quality that fits 1 KB, decoded back as the preview (what contacts will see). */
    fun draftOf(small: android.graphics.Bitmap): AvatarDraft? {
        for (quality in AVATAR_QUALITIES) {
            val webp = PictureMaker.webp(small, quality)
            if (webp.size !in 1..org.phonemesh.core.account.AvatarPhotos.MAX_BYTES) continue
            val preview = decodeAvatar(webp) ?: return null
            return AvatarDraft(webp, small.width, small.height, PictureMaker.sketchOf(small), preview)
        }
        return null
    }

    /** At most one photo change a day (decision 50): whether a different photo may be saved now. */
    fun photoChangeAllowed(now: Long = System.currentTimeMillis()): Boolean =
        history.ownPhoto()?.let { now - it.changed >= PHOTO_GAP_MS } ?: true

    /** Saves the profile with [photo] as its picture; false (nothing saved, a note says why) within a day of the last change. */
    fun saveProfilePhoto(name: String, colour: Int, photo: AvatarDraft): Boolean {
        val now = System.currentTimeMillis()
        val same = history.ownPhoto()?.webp?.contentEquals(photo.webp) == true
        if (!same && !photoChangeAllowed(now)) { note(UiText(R.string.photo_once_a_day)); return false }
        if (!same) history.setOwnPhoto(History.OwnPhoto(photo.webp, photo.width, photo.height, photo.sketch, now))
        saveProfile(name, colour, photo.avatar)
        return true
    }

    /** This phone's photo when its profile shows one; null otherwise. */
    fun ownPhoto(): android.graphics.Bitmap? {
        val wanted = accounts.profile()?.avatar as? org.phonemesh.core.account.Avatar.Photo ?: return null
        val webp = history.ownPhoto()?.webp?.takeIf { org.phonemesh.core.account.AvatarPhotos.id(it).contentEquals(wanted.id) } ?: return null
        return decodeAvatar(webp)
    }

    /** One photo per contact, the one their profile names now: a contact sending new photos replaces it (review B1). */
    private val photoCache = HashMap<String, Pair<String, android.graphics.Bitmap>>()

    /** The photo [accountId] chose, when this phone holds the one [avatar] names; null shows their letter. */
    fun photoOf(accountId: String, avatar: org.phonemesh.core.account.Avatar): android.graphics.Bitmap? {
        val wanted = avatar as? org.phonemesh.core.account.Avatar.Photo ?: return null
        val key = wanted.toString()
        synchronized(photoCache) { photoCache[accountId]?.takeIf { it.first == key }?.let { return it.second } }
        val webp = history.avatarPhoto(accountId)?.takeIf { org.phonemesh.core.account.AvatarPhotos.id(it).contentEquals(wanted.id) } ?: return null
        val bitmap = decodeAvatar(webp) ?: return null
        synchronized(photoCache) { photoCache[accountId] = key to bitmap }
        return bitmap
    }

    /** A received photo as a bitmap, only when it is a picture of at most 64 px a side. */
    private fun decodeAvatar(webp: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(webp, 0, webp.size, bounds)
        val max = org.phonemesh.core.account.AvatarPhotos.MAX_SIDE
        if (bounds.outWidth !in 1..max || bounds.outHeight !in 1..max) return null
        return android.graphics.BitmapFactory.decodeByteArray(webp, 0, webp.size)
    }

    /** This phone's chosen picture: a letter until another is chosen. */
    fun ownAvatar(): org.phonemesh.core.account.Avatar = accounts.profile()?.avatar ?: org.phonemesh.core.account.Avatar.Letter

    /** A radio's name for screens: "Radio EE01" when it has none (blank, missing, or just its address). */
    fun radioLabel(name: String?, address: String?): String =
        if (name.isNullOrBlank() || name == address) text(unnamedRadio(address.orEmpty())) else name

    /** A radio on the radio screen: the name it announces once it has connected, its Bluetooth [name] before that. */
    fun ownRadioLabel(name: String?, address: String?): String =
        radioLabel(address?.let { settings.radioOwnName(it) } ?: name, address)

    fun senderName(id: String): String = ClaimedSender.parse(id)?.name ?: knownName(id) ?: text(unnamedRadio(id))

    /** People in a group: one per claimed account or old radio, however often a claimed name changes. */
    fun peopleHeard(chatId: Long): Int = PeopleHeard.count(history.sendersHeard(chatId).map { ClaimedSender.person(it) }, null)

    private fun ownAccountId(): AccountId? = history.accountRow()?.identity?.let { AccountId.of(it) }

    /** This phone's account id and name when a shared post can carry them; null before a name is saved or if the saved one cannot be posted. */
    private fun sharedIdentity(): Pair<AccountId, String>? = history.accountRow()
        ?.takeIf { it.name.isNotEmpty() && Names.valid(it.name) && it.name.toByteArray(Charsets.UTF_8).size <= Names.MAX_BYTES }
        ?.let { AccountId.of(it.identity) to it.name }

    private fun canPost(): Boolean = sharedIdentity() != null

    /** A shared message that cannot be built: shown as a refusal, never a reason to send other bytes or to wait for a radio. */
    private class Unsendable(cause: Exception) : RuntimeException(cause)

    /**
     * Radio packet id of a shared post in flight -> its message. Delivery reports name the radio's id; replies and reactions
     * name the post's reference, which is what the message keeps in History. In memory only: a report cannot outlive the
     * radio session that made it.
     */
    private val sharedFlights = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > 64
    }

    private fun sharedFlight(packetId: Long): Message? = sharedFlights[packetId]?.let { history.message(it) }

    /** The radio id a message was last handed to the radio under: kept in memory for shared posts, in History for old texts. */
    private fun radioIdOf(messageId: Long): Long? =
        sharedFlights.entries.lastOrNull { it.value == messageId }?.key ?: history.message(messageId)?.packetId

    private fun sharedFrame(identity: Pair<AccountId, String>, kind: SharedPost.Kind, language: String, text: String, ref: ByteArray?, atMs: Long): ByteArray =
        try {
            SharedPost.encode(SharedPost.Post(
                kind, identity.first, atMs / 1000, identity.second,
                if (kind == SharedPost.Kind.REACTION) "" else MessageCodec.canonical(language), ref, text,
            ))
        } catch (e: IllegalArgumentException) { throw Unsendable(e) }

    /**
     * Hands one text to the radio. A group message is a shared post (port 303): plain, or a reply naming the post answered by
     * its reference. The message keeps that reference as its packet id (from its own time, so a resend and a restart give
     * the same one); the radio's id goes only to [sharedFlights]. An answer to an old message (no reference exists for it)
     * and every non-group chat keep the old tagged text with the radio-id mark. A name or text the codec refuses is never
     * replaced by other bytes: it throws [Unsendable].
     */
    private fun sendText(messageId: Long, text: String, language: String, chat: Chat, replyTo: Long?) {
        val answersOld = replyTo != null && replyTo in 1 until REF_FLOOR
        if (chat.kind != ChatKind.GROUP || answersOld) {
            history.attachPacket(messageId, radio.send(MessageCodec.encode(text, language), destinationOf(chat), PRIMARY_CHANNEL, replyTo ?: 0L))
            return
        }
        val identity = sharedIdentity() ?: throw Unsendable(IllegalStateException("no postable name"))
        val message = history.message(messageId) ?: throw Unsendable(IllegalStateException("message is gone"))
        val answers = replyTo?.takeIf { it >= REF_FLOOR }
        val frame = sharedFrame(
            identity, if (answers != null) SharedPost.Kind.REPLY else SharedPost.Kind.TEXT, language, text,
            answers?.let { AppMailStore.refBytes(it) }, message.time,
        )
        val radioId = radio.sendBroadcastData(MeshRadio.SHARED_CHAT_PORT, frame, PRIMARY_CHANNEL)
        sharedFlights.values.removeAll { it == messageId }
        sharedFlights[radioId] = messageId
        history.attachPacket(messageId, AppMailStore.refNumber(SharedPost.reference(identity.first, message.time / 1000, text)))
    }

    /** One reaction on the radio: a shared reaction for a post that has a reference, the old emoji mark for an old message. */
    private fun sendReaction(chat: Chat, emoji: String, target: Long): Long {
        if (chat.kind != ChatKind.GROUP || target < REF_FLOOR) return radio.send(emoji, destinationOf(chat), PRIMARY_CHANNEL, replyTo = target, reaction = true)
        val identity = sharedIdentity() ?: throw Unsendable(IllegalStateException("no postable name"))
        val frame = sharedFrame(identity, SharedPost.Kind.REACTION, "", emoji, AppMailStore.refBytes(target), System.currentTimeMillis())
        return radio.sendBroadcastData(MeshRadio.SHARED_CHAT_PORT, frame, PRIMARY_CHANNEL)
    }

    private fun receiveShared(payload: ByteArray, radioId: Long) {
        when (val read = SharedPost.read(payload)) {
            null -> return
            is SharedPost.Read.V1 -> receiveOldPost(read.message, radioId)
            is SharedPost.Read.V2 -> receivePost(read.post)
        }
    }

    /**
     * A shared post (port 303, version 2). The name is only claimed: it is kept as the post gave it and never matched to a
     * saved contact, so no contact's name or colour can be borrowed (review F9). The message keeps the post's reference,
     * computed from the sender's own id, time and text, so replies and reactions from every phone find it. A copy of a post
     * already kept (a resend, a relay) and a copy of this phone's own post are dropped.
     */
    private fun receivePost(post: SharedPost.Post) {
        if (post.sender == ownAccountId()) return
        val chat = history.groupChat(PRIMARY_CHANNEL)
        val key = ClaimedSender.key(post.sender, post.name)
        if (post.kind == SharedPost.Kind.REACTION) {
            history.addReaction(chat.id, ClaimedSender.person(key), post.text, AppMailStore.refNumber(post.ref!!), System.currentTimeMillis())
            changed(Change.MESSAGES)
            return
        }
        val number = AppMailStore.refNumber(post.reference())
        val kept = history.byRadioId(chat.id, number)
        if (kept != null && ClaimedSender.parse(kept.senderId.orEmpty())?.account == post.sender) return
        keepHeard(history.addIncoming(
            chat.id, key, post.text, post.language, System.currentTimeMillis(), countUnread = openChatId != chat.id,
            packetId = number, replyTo = post.ref?.let { AppMailStore.refNumber(it) },
        ))
    }

    /** A version-1 post: no time or kind, so no shared reference; it keeps the radio id it arrived with, like an old text. */
    private fun receiveOldPost(post: org.phonemesh.core.wire.SharedChatWire.Message, radioId: Long) {
        if (post.sender == ownAccountId()) return
        try { MessageCodec.validateText(post.text) } catch (e: IllegalArgumentException) {
            Log.w(TAG, "shared post dropped", e)
            return
        }
        val chat = history.groupChat(PRIMARY_CHANNEL)
        keepHeard(history.addIncoming(
            chat.id, ClaimedSender.key(post.sender, post.name), post.text, post.language, System.currentTimeMillis(),
            countUnread = openChatId != chat.id, packetId = radioId.takeIf { it != 0L },
        ))
    }

    private fun keepHeard(message: Message) {
        changed(Change.MESSAGES)
        if (settings.readAloud && observers.isNotEmpty() && recordState == RecordState.IDLE && playingId == null) play(message.id)
    }

    fun groupChatId(): Long = history.groupChat(PRIMARY_CHANNEL).id

    fun startDirectChat(peer: RadioPeer): Long = history.directChat(peer.id, peer.name.takeIf { it.isNotBlank() && it != peer.id }).id

    fun openChat(chatId: Long) {
        openChatId = chatId
        history.clearUnread(chatId)
        changed(Change.MESSAGES)
    }

    fun closeChat(chatId: Long) {
        if (openChatId == chatId) openChatId = null
    }

    // ---- Drafts and recording

    fun draft(chatId: Long): String? = drafts[chatId]

    /** The draft was cut at the one-part limit and has not been edited since. */
    fun draftWasCut(chatId: Long): Boolean = chatId in cutDrafts

    fun setDraft(chatId: Long, text: String?) {
        cutDrafts.remove(chatId)
        if (text.isNullOrEmpty()) drafts.remove(chatId) else drafts[chatId] = text
    }

    fun draftFit(chatId: Long): DraftFit = fitFor(chatId, drafts[chatId].orEmpty())

    /**
     * The text bytes a draft in [chatId] may use: for the group, what is left of one 228-byte shared post after its header,
     * this phone's name, the language and (for a reply) the reference. Null for every other chat, and for an answer to an old
     * message, which goes out as old tagged text and keeps the old limit. Before a name exists the longest name is assumed.
     */
    private fun sharedRoom(chatId: Long, language: String): Int? {
        if (history.chat(chatId)?.kind != ChatKind.GROUP) return null
        val target = replyTarget(chatId)
        if (target != null && (target.packetId ?: 0L) < REF_FLOOR) return null
        val nameBytes = history.accountRow()?.name?.toByteArray(Charsets.UTF_8)?.size?.takeIf { it > 0 } ?: Names.MAX_BYTES
        return SharedPost.room(if (target != null) SharedPost.Kind.REPLY else SharedPost.Kind.TEXT, nameBytes, MessageCodec.canonical(language))
    }

    /** Whether [text] is a sendable draft in [chatId], by that chat's actual packet budget. */
    fun fitFor(chatId: Long, text: String): DraftFit {
        val tag = currentLanguage().tag
        return sharedRoom(chatId, tag)?.let { DraftRules.fitWithin(text, it) } ?: DraftRules.fit(text, tag)
    }

    /** [text] cut to fit [chatId]'s budget, as [DraftRules.trimToFit] does for the old one. */
    fun trimFor(chatId: Long, text: String): String {
        val tag = currentLanguage().tag
        return sharedRoom(chatId, tag)?.let { DraftRules.trimWithin(text, it) } ?: DraftRules.trimToFit(text, tag)
    }

    fun readiness(): Readiness = store.readiness(settings.engine, currentLanguage().tag)

    /** Voice needs its packs and a usable route. A hub connection alone is not a pinned answer. */
    fun canTalk(): Boolean = readiness() is Readiness.Ready &&
        (connected || hubRoute(rest = true, answeredOnly = true) != null)

    fun startRecording(chatId: Long): Boolean {
        if (recordState != RecordState.IDLE || importing || collecting) return false
        val pack = readiness() as? Readiness.Ready ?: return false
        stopPlayback()
        val epoch = ++recordEpoch
        recordState = RecordState.RECORDING
        recordingChatId = chatId
        recordLocked = false
        recordingStartedAt = SystemClock.elapsedRealtime()
        main.postDelayed(recordLimit, recordLimitMs)
        changed(Change.RECORDING)
        speech.startRecording(onStarted = {}) recorded@{ samples, error ->
            if (epoch != recordEpoch) return@recorded
            main.removeCallbacks(recordLimit)
            if (samples == null) {
                endRecording()
                note(UiText(recordErrorText(error)))
                changed(Change.RECORDING)
                return@recorded
            }
            recordState = RecordState.TRANSCRIBING
            changed(Change.RECORDING)
            speech.transcribe(pack, samples) heard@{ text ->
                if (epoch != recordEpoch) return@heard
                endRecording()
                if (text.isNullOrBlank()) {
                    note(UiText(R.string.transcribe_failed))
                } else {
                    val heard = text.trim()
                    val kept = trimFor(chatId, heard)
                    setDraft(chatId, kept)
                    if (kept != heard) cutDrafts += chatId
                }
                changed(Change.RECORDING)
            }
        }
        return true
    }

    /** Stops the microphone; the transcription becomes the chat's draft. */
    fun finishRecording() {
        if (recordState != RecordState.RECORDING) return
        main.removeCallbacks(recordLimit)
        speech.stopRecording()
    }

    fun cancelRecording() {
        if (recordState == RecordState.IDLE) return
        recordEpoch++
        val wasRecording = recordState == RecordState.RECORDING
        endRecording()
        if (wasRecording) speech.stopRecording()
        changed(Change.RECORDING)
    }

    private fun endRecording() {
        main.removeCallbacks(recordLimit)
        recordState = RecordState.IDLE
        recordingChatId = null
        recordLocked = false
    }

    private fun recordErrorText(error: RecordError?): Int = when (error) {
        RecordError.TOO_SHORT -> R.string.record_too_short
        RecordError.NO_PERMISSION -> R.string.mic_denied
        else -> R.string.record_failed
    }

    // ---- Sending

    /** Sends the chat's draft. Nothing is sent without this call (the ➤ button). */
    fun send(chatId: Long): Boolean {
        val text = drafts[chatId]?.trim().orEmpty()
        // No region yet, or a region write is running: nothing goes out and the draft stays for the card to explain.
        if (region.blocksSending) { changed(Change.REGION); return false }
        val language = currentLanguage().tag
        if (fitFor(chatId, text) != DraftFit.FITS) return false
        val chat = history.chat(chatId) ?: return false
        // A shared post carries the sender's name: without a postable one nothing goes out and the draft stays.
        if (chat.kind == ChatKind.GROUP && !canPost()) { note(UiText(R.string.add_name_first)); return false }
        // Old radio-addressed chats are read only: nothing goes out, whatever the draft says.
        if (chat.kind == ChatKind.DIRECT) { note(UiText(R.string.old_chat_read_only)); return false }
        if (playingId == DRAFT_PLAYBACK) stopPlayback()
        val message = history.addOutgoing(chatId, text, language, System.currentTimeMillis(), replyTarget(chatId)?.packetId)
        drafts.remove(chatId)
        cutDrafts.remove(chatId)
        replyTargets.remove(chatId)
        // Before any notification: observers read setup state from this flag while they render.
        settings.firstMessageSent = true
        if (chat.kind == ChatKind.PERSON) sendPerson(message.id, chat, text, language, message.replyTo)
        else if (chat.kind == ChatKind.PRIVATE_GROUP) sendGroup(message.id, chat, listOf(Body.encode(personBody(text, language, message.replyTo)) to false))
        else transmit(message.id, text, language, chat, message.replyTo)
        changed(Change.MESSAGES)
        return true
    }

    /** Group bodies for message [messageId]: sealed, kept, then out by hub and radio; FAILED when the group cannot send now. */
    private fun sendGroup(messageId: Long, chat: Chat, bodies: List<Pair<ByteArray, Boolean>>): Boolean {
        var sent = false
        if (!mailOperation {
            sent = groups.send(AppGroupStore.hexBytes(chat.address), bodies, messageId, System.currentTimeMillis()) != null
            if (sent) flushGroups()
            schedulePostman()
        } || !sent) { runCatching { history.setStatus(messageId, DeliveryStatus.FAILED) }; return false }
        return true
    }

    // ---- Person chats (accounts plan step 13): wire v2 through the Postman, broadcast on port 301

    /** The messaging engine; only touched once a name (and so the account keys) exists. */
    private val mailStore by lazy { AppMailStore(history, accounts) }
    val postman: Postman by lazy {
        Postman(accounts, mailStore, groups = Postman.GroupInbox { from, body, now -> groups.takePrivate(from, body, now) })
    }

    // ---- Private groups (v0.1.2, decisions 47, 66, 69, 72, 74): the engine, its storage, and its packets' way out.
    val groupStore: AppGroupStore by lazy { AppGroupStore(history, accounts, Vault()) }
    val groups: org.phonemesh.core.group.GroupPost by lazy { org.phonemesh.core.group.GroupPost(accounts.identity, groupStore) }

    /**
     * Private bodies the group engine left for contacts go to the Postman (a full window keeps them for later; a
     * contact without a session drops them), then every group packet still waiting goes to a hub or the radio.
     * A private body may go twice after a crash: receivers take it twice without harm.
     */
    /** Group key rows already reported as "cannot reach" in this run (one note each). */
    private val keysNoted = HashSet<Long>()

    /** When group state was last pruned (once an hour); tests set it back to run the prune at once. */
    internal var groupsPrunedAt = 0L

    /** A contact's name, else a name they sent some group, else "Someone". */
    private fun nameOfAnyone(account: String): String =
        history.contactRow(account)?.name ?: groupStore.rows.anyProfile(account)?.let { org.phonemesh.core.account.Profile.decode(it)?.name }
            ?: text(UiText(R.string.group_someone))

    /** Once an hour: old group state goes (review G9-10), and anyone who never got a key is named. */
    private fun pruneGroups(now: Long) {
        if (now - groupsPrunedAt < 3_600_000L) return
        groupsPrunedAt = now
        for (a in groupStore.prune(now).keysNotSent) note(UiText(R.string.group_key_not_sent, listOf(nameOfAnyone(a.toString()))))
    }

    private fun flushGroups() {
        val now = System.currentTimeMillis()
        for ((rowId, to, body) in groupStore.privates()) {
            val out = try { postman.send(to, body, null, now) } catch (_: IllegalStateException) {
                if (body.kind == org.phonemesh.core.group.GroupControl.KEY) {
                    // A key is kept for a contact who may come back (it is let go after the 72-hour horizon, with a note),
                    // and the person is told who cannot be reached (review G9-7).
                    if (keysNoted.add(rowId)) note(UiText(R.string.group_cannot_reach, listOf(nameOfAnyone(to.toString()))))
                    continue
                }
                groupStore.rows.dropPrivate(rowId); continue
            } finally { body.wipe() }
            if (out == null) continue
            groupStore.rows.dropPrivate(rowId)
            push(out)
        }
        for (row in groupStore.rows.outWaiting()) pushId(-row.id)
    }

    private fun sendPerson(messageId: Long, chat: Chat, text: String, language: String, replyTo: Long?) {
        val body = personBody(text, language, replyTo)
        if (!mailOperation {
            val now = System.currentTimeMillis()
            val contact = AppMailStore.idOf(chat.address)
            val out = postman.send(contact, body, messageId, now)
            if (out != null) push(out)
            profileAfterMessage(contact, now)?.let { push(it) }
            photoAfterMessage(contact, now).forEach { push(it) }
            schedulePostman()
        }) runCatching { history.setStatus(messageId, DeliveryStatus.FAILED) }
    }

    /**
     * Profile changes reach a contact behind the next message sent to them (profile spec PF5): one update with the
     * newest profile when they were not sent it yet, at most one an hour, so edits never fan out to everyone at once
     * and cost one small packet only after a change. It is an ordinary private packet: kept, resent, acknowledged.
     */
    private fun profileAfterMessage(contact: org.phonemesh.core.account.AccountId, now: Long): Postman.Out? = history.transaction {
        val own = accounts.profile() ?: return@transaction null
        val (sent, at) = history.profileSent(contact.toString())
        if (own.revision <= sent || now - at < PROFILE_GAP_MS) return@transaction null
        val out = postman.send(contact, Body.ProfileUpdate(own), null, now)
        history.setProfileSent(contact.toString(), own.revision, now)
        out
    }

    /**
     * This phone's photo follows the next message to a contact who does not have it yet (decision 50 D): its pieces as
     * ordinary private packets, kept, resent and acknowledged. Never on the shared chat. Nothing when the profile shows
     * no photo. Only through a hub (decision 64): with no hub in reach it waits for a later message, and the pieces
     * never go on this phone's radio, nor does the hub air them; the contact gets it when they fetch mail from a hub.
     */
    private fun photoAfterMessage(contact: org.phonemesh.core.account.AccountId, now: Long): List<Postman.Out> = history.transaction {
        val wanted = accounts.profile()?.avatar as? org.phonemesh.core.account.Avatar.Photo ?: return@transaction emptyList()
        val own = history.ownPhoto()?.takeIf { org.phonemesh.core.account.AvatarPhotos.id(it.webp).contentEquals(wanted.id) }
            ?: return@transaction emptyList()
        if (history.photoSent(contact.toString())?.contentEquals(wanted.id) == true) return@transaction emptyList()
        if (hubWire() == null) return@transaction emptyList()
        val outs = org.phonemesh.core.account.AvatarPhotos.pieces(own.webp, own.width, own.height, own.sketch)
            .mapNotNull { postman.send(contact, Body.AvatarPiece(it), null, now) }
            .onEach { history.markHubOnly(it.outboxId) }
        history.setPhotoSent(contact.toString(), wanted.id)
        outs
    }

    private fun personBody(text: String, language: String, replyTo: Long?): Body =
        if (replyTo != null && replyTo >= REF_FLOOR) Body.Reply(AppMailStore.refBytes(replyTo), language, text) else Body.Text(language, text)

    /** Retry owns the existing bytes; only a bubble that never sealed can start a new packet. */
    private fun retryPerson(message: Message, chat: Chat): Boolean =
        if (message.kind == MessageKind.PICTURE) retryPersonPicture(message, chat) else retryPersonText(message, chat)

    private fun retryPersonText(message: Message, chat: Chat): Boolean = mailOperation {
        val now = System.currentTimeMillis()
        val contact = AppMailStore.idOf(chat.address)
        val out = history.transaction {
            val stored = history.outbox(chat.address).lastOrNull { it.messageId == message.id }
            history.setStatus(message.id, DeliveryStatus.WAITING)
            when {
                stored != null && stored.state != Postman.Status.SUPERSEDED.name -> {
                    if (stored.state == Postman.Status.DELIVERED.name) throw org.phonemesh.core.mail.MailStore.Refused()
                    history.updateOutbox(stored.id, stored.tries, now + Postman.FIRST_RETRY_MS, Postman.Status.WAITING.name)
                    Postman.Out(contact, stored.packet, stored.id, stored.isReceipt)
                }
                history.held(chat.address).any { it.messageId == message.id } -> null
                else -> postman.send(contact, personBody(message.text, message.language ?: currentLanguage().tag, message.replyTo), message.id, now)
            }
        }
        out?.let { push(it) }
        schedulePostman()
    }

    /** Storage refusal is recoverable, never an invitation to publish uncommitted bytes or report delivery. */
    private fun mailOperation(action: () -> Unit): Boolean {
        try { action(); return true } catch (failure: Exception) {
            if (failure is PersonPictures.IdLimit) {
                note(UiText(R.string.picture_id_limit))
                return false
            }
            if (failure !is SQLiteException && failure !is org.phonemesh.core.mail.MailStore.Refused &&
                failure !is java.security.GeneralSecurityException && failure !is java.io.IOException) throw failure
            main.removeCallbacks(postmanTick)
            // A transient write refusal must not permanently cancel local recovery/expiry.
            // Back off rather than spin; runPostman still honours the blocked-radio boundary.
            main.postDelayed(postmanTick, 5_000L)
            note(UiText(R.string.mail_save_failed))
            return false
        }
    }

    private data class MailFlight(val packetId: Long, val outboxId: Long)
    private val mailQueue = LinkedHashSet<Long>()
    /** This phone's private mail on air in the last hour, at most 180 s (AE4 in-app backstop); kept across restarts. */
    private val allowance = RadioAllowance.decode(settings.radioAllowance)
    private val allowanceRoom = Runnable { pumpMail() }

    /** Milliseconds on air for [bytes] of private mail on the radio's setting (LongFast, the default, when unknown). */
    private fun mailMillis(bytes: Int) = RadioTime.seconds(listOf(bytes), knownShape() ?: LoraAirtime.DEFAULT) * 1000.0

    /** True when the radio allowance has room for this mail now; otherwise the queue waits and tries again then. */
    internal fun allowanceRoomFor(bytes: Int, now: Long = System.currentTimeMillis()): Boolean {
        val room = allowance.nextRoom(now, mailMillis(bytes)) ?: return true
        main.removeCallbacks(allowanceRoom)
        main.postDelayed(allowanceRoom, room - now)
        return false
    }

    /** Radio time this phone's private mail used in the last hour, in milliseconds. */
    fun radioAllowanceUsed(now: Long = System.currentTimeMillis()): Double = allowance.used(now)
    private var mailFlight: MailFlight? = null
    private var mailBlocked = false
    private val mailStalled = Runnable { pauseMail() }

    /** Queue durable row identities, never credit a synchronous send() return as radio acceptance. */
    private fun push(out: Postman.Out) = pushId(out.outboxId)

    /** [id] is an outbox row, or minus a group outbox row (private groups share the radio and hub ways). */
    private fun pushId(id: Long) {
        if (mailFlight?.outboxId == id || id in hubFlight) return
        // A joined hub comes first; the stock radio only gets what the hub did not take (see hubOffered).
        if (offerHub(id)) return
        mailQueue.add(id)
        pumpMail()
    }

    private fun pumpMail() {
        if (!mayTransmit() || mailBlocked || mailFlight != null || draining != null) return
        while (mailQueue.isNotEmpty()) {
            val id = mailQueue.first()
            if (id < 0) {
                val g = groupStore.rows.out(-id)
                // Done already, or only for a hub (decisions 64, 69): it waits for one, never this radio.
                if (g == null || g.state != AppGroupStore.STATE_WAITING || g.hubOnly) { mailQueue.remove(id); continue }
                if (!allowanceRoomFor(g.packet.size)) return
                mailQueue.remove(id)
                val packetId = try {
                    radio.sendBroadcastData(MeshRadio.ACCOUNT_PORT, g.packet, PRIMARY_CHANNEL)
                } catch (_: RuntimeException) { pauseMail(); return }
                allowance.record(System.currentTimeMillis(), mailMillis(g.packet.size))
                settings.radioAllowance = allowance.encode()
                mailFlight = MailFlight(packetId, id)
                main.postDelayed(mailStalled, DRAIN_WAIT_MS)
                return
            }
            val row = history.outboxRow(id)
            // A QR replacement or a peer receipt can retire queued work before its turn.
            if (row == null || row.state !in setOf("WAITING", "SENT", "HUB")) { mailQueue.remove(id); continue }
            // Decision 64: hub-only rows stay durable and wait for a hub; never this radio.
            if (history.isHubOnly(id)) { mailQueue.remove(id); continue }
            // AE4 backstop: past 180 s this hour, the packet keeps its place and goes when there is room again.
            if (!allowanceRoomFor(row.packet.size)) return
            mailQueue.remove(id)
            val packetId = try {
                radio.sendBroadcastData(MeshRadio.ACCOUNT_PORT, row.packet, PRIMARY_CHANNEL)
            } catch (_: RuntimeException) { pauseMail(); return }
            allowance.record(System.currentTimeMillis(), mailMillis(row.packet.size))
            settings.radioAllowance = allowance.encode()
            mailFlight = MailFlight(packetId, id)
            main.postDelayed(mailStalled, DRAIN_WAIT_MS)
            return
        }
    }

    /** Unknown/failed writes stay durable; a new ready connection may offer the same bytes again. */
    private fun pauseMail() {
        mailFlight = null
        mailQueue.clear()
        mailBlocked = true
        main.removeCallbacks(mailStalled)
        main.removeCallbacks(postmanTick)
        note(UiText(R.string.mail_radio_retry))
        changed(Change.DELIVERY)
        // Transmission is blocked, not incoming ciphertext retention maintenance.
        mailOperation { schedulePostman() }
    }

    private fun onMailDelivery(packetId: Long, state: DeliveryState): Boolean {
        val flight = mailFlight?.takeIf { it.packetId == packetId } ?: return false
        when (state) {
            DeliveryState.QUEUED -> {
                if (!mailOperation { if (flight.outboxId < 0) groupStore.aired(-flight.outboxId) else postman.transmitted(flight.outboxId) }) {
                    mailFlight = null; mailQueue.clear(); mailBlocked = true
                    main.removeCallbacks(mailStalled)
                    return true
                }
                mailFlight = null
                main.removeCallbacks(mailStalled)
                changed(Change.DELIVERY)
                // A hello committed during the previous flight may not be in the in-memory queue yet.
                main.post { if (mailOperation { pumpMail(); sendQueued() }) runPostman() }
            }
            DeliveryState.FAILED, DeliveryState.UNCONFIRMED -> pauseMail()
            else -> Unit
        }
        return true
    }

    private val postmanTick = Runnable { runPostman() }

    /** Receipts, held messages and resends that are due; then the next wake-up while the app runs. */
    fun runPostman() {
        if (account() == null) return
        mailOperation {
            if (postman.recoverIncoming(System.currentTimeMillis()).isNotEmpty()) changed(Change.MESSAGES)
            groups.tick(System.currentTimeMillis())
            pruneGroups(System.currentTimeMillis())
            materializePictures()
            val hubUp = hubWire() != null
            if (!hubUp && (!mayTransmit() || mailBlocked || mailFlight != null)) { schedulePostman(); return@mailOperation }
            if (!hubUp && mailQueue.isNotEmpty()) { pumpMail(); return@mailOperation }
            val due = postman.due(System.currentTimeMillis())
            (due + postman.unsent()).distinctBy { it.outboxId }.forEach { push(it) }
            flushGroups()
            changed(Change.DELIVERY)
            schedulePostman()
        }
    }

    private fun schedulePostman() {
        main.removeCallbacks(postmanTick)
        val mailNext = if (hubWire() == null && (!mayTransmit() || mailBlocked || mailFlight != null || mailQueue.isNotEmpty()))
            postman.nextIncomingExpiry() else postman.nextDue()
        val groupNext = runCatching { groups.nextDue() }.getOrNull()
        val next = listOfNotNull(mailNext, pictureWorkAt, groupNext).minOrNull() ?: return
        main.postDelayed(postmanTick, (next - System.currentTimeMillis()).coerceIn(1_000L, 30 * 60_000L))
    }

    // ---- Hub paths (accounts plan step 16): a pinned hub on its Wi-Fi comes before the stock radio.
    // Network work runs on two daemon threads and every result returns to the main thread. A packet goes as the exact
    // durable bytes of its outbox row (its own port), never sealed again, and a hub only earns "reached the hub" (review F17).
    // Everything in the one outbox takes this path, so a hello or a picture queued there needs no separate route.
    // Order of ways: the hub over Wi-Fi, then the same hub over Bluetooth (GATT; TLS pinned inside the stream), then the stock radio.
    // Background roaming is opt-in and real only while HubRoamingService runs for the current epoch ("Hub roaming" below);
    // the setting alone is only the person's wish, and the Bluetooth hub link is not roaming by itself.

    /** The way to a hub's Wi-Fi: [create] sets it on a phone, tests set a fake. Null means no hub path at all. */
    internal var hub: HubLink? = null

    /** The way to the same hub over Bluetooth: [create] sets it on a phone, tests may set a fake. Null means no Bluetooth hub path. */
    internal var bluetoothHub: HubLink? = null
    private val hubSendIo = Executors.newSingleThreadExecutor { Thread(it, "hub-send").apply { isDaemon = true } }
    private val hubPollIo = Executors.newSingleThreadExecutor { Thread(it, "hub-poll").apply { isDaemon = true } }
    private val hubCursor by lazy { HubCursor(history) }
    private val hubFlight = HashSet<Long>()
    @Volatile private var failedHubWire: HubWire? = null
    private var hubLinkRevision = 0L
    private var hubKnownCodes: Set<String> = emptySet()
    private var hubScanCodes: Set<String>? = null
    private var hubScanSince = 0L
    private var hubEpoch = 0
    private var hubPolling = false
    private var hubBacklog = false
    private var hubChecked = false
    /** When each hub way last failed a send; a way rests [hubRestMs] after that and the next way in the order is used. */
    private val hubFailedAt = HashMap<MailPaths.Path, Long>()
    // A healthy mailbox poll must not erase the cooldown after a failed send.
    private val hubPollFailedAt = HashMap<MailPaths.Path, Long>()
    private val hubWaitSeconds = 25
    private val hubPage = 200
    private val hubRetryMs = 10_000L
    private val hubIdleMs = 30_000L
    private val hubEmptyMs = 1_000L
    private val hubRestMs = 30_000L
    /** Pending requests and the mailbox cursor commit together, including before the first contact exists. */
    private val hubHellos = true
    private val hubPollTick = Runnable { hubPollSoon() }
    private val hubRetryTick = Runnable { runPostman() }
    /** Retry silent/expired discovery while a screen stays open; never grants background roaming. */
    private val hubDiscoveryTick = Runnable {
        if (started > 0 && hubCard() != null) {
            if (hub?.wire() == null && bluetoothHub?.wire() == null) hubOpen()
            else armHubDiscovery()
        }
    }

    private fun armHubDiscovery() {
        main.removeCallbacks(hubDiscoveryTick)
        if (started > 0 && hubCard() != null) main.postDelayed(hubDiscoveryTick, roamingRetryMs)
    }
    /** Hub ways that completed a request over the pinned key since the hub was joined; a bare connection is never in here. */
    private val hubAnswered = HashMap<MailPaths.Path, HubWire>()
    private var hubFailure: HubFailure? = null

    /** The platform side of roaming: [create] sets it on a phone, tests set a fake. Null means roaming cannot start. */
    internal var roamingHost: RoamingHost? = null
    /** The foreground service runs for the current epoch: only then does hub work outlive the last screen. */
    var roamingActive = false
        private set
    /** The service was asked for and has not reported yet; ends in [roamingAttach] or after [roamingStartWaitMs]. */
    var roamingStarting = false
        private set
    /** Why roaming is off after a refusal or a failure, until the person tries again. */
    var roamingBlock: RoamingBlock? = null
        private set
    private var roamingEpoch = 0
    // Test hook: tests shorten the wait for a service that never reports.
    internal var roamingStartWaitMs = ROAMING_START_WAIT_MS
    private val roamingStartLate = Runnable { if (roamingStarting) stopRoaming(RoamingBlock.START_REFUSED) }
    private val roamingRetryTick = Runnable { retryRoaming() }
    private val roamingRetryMs = 60_000L

    private fun retryRoaming() {
        if (!roamingActive || hubCard() == null) return
        // The links fence their own live attempts; this tick also recovers a scan that ended silently.
        if (hub?.wire() == null && bluetoothHub?.wire() == null) hubOpen()
        autoReconnect()
        main.postDelayed(roamingRetryTick, roamingRetryMs)
    }

    /** The hub the order of ways puts first, if any: Wi-Fi joined, else Bluetooth connected, and the app open or roaming on. */
    private fun hubAllowed(): HubWire? = hubRoute(rest = false)?.wire

    /** [hubAllowed], minus the ways that failed a moment ago, so a dead way is not asked again every second and the next way is used. */
    private fun hubWire(): HubWire? = hubRoute(rest = true)?.wire

    private fun hubRoute(rest: Boolean, answeredOnly: Boolean = false, polling: Boolean = false): HubRoutes.Route? {
        val now = SystemClock.elapsedRealtime()
        val rested = if (rest) hubFailedAt.filterValues { now - it < hubRestMs }.keys +
            (if (polling) hubPollFailedAt.filterValues { now - it < hubRestMs }.keys else emptySet()) else emptySet()
        val wifi = hub?.wire()?.takeIf { !answeredOnly || hubAnswered[MailPaths.Path.WIFI_HUB] === it }
        val bluetooth = bluetoothHub?.wire()?.takeIf { !answeredOnly || hubAnswered[MailPaths.Path.BLUETOOTH_HUB] === it }
        return HubRoutes.pick(wifi, bluetooth, mayTransmit(), started > 0, roamingActive, rested)
    }

    private fun hubCard(): HubCard? = settings.hubCard?.let { HubCard.parse(it) }

    /** Anything a poll or send started for an earlier hub, or before leaving, is thrown away when it comes back. */
    private fun hubReset() {
        hubEpoch++
        hubChecked = false
        hubBacklog = false
        hubKnownCodes = emptySet()
        hubScanCodes = null
        hubScanSince = 0L
        hubAnswered.clear()
        hubPollFailedAt.clear()
        hubFailure = null
    }

    /** Hands the row's exact bytes to the hub on its own thread. True when the hub path has taken over the row. */
    private fun offerHub(id: Long): Boolean {
        val route = hubRoute(rest = true) ?: return false
        val pin = hubCard()?.pin ?: return false
        if (id < 0) {
            val g = groupStore.rows.out(-id) ?: return false
            if (g.state != AppGroupStore.STATE_WAITING) return false
            return offerBytes(id, route, pin, MeshRadio.ACCOUNT_PORT, g.packet.copyOf(), g.hubOnly)
        }
        val row = history.outboxRow(id) ?: return false
        if (row.state !in setOf("WAITING", "SENT", "HUB")) return false
        // Decision 24: a hub airs a packet once, so a private message's scheduled retry goes back to the same accepting
        // hub as the same stored bytes; the hub decides whether to air it again. Other accepted rows are not reoffered.
        if (HubCustody(history).has(id, pin) && (row.port != MeshRadio.ACCOUNT_PORT || row.isReceipt)) return false
        return offerBytes(id, route, pin, row.port, row.packet.copyOf(), history.isHubOnly(id))
    }

    private fun offerBytes(id: Long, route: HubRoutes.Route, pin: String, port: Int, bytes: ByteArray, held: Boolean): Boolean {
        val wire = route.wire
        val epoch = hubEpoch
        if (hubFlight.isEmpty()) failedHubWire = null
        hubFlight.add(id)
        try {
            hubSendIo.execute {
                // Serial IO: one dead wire must not consume a full timeout for every queued picture part.
                val result = if (failedHubWire === wire) HubDelivery.Result.NOT_REACHED else try {
                    HubDelivery.offer(wire, port, bytes, held)
                } catch (_: RuntimeException) { HubDelivery.Result.NOT_REACHED }
                if (result != HubDelivery.Result.REACHED) failedHubWire = wire
                main.post { hubOffered(id, route.path, wire, pin, epoch, result) }
            }
        } catch (_: RejectedExecutionException) {
            hubFlight.remove(id)
            return false
        }
        return true
    }

    private fun hubOffered(id: Long, path: MailPaths.Path, wire: HubWire, pin: String, epoch: Int, result: HubDelivery.Result) {
        hubFlight.remove(id)
        if (epoch != hubEpoch) {
            // The hub was left or replaced while this was out: the answer is about another hub, so the row is offered again.
            if (!offerHub(id)) { mailQueue.add(id); mailOperation { pumpMail() } }
            return
        }
        val current = currentHubWire(path) === wire
        if (!current && result != HubDelivery.Result.REACHED) {
            // A dead previous connection says nothing about its same-pin replacement.
            if (!offerHub(id)) { mailQueue.add(id); mailOperation { pumpMail() } }
            return
        }
        if (result == HubDelivery.Result.REACHED) {
            if (current) {
                hubFailedAt.remove(path)
                hubAnswered[path] = wire
                if (hubFailure?.path == path) hubFailure = null
                keepHubBluetooth()
            }
            markReachedHub(id, pin)
            changed(Change.DELIVERY)
            return
        }
        // Not reached: this way rests a little and the same stored bytes go on to the next way, the other hub, else the stock radio.
        hubFailedAt[path] = SystemClock.elapsedRealtime()
        hubAnswered.remove(path)
        hubFailure = HubFailure(path, result)
        keepHubBluetooth()
        changed(Change.RADIO)
        main.removeCallbacks(hubRetryTick)
        main.postDelayed(hubRetryTick, hubRestMs)
        if (offerHub(id)) return
        mailQueue.add(id)
        mailOperation { pumpMail() }
    }

    /** "Reached the hub" for this one row, by id (a hello has no code to acknowledge): never Delivered (review F17). */
    private fun markReachedHub(id: Long, pin: String) {
        mailOperation { if (id < 0) history.transaction { groupStore.reachedHub(-id) } else mailStore.markHubReached(id, pin) }
    }

    private fun currentHubWire(path: MailPaths.Path): HubWire? = when (path) {
        MailPaths.Path.WIFI_HUB -> hub?.wire()
        MailPaths.Path.BLUETOOTH_HUB -> bluetoothHub?.wire()
        else -> null
    }

    /** Long-polls the hub for mail to the codes this phone expects: one request at a time, on its own thread. */
    private fun hubPollSoon() {
        main.removeCallbacks(hubPollTick)
        if (hubPolling || account() == null) return
        val route = hubRoute(rest = true, polling = true) ?: run {
            if (hubAllowed() != null) main.postDelayed(hubPollTick, hubRetryMs)
            return
        }
        val wire = route.wire
        val path = route.path
        val card = hubCard() ?: return
        val currentCodes = runCatching { (postman.expectedCodes() + groups.expectedCodes(System.currentTimeMillis())).toSet() }.getOrNull()
        if (currentCodes == null) { main.postDelayed(hubPollTick, hubRetryMs); return }
        val pin = card.pin
        val durableSince = runCatching { hubCursor.get(pin) }.getOrNull()
        if (durableSince == null) { main.postDelayed(hubPollTick, hubRetryMs); return }
        // Codes can become requestable after the cursor passed their letters. Replay-safe catch-up uses a
        // stable query across pages; newly gained codes trigger another pass only after this pass completes.
        if (hubScanCodes == null && !hubKnownCodes.containsAll(currentCodes)) {
            hubScanCodes = currentCodes
            hubScanSince = 0L
        }
        val scanning = hubScanCodes != null
        val codes = hubScanCodes ?: currentCodes
        val since = if (scanning) hubScanSince else durableSince
        val epoch = hubEpoch
        val linkRevision = hubLinkRevision
        val check = !hubChecked
        val wait = if (hubBacklog || check || scanning) 0 else hubWaitSeconds
        hubPolling = true
        try {
            hubPollIo.execute {
                val outcome = try {
                    val newest = if (check) wire.latest() else null
                    newest to wire.mail(since, codes, wait, hubHellos)
                } catch (_: IOException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                main.post {
                    if (epoch == hubEpoch && (currentHubWire(path) !== wire || linkRevision != hubLinkRevision)) {
                        hubPolling = false
                        main.post { hubPollSoon() }
                    } else {
                        hubPollAnswered(path, epoch, outcome != null)
                        hubPolled(epoch, pin, since, durableSince, scanning, codes, outcome)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            hubPolling = false
        }
    }

    private fun hubPolled(epoch: Int, pin: String, since: Long, durableSince: Long, scanning: Boolean, codes: Set<String>, outcome: Pair<Long?, List<HubClient.Mail>>?) {
        hubPolling = false
        // The hub was left or replaced while this was out: nothing in it is acted on, and the cursor stays where it was.
        if (epoch != hubEpoch) { main.post { hubPollSoon() }; return }
        if (outcome == null) { main.postDelayed(hubPollTick, hubRetryMs); return }
        val (newest, got) = outcome
        if (newest != null) {
            hubChecked = true
            if (newest < durableSince) {
                // The hub lost its mail: our position means nothing there any more.
                mailOperation { hubCursor.reset(pin) }
                hubBacklog = false
                hubScanSince = 0L
                main.postDelayed(hubPollTick, 0L)
                return
            }
        }
        var stored = true
        for (item in got.sortedBy { it.seq }) {
            if (!hubLetter(pin, item)) { stored = false; break }
        }
        if (stored) {
            if (scanning) {
                hubScanSince = got.maxOfOrNull { it.seq } ?: since
                if (got.size < hubPage) { hubKnownCodes = codes; hubScanCodes = null }
            } else hubKnownCodes = codes
        }
        hubBacklog = stored && got.size >= hubPage
        main.postDelayed(hubPollTick, when { !stored -> hubRetryMs; got.isEmpty() -> hubEmptyMs; else -> 0L })
    }

    /**
     * One letter from the hub. Opening it and moving the cursor past it are one History transaction: a letter that did
     * not commit leaves the cursor where it was, so the next poll offers it again. Letters that are not for this phone
     * (other ports, hellos nobody asked for) are definitively ignored and the cursor moves past them the same way.
     */
    private fun hubLetter(pin: String, item: HubClient.Mail): Boolean {
        var shown = false
        var request: Accounts.Request? = null
        val committed = mailOperation {
            history.transaction {
                if (item.port == MeshRadio.ACCOUNT_PORT) {
                    if (Start.isHello(item.packet)) request = accountsFlow.onPacket(item.packet)
                    else {
                        val now = System.currentTimeMillis()
                        shown = groups.receive(item.packet, now) || postman.receive(item.packet, now) != null
                    }
                }
                hubCursor.advance(pin, item.seq)
            }
            schedulePostman()
        }
        if (committed) {
            request?.let { note(UiText(R.string.add_wants_to_talk, listOf(it.name))) }
            changed(if (shown || request != null) Change.MESSAGES else Change.DELIVERY)
        }
        return committed
    }

    private fun hubChanged() {
        hubLinkRevision++
        changed(Change.DELIVERY)
        hubChecked = false
        keepHubBluetooth()
        main.post { runPostman(); hubPollSoon() }
    }

    /** A merely linked Wi-Fi socket cannot displace the working pinned Bluetooth route. */
    private fun keepHubBluetooth() {
        val card = hubCard() ?: return
        if (hubRoute(rest = true, answeredOnly = true)?.path == MailPaths.Path.WIFI_HUB) bluetoothHub?.leave()
        else if (started > 0 || roamingActive) bluetoothHub?.join(card) { main.post { hubChanged() } }
    }

    /** A screen opened: join the saved hub's Wi-Fi (the system asks the person once to connect). */
    private fun hubOpen() {
        val card = hubCard() ?: return
        hub?.join(card) { main.post { hubChanged() } }
        // Both ways start together; Bluetooth is released only after Wi-Fi answers through its pinned key.
        bluetoothHub?.join(card) { main.post { hubChanged() } }
        armHubDiscovery()
    }

    /** The last screen has been closed for a while: leave the hub's Wi-Fi (closing its sockets) and stop polling, unless roaming is on. */
    private fun hubClosed() {
        // The setting alone keeps nothing: only a running roaming service does.
        if (roamingActive) return
        hubReset()
        main.removeCallbacks(hubPollTick)
        main.removeCallbacks(hubRetryTick)
        main.removeCallbacks(hubDiscoveryTick)
        hub?.leave()
        bluetoothHub?.leave()
    }

    /** The hub QR was scanned in person (the only way a hub is trusted): keep it, forget the old hub, join now. */
    fun useHub(card: HubCard) {
        settings.hubCard = card.encode()
        hubReset()
        hubFailedAt.clear()
        hub?.leave()
        bluetoothHub?.leave()
        if (started > 0) hubOpen()
    }

    /** Forgets the hub: roaming ends, both hub transports close and the saved card (with the Wi-Fi password) is erased. */
    fun forgetHub() {
        stopRoaming()
        settings.hubCard = null
        hubReset()
        hubFailedAt.clear()
        main.removeCallbacks(hubPollTick)
        main.removeCallbacks(hubRetryTick)
        main.removeCallbacks(hubDiscoveryTick)
        hub?.leave()
        bluetoothHub?.leave()
        changed(Change.RADIO)
        changed(Change.DELIVERY)
        // What waited on the hub is offered to the radio now, from the same stored bytes.
        main.post { runPostman() }
    }

    /** Tries the saved hub's ways again (after a permission was granted); nothing is joined while no screen is open and roaming is off. */
    fun retryHub() {
        hubFailedAt.clear()
        if (started > 0 || roamingActive) hubOpen()
    }

    /** What the hub screen shows. A wire is a connection only; a way is [HubWay.ANSWERED] after a request over the pinned key. */
    fun hubStatus(): HubStatus {
        val saved = hubCard() != null
        val wifiPossible = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        return HubStatus(
            saved,
            if (wifiPossible) hubWay(hub, MailPaths.Path.WIFI_HUB) else HubWay.NONE,
            hubWay(bluetoothHub, MailPaths.Path.BLUETOOTH_HUB),
            if (saved) hubRoute(rest = true)?.path else null,
            if (saved) hubFailure else null,
        )
    }

    private fun hubWay(link: HubLink?, path: MailPaths.Path): HubWay = when {
        hubCard() == null || link == null -> HubWay.NONE
        link.wire() == null -> HubWay.SEARCHING
        hubAnswered[path] === link.wire() -> HubWay.ANSWERED
        else -> HubWay.LINKED
    }

    /** A poll finished: it answered over the pinned key, or it did not. Anything about an older hub is ignored. */
    private fun hubPollAnswered(path: MailPaths.Path, epoch: Int, answered: Boolean) {
        val wire = currentHubWire(path)
        if (epoch != hubEpoch) return
        if (answered) hubPollFailedAt.remove(path) else hubPollFailedAt[path] = SystemClock.elapsedRealtime()
        if (answered == (wire != null && hubAnswered[path] === wire)) return
        if (answered) {
            if (wire != null) hubAnswered[path] = wire
            if (hubFailure?.path == path) hubFailure = null
        } else {
            hubAnswered.remove(path)
            hubFailure = HubFailure(path, HubDelivery.Result.NOT_REACHED)
        }
        keepHubBluetooth()
        changed(Change.RADIO)
    }

    // ---- Hub roaming (accounts decision 9): opt-in, default off. Every start, stop and late callback carries an epoch;
    // turning roaming off, forgetting the hub and every failure bump it, so a late callback cannot restore a stopped service.
    // Roaming holds the hub work only. The stock radio is still let go after the grace, as without roaming.

    /** Why roaming cannot start now, or null: a saved hub and the Bluetooth permissions the hub link needs. */
    fun roamingPrerequisite(): RoamingBlock? = when {
        hubCard() == null -> RoamingBlock.NO_HUB
        access.radioPermissionsMissing().isNotEmpty() -> RoamingBlock.PERMISSION
        else -> null
    }

    /**
     * The person's choice. On: refused with a note (and a false) when a prerequisite is missing or Android will not start the
     * service; nothing is kept on in that case. The setting turns on here but roaming is real only after [roamingAttach].
     */
    fun setHubRoaming(on: Boolean): Boolean {
        if (!on) { stopRoaming(); return true }
        if (roamingActive || roamingStarting) return true
        roamingPrerequisite()?.let { stopRoaming(it); return false }
        return startRoaming()
    }

    private fun startRoaming(): Boolean {
        val host = roamingHost
        if (host == null) { stopRoaming(RoamingBlock.START_REFUSED); return false }
        val epoch = ++roamingEpoch
        settings.hubRoaming = true
        roamingBlock = null
        roamingStarting = true
        main.removeCallbacks(roamingStartLate)
        main.postDelayed(roamingStartLate, roamingStartWaitMs)
        if (!host.start(epoch)) { stopRoaming(RoamingBlock.START_REFUSED); return false }
        changed(Change.RADIO)
        return true
    }

    /**
     * Roaming is off for good: the wish, the service and the hub work that only roaming kept. [block] says why when a refusal
     * or a failure did it; the person gets that sentence as a note. The epoch moves first, so the service's own end is ignored.
     */
    private fun stopRoaming(block: RoamingBlock? = null) {
        roamingEpoch++
        main.removeCallbacks(roamingStartLate)
        main.removeCallbacks(roamingRetryTick)
        settings.hubRoaming = false
        roamingActive = false
        roamingStarting = false
        roamingBlock = block
        runCatching { roamingHost?.stop() }
        if (block != null) note(UiText(block.words))
        // Without a screen, the hub work only roaming kept ends now, not after the grace.
        if (started == 0) { leaveRadio(); hubClosed() }
        changed(Change.RADIO)
    }

    /** What a starting service learns before it goes to the foreground. */
    fun roamingGate(epoch: Int): RoamingGate = when {
        epoch != roamingEpoch || !settings.hubRoaming || !roamingStarting -> RoamingGate.STALE
        roamingPrerequisite() != null -> RoamingGate.BLOCKED
        else -> RoamingGate.RUN
    }

    /** The service is in the foreground for [epoch]. False means it must leave at once: that start was cancelled, replaced or is no longer allowed. */
    fun roamingAttach(epoch: Int): Boolean {
        when (roamingGate(epoch)) {
            RoamingGate.STALE -> return false
            RoamingGate.BLOCKED -> { stopRoaming(roamingPrerequisite() ?: RoamingBlock.START_REFUSED); return false }
            RoamingGate.RUN -> Unit
        }
        main.removeCallbacks(roamingStartLate)
        roamingStarting = false
        roamingActive = true
        roamingBlock = null
        radioLetGo = false
        autoReconnect()
        main.removeCallbacks(roamingRetryTick)
        main.postDelayed(roamingRetryTick, roamingRetryMs)
        // Both hub ways start together, as when a screen opens; hubChanged lets Bluetooth go the moment Wi-Fi has a wire.
        hubOpen()
        changed(Change.RADIO)
        return true
    }

    /** The service for [epoch] ended or could not start. An older epoch's end changes nothing. */
    fun roamingServiceEnded(epoch: Int, block: RoamingBlock) {
        if (epoch == roamingEpoch && (roamingActive || roamingStarting)) stopRoaming(block)
    }

    /** A real screen opened: a standing choice whose service died with the process starts again (a screen may start it). */
    fun resumeRoaming() {
        if (!settings.hubRoaming || roamingActive || roamingStarting) return
        roamingPrerequisite()?.let { stopRoaming(it); return }
        startRoaming()
    }

    private fun receivePerson(payload: ByteArray) {
        mailOperation {
            // A private group's packet is read by its group; anything else may be private mail.
            if (groups.receive(payload, System.currentTimeMillis())) {
                materializePictures()
                flushGroups()
                schedulePostman()
                changed(Change.MESSAGES)
                return@mailOperation
            }
            val got = postman.receive(payload, System.currentTimeMillis())
            // The pieces are durable already; a picture whose last piece just arrived gets its file now.
            materializePictures()
            // A private group body (a key, an invitation, a leaving) may have given the group engine something to send.
            if (got?.body is Body.Group) flushGroups()
            schedulePostman()
            // Content and ack effects are durable before observers run; a receipt has no bubble of its own.
            if (got == null) changed(Change.DELIVERY) else changed(Change.MESSAGES)
        }
    }

    /** Same bubble, new packet. Never automatic. A private message may arrive twice; accepted by the spec. */
    fun resend(messageId: Long): Boolean {
        val message = history.message(messageId) ?: return false
        if (message.direction != Direction.OUT || message.status?.canResend != true) return false
        if (history.chat(message.chatId)?.kind == ChatKind.DIRECT) return false
        if (message.kind == MessageKind.PICTURE) return resendPicture(message)
        val chat = history.chat(message.chatId) ?: return false
        // Like send: no region yet, or a region write is running. Nothing goes out and the message stays as it is.
        if (region.blocksSending) { changed(Change.REGION); return false }
        if (chat.kind == ChatKind.PERSON) {
            val retried = retryPerson(message, chat)
            changed(Change.DELIVERY)
            return retried
        }
        history.setStatus(messageId, DeliveryStatus.WAITING)
        transmit(messageId, message.text, message.language ?: currentLanguage().tag, chat, message.replyTo)
        changed(Change.DELIVERY)
        return true
    }

    /**
     * Hands the message to the radio. With no radio ready it waits instead (QUEUED, "Waiting for a radio") and goes by
     * itself once one is (sendQueued). True when the radio took it.
     */
    private fun transmit(messageId: Long, text: String, language: String, chat: Chat, replyTo: Long?): Boolean {
        // Last guard: no path may address a radio in an old chat.
        if (chat.kind == ChatKind.DIRECT) { history.setStatus(messageId, DeliveryStatus.FAILED); return false }
        if (!mayTransmit()) { history.setStatus(messageId, DeliveryStatus.QUEUED); return false }
        return try {
            sendText(messageId, text, language, chat, replyTo)
            true
        } catch (e: Unsendable) {
            // A name or text the codec refuses is a visible failure with Renvoyer, not bytes of another kind and not a wait for a radio.
            Log.w(TAG, "shared message refused", e)
            history.setStatus(messageId, DeliveryStatus.FAILED)
            if (sharedIdentity() == null) note(UiText(R.string.add_name_first))
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "kept until a radio is ready", e)
            history.setStatus(messageId, DeliveryStatus.QUEUED)
            false
        }
    }

    // ---- Planned messages

    /**
     * Keeps the draft (and the reply being made) as a message planned for [due]. It goes out when its time comes and the
     * radio is ready: at once while the app is open, through the alarm and a short background send while it is closed.
     */
    fun plan(chatId: Long, due: Long): Boolean {
        val text = drafts[chatId]?.trim().orEmpty()
        val language = currentLanguage().tag
        if (fitFor(chatId, text) != DraftFit.FITS) return false
        val chat = history.chat(chatId) ?: return false
        if (chat.kind == ChatKind.GROUP && !canPost()) { note(UiText(R.string.add_name_first)); return false }
        if (chat.kind == ChatKind.DIRECT) { note(UiText(R.string.old_chat_read_only)); return false }
        if (playingId == DRAFT_PLAYBACK) stopPlayback()
        history.addPlanned(chatId, text, language, due, replyTarget(chatId)?.packetId)
        drafts.remove(chatId)
        cutDrafts.remove(chatId)
        replyTargets.remove(chatId)
        armPlans()
        changed(Change.MESSAGES)
        return true
    }

    fun cancelPlanned(id: Long) {
        if (!history.deletePlanned(id)) return
        armPlans()
        changed(Change.MESSAGES)
    }

    fun hasDuePlanned(): Boolean = history.duePlanned(System.currentTimeMillis()).isNotEmpty()

    /**
     * Sends every planned message whose time has come, when the radio is ready and may send. One the radio refuses
     * becomes a failure the person can resend, like any message. The rest waits for the radio.
     */
    fun sendDue() {
        val now = System.currentTimeMillis()
        val due = history.duePlanned(now)
        if (due.isNotEmpty() && connected && !region.blocksSending) {
            for (planned in due) {
                val chat = history.chat(planned.chatId) ?: continue
                // Planned before the chat became read only: dropped with a word, never sent by radio address.
                if (chat.kind == ChatKind.DIRECT) { history.deletePlanned(planned.id); note(UiText(R.string.old_chat_read_only)); continue }
                val committed = mailOperation {
                    if (chat.kind == ChatKind.PERSON) {
                        val out = history.transaction {
                            val message = history.addOutgoing(chat.id, planned.text, planned.language, now, planned.replyTo, plannedAt = planned.due)
                            val packet = postman.send(AppMailStore.idOf(chat.address), personBody(planned.text, planned.language, planned.replyTo), message.id, now)
                            if (!history.deletePlanned(planned.id)) throw org.phonemesh.core.mail.MailStore.Refused()
                            listOfNotNull(packet, profileAfterMessage(AppMailStore.idOf(chat.address), now)) +
                                photoAfterMessage(AppMailStore.idOf(chat.address), now)
                        }
                        out.forEach { push(it) }
                        schedulePostman()
                    } else {
                        val message = history.transaction {
                            val saved = history.addOutgoing(chat.id, planned.text, planned.language, now, planned.replyTo, plannedAt = planned.due)
                            if (!history.deletePlanned(planned.id)) throw org.phonemesh.core.mail.MailStore.Refused()
                            saved
                        }
                        transmit(message.id, planned.text, planned.language, chat, planned.replyTo)
                    }
                }
                if (committed) settings.firstMessageSent = true
            }
            changed(Change.MESSAGES)
        }
        armPlans()
    }

    /** The alarm for the next planned message, and a check while this process lives (again a minute later when the radio was away). */
    private fun armPlans() {
        main.removeCallbacks(planCheck)
        val next = history.nextDue()
        planAlarm.set(next)
        if (next != null) {
            val wait = next - System.currentTimeMillis()
            main.postDelayed(planCheck, if (wait > 0) wait else PLAN_RETRY_MS)
        }
    }

    /** The message the next send in [chatId] answers; only messages that went over the radio can be answered. */
    fun replyTarget(chatId: Long): Message? = replyTargets[chatId]?.let { history.message(it) }?.takeIf { it.packetId != null }

    fun setReplyTarget(chatId: Long, messageId: Long?) {
        if (messageId == null) replyTargets.remove(chatId) else replyTargets[chatId] = messageId
        changed(Change.MESSAGES)
    }

    /** What a reply bubble quotes: the answered message on this phone, or null when this phone never had it. */
    fun quoted(message: Message): Message? = message.replyTo?.let { history.byRadioId(message.chatId, it) }

    /**
     * Sends [emoji] as a reaction to a message that went over the radio: one radio message, the emoji alone with
     * Meshtastic's reply and emoji marks. Not tracked for delivery. Nothing is kept when the radio cannot take it.
     */
    fun react(messageId: Long, emoji: String): Boolean {
        val message = history.message(messageId) ?: return false
        val target = message.packetId ?: return false
        val chat = history.chat(message.chatId) ?: return false
        if (chat.kind == ChatKind.DIRECT) return false
        // One emoji (decision 29), not just anything within the 32-byte limit: three thumbs fit the bytes too.
        if (org.phonemesh.core.wire.Reactions.single(emoji) != emoji) return false
        val now = System.currentTimeMillis()
        if (chat.kind == ChatKind.PRIVATE_GROUP) {
            if (target < REF_FLOOR) return false
            var sent = false
            if (!mailOperation {
                sent = groups.send(AppGroupStore.hexBytes(chat.address), listOf(Body.encode(Body.Reaction(AppMailStore.refBytes(target), emoji)) to false), null, now) != null
                if (sent) { history.addReaction(chat.id, null, emoji, target, now, waiting = false); flushGroups() }
                schedulePostman()
            } || !sent) return false
            changed(Change.MESSAGES)
            return true
        }
        if (chat.kind == ChatKind.PERSON) {
            if (target < REF_FLOOR) return false
            if (!mailOperation {
                val out = history.transaction {
                    val made = postman.send(AppMailStore.idOf(chat.address), Body.Reaction(AppMailStore.refBytes(target), emoji), null, now)
                    history.addReaction(chat.id, null, emoji, target, now, waiting = false)
                    made
                }
                out?.let { push(it) }
                schedulePostman()
            }) return false
            changed(Change.MESSAGES)
            return true
        }
        if (chat.kind == ChatKind.GROUP && !canPost()) { note(UiText(R.string.add_name_first)); return false }
        if (chat.kind == ChatKind.GROUP && target >= REF_FLOOR && emoji.toByteArray(Charsets.UTF_8).size !in 1..SharedPost.MAX_EMOJI) return false
        val sent = mayTransmit() && try {
            sendReaction(chat, emoji, target)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "reaction kept until a radio is ready", e)
            false
        }
        // Never silent: no radio means the reaction waits on this phone, marked, and goes when one is ready.
        history.addReaction(chat.id, null, emoji, target, now, waiting = !sent)
        if (!sent) note(UiText(R.string.reaction_waiting))
        changed(Change.MESSAGES)
        return true
    }

    /** True while the picture [messageId] waits for a radio, not yet sending. */
    fun pictureWaitsForRadio(messageId: Long): Boolean = history.message(messageId)?.status == DeliveryStatus.QUEUED

    /** A radio is connected and may send now (it has a region and none is being written). */
    private fun mayTransmit(): Boolean = connected && !region.blocksSending

    private fun destinationOf(chat: Chat): String = when (chat.kind) {
        ChatKind.GROUP -> BROADCAST
        // Old radio-addressed chats are read only (accounts spec, decision 11): receive, never send.
        ChatKind.DIRECT -> error("old chats are read only")
        // An account id is no radio address: person chats send through wire v2 (step 13), never this path.
        ChatKind.PERSON -> error("person chats do not use radio addresses")
        ChatKind.PRIVATE_GROUP -> error("private groups send through their group")
    }

    /**
     * Sends what waits for a radio once one is ready: messages and pictures in the order they were sent, then reactions.
     * Stops at the first the radio refuses; the rest keeps waiting.
     */
    fun sendQueued() {
        if (!mayTransmit() || draining != null || mailFlight != null) return
        // One at a time: the radio's Bluetooth queue holds three, and a fourth write is dropped without a word.
        val message = history.queued().firstOrNull()
        if (message != null) {
            val chat = history.chat(message.chatId)
            if (chat == null || chat.kind == ChatKind.DIRECT) {
                history.setStatus(message.id, DeliveryStatus.FAILED)
                main.post { sendQueued() }
                return
            }
            if (chat.kind == ChatKind.PERSON) {
                if (retryPerson(message, chat)) main.post { sendQueued() }
                changed(Change.MESSAGES)
                return
            }
            if (message.kind == MessageKind.PICTURE) {
                if (resendPicture(message)) drainUntilTaken(Drain.Picture(message.id))
                else { history.setStatus(message.id, DeliveryStatus.FAILED); main.post { sendQueued() } }
            } else {
                history.setStatus(message.id, DeliveryStatus.WAITING)
                if (transmit(message.id, message.text, message.language ?: currentLanguage().tag, chat, message.replyTo)) {
                    radioIdOf(message.id)?.let { drainUntilTaken(Drain.Packet(it)) }
                }
            }
            changed(Change.MESSAGES)
            return
        }
        val queued = history.queuedReactions().firstOrNull() ?: return
        val chat = history.chat(queued.chatId) ?: return
        if (chat.kind == ChatKind.DIRECT) { history.deleteMessage(queued.id); main.post { sendQueued() }; return }
        val packetId = try {
            sendReaction(chat, queued.emoji, queued.target)
        } catch (e: RuntimeException) {
            Log.w(TAG, "queued reaction still waiting", e)
            return
        }
        history.reactionSent(queued.id, packetId)
        drainUntilTaken(Drain.Packet(packetId))
        changed(Change.MESSAGES)
    }

    /** What sendQueued handed the radio last: a text or reaction packet, or a picture's first packet. */
    private sealed interface Drain {
        data class Packet(val id: Long) : Drain
        data class Picture(val messageId: Long) : Drain
    }

    /** The waiting item on its way; the next goes once the radio reports on it, or after [DRAIN_WAIT_MS] without a word. */
    private var draining: Drain? = null
    private val drainStalled = Runnable { drained() }

    private fun drainUntilTaken(item: Drain) {
        draining = item
        main.removeCallbacks(drainStalled)
        main.postDelayed(drainStalled, DRAIN_WAIT_MS)
    }

    private fun drained() {
        if (draining == null) return
        draining = null
        main.removeCallbacks(drainStalled)
        main.post { mailOperation { pumpMail() }; sendQueued() }
    }

    /** Any report but "being written" means the radio has dealt with it: the next waiting item may go. */
    private fun onDrainReport(packetId: Long, state: DeliveryState) {
        if (state == DeliveryState.SUBMITTING) return
        when (val item = draining) {
            is Drain.Packet -> if (item.id == packetId) drained()
            is Drain.Picture -> if (picturePackets[packetId]?.first == item.messageId) drained()
            null -> Unit
        }
    }

    fun pinChat(chatId: Long, pinned: Boolean) {
        history.setPinned(chatId, pinned)
        changed(Change.MESSAGES)
    }

    /** Deletes a chat's messages on this phone only (a private chat goes too); its draft and playback go with them. */
    fun deleteChat(chatId: Long): Boolean {
        // A group this phone still belongs to is left first: deleting only its chat would leave a live group nobody can see (review G9-5).
        history.chat(chatId)?.takeIf { it.kind == ChatKind.PRIVATE_GROUP }?.let { chat ->
            val row = groupStore.rows.group(chat.address)
            if (row != null) {
                if (!row.left) { note(UiText(R.string.group_leave_first)); return false }
                // A group this phone left is dropped with its chat; if dropping it fails the chat stays with it.
                var forgotten = false
                if (!mailOperation { forgotten = groups.forget(AppGroupStore.hexBytes(chat.address)) } || !forgotten) return false
            }
        }
        val playing = playingId?.let { history.message(it) }
        if (playing?.chatId == chatId) stopPlayback()
        setDraft(chatId, null)
        val files = history.pictureFiles(chatId)
        val pictureIds = history.pictureMessageIds(chatId)
        if (!history.deleteChat(chatId)) return false
        files.forEach { File(picturesDir, it).delete() }
        pictureIds.forEach { forgetView(it); PictureLooks.forget(app, it) }
        changed(Change.MESSAGES)
        return true
    }

    /** Puts a message's words in another chat's draft, to hear and send there. Nothing goes out until Send. */
    fun forward(messageId: Long, toChatId: Long): Boolean {
        val message = history.message(messageId)?.takeIf { it.kind == MessageKind.TEXT } ?: return false
        val target = history.chat(toChatId) ?: return false
        if (target.kind == ChatKind.DIRECT) return false
        val kept = trimFor(toChatId, message.text)
        setDraft(toChatId, kept)
        if (kept != message.text) cutDrafts += toChatId
        return true
    }

    // ---- Pictures

    /** Pictures go only to a person with a session (wire v2), and only in builds that carry them. Old radio chats never send. */
    fun canSendPictures(chatId: Long): Boolean {
        if (!BuildConfig.PICTURES) return false
        val chat = history.chat(chatId) ?: return false
        if (chat.kind == ChatKind.PRIVATE_GROUP) return groupView(chatId)?.canSend == true
        return chat.kind == ChatKind.PERSON && history.sessionRow(chat.address) != null
    }

    /**
     * The connected radio's modulation for radio-time estimates. Null when no radio is connected or its setting is not
     * one Meshtastic names: the airtime meter never guesses LongFast (airtime spec 6.1).
     */
    fun knownShape(): LoraShape? = (if (connected) radio.modemPreset() else null)?.let { LoraAirtime.preset(it) }

    /**
     * Radio time of [picture] sent to [chatId]'s person now (airtime meter, AM3 A): the packets [sendPicture] would
     * seal, key and riding receipt included, each sent once on one hop. Null when the radio setting or session is
     * unknown. Nothing is sealed or kept.
     */
    fun pictureSeconds(chatId: Long, picture: PreparedPicture): Double? {
        val shape = knownShape() ?: return null
        val chat = history.chat(chatId)?.takeIf { it.kind == ChatKind.PERSON } ?: return null
        val packets = PictureWire.splitV2(0, picture.codec, picture.width, picture.height, picture.bytes, picture.sketch)
        val bodies = packets.map { p -> PictureWire.encodeV2(p).let { if (p is PictureWire.Head) Body.PictureHead(it) else Body.PicturePart(it) } }
        val sizes = runCatching { postman.sizes(AppMailStore.idOf(chat.address), bodies) }.getOrNull() ?: return null
        return RadioTime.seconds(sizes, shape)
    }

    /**
     * Radio time of [chatId]'s draft as [send] would put it on the radio now (airtime meter, AM1-AM2 A): the same body
     * or post bytes, priced on the connected radio's setting. Null when there is no sendable draft;
     * [RadioTime.Shown.Unknown] when the setting (or a person's session) is unknown. Nothing is sealed or kept.
     */
    fun draftRadioTime(chatId: Long): RadioTime.Shown? {
        val text = drafts[chatId]?.trim().orEmpty()
        if (fitFor(chatId, text) != DraftFit.FITS) return null
        val chat = history.chat(chatId)?.takeIf { it.kind != ChatKind.DIRECT } ?: return null
        val shape = knownShape() ?: return RadioTime.Shown.Unknown
        val language = currentLanguage().tag
        val replyTo = replyTarget(chatId)?.packetId
        val payload = runCatching {
            when {
                chat.kind == ChatKind.PERSON -> postman.sizes(AppMailStore.idOf(chat.address), listOf(personBody(text, language, replyTo)))
                    ?.let { RadioTime.PRIVATE_PORT to it }
                replyTo != null && replyTo < REF_FLOOR ->
                    RadioTime.TEXT_PORT to listOf(MessageCodec.encode(text, language).toByteArray(Charsets.UTF_8).size)
                else -> sharedIdentity()?.let { id ->
                    val kind = if (replyTo != null) SharedPost.Kind.REPLY else SharedPost.Kind.TEXT
                    MeshRadio.SHARED_CHAT_PORT to listOf(
                        sharedFrame(id, kind, language, text, replyTo?.let { AppMailStore.refBytes(it) }, System.currentTimeMillis()).size)
                }
            }
        }.getOrNull()
        return RadioTime.shown(payload?.let { (port, sizes) -> RadioTime.seconds(sizes, shape, port) })
    }

    /**
     * Sends [picture] to a person: a head and parts, each sealed alone and kept before any is sent, so a restart or a
     * lost packet never loses it. The packets go one radio write at a time (pumpMail), at most 20 past the last ack.
     * Nothing is sent without this call (the Send button). False, with nothing kept, when it cannot be stored.
     */
    fun sendPicture(chatId: Long, picture: PreparedPicture): Boolean {
        if (!canSendPictures(chatId)) return false
        val chat = history.chat(chatId) ?: return false
        val name = "out-${java.util.UUID.randomUUID()}.pic"
        val file = File(picturesDir, name)
        val now = System.currentTimeMillis()
        val kept = mailOperation {
            // The id reservation, bubble, file row and sealed packets commit together, or none does.
            val out = history.transaction {
                val pictureId = personPictures.freshId(chatId) { random.nextInt(0x10000) }
                val group = chat.kind == ChatKind.PRIVATE_GROUP
                val packets = if (group) PictureWire.splitGroup(pictureId, picture.codec, picture.width, picture.height, picture.bytes, picture.sketch)
                    else PictureWire.splitV2(pictureId, picture.codec, picture.width, picture.height, picture.bytes, picture.sketch)
                if (group && packets.size - 1 > PictureWire.MAX_PARTS_GROUP) error("too large for a group")
                file.writeBytes(picture.bytes)
                val row = PictureRow(0, pictureId, picture.codec.wire, picture.width, picture.height, picture.sketch, name, 0, packets.size, false)
                sealPicture(chat, history.addOutgoingPicture(chatId, row, now).id, packets, now)
            }
            settings.firstMessageSent = true
            out.forEach { push(it) }
            if (chat.kind == ChatKind.PRIVATE_GROUP) flushGroups()
            schedulePostman()
        }
        if (!kept) { file.delete(); return false }
        changed(Change.MESSAGES)
        return true
    }

    private fun sealPicture(chat: Chat, messageId: Long, packets: List<PictureWire.Packet>, now: Long): List<Postman.Out> {
        if (chat.kind == ChatKind.PRIVATE_GROUP) {
            // The head and its sketch by radio, so radio-only members see the sketch; every part only through a hub.
            val bodies = packets.map { packet ->
                val bytes = PictureWire.encode(packet)
                Body.encode(if (packet is PictureWire.Head) Body.PictureHead(bytes) else Body.PicturePart(bytes)) to (packet !is PictureWire.Head)
            }
            checkNotNull(groups.send(AppGroupStore.hexBytes(chat.address), bodies, messageId, now)) { "the group cannot send now" }
            return emptyList()
        }
        return postman.sendPicture(AppMailStore.idOf(chat.address), messageId, packets, now).filterNotNull() +
            listOfNotNull(profileAfterMessage(AppMailStore.idOf(chat.address), now)) + photoAfterMessage(AppMailStore.idOf(chat.address), now)
    }

    /** Picture retry is for people only: the stored packets go again, byte for byte. Old radio chats never resend. */
    private fun resendPicture(message: Message): Boolean {
        val chat = history.chat(message.chatId)?.takeIf { it.kind == ChatKind.PERSON } ?: return false
        if (region.blocksSending) { changed(Change.REGION); return false }
        val retried = retryPersonPicture(message, chat)
        changed(Change.DELIVERY)
        return retried
    }

    /**
     * Every stored packet of the picture that is not delivered or retired goes again, unchanged. Only a picture whose
     * packets were all retired (a new QR session) is sealed anew, from its file, under the new keys.
     */
    private fun retryPersonPicture(message: Message, chat: Chat): Boolean = mailOperation {
        val now = System.currentTimeMillis()
        val contact = AppMailStore.idOf(chat.address)
        val out = history.transaction {
            val live = history.outbox(chat.address).filter { it.messageId == message.id && it.state != Postman.Status.SUPERSEDED.name }
            val held = history.held(chat.address).any { it.messageId == message.id }
            if (live.isNotEmpty() || held) {
                if (!held && live.all { it.state == Postman.Status.DELIVERED.name }) throw org.phonemesh.core.mail.MailStore.Refused()
                history.setStatus(message.id, DeliveryStatus.WAITING)
                live.filter { it.state != Postman.Status.DELIVERED.name }.map { row ->
                    history.updateOutbox(row.id, row.tries, now + Postman.FIRST_RETRY_MS, Postman.Status.WAITING.name)
                    Postman.Out(contact, row.packet, row.id, row.isReceipt)
                }
            } else {
                val row = history.picture(message.id) ?: throw org.phonemesh.core.mail.MailStore.Refused()
                val codec = PictureCodec.of(row.codec) ?: throw org.phonemesh.core.mail.MailStore.Refused()
                val bytes = row.file?.let { File(picturesDir, it) }?.takeIf { it.isFile }?.readBytes()
                    ?: throw org.phonemesh.core.mail.MailStore.Refused()
                history.setStatus(message.id, DeliveryStatus.WAITING)
                history.setPictureProgress(message.id, 0)
                sealPicture(chat, message.id, PictureWire.splitV2(row.pictureId, codec, row.width, row.height, bytes, row.sketch), now)
            }
        }
        out.forEach { push(it) }
        schedulePostman()
    }

    /**
     * Writes the file of every received picture whose pieces are all kept, then drops the pieces; gives up pictures the
     * sender has stopped resending. Safe to repeat: a failure leaves the pieces for the next call (a restart, a receive).
     * Expiry runs after the files, and never touches a picture whose pieces are all kept.
     */
    private fun materializePictures() {
        val now = System.currentTimeMillis()
        val ready = try {
            personPictures.ready()
        } catch (e: SQLiteException) {
            Log.w(TAG, "received pictures wait", e)
            pictureWorkAt = now + 5_000L
            return
        }
        var changedSome = false
        var retry = false
        for (picture in ready) {
            val name = "in-${picture.messageId}.pic"
            try {
                val tmp = File(picturesDir, "$name.tmp")
                tmp.writeBytes(picture.bytes)
                check(tmp.renameTo(File(picturesDir, name))) { "picture file not moved" }
                personPictures.finished(picture, name)
                changedSome = true
            } catch (e: java.io.IOException) {
                Log.w(TAG, "picture file waits", e)
                retry = true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "picture file waits", e)
                retry = true
            } catch (e: SQLiteException) {
                Log.w(TAG, "picture file waits", e)
                retry = true
            }
        }
        // A group picture whose last part came through a hub gets its file the same way (G5).
        val groupReady = if (account() == null) emptyList() else try { groupStore.pictures.ready() } catch (e: SQLiteException) {
            Log.w(TAG, "group pictures wait", e)
            retry = true
            emptyList()
        }
        for (picture in groupReady) {
            val name = "in-${picture.messageId}.pic"
            try {
                val tmp = File(picturesDir, "$name.tmp")
                tmp.writeBytes(picture.bytes)
                check(tmp.renameTo(File(picturesDir, name))) { "picture file not moved" }
                groupStore.pictures.finished(picture, name)
                changedSome = true
            } catch (e: java.io.IOException) {
                Log.w(TAG, "group picture file waits", e); retry = true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "group picture file waits", e); retry = true
            } catch (e: SQLiteException) {
                Log.w(TAG, "group picture file waits", e); retry = true
            }
        }
        try {
            if (personPictures.expire(now - org.phonemesh.core.mail.MailStore.REPLAY_LIFETIME) > 0) changedSome = true
        } catch (e: SQLiteException) {
            Log.w(TAG, "old picture pieces stay", e)
            retry = true
        }
        val expiry = try { personPictures.nextExpiry() } catch (e: SQLiteException) {
            Log.w(TAG, "picture expiry waits", e)
            retry = true
            null
        }
        pictureWorkAt = listOfNotNull(expiry, if (retry) now + 5_000L else null).minOrNull()
        if (changedSome) changed(Change.MESSAGES)
    }

    private fun startPicture(messageId: Long, address: String, packets: List<PictureWire.Packet>) {
        val sender = PictureSender(packets.map { PictureWire.encode(it) })
        outgoing[messageId] = Outgoing(messageId, address, sender)
        act(outgoing.getValue(messageId), sender.start())
    }

    private fun act(transfer: Outgoing, action: PictureSender.Action?) {
        when (action) {
            null -> Unit
            is PictureSender.Action.Send -> {
                val packetId = try {
                    radio.sendData(PictureWire.PORT, transfer.sender.packet(action.index), transfer.address, PRIMARY_CHANNEL)
                } catch (e: RuntimeException) {
                    // This phone's radio could not take the piece: not a failure. The picture waits for a radio and
                    // starts again with the same id, so the other phone keeps the pieces it already has.
                    Log.w(TAG, "picture kept until a radio is ready", e)
                    outgoing.remove(transfer.messageId)
                    picturePackets.values.removeAll { it.first == transfer.messageId }
                    history.setStatus(transfer.messageId, DeliveryStatus.QUEUED)
                    if ((draining as? Drain.Picture)?.messageId == transfer.messageId) { draining = null; main.removeCallbacks(drainStalled) }
                    changed(Change.DELIVERY)
                    return
                }
                picturePackets[packetId] = transfer.messageId to action.index
            }
            is PictureSender.Action.Finished -> {
                outgoing.remove(transfer.messageId)
                picturePackets.values.removeAll { it.first == transfer.messageId }
                history.setPictureProgress(transfer.messageId, transfer.sender.acknowledged)
                history.setStatus(transfer.messageId, if (action.delivered) DeliveryStatus.DELIVERED else DeliveryStatus.FAILED)
                changed(Change.DELIVERY)
            }
        }
    }

    private fun onPicturePacket(packetId: Long, state: DeliveryState) {
        val acknowledged = when (state) {
            DeliveryState.ACKNOWLEDGED -> true
            DeliveryState.FAILED, DeliveryState.UNCONFIRMED -> false
            else -> return
        }
        val (messageId, index) = picturePackets.remove(packetId) ?: return
        val transfer = outgoing[messageId] ?: return
        val action = transfer.sender.onOutcome(index, acknowledged)
        // The first piece the other radio confirmed: its id is what replies and reactions to this picture name.
        if (acknowledged && index == 0 && history.message(messageId)?.packetId == null) history.attachPacket(messageId, packetId)
        if (acknowledged) {
            history.setPictureProgress(messageId, transfer.sender.acknowledged)
            changed(Change.DELIVERY)
        }
        act(transfer, action)
    }

    override fun onBroadcastData(sender: String, port: Int, payload: ByteArray, channel: Int, packetId: Long, signal: String) {
        t1?.got(sender, payload, channel, packetId, signal)
        // The shared chat is read with or without a name of this phone's own, so it comes before the account check below.
        if (port == MeshRadio.SHARED_CHAT_PORT) { if (channel == PRIMARY_CHANNEL) receiveShared(payload, packetId); return }
        // A hello for this phone: "<name> veut vous parler". Phones without a name have no keys, so nothing to open.
        if (channel != PRIMARY_CHANNEL || account() == null) return
        when {
            port == MeshRadio.ACCOUNT_PORT && Start.isHello(payload) -> mailOperation {
                accountsFlow.onPacket(payload)?.let { request ->
                    note(UiText(R.string.add_wants_to_talk, listOf(request.name)))
                }
                runPostman()
                changed(Change.MESSAGES)
            }
            port == MeshRadio.ACCOUNT_PORT -> receivePerson(payload)
            port == MeshRadio.HUB_ACK_PORT -> if (mailOperation { postman.hubAck(payload) }) changed(Change.DELIVERY)
        }
    }

    override fun onData(sender: String, port: Int, payload: ByteArray, channel: Int, packetId: Long) {
        if (!BuildConfig.PICTURES || port != PictureWire.PORT || channel != PRIMARY_CHANNEL) return
        val packet = PictureWire.decode(payload) ?: return
        // The first piece's radio id is what a reply or reaction to this picture names, on both phones.
        if (packet is PictureWire.Head && packetId != 0L) pictureHeads[sender to packet.id] = packetId
        onPictureEvents(assembler.accept(sender, packet))
        main.removeCallbacks(pictureTick)
        if (assembler.pending > 0) main.postDelayed(pictureTick, PICTURE_TICK_MS)
    }

    /** Radio ids of pictures' first pieces as they arrived, until the picture is whole, broken or gone. */
    private val pictureHeads = HashMap<Pair<String, Int>, Long>()

    private fun rememberHead(key: Pair<String, Int>, messageId: Long) {
        pictureHeads[key]?.let { if (history.message(messageId)?.packetId == null) history.attachPacket(messageId, it) }
    }

    private fun onPictureEvents(events: List<PictureAssembler.Event>) {
        for (event in events) {
            val key = event.sender to event.id
            if (event is PictureAssembler.Event.Progress) incoming[key]?.let { rememberHead(key, it) }
            when (event) {
                is PictureAssembler.Event.Started -> {
                    val chat = history.directChat(event.sender, knownName(event.sender))
                    val head = event.head
                    val row = PictureRow(0, head.id, head.codec.wire, head.width, head.height, head.sketch, null, event.have, head.parts, false)
                    incoming[key] = history.addIncomingPicture(chat.id, event.sender, row, System.currentTimeMillis(), countUnread = openChatId != chat.id).id
                    rememberHead(key, incoming.getValue(key))
                    changed(Change.MESSAGES)
                }
                is PictureAssembler.Event.Progress -> incoming[key]?.let { history.setPictureProgress(it, event.have); changed(Change.DELIVERY) }
                is PictureAssembler.Event.Done -> incoming.remove(key)?.let { id ->
                    rememberHead(key, id)
                    pictureHeads.remove(key)
                    val name = "in-$id.pic"
                    File(picturesDir, name).writeBytes(event.bytes)
                    history.setPictureFile(id, name, event.head.parts)
                    changed(Change.MESSAGES)
                }
                is PictureAssembler.Event.Broken, is PictureAssembler.Event.Expired -> incoming.remove(key).also { pictureHeads.remove(key) }?.let {
                    history.setPictureLost(it)
                    changed(Change.MESSAGES)
                }
            }
        }
    }

    /** A picture message's pixels for the screen: the received or sent picture, else its sketch. */
    /** A whole picture (its pixels are on this phone) can go to another private chat. */
    fun canForwardPicture(messageId: Long): Boolean =
        BuildConfig.PICTURES && history.picture(messageId)?.file?.let { File(picturesDir, it).isFile } == true

    /** A whole picture as it would go out again: the same bytes and sketch, nothing re-encoded. */
    fun preparedPicture(messageId: Long): PreparedPicture? {
        val row = history.picture(messageId) ?: return null
        val codec = PictureCodec.of(row.codec) ?: return null
        val bytes = row.file?.let { File(picturesDir, it) }?.takeIf { it.isFile }?.readBytes() ?: return null
        val preview = PictureMaker.decode(row.codec, bytes, row.sketch) ?: return null
        return PreparedPicture(codec, row.width, row.height, bytes, row.sketch, preview, -1)
    }

    /**
     * The view of a picture chosen for the chat (see [useView]), kept at the bubble's own size as a JPEG: tens of KB on
     * disk and about a megabyte in memory, however large the AI made it. The full size is made again when the picture
     * is opened. "view-<id>.png" is the full-size copy an earlier test build kept; it is still read, and goes with
     * [forgetView].
     */
    private fun viewFile(messageId: Long) = File(picturesDir, "view-$messageId.jpg")
    private fun oldViewFile(messageId: Long) = File(picturesDir, "view-$messageId.png")
    private fun viewLookFile(messageId: Long) = File(picturesDir, "view-$messageId.look")
    private fun shownViewFile(messageId: Long) = viewFile(messageId).takeIf { it.isFile } ?: oldViewFile(messageId).takeIf { it.isFile }

    /** Which view the chat shows for [messageId] (a [PictureLook] key), or null for the picture as it came. */
    fun chosenLook(messageId: Long): String? =
        viewLookFile(messageId).takeIf { it.isFile && shownViewFile(messageId) != null }?.readText()?.trim()?.ifEmpty { null }

    /** A chat bubble is 60% of the screen wide (see ConversationActivity.pictureView). */
    private fun bubbleWidth(): Int = (app.resources.displayMetrics.widthPixels * 0.6f).toInt().coerceAtLeast(1)

    /** From now on the chat shows [bitmap] for this picture. Only on this phone; nothing is sent and the other phone keeps its own. */
    fun useView(messageId: Long, bitmap: android.graphics.Bitmap, lookKey: String): Boolean = runCatching {
        val width = minOf(bitmap.width, bubbleWidth())
        val small = if (width == bitmap.width) bitmap
        else android.graphics.Bitmap.createScaledBitmap(bitmap, width, (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1), true)
        val tmp = File(picturesDir, "view-$messageId.tmp")
        tmp.outputStream().use { check(small.compress(android.graphics.Bitmap.CompressFormat.JPEG, VIEW_QUALITY, it)) }
        oldViewFile(messageId).delete()
        check(tmp.renameTo(viewFile(messageId)))
        viewLookFile(messageId).writeText(lookKey)
        changed(Change.MESSAGES)
    }.onFailure { Log.w(TAG, "chosen view not kept", it) }.isSuccess

    /** The chat shows the picture as it came again. */
    fun useOriginal(messageId: Long) {
        forgetView(messageId)
        changed(Change.MESSAGES)
    }

    /** What the chat shows: the chosen view when there is one, otherwise the picture as it came. */
    fun pictureShown(messageId: Long): android.graphics.Bitmap? =
        shownViewFile(messageId)?.let { android.graphics.BitmapFactory.decodeFile(it.path) } ?: pictureBitmap(messageId)

    private fun forgetView(messageId: Long) {
        viewFile(messageId).delete()
        oldViewFile(messageId).delete()
        viewLookFile(messageId).delete()
    }

    fun pictureBitmap(messageId: Long): android.graphics.Bitmap? {
        val row = history.picture(messageId) ?: return null
        val bytes = row.file?.let { File(picturesDir, it) }?.takeIf { it.isFile }?.readBytes()
        return PictureMaker.decode(row.codec, bytes, row.sketch)
    }

    /** Deletes a message on this phone only: what the radio sent cannot be called back. A late receipt finds nothing. */
    fun deleteMessage(messageId: Long): Boolean {
        if (playingId == messageId) stopPlayback()
        outgoing.remove(messageId)?.sender?.cancel()
        picturePackets.values.removeAll { it.first == messageId }
        history.picture(messageId)?.file?.let { File(picturesDir, it).delete() }
        forgetView(messageId)
        PictureLooks.forget(app, messageId)
        if (!history.deleteMessage(messageId)) return false
        changed(Change.MESSAGES)
        return true
    }

    /** Settings → Show the guide again: every tip and the hold-to-talk hint come back; the radio and voice stay. */
    fun showGuideAgain() {
        settings.tipsShown = emptySet()
        settings.tipShowings = emptyMap()
        settings.firstMessageSent = false
    }

    // ---- Account (accounts plan step 10)

    /** This phone's account keys; made on first use (the first time a name is saved). */
    val accounts: AccountStore by lazy { AccountStore(history, keyPrefix = accountKeys) }

    /** The chosen name and colour, or null before a name is saved. Reads only: no keys are made. */
    fun account(): Pair<String, Int>? = history.accountRow()?.takeIf { it.name.isNotEmpty() }?.let { it.name to it.colour }

    /** Adding people: codes, links, hellos, the safety picture (step 11). Only touched once a name is saved. */
    val accountsFlow: Accounts by lazy {
        Accounts(history, accounts, send = { _ ->
            // Accounts has already committed the hello; use the same durable single-flight queue as every message.
            runPostman()
            false
        })
    }

    fun acceptRequest(request: Accounts.Request): Accounts.Added.Contact? {
        var added: Accounts.Added.Contact? = null
        if (mailOperation { added = accountsFlow.accept(request) }) changed(Change.MESSAGES)
        return added
    }

    fun ignoreRequest(request: Accounts.Request) {
        if (mailOperation { accountsFlow.ignore(request) }) changed(Change.MESSAGES)
    }

    /** Existing contacts migrate the guide without recreating keys, sessions or chats. Opening a code alone does not. */
    fun hasAddedSomeone(): Boolean {
        if (!settings.firstPersonAdded && history.contactRows().isNotEmpty()) settings.firstPersonAdded = true
        return settings.firstPersonAdded
    }

    /** Safety decisions update every visible contact view, even during a rapid activity return. */
    fun setContactMet(account: org.phonemesh.core.account.AccountId, met: Boolean) {
        accountsFlow.setMet(account, met)
        changed(Change.MESSAGES)
    }

    /** Saves the name and colour, making the account's keys the first time. */
    fun saveAccount(name: String, colour: Int) {
        accounts.setName(name, colour)
        changed(Change.MESSAGES)
    }

    /** Saves the name, colour (RGB) and picture: a new profile revision when something changed (decisions 48-49). */
    fun saveProfile(name: String, colour: Int, avatar: org.phonemesh.core.account.Avatar) {
        accounts.setProfile(name, colour, avatar)
        // Each private group gets the new name and picture soon (decision 72 b).
        mailOperation { groups.profileChanged(System.currentTimeMillis()); schedulePostman() }
        changed(Change.MESSAGES)
    }

    // ---- Playback

    /** Choosing a voice never plays it or changes a peer's identity/session. */
    fun setContactVoice(accountId: String, voiceId: String?): Boolean {
        if (voiceId != null && store.list().none { it.kind == PackKind.VOICE && it.id == voiceId }) {
            note(UiText(R.string.contact_voice_missing))
            return false
        }
        val saved = try { history.setContactVoice(accountId, voiceId) }
            catch (_: android.database.sqlite.SQLiteException) { false }
        if (saved) changed(Change.MESSAGES) else note(UiText(R.string.contact_voice_failed))
        return saved
    }

    /** Plays a message; tapping the one playing stops it. One at a time. */
    fun play(messageId: Long) {
        if (playingId == messageId) { stopPlayback(); return }
        val message = history.message(messageId) ?: return
        if (message.kind != MessageKind.TEXT) return
        // A claimed shared-chat sender is not an authenticated contact. Own messages and drafts
        // retain the user's voice; only incoming person-chat content uses this local preference.
        val chat = history.chat(message.chatId)
        val voice = if (message.direction == Direction.IN && chat?.kind == ChatKind.PERSON)
            history.contactRow(chat.address)?.voice else null
        speakAs(messageId, message.text, message.language, voice)
    }

    fun playDraft(chatId: Long) {
        if (playingId == DRAFT_PLAYBACK) { stopPlayback(); return }
        val text = drafts[chatId]?.trim()?.takeIf { it.isNotEmpty() } ?: return
        speakAs(DRAFT_PLAYBACK, text, currentLanguage().tag)
    }

    /** Reads one on-screen sentence aloud with the app's own voice (cards, country names); a second tap stops it. */
    fun speakLine(text: String) {
        if (playingId == LINE_PLAYBACK) { stopPlayback(); return }
        speakAs(LINE_PLAYBACK, text, currentLanguage().tag)
    }

    /** A how-to sentence can be read: it has a recorded clip, or the voice is ready. */
    fun canSayHowTo(scene: String): Boolean = currentLanguage().howToClips[scene] != null || readiness() is Readiness.Ready

    /** Reads the scene's clip or sentence; an overridden sentence uses the downloaded voice. */
    fun sayHowTo(scene: String, sentence: String? = null) {
        if (sentence == null) currentLanguage().howToClips[scene]?.let { clip ->
            stopPlayback()
            speech.playClip(clip) {}
            return
        }
        if (readiness() !is Readiness.Ready) return
        speakAs(HOWTO_PLAYBACK, sentence ?: text(UiText(HowTo.sentence(scene))), currentLanguage().tag)
    }

    /** Explicit contact choice, then message-language voice, then the user's voice with a note. */
    private fun speakAs(id: Long, text: String, languageTag: String?, preferredVoice: String? = null) {
        if (recordState != RecordState.IDLE) return
        val wanted = LanguageRegistry.forMessage(languageTag)
        val exact = wanted?.let { store.readiness(settings.engine, it.tag) as? Readiness.Ready }
        val selected = preferredVoice?.let { id -> store.list().firstOrNull { it.kind == PackKind.VOICE && it.id == id } }
        val preferred = selected?.let { voice ->
            (store.readiness(settings.engine, checkNotNull(voice.languageTag)) as? Readiness.Ready)?.copy(voice = voice)
        }
        val pack = preferred ?: exact ?: readiness() as? Readiness.Ready ?: run { note(UiText(R.string.voice_missing)); return }
        if (preferredVoice != null && preferred == null) {
            note(UiText(R.string.contact_voice_missing))
        } else if (preferred == null && exact == null && languageTag != null && wanted?.tag != currentLanguage().tag) {
            note(UiText(R.string.voice_fallback, listOf(wanted?.endonym ?: Locale.forLanguageTag(languageTag).let { it.getDisplayLanguage(it) })))
        }
        stopPlayback()
        val epoch = ++playEpoch
        playingId = id
        changed(Change.PLAYBACK)
        speech.speak(pack, text) spoken@{ ok ->
            if (epoch != playEpoch) return@spoken
            playingId = null
            if (!ok) note(UiText(R.string.play_failed))
            changed(Change.PLAYBACK)
        }
    }

    fun stopPlayback() {
        if (playingId == null) return
        playEpoch++
        playingId = null
        speech.stopPlayback()
        changed(Change.PLAYBACK)
    }

    // ---- Packs

    fun missingVoicePacks(): List<CatalogEntry> = currentLanguage().let {
        SetupPlan.missing(GeneratedPackCatalog.entries, store.list(), it.voicePackId, it.tag)
    }

    /** Progress over the voice card's downloads, or null when none is running. */
    fun voiceDownloadPercent(): Int? {
        val downloads = downloads()
        val pending = runCatching { downloads.pending() }.getOrDefault(emptyMap())
        val running = missingVoicePacks().filter { it.id in pending }
        if (running.isEmpty()) return null
        val done = running.sumOf { runCatching { downloads.progress(it.id) }.getOrNull()?.first ?: 0L }
        return PackRows.percent(done to running.sumOf { it.zipBytes })
    }

    /** The voice card's ⬇: starts every missing pack (engine first) that is not already downloading. */
    fun downloadVoice() {
        val downloads = downloads()
        val pending = runCatching { downloads.pending() }.getOrDefault(emptyMap())
        for (entry in missingVoicePacks()) {
            if (entry.id in pending) continue
            try {
                PackMessages.forDecision(downloads.start(entry))?.let { note(it); return }
            } catch (e: Exception) {
                note(PackMessages.forFailure(e))
                return
            }
        }
        pollDownloads()
    }

    fun startDownload(entry: CatalogEntry) {
        if (recordState != RecordState.IDLE) { note(UiText(R.string.wait_for_operation)); return }
        try {
            note(PackMessages.forDecision(downloads().start(entry)) ?: UiText(R.string.download_started, listOf(PackDownloadText.name(entry))))
        } catch (e: Exception) {
            note(PackMessages.forFailure(e))
        }
        pollDownloads()
    }

    fun cancelDownload(entry: CatalogEntry) {
        runCatching { downloads().cancel(entry.id) }.onFailure { Log.w(TAG, "cancel failed", it) }
        note(UiText(R.string.download_cancelled, listOf(PackDownloadText.name(entry))))
        changed(Change.PACKS)
    }

    /** Refreshes progress and installs finished downloads; repeats every second while a screen watches. */
    fun pollDownloads() {
        main.removeCallbacks(poll)
        val downloads = downloads()
        if (runCatching { downloads.pending() }.getOrDefault(emptyMap()).isEmpty()) return
        changed(Change.PACKS)
        if (!collecting && recordState == RecordState.IDLE && runCatching { downloads.anyFinished() }.getOrDefault(true)) collect(downloads)
        if (observers.isNotEmpty()) main.postDelayed(poll, POLL_MS)
    }

    private fun collect(downloads: PackDownloads) {
        collecting = true
        work.execute {
            val results = runCatching { downloads.collectFinished() }.getOrElse { listOf(Result.failure<PackInfo>(it)) }
            if (results.any { it.isSuccess }) speech.releaseEngine()
            main.post {
                collecting = false
                results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { note(PackMessages.forFailure(it)) }
                    ?: results.firstOrNull()?.getOrNull()?.let { note(UiText(R.string.pack_installed, listOf(packName(it)))) }
                changed(Change.PACKS)
            }
        }
    }

    /** Installs a ZIP the user picked or confirmed. Refused while recording. */
    fun importPack(uri: Uri) {
        if (recordState != RecordState.IDLE || importing) { note(UiText(R.string.wait_for_operation)); return }
        importing = true
        changed(Change.PACKS)
        work.execute {
            val result = try {
                val input = app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("no stream")
                val installed = ProgressInputStream(input, IMPORT_PROGRESS_STEP) { bytes -> main.post { note(PackMessages.importProgress(bytes)) } }
                    .use { store.install(it) }
                // The pack is here: its own download, if one is still running or waiting, has nothing left to bring.
                runCatching { downloads().cancel(installed.id) }.onFailure { Log.w(TAG, "cancel failed", it) }
                speech.releaseEngine()
                if (installed.kind == PackKind.ENGINE) settings.engine = installed.id
                UiText(R.string.pack_imported, listOf(packName(installed)))
            } catch (e: Exception) {
                Log.w(TAG, "import failed", e)
                PackMessages.forImportFailure(e)
            }
            main.post {
                importing = false
                note(result)
                changed(Change.PACKS)
            }
        }
    }

    /** Nothing may keep using the files about to be deleted: speech is released first. */
    fun removePack(id: String, name: UiText) {
        if (recordState != RecordState.IDLE || importing) { note(UiText(R.string.wait_for_operation)); return }
        stopPlayback()
        work.execute {
            speech.releaseEngine()
            val removed = runCatching { store.remove(id) }.getOrDefault(false)
            main.post {
                note(if (removed) UiText(R.string.pack_removed, listOf(name)) else PackMessages.REMOVE_FAILED)
                changed(Change.PACKS)
            }
        }
    }

    /** Prepares the APK and installed packs for the share sheet. */
    fun share(onReady: (List<Uri>) -> Unit) {
        work.execute {
            try {
                val shared = PackSharing.prepare(app, store, onProgress = { bytes -> main.post { note(PackMessages.shareProgress(bytes)) } })
                val skipped = PackMessages.forSkipped(shared.skipped.map { text(packName(it)) })
                main.post {
                    note(skipped ?: UiText(R.string.share_choose))
                    onReady(shared.uris)
                }
            } catch (e: Exception) {
                Log.w(TAG, "share failed", e)
                main.post { note(PackMessages.forFailure(e)) }
            }
        }
    }

    fun packName(pack: PackInfo): UiText =
        GeneratedPackCatalog.entries.firstOrNull { it.id == pack.id }?.let { PackDownloadText.name(it) } ?: UiText(R.string.plain, listOf(pack.label))

    fun close() {
        main.removeCallbacksAndMessages(null)
        observers.clear()
        runCatching { speech.stopRecording() }
        runCatching { speech.close() }
        runCatching { radio.close() }
        work.shutdown()
        roamingActive = false
        roamingEpoch++
        runCatching { roamingHost?.stop() }
        runCatching { hub?.leave() }
        runCatching { bluetoothHub?.leave() }
        hubSendIo.shutdownNow()
        hubPollIo.shutdownNow()
        history.close()
    }

    // Test hook: returns once everything queued on the work thread has run.
    internal fun awaitWorkForTest() {
        work.submit {}.get(120, TimeUnit.SECONDS)
    }

    companion object {
        /** At most one profile update an hour to each contact (profile spec PF5). */
        const val PROFILE_GAP_MS = 60 * 60_000L
        const val PHOTO_GAP_MS = 24 * 60 * 60_000L
        private const val AVATAR_LOAD_SIDE = 256
        private val AVATAR_QUALITIES = listOf(80, 65, 50, 35, 20, 10, 0)
        /** Person-chat references are numbers at or above this (radio packet ids are below). */
        private const val REF_FLOOR = 1L shl 40

        const val PRIMARY_CHANNEL = 0
        const val DRAFT_PLAYBACK = -1L
        const val HOWTO_PLAYBACK = -2L
        const val LINE_PLAYBACK = -3L
        /** The name screen records into this draft. */
        const val NAME_DRAFT = -4L
        const val BROADCAST = "^all"
        const val BACKGROUND_GRACE_MS = 60_000L
        /** Android wants a started foreground service in the foreground within seconds; one that never reports is a failure. */
        const val ROAMING_START_WAIT_MS = 15_000L
        const val CONNECT_WAIT_MS = 35_000L
        const val SCAN_WAIT_MS = 30_000L
        const val POLL_MS = 1_000L
        const val PICTURE_TICK_MS = 30_000L
        const val HISTORY_DB = "history.db"
        /** A reaction is one emoji, perhaps with a skin tone or joined parts; anything longer is not a reaction. */
        const val MAX_REACTION_BYTES = 32
        /** A due message the radio could not take yet is tried again this often while the app lives. */
        const val PLAN_RETRY_MS = 60_000L
        /** How long a waiting item may go without a word from the radio before the next one goes anyway. */
        const val DRAIN_WAIT_MS = 15_000L
        /** The reactions offered: few, large, and readable without words. */
        val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
        private const val MAX_FOUND = 50
        private const val IMPORT_PROGRESS_STEP = 5_000_000L
        private const val TAG = "AppCore"
        /** JPEG quality of a view kept for the chat; at bubble size the loss does not show. */
        private const val VIEW_QUALITY = 90

        @Volatile private var instance: AppCore? = null

        fun get(context: Context): AppCore = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        fun create(
            app: Context,
            radioFactory: ((MeshRadio.Listener) -> MeshRadio)? = null,
            speech: Speech? = null,
            historyName: String? = HISTORY_DB,
            prefsName: String = Settings.PREFS,
            access: DeviceAccess? = null,
            planAlarm: PlanAlarm? = null,
        ): AppCore = AppCore(
            app,
            radioFactory ?: { listener -> BleMeshTransport(app, listener) },
            speech ?: DeviceSpeech(app),
            History(app, historyName),
            Settings(app, prefsName),
            access ?: PhoneAccess(app),
            planAlarm ?: SystemPlanAlarm(app),
            // Tests use their own Keystore names, so they never replace a real account's key.
            if (historyName == HISTORY_DB) "phonemesh" else "test-core",
        ).also { core ->
            if (historyName == HISTORY_DB) {
                core.hub = WifiHubLink(app)
                core.bluetoothHub = BleHubLink(app)
                core.roamingHost = SystemRoamingHost(app)
            }
        }

        /** "Radio 4a2f": the last four hex digits of a radio id with no name yet. */
        fun unnamedRadio(id: String) =
            UiText(R.string.unnamed_radio, listOf(id.removePrefix("!").filter { it.isLetterOrDigit() }.takeLast(4)))

        // Test hook: swaps the process-wide core; null lets the next get() build the real one.
        internal fun replaceForTest(core: AppCore?) = synchronized(this) {
            instance?.close()
            instance = core
        }
    }
}

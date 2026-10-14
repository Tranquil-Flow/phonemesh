package org.phonemesh.app

import android.content.Context
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
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
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
) : MeshRadio.Listener {
    enum class Change { RADIO, SCAN, MESSAGES, DELIVERY, PEERS, PACKS, RECORDING, PLAYBACK, NOTE, REGION }
    enum class RecordState { IDLE, RECORDING, TRANSCRIBING }
    fun interface Observer { fun onCoreChange(change: Change) }
    data class FoundRadio(val address: String, val name: String, val rssi: Int)
    data class ChatRow(val chat: Chat, val title: String, val last: Message?, val peopleHeard: Int?)

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
    private val backgroundStop = Runnable { if (started == 0) leaveRadio() }
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
    val picturesDir: File get() = File(app.filesDir, "pictures").apply { mkdirs() }
    private val t1 = if (BuildConfig.T1_PROBE) T1ProbeRun(app) { radio.sendBroadcastData(MeshRadio.ACCOUNT_PORT, it, PRIMARY_CHANNEL) } else null

    init {
        t1?.start()
        history.expireWaiting()
        history.loseUnfinishedPictures()
        history.groupChat(PRIMARY_CHANNEL)
        // A new process (or a phone that restarted) arms the next planned message again.
        armPlans()
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
        if (started == 1) autoReconnect()
    }

    /** No background service in 3a: the radio is let go a minute after the last screen closes. */
    fun activityStopped() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) main.postDelayed(backgroundStop, BACKGROUND_GRACE_MS)
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
        if (becameReady) sendDue()
        if (becameReady) sendQueued()
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
        onDrainReport(packetId, state)
        if (picturePackets.containsKey(packetId)) { onPicturePacket(packetId, state); return }
        val message = history.byPacket(packetId) ?: return
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

    fun chatRows(): List<ChatRow> = history.chats().map { chat ->
        ChatRow(
            chat, chatTitle(chat), history.lastMessage(chat.id),
            if (chat.kind == ChatKind.GROUP) PeopleHeard.count(history.sendersHeard(chat.id), null) else null,
        )
    }

    /** A radio's name for screens: "Radio EE01" when it has none (blank, missing, or just its address). */
    fun radioLabel(name: String?, address: String?): String =
        if (name.isNullOrBlank() || name == address) text(unnamedRadio(address.orEmpty())) else name

    /** A radio on the radio screen: the name it announces once it has connected, its Bluetooth [name] before that. */
    fun ownRadioLabel(name: String?, address: String?): String =
        radioLabel(address?.let { settings.radioOwnName(it) } ?: name, address)

    fun senderName(id: String): String = knownName(id) ?: text(unnamedRadio(id))

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

    fun draftFit(chatId: Long): DraftFit = DraftRules.fit(drafts[chatId].orEmpty(), currentLanguage().tag)

    fun readiness(): Readiness = store.readiness(settings.engine, currentLanguage().tag)

    /** The microphone is usable: the voice is ready and the radio connected. */
    fun canTalk(): Boolean = connected && readiness() is Readiness.Ready

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
                    val kept = DraftRules.trimToFit(heard, currentLanguage().tag)
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
        if (DraftRules.fit(text, language) != DraftFit.FITS) return false
        val chat = history.chat(chatId) ?: return false
        if (playingId == DRAFT_PLAYBACK) stopPlayback()
        val message = history.addOutgoing(chatId, text, language, System.currentTimeMillis(), replyTarget(chatId)?.packetId)
        drafts.remove(chatId)
        cutDrafts.remove(chatId)
        replyTargets.remove(chatId)
        // Before any notification: observers read setup state from this flag while they render.
        settings.firstMessageSent = true
        transmit(message.id, text, language, chat, message.replyTo)
        changed(Change.MESSAGES)
        return true
    }

    /** Same bubble, new packet. Never automatic. A private message may arrive twice; accepted by the spec. */
    fun resend(messageId: Long): Boolean {
        val message = history.message(messageId) ?: return false
        if (message.direction != Direction.OUT || message.status?.canResend != true) return false
        if (message.kind == MessageKind.PICTURE) return resendPicture(message)
        val chat = history.chat(message.chatId) ?: return false
        // Like send: no region yet, or a region write is running. Nothing goes out and the message stays as it is.
        if (region.blocksSending) { changed(Change.REGION); return false }
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
        if (!mayTransmit()) { history.setStatus(messageId, DeliveryStatus.QUEUED); return false }
        return try {
            history.attachPacket(messageId, radio.send(MessageCodec.encode(text, language), destinationOf(chat), PRIMARY_CHANNEL, replyTo ?: 0L))
            true
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
        if (DraftRules.fit(text, language) != DraftFit.FITS) return false
        history.chat(chatId) ?: return false
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
                history.deletePlanned(planned.id)
                val chat = history.chat(planned.chatId) ?: continue
                val message = history.addOutgoing(chat.id, planned.text, planned.language, now, planned.replyTo, plannedAt = planned.due)
                settings.firstMessageSent = true
                transmit(message.id, planned.text, planned.language, chat, planned.replyTo)
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
        val now = System.currentTimeMillis()
        val sent = mayTransmit() && try {
            radio.send(emoji, destinationOf(chat), PRIMARY_CHANNEL, replyTo = target, reaction = true)
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

    private fun destinationOf(chat: Chat): String = if (chat.kind == ChatKind.GROUP) BROADCAST else chat.address

    /**
     * Sends what waits for a radio once one is ready: messages and pictures in the order they were sent, then reactions.
     * Stops at the first the radio refuses; the rest keeps waiting.
     */
    fun sendQueued() {
        if (!mayTransmit() || draining != null) return
        // One at a time: the radio's Bluetooth queue holds three, and a fourth write is dropped without a word.
        val message = history.queued().firstOrNull()
        if (message != null) {
            val chat = history.chat(message.chatId)
            if (chat == null) {
                history.setStatus(message.id, DeliveryStatus.FAILED)
                main.post { sendQueued() }
                return
            }
            if (message.kind == MessageKind.PICTURE) {
                if (resendPicture(message)) drainUntilTaken(Drain.Picture(message.id))
                else { history.setStatus(message.id, DeliveryStatus.FAILED); main.post { sendQueued() } }
            } else {
                history.setStatus(message.id, DeliveryStatus.WAITING)
                if (transmit(message.id, message.text, message.language ?: currentLanguage().tag, chat, message.replyTo)) {
                    history.message(message.id)?.packetId?.let { drainUntilTaken(Drain.Packet(it)) }
                }
            }
            changed(Change.MESSAGES)
            return
        }
        val queued = history.queuedReactions().firstOrNull() ?: return
        val chat = history.chat(queued.chatId) ?: return
        val packetId = try {
            radio.send(queued.emoji, destinationOf(chat), PRIMARY_CHANNEL, replyTo = queued.target, reaction = true)
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
        main.post { sendQueued() }
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
        history.chat(toChatId) ?: return false
        val kept = DraftRules.trimToFit(message.text, currentLanguage().tag)
        setDraft(toChatId, kept)
        if (kept != message.text) cutDrafts += toChatId
        return true
    }

    // ---- Pictures

    /** Pictures go only in private chats, and only in builds that carry them. */
    fun canSendPictures(chatId: Long): Boolean =
        BuildConfig.PICTURES && history.chat(chatId)?.kind == ChatKind.DIRECT

    /** The connected radio's modulation for time-on-air estimates; LongFast (the default) when unknown. */
    fun radioShape(): LoraShape = (if (connected) radio.modemPreset() else null)?.let { LoraAirtime.preset(it) } ?: LoraAirtime.DEFAULT

    /** Estimated seconds on air for [picture], one hop, every packet with its acknowledgement. */
    fun pictureSeconds(picture: PreparedPicture): Double {
        val packets = PictureWire.split(0, picture.codec, picture.width, picture.height, picture.bytes, picture.sketch)
        return LoraAirtime.pictureSeconds(packets.map { PictureWire.encode(it).size }, radioShape())
    }

    /** Sends [picture] in the private chat [chatId]. Nothing is sent without this call (the Send button). */
    fun sendPicture(chatId: Long, picture: PreparedPicture): Boolean {
        if (!canSendPictures(chatId)) return false
        val chat = history.chat(chatId) ?: return false
        val pictureId = random.nextInt(0x10000)
        val name = "out-${System.currentTimeMillis()}-$pictureId.pic"
        File(picturesDir, name).writeBytes(picture.bytes)
        val packets = PictureWire.split(pictureId, picture.codec, picture.width, picture.height, picture.bytes, picture.sketch)
        val row = PictureRow(0, pictureId, picture.codec.wire, picture.width, picture.height, picture.sketch, name, 0, packets.size, false)
        val message = history.addOutgoingPicture(chatId, row, System.currentTimeMillis())
        settings.firstMessageSent = true
        // No radio ready: the bubble says it waits, and it goes when one is (sendQueued).
        if (mayTransmit()) startPicture(message.id, chat.address, packets) else history.setStatus(message.id, DeliveryStatus.QUEUED)
        changed(Change.MESSAGES)
        return true
    }

    /** Same picture, same id, from the start: the other phone keeps the pieces it already has for 10 minutes. */
    private fun resendPicture(message: Message): Boolean {
        val chat = history.chat(message.chatId) ?: return false
        if (region.blocksSending) { changed(Change.REGION); return false }
        val row = history.picture(message.id) ?: return false
        val codec = PictureCodec.of(row.codec) ?: return false
        val bytes = row.file?.let { File(picturesDir, it) }?.takeIf { it.isFile }?.readBytes() ?: return false
        val packets = PictureWire.split(row.pictureId, codec, row.width, row.height, bytes, row.sketch)
        history.setPictureProgress(message.id, 0)
        // No radio: it waits, saying so, and starts once one is ready (sendQueued).
        if (!mayTransmit()) { history.setStatus(message.id, DeliveryStatus.QUEUED); changed(Change.DELIVERY); return true }
        history.setStatus(message.id, DeliveryStatus.WAITING)
        startPicture(message.id, chat.address, packets)
        changed(Change.DELIVERY)
        return true
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

    // ---- Playback

    /** Plays a message; tapping the one playing stops it. One at a time. */
    fun play(messageId: Long) {
        if (playingId == messageId) { stopPlayback(); return }
        val message = history.message(messageId) ?: return
        if (message.kind != MessageKind.TEXT) return
        speakAs(messageId, message.text, message.language)
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

    /** The voice for the message's language; when it is missing, the user's own voice and a small note. */
    private fun speakAs(id: Long, text: String, languageTag: String?) {
        if (recordState != RecordState.IDLE) return
        val wanted = LanguageRegistry.forMessage(languageTag)
        val exact = wanted?.let { store.readiness(settings.engine, it.tag) as? Readiness.Ready }
        val pack = exact ?: readiness() as? Readiness.Ready ?: run { note(UiText(R.string.voice_missing)); return }
        if (exact == null && languageTag != null && wanted?.tag != currentLanguage().tag) {
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
        history.close()
    }

    // Test hook: returns once everything queued on the work thread has run.
    internal fun awaitWorkForTest() {
        work.submit {}.get(120, TimeUnit.SECONDS)
    }

    companion object {
        const val PRIMARY_CHANNEL = 0
        const val DRAFT_PLAYBACK = -1L
        const val HOWTO_PLAYBACK = -2L
        const val LINE_PLAYBACK = -3L
        const val BROADCAST = "^all"
        const val BACKGROUND_GRACE_MS = 60_000L
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
        )

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

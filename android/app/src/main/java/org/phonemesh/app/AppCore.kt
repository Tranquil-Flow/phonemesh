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
import org.phonemesh.core.RadioReport
import org.phonemesh.core.Readiness
import org.phonemesh.core.RecordGesture
import org.phonemesh.core.SetupPlan
import org.phonemesh.core.readiness
import java.io.File
import java.io.FileNotFoundException
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
        changed = { changed(Change.REGION) },
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
    var lastNote: UiText? = null
        private set

    init {
        history.expireWaiting()
        history.groupChat(PRIMARY_CHANNEL)
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
        main.removeCallbacks(connectExpired)
        runCatching { radio.connect(address) }.onFailure { connectingAddress = null }
        // Nothing else reports the end of the wait, so the screens are told when it runs out.
        if (connectingAddress != null) main.postDelayed(connectExpired, connectWaitMs)
        changed(Change.RADIO)
    }

    /** True while a connection asked for in the last 35 s has not finished (the radio gives up after 30 s). */
    fun isConnecting(address: String): Boolean = !connected && isAttempting(address)

    /** Like [isConnecting], but also true while another radio is still connected and this one was just asked for. */
    fun isAttempting(address: String): Boolean = connectingAddress == address &&
        SystemClock.elapsedRealtime() - connectingSince < connectWaitMs

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
        // Only a real change of the link reaches the region check: ready means a new handshake has finished.
        if (becameReady) region.onReady()
        if (was && !connected) region.onLost()
        if (connected != was || attempting) changed(Change.RADIO)
    }

    /** The radio gave up on the connection asked for: the wait ends now rather than after 35 s. */
    override fun onConnectFailed() {
        if (connectingAddress == null) return
        endAttempt()
        changed(Change.RADIO)
    }

    private fun endAttempt() {
        connectingAddress = null
        main.removeCallbacks(connectExpired)
    }

    override fun onDevice(address: String, name: String, rssi: Int) {
        if (!scanning || (address !in foundRadios && foundRadios.size >= MAX_FOUND)) return
        foundRadios[address] = FoundRadio(address, name, rssi)
        changed(Change.SCAN)
    }

    override fun onPeersChanged() {
        history.renameAll(radio.peers().filter { it.name.isNotBlank() && it.name != it.id }.associate { it.id to it.name })
        changed(Change.PEERS)
    }

    /** Only the primary channel in 3a. Malformed text is dropped (logged), never shown. */
    override fun onText(sender: String, text: String, channel: Int, direct: Boolean) {
        if (channel != PRIMARY_CHANNEL) return
        val received = try {
            MessageCodec.inspectIncoming(text)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "incoming message dropped", e)
            return
        }
        val chat = if (direct) history.directChat(sender, knownName(sender)) else history.groupChat(PRIMARY_CHANNEL)
        history.addIncoming(chat.id, sender, received.text, received.language, System.currentTimeMillis(), countUnread = openChatId != chat.id)
        changed(Change.MESSAGES)
    }

    override fun onDelivery(packetId: Long, state: DeliveryState, detail: String) {
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

    fun chatTitle(chat: Chat): String = when {
        chat.kind == ChatKind.GROUP -> text(UiText(R.string.group_name))
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
        val message = history.addOutgoing(chatId, text, language, System.currentTimeMillis())
        drafts.remove(chatId)
        cutDrafts.remove(chatId)
        // Before any notification: observers read setup state from this flag while they render.
        settings.firstMessageSent = true
        transmit(message.id, text, language, chat)
        changed(Change.MESSAGES)
        return true
    }

    /** Same bubble, new packet. Never automatic. A private message may arrive twice; accepted by the spec. */
    fun resend(messageId: Long): Boolean {
        val message = history.message(messageId) ?: return false
        if (message.direction != Direction.OUT || message.status?.canResend != true) return false
        val chat = history.chat(message.chatId) ?: return false
        // Like send: no region yet, or a region write is running. Nothing goes out and the message stays as it is.
        if (region.blocksSending) { changed(Change.REGION); return false }
        history.setStatus(messageId, DeliveryStatus.WAITING)
        transmit(messageId, message.text, message.language ?: currentLanguage().tag, chat)
        changed(Change.DELIVERY)
        return true
    }

    /** A radio that is not ready makes the message a failure the user can resend. */
    private fun transmit(messageId: Long, text: String, language: String, chat: Chat) {
        val destination = if (chat.kind == ChatKind.GROUP) BROADCAST else chat.address
        try {
            history.attachPacket(messageId, radio.send(MessageCodec.encode(text, language), destination, PRIMARY_CHANNEL))
        } catch (e: RuntimeException) {
            Log.w(TAG, "send failed", e)
            history.setStatus(messageId, DeliveryStatus.FAILED)
        }
    }

    // ---- Playback

    /** Plays a message; tapping the one playing stops it. One at a time. */
    fun play(messageId: Long) {
        if (playingId == messageId) { stopPlayback(); return }
        val message = history.message(messageId) ?: return
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
        const val HISTORY_DB = "history.db"
        private const val MAX_FOUND = 50
        private const val IMPORT_PROGRESS_STEP = 5_000_000L
        private const val TAG = "AppCore"

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
        ): AppCore = AppCore(
            app,
            radioFactory ?: { listener -> BleMeshTransport(app, listener) },
            speech ?: DeviceSpeech(app),
            History(app, historyName),
            Settings(app, prefsName),
            access ?: PhoneAccess(app),
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

package org.phonemesh.app.demo

import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RadioPeer
import org.phonemesh.ble.RadioRegions
import org.phonemesh.ble.RegionResult
import org.phonemesh.ble.TextMark
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's radio in the demo: a stock Meshtastic radio on LongFast, reached without Bluetooth. Find radio lists it,
 * connecting takes a moment, a new radio has no region until the app writes one (and then restarts, as real radios do),
 * and what the app sends goes onto the [DemoMesh]. Like [org.phonemesh.ble.BleMeshTransport], sends throw until the radio
 * is ready and every callback reaches the app through [post] (the main thread).
 */
class DemoRadio(
    private val listener: MeshRadio.Listener,
    private val mesh: DemoMesh,
    private val clock: DemoClock,
    private val post: (() -> Unit) -> Unit,
    private val state: DemoState,
    private val names: () -> List<RadioPeer> = { emptyList() },
    /** The demo world's hook: the radio became ready (true) or went (false). Runs on the clock's thread. */
    private val readyChanged: (Boolean) -> Unit = {},
) : MeshRadio {
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var attempt = 0
    @Volatile private var scan = 0
    @Volatile private var scanning = false
    private val packets = AtomicLong(0x2000_0000L + (clock.now() and 0xFFFFFL))

    init {
        mesh.join(NODE) { from, port, payload, to -> heard(from, port, payload, to) }
        mesh.setOn(NODE, false)
    }

    private val switchedOn: Boolean get() = state.string(KEY_OFF) == null

    private fun emit(event: MeshRadio.Listener.() -> Unit) = post { if (!closed) listener.event() }

    override fun scan() {
        val mine = ++scan
        scanning = true
        emit { onState(ready, "Scanning") }
        clock.after(FIND_MS) { if (scanning && scan == mine && switchedOn) emit { onDevice(ADDRESS, NAME, RSSI) } }
        clock.after(SCAN_MS) { if (scanning && scan == mine) { scanning = false; emit { onScanStopped() } } }
    }

    override fun stopScan() {
        if (!scanning) return
        scanning = false
        scan++
        emit { onScanStopped() }
    }

    override fun connect(address: String) {
        val mine = ++attempt
        if (address != ADDRESS) {
            clock.after(FAIL_MS) { if (attempt == mine) emit { onConnectFailed() } }
            return
        }
        emit { onState(false, "Connecting") }
        clock.after(CONNECT_MS) {
            if (attempt != mine || closed) return@after
            if (!switchedOn) {
                clock.after(GIVE_UP_MS - CONNECT_MS) { if (attempt == mine && !ready) emit { onConnectFailed() } }
                return@after
            }
            ready = true
            mesh.setOn(NODE, true)
            emit { onState(true, "Ready") }
            readyChanged(true)
        }
    }

    override fun disconnect() {
        attempt++
        drop("Disconnected")
    }

    private fun drop(detail: String) {
        val was = ready
        ready = false
        mesh.setOn(NODE, false)
        if (was) { emit { onState(false, detail) }; readyChanged(false) }
    }

    override fun peers(): List<RadioPeer> = if (ready) names() else emptyList()
    override fun modemPreset(): String? = if (ready) "LONG_FAST" else null
    override fun ownName(): String? = if (ready) NAME else null
    override fun channelName(): String? = null
    override fun region(): String? = if (ready) state.string(KEY_REGION) ?: "UNSET" else null

    override fun setRegion(region: String, done: (RegionResult) -> Unit) {
        require(region in RadioRegions.all) { "not a region the app may write: $region" }
        if (!ready) { emit { done(RegionResult.DROPPED) }; return }
        state.put(KEY_REGION, region)
        clock.after(WRITE_MS) {
            emit { done(RegionResult.SENT) }
            // A real radio reboots to apply a new region; the app reconnects and checks.
            clock.after(RESTART_MS) { attempt++; drop("Radio restarting") }
        }
    }

    override fun send(text: String, destinationId: String, channel: Int, replyTo: Long, reaction: Boolean): Long =
        submit(TEXT_PORT, text.toByteArray(Charsets.UTF_8), destinationId)

    override fun sendData(port: Int, payload: ByteArray, destinationId: String, channel: Int): Long =
        submit(port, payload, destinationId)

    override fun sendBroadcastData(port: Int, payload: ByteArray, channel: Int): Long = submit(port, payload, BROADCAST)

    private fun submit(port: Int, payload: ByteArray, to: String): Long {
        check(ready) { "the demo radio is not connected" }
        val id = packets.incrementAndGet()
        val bytes = payload.copyOf()
        emit { onDelivery(id, DeliveryState.SUBMITTING, "") }
        clock.after(QUEUE_MS) {
            if (!ready) { emit { onDelivery(id, DeliveryState.FAILED, "radio gone") }; return@after }
            emit { onDelivery(id, DeliveryState.QUEUED, "") }
            if (to == BROADCAST) mesh.broadcast(NODE, port, bytes)
            else mesh.send(NODE, to, port, bytes) { arrived ->
                emit { onDelivery(id, if (arrived) DeliveryState.ACKNOWLEDGED else DeliveryState.FAILED, "") }
            }
        }
        return id
    }

    private fun heard(from: String, port: Int, payload: ByteArray, to: String?) {
        if (!ready || closed) return
        val id = packets.incrementAndGet()
        when {
            port == TEXT_PORT -> {
                val text = String(payload, Charsets.UTF_8)
                emit { onText(from, text, 0, to != null, TextMark(packetId = id)) }
            }
            port in DATA_PORTS ->
                if (to == null) emit { onBroadcastData(from, port, payload, 0, id, SIGNAL) }
                else emit { onData(from, port, payload, 0, id) }
        }
    }

    /** Demo control: the radio's battery is out or its switch is off. */
    fun switchOff() {
        state.put(KEY_OFF, "1")
        attempt++
        drop("Radio switched off")
    }

    fun switchOn() { state.put(KEY_OFF, null) }

    val isOn: Boolean get() = switchedOn
    val isReady: Boolean get() = ready

    override fun close() {
        closed = true
        attempt++
        ready = false
        mesh.setOn(NODE, false)
    }

    companion object {
        const val ADDRESS = "D0:E1:00:00:00:01"
        const val NODE = "!d0e10001"
        const val NAME = "Demo radio"
        const val TEXT_PORT = 1
        const val BROADCAST = "^all"
        const val SIGNAL = "rssi=-71 snr=6.25 hops=0"
        /** The private ports Meshtastic hands to the app as data, as BleMeshTransport does. */
        private val DATA_PORTS = setOf(MeshRadio.PICTURE_PORT, MeshRadio.ACCOUNT_PORT, MeshRadio.HUB_ACK_PORT, MeshRadio.SHARED_CHAT_PORT)
        private const val RSSI = -58
        private const val KEY_REGION = "radio.region"
        private const val KEY_OFF = "radio.off"
        private const val FIND_MS = 900L
        private const val SCAN_MS = 30_000L
        private const val FAIL_MS = 1_500L
        private const val CONNECT_MS = 1_800L
        private const val GIVE_UP_MS = 30_000L
        private const val WRITE_MS = 700L
        private const val RESTART_MS = 500L
        private const val QUEUE_MS = 120L
    }
}

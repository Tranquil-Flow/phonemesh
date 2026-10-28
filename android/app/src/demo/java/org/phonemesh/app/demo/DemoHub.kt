package org.phonemesh.app.demo

import org.phonemesh.app.HubLink
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubWire
import org.phonemesh.hub.Hub
import org.phonemesh.hub.Mailbox
import org.phonemesh.hub.Outbox
import org.phonemesh.hub.Radio
import org.phonemesh.hub.RadioNotSubmittedException
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/**
 * The demo's local hub: the real hub engine ([Hub], its [Mailbox] and [Outbox] from `android/hub`) with a radio on the
 * [DemoMesh], stored in [dir] so its mail survives restarts. Phones reach it through [wifiLink] and [bluetoothLink], which
 * stand in for the hub's Wi-Fi and its Mac Bluetooth bridge: they answer only to this hub's own [card], and stop answering
 * when the hub is off or that way is down. What the demo leaves out is the transport itself (no Wi-Fi join, no TLS).
 */
class DemoHub(val dir: File, private val mesh: DemoMesh, private val clock: DemoClock) : AutoCloseable {
    val card: HubCard = loadCard()
    private val mailbox = Mailbox(File(dir, "mail").apply { mkdirs() }, DAYS)
    private val hub = Hub(mailbox, MeshRadioOut(), outbox = Outbox(File(dir, "outbox.bin"))).also { it.clock = clock::now }

    @Volatile private var powered = true
    @Volatile private var wifi = true
    @Volatile private var bluetooth = true
    @Volatile private var closed = false

    private val wifiWay = Way { wifi }
    private val bluetoothWay = Way { bluetooth }
    val wifiLink: HubLink get() = wifiWay
    val bluetoothLink: HubLink get() = bluetoothWay

    init {
        mesh.join(NODE) { _, port, payload, _ -> if (powered && !closed) hub.onData(port, payload, SIGNAL) }
        tick()
    }

    private inner class MeshRadioOut : Radio {
        override fun broadcast(port: Int, payload: ByteArray) {
            if (!powered || closed) throw RadioNotSubmittedException("the demo hub is switched off")
            mesh.broadcast(NODE, port, payload)
        }
    }

    private fun tick() {
        if (closed) return
        if (powered) runCatching { hub.tick() }
        clock.after(TICK_MS) { tick() }
    }

    val isOn: Boolean get() = powered
    val wifiOn: Boolean get() = wifi
    val bluetoothOn: Boolean get() = bluetooth

    /** Demo control: the hub's power. Off, its radio neither hears nor airs and neither way answers. */
    fun setPower(on: Boolean) { powered = on; mesh.setOn(NODE, on); changed() }
    fun setWifi(on: Boolean) { wifi = on; changed() }
    fun setBluetooth(on: Boolean) { bluetooth = on; changed() }

    private fun changed() { wifiWay.recheck(); bluetoothWay.recheck() }

    /** One way to the hub, standing in for [org.phonemesh.app.WifiHubLink] or [org.phonemesh.app.BleHubLink]. */
    private inner class Way(private val up: () -> Boolean) : HubLink {
        private var joined = false
        private var joinedPin: String? = null
        private var generation = 0
        private var current: Wire? = null
        private var onChange: (() -> Unit)? = null

        fun reachable() = !closed && powered && up()

        @Synchronized override fun wire(): HubWire? {
            if (!joined || !reachable()) return null
            return current ?: Wire(this).also { current = it }
        }

        override fun join(card: HubCard, changed: () -> Unit) {
            val mine = synchronized(this) {
                // Like the real links, joining the card already joined (or being joined) keeps that join: AppCore asks
                // again on every hub change, and a fresh join each time would never settle.
                if (card.pin == joinedPin && card.pin == this@DemoHub.card.pin) { onChange = changed; return }
                retire()
                joined = false
                joinedPin = card.pin
                onChange = changed
                ++generation
            }
            if (card.pin != this@DemoHub.card.pin) return
            clock.after(JOIN_MS) {
                val now = synchronized(this) { (generation == mine).also { if (it) joined = true } }
                if (now) changed()
            }
        }

        @Synchronized override fun leave() {
            generation++
            joined = false
            joinedPin = null
            onChange = null
            retire()
        }

        fun recheck() {
            val notify = synchronized(this) {
                if (!reachable()) retire()
                if (joined) onChange else null
            }
            notify?.invoke()
        }

        private fun retire() { current?.dead = true; current = null }
    }

    /** What [HubClient] does over the network, done in-process: the same answers from the same engine. */
    private inner class Wire(private val way: Way) : HubWire {
        @Volatile var dead = false

        private fun reach() { if (dead || !way.reachable()) throw IOException("the demo hub does not answer") }

        override fun send(port: Int, packet: ByteArray): HubClient.Sent = offer(port, packet, air = true)

        override fun sendHeld(port: Int, packet: ByteArray): HubClient.Sent = offer(port, packet, air = false)

        private fun offer(port: Int, packet: ByteArray, air: Boolean): HubClient.Sent {
            reach()
            return when (hub.fromPhone(PHONE, port, packet, air)) {
                Hub.Sent.STORED -> HubClient.Sent.STORED
                Hub.Sent.ALREADY -> HubClient.Sent.ALREADY
                Hub.Sent.REFUSED -> HubClient.Sent.REFUSED
                Hub.Sent.BUSY -> HubClient.Sent.BUSY
            }
        }

        override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<HubClient.Mail> {
            reach()
            val found = mailbox.await(since, codes.toSet(), hellos, false, waitSeconds * 1_000L)
            reach()
            return found.map { HubClient.Mail(it.seq, it.port, it.bytes) }
        }

        override fun latest(): Long { reach(); return mailbox.latest() }
    }

    private fun loadCard(): HubCard {
        dir.mkdirs()
        val file = File(dir, "card.txt")
        if (file.exists()) HubCard.parse(file.readText().trim())?.let { return it }
        val pin = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return HubCard(WIFI, PASSWORD, PORT, pin).also { file.writeText(it.encode()) }
    }

    override fun close() {
        closed = true
        changed()
    }

    companion object {
        const val NODE = "!d0e1face"
        const val WIFI = "Phonemesh demo hub"
        private const val PASSWORD = "demo-hub-1234"
        private const val PORT = 8443
        private const val DAYS = 3
        private const val PHONE = "demo-phone"
        private const val SIGNAL = "rssi=-80 snr=4.5 hops=0"
        private const val TICK_MS = 1_000L
        private const val JOIN_MS = 1_200L
    }
}

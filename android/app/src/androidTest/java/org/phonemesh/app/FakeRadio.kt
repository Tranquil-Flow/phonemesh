package org.phonemesh.app

import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RadioPeer
import org.phonemesh.ble.RadioRegions
import org.phonemesh.ble.RegionResult

/** Test radio: records what the app asks for and lets a test play the radio's side. Main thread only. */
class FakeRadio(private val listener: MeshRadio.Listener) : MeshRadio {
    data class Sent(val text: String, val destinationId: String, val channel: Int, val packetId: Long)

    val sent = mutableListOf<Sent>()
    val connects = mutableListOf<String>()
    var scans = 0
        private set
    var stopScans = 0
        private set
    var disconnects = 0
        private set
    var closed = false
        private set

    /** Like BleMeshTransport: send() throws until the radio is connected and configured. */
    var ready = false
    var peerList: List<RadioPeer> = emptyList()
    private var nextPacket = 1000L

    // Like BleMeshTransport, scan bookkeeping is reported as a diagnostic state that keeps the connection flag.
    override fun scan() { scans++; listener.onState(ready, "Scanning") }
    override fun stopScan() { stopScans++; listener.onState(ready, "Scan stopped") }
    override fun connect(address: String) { connects += address }
    override fun disconnect() { disconnects++ }
    override fun peers(): List<RadioPeer> = peerList
    override fun send(text: String, destinationId: String, channel: Int): Long {
        check(ready) { "Meshtastic radio is not ready" }
        val id = nextPacket++
        sent += Sent(text, destinationId, channel, id)
        return id
    }
    /** The region the fake radio reports while ready; tests change it to play a restart. */
    var region: String? = "EU_868"
    val regionWrites = mutableListOf<String>()
    var regionResult = RegionResult.SENT
    override fun region(): String? = if (ready) region else null
    /** Like BleMeshTransport: a region that is not offered is refused before anything is written. */
    override fun setRegion(region: String, done: (RegionResult) -> Unit) {
        require(region in RadioRegions.all) { "not a writable region: $region" }
        regionWrites += region
        done(regionResult)
    }
    override fun close() { closed = true }

    fun connected(on: Boolean) {
        ready = on
        listener.onState(on, if (on) "ready" else "lost")
    }
    /** The scan ended without results: a failure or Android's throttling, reported like BleMeshTransport does. */
    fun failScan() = listener.onScanStopped()
    fun failConnect() = listener.onConnectFailed()
    fun receive(sender: String, text: String, direct: Boolean, channel: Int = 0) = listener.onText(sender, text, channel, direct)
    fun deliver(packetId: Long, state: DeliveryState) = listener.onDelivery(packetId, state, "test")
    fun found(address: String, name: String, rssi: Int) = listener.onDevice(address, name, rssi)
    fun setPeers(peers: List<RadioPeer>) {
        peerList = peers
        listener.onPeersChanged()
    }
}

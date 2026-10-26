package org.phonemesh.app.demo

import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.MeshRadio
import java.util.PriorityQueue

/** Virtual time: [advance] runs every task that falls due, in order, on the calling thread. */
class ManualClock(var time: Long = 1_800_000_000_000L) : DemoClock {
    private class Task(val at: Long, val seq: Long, val run: () -> Unit)
    private val tasks = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var seq = 0L
    override fun now() = time
    override fun after(millis: Long, task: () -> Unit) { tasks += Task(time + maxOf(0L, millis), seq++, task) }
    fun advance(millis: Long) {
        val end = time + millis
        while (true) {
            val next = tasks.peek() ?: break
            if (next.at > end) break
            tasks.poll()
            time = maxOf(time, next.at)
            next.run()
        }
        time = end
    }
}

class MapState : DemoState {
    val values = HashMap<String, String>()
    override fun string(key: String): String? = values[key]
    override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

/** Everything one mesh node heard. */
class Ear : DemoMesh.Ear {
    class Heard(val from: String, val port: Int, val payload: ByteArray, val to: String?, val at: Long)
    val heard = mutableListOf<Heard>()
    var clock: ManualClock? = null
    override fun heard(from: String, port: Int, payload: ByteArray, to: String?) {
        heard += Heard(from, port, payload, to, clock?.now() ?: 0L)
    }
}

/** What the app (AppCore) would hear from a radio. */
class RecordingListener : MeshRadio.Listener {
    class Heard(val sender: String, val port: Int, val payload: ByteArray, val channel: Int, val packetId: Long)
    val states = mutableListOf<Pair<Boolean, String>>()
    val devices = mutableListOf<Pair<String, String>>()
    val deliveries = mutableListOf<Pair<Long, DeliveryState>>()
    val broadcasts = mutableListOf<Heard>()
    val data = mutableListOf<Heard>()
    val texts = mutableListOf<Triple<String, String, Boolean>>()
    var connectFailed = 0
    var scanStopped = 0
    override fun onState(connected: Boolean, detail: String) { states += connected to detail }
    override fun onConnectFailed() { connectFailed++ }
    override fun onScanStopped() { scanStopped++ }
    override fun onText(sender: String, text: String, channel: Int, direct: Boolean) { texts += Triple(sender, text, direct) }
    override fun onDevice(address: String, name: String, rssi: Int) { devices += address to name }
    override fun onDelivery(packetId: Long, state: DeliveryState, detail: String) { deliveries += packetId to state }
    override fun onData(sender: String, port: Int, payload: ByteArray, channel: Int, packetId: Long) {
        data += Heard(sender, port, payload, channel, packetId)
    }
    override fun onBroadcastData(sender: String, port: Int, payload: ByteArray, channel: Int, packetId: Long, signal: String) {
        broadcasts += Heard(sender, port, payload, channel, packetId)
    }
    val connected: Boolean get() = states.lastOrNull()?.first == true
}

package org.phonemesh.hub

import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos
import org.phonemesh.ble.MeshtasticProtocol
import org.phonemesh.ble.RadioEvent
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the hub needs from its radio. */
interface Radio {
    /** Broadcast [payload] on [port] (301-303) on the primary channel, with a fresh packet id. */
    fun broadcast(port: Int, payload: ByteArray)
}

/** Only throw this when no byte of this packet was offered to the serial writer.
 * Ordinary I/O exceptions are ambiguous and must NOT be retried automatically in the same process. */
class RadioNotSubmittedException(message: String) : java.io.IOException(message)

/** What the radio tells the hub. */
interface RadioListener {
    fun onData(port: Int, payload: ByteArray, signal: String)
    /** The radio's rebroadcast mode, read at start; relays must be ALL for our ports (T1 desk check F3). */
    fun onRebroadcastMode(mode: ConfigProtos.Config.DeviceConfig.RebroadcastMode) {}
    fun onReady(nodeNum: Long) {}
    fun onLog(line: String) {}
}

/**
 * A Meshtastic radio on the serial API: [Framing] over the port's streams. At start it wakes the radio and asks for its
 * configuration, then passes up broadcasts on ports 301-303. A heartbeat every few minutes keeps the session open.
 */
class SerialRadio(private val input: InputStream, private val output: OutputStream, private val listener: RadioListener) : Radio {
    private val reader = Framing.Reader()
    private val ports = MeshtasticProtocol.BROADCAST_PORTS
    @Volatile private var running = true

    fun start(configId: Int = MeshtasticProtocol.newNonce()) {
        thread(name = "hub-radio-read", isDaemon = true) { readLoop() }
        write(Framing.WAKE, framed = false)
        write(MeshtasticProtocol.buildWantConfig(configId))
        thread(name = "hub-radio-heartbeat", isDaemon = true) {
            var nonce = MeshtasticProtocol.FIRST_HEARTBEAT_NONCE
            while (running) {
                Thread.sleep(HEARTBEAT_MILLIS)
                runCatching { write(MeshtasticProtocol.buildHeartbeat(nonce++)) }
            }
        }
    }

    fun stop() { running = false }

    override fun broadcast(port: Int, payload: ByteArray) {
        if (!running) throw RadioNotSubmittedException("radio stopped before submission")
        write(MeshtasticProtocol.buildBroadcastDataToRadio(port, payload, channel = 0))
    }

    @Synchronized private fun write(bytes: ByteArray, framed: Boolean = true) {
        output.write(if (framed) Framing.frame(bytes) else bytes)
        output.flush()
    }

    private fun readLoop() {
        val buffer = ByteArray(1024)
        while (running) {
            val count = runCatching { input.read(buffer) }.getOrElse { listener.onLog("radio read failed: ${it.message}"); -1 }
            if (count < 0) break
            reader.feed(buffer, count, frames = ::handle, lines = listener::onLog)
        }
        listener.onLog("radio closed")
    }

    internal fun handle(frame: ByteArray) {
        val fromRadio = runCatching { MeshProtos.FromRadio.parseFrom(frame) }.getOrNull() ?: return
        if (fromRadio.hasConfig() && fromRadio.config.hasDevice()) listener.onRebroadcastMode(fromRadio.config.device.rebroadcastMode)
        when (val event = MeshtasticProtocol.parseFromRadio(fromRadio, ports)) {
            is RadioEvent.Data -> if (event.to == MeshtasticProtocol.BROADCAST_NUM) listener.onData(event.port, event.payload, event.signal)
            is RadioEvent.MyInfo -> listener.onReady(event.nodeNum.toLong() and MeshtasticProtocol.BROADCAST_NUM)
            else -> Unit
        }
    }

    companion object {
        const val HEARTBEAT_MILLIS = 5 * 60_000L

        /**
         * Opens a USB serial device such as /dev/cu.usbmodem1101 at 115200 baud, raw. macOS's `stty -f` sets the line
         * while this process holds the device open (cu devices reset their settings when the last holder closes).
         */
        fun openTty(path: String): Pair<InputStream, OutputStream> {
            val device = File(path)
            require(device.exists()) { "no serial device at $path" }
            val input = device.inputStream()
            val output = java.io.FileOutputStream(device)
            val stty = ProcessBuilder("stty", "-f", path, "115200", "raw", "-echo", "-hupcl", "clocal").redirectErrorStream(true).start()
            check(stty.waitFor(5, TimeUnit.SECONDS) && stty.exitValue() == 0) {
                "stty failed for $path: " + stty.inputStream.bufferedReader().readText()
            }
            return input to output
        }
    }
}

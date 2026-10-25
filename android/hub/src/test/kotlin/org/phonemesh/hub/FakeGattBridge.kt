package org.phonemesh.hub

import org.phonemesh.core.hub.GattFrame
import org.phonemesh.core.hub.HubGatt
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * A JVM model of the Mac bridge (hub-ble-bridge, Swift) for tests: it speaks the same frames as the phone's GattStreams
 * and forwards only opaque bytes to ONE fixed loopback port. It never parses, terminates or logs what it carries. It
 * obeys the same flow control (credit window per stream, one DATA direction at a time per frame) and connects only to
 * 127.0.0.1 and the port it was built with, whatever a phone writes. The Swift bridge must behave the same way; this
 * model is the executable statement of that behaviour, not a test of the Swift code.
 */
class FakeGattBridge(
    private val upstreamPort: Int,
    private val maxFrame: () -> Int,
    private val toPhone: (ByteArray) -> Unit,
) : AutoCloseable {
    private class Lane(val id: Int, val socket: Socket) {
        var credit = HubGatt.WINDOW
        var txSeq = 0
        var rxSeq = 0
        @Volatile var done = false
    }

    private val lanes = ConcurrentHashMap<Int, Lane>()
    private val sendLock = Any()
    @Volatile private var closed = false

    /** One write from the phone (one whole frame), delivered in order. */
    fun onFrame(bytes: ByteArray) {
        if (closed) return
        val frame = GattFrame.decode(bytes) ?: return
        when (frame.type) {
            HubGatt.OPEN -> open(frame.stream)
            HubGatt.DATA -> {
                val lane = lanes[frame.stream] ?: return
                if (frame.seq != lane.rxSeq) return reset(lane, HubGatt.RESET_PROTOCOL)
                lane.rxSeq = (lane.rxSeq + 1) and 0xFF
                try {
                    lane.socket.getOutputStream().write(frame.payload)
                    lane.socket.getOutputStream().flush()
                } catch (_: IOException) {
                    return reset(lane, HubGatt.RESET_UNAVAILABLE)
                }
                send(GattFrame.credit(lane.id, frame.payload.size))
            }
            HubGatt.CREDIT -> lanes[frame.stream]?.let { lane ->
                synchronized(lane) {
                    if (lane.credit + frame.credit > HubGatt.WINDOW) reset(lane, HubGatt.RESET_FLOW)
                    else { lane.credit += frame.credit; (lane as Object).notifyAll() }
                }
            }
            HubGatt.CLOSE, HubGatt.RESET -> lanes[frame.stream]?.let { end(it) }
        }
    }

    private fun open(id: Int) {
        if (lanes.size >= HubGatt.MAX_STREAMS || lanes.containsKey(id)) return send(GattFrame.reset(id, HubGatt.RESET_BUSY))
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), upstreamPort), 2_000)
        } catch (_: IOException) {
            return send(GattFrame.reset(id, HubGatt.RESET_UNAVAILABLE))
        }
        val lane = Lane(id, socket)
        lanes[id] = lane
        send(GattFrame.opened(id))
        Thread({ pump(lane) }, "fake-bridge-$id").apply { isDaemon = true }.start()
    }

    /** Upstream to phone: only while the phone has granted credit, so a slow phone slows the server instead of filling memory. */
    private fun pump(lane: Lane) {
        val buffer = ByteArray(HubGatt.MAX_FRAME)
        try {
            while (!lane.done) {
                val room = synchronized(lane) {
                    while (lane.credit == 0 && !lane.done) (lane as Object).wait()
                    minOf(lane.credit, HubGatt.maxPayload(maxFrame()), buffer.size)
                }
                if (lane.done) return
                val n = lane.socket.getInputStream().read(buffer, 0, room)
                if (n < 0) {
                    if (!lane.done) { send(GattFrame.close(lane.id)); end(lane, tell = false) }
                    return
                }
                synchronized(lane) { lane.credit -= n }
                val seq = lane.txSeq
                lane.txSeq = (lane.txSeq + 1) and 0xFF
                send(GattFrame.data(lane.id, seq, buffer.copyOf(n)))
            }
        } catch (_: IOException) {
            if (!lane.done) reset(lane, HubGatt.RESET_UNAVAILABLE)
        } catch (_: InterruptedException) {
        }
    }

    private fun reset(lane: Lane, reason: Int) {
        send(GattFrame.reset(lane.id, reason))
        end(lane, tell = false)
    }

    private fun end(lane: Lane, tell: Boolean = true) {
        if (!lanes.remove(lane.id, lane)) return
        lane.done = true
        runCatching { lane.socket.close() }
        synchronized(lane) { (lane as Object).notifyAll() }
    }

    private fun send(frame: GattFrame) = synchronized(sendLock) { if (!closed) toPhone(frame.encode()) }

    override fun close() {
        closed = true
        lanes.values.toList().forEach { end(it) }
    }
}

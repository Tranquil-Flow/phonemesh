package org.phonemesh.core.hub

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The phone's end of the Bluetooth hub transport for ONE connected GATT link (see [HubGatt] for the wire): it cuts each
 * stream into frames that fit the link's MTU, writes them one at a time, puts notifications back in order, and gives
 * [HubClient] plain blocking streams. A new GATT connection gets a new [GattStreams]; an old one that is dead ignores
 * every later callback, which is the generation fence for stale Android callbacks.
 *
 * Bounds: one write is outstanding at a time (the next starts only after [written]); at most [HubGatt.MAX_STREAMS] streams;
 * at most [HubGatt.WINDOW] unread bytes per stream (the hub may not send more until the app has read, so a slow reader
 * stalls the hub instead of growing memory); at most [MAX_QUEUED] frames waiting to be written; every blocking call ends
 * at the stream's own total deadline. A frame that cannot be understood, a refused write or a lost link ends the whole
 * link: every stream fails with an IOException and [onDead] runs (it must not block; the Android end disconnects).
 * Nothing here knows about TLS; the bytes are opaque.
 */
class GattStreams(
    private val port: GattPort,
    private val onDead: (String) -> Unit = {},
) : HubStreams {
    private class Lane(val id: Int) {
        var open = false
        var remoteClosed = false
        var failure: IOException? = null
        val inbound = ArrayDeque<ByteArray>()
        var headOffset = 0
        var inboundBytes = 0
        var rxSeq = 0
        var txSeq = 0
        var sendCredit = HubGatt.WINDOW
        var unreturned = 0
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val lanes = HashMap<Int, Lane>()
    private val outbound = ArrayDeque<ByteArray>()
    private var writing = false
    private var nextId = 1
    private var deadError: IOException? = null

    val isDead: Boolean get() = lock.withLock { deadError != null }

    override fun open(totalMillis: Int): HubStream {
        val until = System.nanoTime() + totalMillis.coerceAtLeast(1) * 1_000_000L
        lock.withLock {
            deadError?.let { throw IOException("the Bluetooth link is gone: ${it.message}") }
            if (lanes.size >= HubGatt.MAX_STREAMS) throw IOException("the Bluetooth link is busy")
            val lane = Lane(allocate())
            lanes[lane.id] = lane
            enqueue(GattFrame.open(lane.id).encode())
            pump()
            try {
                await(until, "opening") { lane.open || lane.failure != null }
            } catch (e: IOException) {
                end(lane, HubGatt.RESET_TIMEOUT)
                throw e
            }
            lane.failure?.let { throw it }
            return LaneStream(lane, until)
        }
    }

    /** One notification from the hub (a whole frame). Call from the one thread that delivers a link's callbacks, in order. */
    fun onFrame(bytes: ByteArray) = lock.withLock {
        if (deadError != null) return@withLock
        val frame = GattFrame.decode(bytes) ?: return@withLock kill("bad frame from the hub")
        val lane = lanes[frame.stream] ?: return@withLock // a stream we closed or never opened: stale, ignored
        when (frame.type) {
            HubGatt.OPENED -> if (lane.open) reset(lane, HubGatt.RESET_PROTOCOL) else lane.open = true
            HubGatt.DATA -> {
                if (!lane.open || lane.remoteClosed || frame.seq != lane.rxSeq) return@withLock reset(lane, HubGatt.RESET_PROTOCOL)
                if (lane.inboundBytes + frame.payload.size > HubGatt.WINDOW) return@withLock reset(lane, HubGatt.RESET_FLOW)
                lane.rxSeq = (lane.rxSeq + 1) and 0xFF
                lane.inbound.add(frame.payload)
                lane.inboundBytes += frame.payload.size
            }
            HubGatt.CREDIT -> {
                if (lane.sendCredit + frame.credit > HubGatt.WINDOW) return@withLock reset(lane, HubGatt.RESET_FLOW)
                lane.sendCredit += frame.credit
            }
            HubGatt.CLOSE -> lane.remoteClosed = true
            HubGatt.RESET -> {
                lane.failure = IOException("the hub reset the stream (${frame.payload[0].toInt() and 0xFF})")
                lanes.remove(lane.id)
            }
            else -> reset(lane, HubGatt.RESET_PROTOCOL) // OPEN is never sent to a phone
        }
        changed.signalAll()
        pump()
    }

    /** The write started by [GattPort.write] finished: the next frame may go. A failed write ends the link (its state is unknown). */
    fun written(ok: Boolean) = lock.withLock {
        if (deadError != null || !writing) return@withLock
        writing = false
        if (!ok) return@withLock kill("a Bluetooth write failed")
        changed.signalAll()
        pump()
    }

    /** The GATT link ended (disconnect, timeout, permission loss, Bluetooth off). Every stream fails. */
    fun linkClosed(reason: String) = lock.withLock { kill(reason) }

    // ---- everything below runs with the lock held

    private fun allocate(): Int {
        for (i in 0 until 255) {
            val id = nextId
            nextId = if (nextId >= 255) 1 else nextId + 1
            if (id !in lanes) return id
        }
        throw IOException("no stream number free")
    }

    private fun enqueue(frame: ByteArray) {
        outbound.add(frame)
    }

    /** Starts the next write if none is outstanding. Credit grants are made here, so many small reads cost one frame. */
    private fun pump() {
        if (writing || deadError != null) return
        val frame = nextFrame() ?: return
        writing = true
        val accepted = try {
            port.write(frame)
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) kill("a Bluetooth write was refused")
    }

    private fun nextFrame(): ByteArray? {
        for (lane in lanes.values) {
            if (lane.unreturned > 0 && (lane.unreturned >= GRANT_STEP || lane.inboundBytes == 0)) {
                val grant = lane.unreturned.coerceAtMost(HubGatt.WINDOW)
                lane.unreturned -= grant
                return GattFrame.credit(lane.id, grant).encode()
            }
        }
        return outbound.poll()
    }

    private fun reset(lane: Lane, reason: Int) {
        lane.failure = IOException("the stream broke (reason $reason)")
        end(lane, reason)
    }

    /** Takes [lane] out of service: later frames for it are ignored, blocked callers fail, and the hub is told. */
    private fun end(lane: Lane, reason: Int) {
        if (lanes.remove(lane.id) == null) return
        if (lane.failure == null) lane.failure = IOException("the hub stream was closed")
        if (deadError == null) {
            enqueue((if (lane.open && reason == HubGatt.RESET_CLOSED) GattFrame.close(lane.id) else GattFrame.reset(lane.id, reason)).encode())
            pump()
        }
        changed.signalAll()
    }

    private fun kill(why: String) {
        if (deadError != null) return
        val error = IOException(why)
        deadError = error
        for (lane in lanes.values) if (lane.failure == null) lane.failure = error
        lanes.clear()
        outbound.clear()
        writing = false
        changed.signalAll()
        onDead(why)
    }

    private fun await(until: Long, what: String, ready: () -> Boolean) {
        while (true) {
            // The deadline is checked first: a hub that drips one byte at a time cannot keep a stream alive past its budget.
            val left = until - System.nanoTime()
            if (left <= 0) throw IOException("the hub took too long ($what)")
            if (ready()) return
            try {
                changed.awaitNanos(left)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted $what")
            }
        }
    }

    private inner class LaneStream(private val lane: Lane, private val until: Long) : HubStream {
        override val input: InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                return lock.withLock {
                    await(until, "reading") { lane.inboundBytes > 0 || lane.failure != null || lane.remoteClosed }
                    lane.failure?.let { throw it }
                    if (lane.inboundBytes == 0) return@withLock -1
                    var copied = 0
                    while (copied < len && lane.inbound.isNotEmpty()) {
                        val head = lane.inbound.peek()
                        val n = minOf(len - copied, head.size - lane.headOffset)
                        System.arraycopy(head, lane.headOffset, b, off + copied, n)
                        copied += n
                        lane.headOffset += n
                        if (lane.headOffset == head.size) { lane.inbound.poll(); lane.headOffset = 0 }
                    }
                    lane.inboundBytes -= copied
                    lane.unreturned += copied
                    pump()
                    copied
                }
            }

            override fun available(): Int = lock.withLock { lane.inboundBytes }

            override fun close() = abort()
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                lock.withLock {
                    var at = off
                    val stop = off + len
                    while (at < stop) {
                        await(until, "writing") { lane.failure != null || (lane.sendCredit > 0 && outbound.size < MAX_QUEUED) }
                        lane.failure?.let { throw it }
                        val chunk = minOf(stop - at, lane.sendCredit, HubGatt.maxPayload(port.maxFrame()))
                        enqueue(GattFrame.data(lane.id, lane.txSeq, b.copyOfRange(at, at + chunk)).encode())
                        lane.txSeq = (lane.txSeq + 1) and 0xFF
                        lane.sendCredit -= chunk
                        at += chunk
                        pump()
                    }
                }
            }

            override fun close() = abort()
        }

        override fun abort() = lock.withLock { end(lane, HubGatt.RESET_CLOSED) }
    }

    private companion object {
        /** Reads are handed back to the hub in lumps of at least this much (or when the buffer is empty). */
        const val GRANT_STEP = HubGatt.WINDOW / 4
        const val MAX_QUEUED = 64
    }
}

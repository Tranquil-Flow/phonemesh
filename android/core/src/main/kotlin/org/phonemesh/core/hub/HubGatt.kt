package org.phonemesh.core.hub

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * One ordered, bounded byte stream to a hub that is not a socket (the Bluetooth GATT link). [HubClient] puts its own
 * standard TLS on top, so everything a stream carries is opaque to the bridge and to anybody listening to the radio.
 */
interface HubStream : AutoCloseable {
    val input: InputStream
    val output: OutputStream

    /** Ends the stream at once, from any thread; blocked reads and writes fail with an IOException. Safe to repeat. */
    fun abort()

    override fun close() = abort()
}

/** Where [HubClient] gets a [HubStream] when the hub is not reached by a socket. */
interface HubStreams {
    /** Opens one stream and returns when the hub end is connected, within [totalMillis], which also bounds the stream's whole life. */
    fun open(totalMillis: Int): HubStream
}

/** What [GattStreams] needs from one connected GATT link; the Android implementation is GattHubCentral (radio-ble). */
interface GattPort {
    /** The largest frame (header included) one write or notification can carry now (negotiated MTU minus 3). */
    fun maxFrame(): Int

    /** Starts writing [frame] (with response) and returns true; the outcome comes back as [GattStreams.written]. False: refused. */
    fun write(frame: ByteArray): Boolean
}

/**
 * The Bluetooth hub transport's fixed facts (accounts decision 19). Wire: every GATT write (phone to hub, characteristic
 * [RX_UUID]) and every notification (hub to phone, [TX_UUID]) is exactly one frame:
 *
 *     byte 0  type    byte 1  stream (1..255)    byte 2  seq (DATA only, else 0)    bytes 3..  payload
 *
 * OPEN (phone, payload = [VERSION]) asks for a new stream; OPENED (hub) says the hub's own HTTPS server accepted it;
 * DATA carries opaque TLS bytes in order (seq counts DATA frames of one direction of one stream, modulo 256); CREDIT
 * (payload = 2 bytes big-endian, 1..[WINDOW]) lets the other side send that many more DATA bytes; CLOSE ends a stream
 * in order; RESET (payload = 1 reason byte) ends it at once. A side may have at most [WINDOW] DATA bytes unacknowledged
 * by CREDIT per stream, and a link carries at most [MAX_STREAMS] streams. The service UUID is derived from the hub pin
 * ([serviceUuid]) so a phone can tell which hub it is looking at; it is a hint, never authentication: the TLS handshake
 * inside the stream is, and no application byte is written before it has matched the pin.
 */
object HubGatt {
    const val RX_UUID = "7c0f6d52-3a2b-4e5b-9c63-1d84a7e2b001"
    const val TX_UUID = "7c0f6d52-3a2b-4e5b-9c63-1d84a7e2b002"

    const val VERSION = 1
    const val HEADER = 3
    const val MIN_FRAME = 8
    const val MAX_FRAME = 512
    const val WINDOW = 8192
    const val MAX_STREAMS = 2

    const val OPEN = 1
    const val OPENED = 2
    const val DATA = 3
    const val CREDIT = 4
    const val CLOSE = 5
    const val RESET = 6

    const val RESET_PROTOCOL = 1
    const val RESET_UNAVAILABLE = 2
    const val RESET_BUSY = 3
    const val RESET_TIMEOUT = 4
    const val RESET_CLOSED = 5
    const val RESET_FLOW = 6

    private const val DOMAIN = "phonemesh-hub-gatt-1:"

    /**
     * The hub's GATT service UUID (and the one UUID it advertises): the first 16 bytes of SHA-256("phonemesh-hub-gatt-1:" + pin),
     * with the version-5 and variant bits set, in lower-case text. Public information, since the pin is.
     */
    fun serviceUuid(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((DOMAIN + pin).toByteArray(Charsets.UTF_8))
        val b = digest.copyOf(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20)
    }

    /** The most DATA bytes one frame carries when links carry [maxFrame] bytes. */
    fun maxPayload(maxFrame: Int): Int = maxFrame.coerceIn(MIN_FRAME, MAX_FRAME) - HEADER
}

/** One frame of the Bluetooth hub transport (see [HubGatt]). */
class GattFrame(val type: Int, val stream: Int, val seq: Int, payload: ByteArray = ByteArray(0)) {
    val payload: ByteArray = payload.copyOf()

    fun encode(): ByteArray {
        val out = ByteArray(HubGatt.HEADER + payload.size)
        out[0] = type.toByte()
        out[1] = stream.toByte()
        out[2] = seq.toByte()
        System.arraycopy(payload, 0, out, HubGatt.HEADER, payload.size)
        return out
    }

    /** The CREDIT amount of a CREDIT frame. */
    val credit: Int get() = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    companion object {
        fun open(stream: Int) = GattFrame(HubGatt.OPEN, stream, 0, byteArrayOf(HubGatt.VERSION.toByte()))
        fun opened(stream: Int) = GattFrame(HubGatt.OPENED, stream, 0)
        fun data(stream: Int, seq: Int, bytes: ByteArray) = GattFrame(HubGatt.DATA, stream, seq and 0xFF, bytes)
        fun credit(stream: Int, bytes: Int) = GattFrame(HubGatt.CREDIT, stream, 0, byteArrayOf((bytes shr 8).toByte(), bytes.toByte()))
        fun close(stream: Int) = GattFrame(HubGatt.CLOSE, stream, 0)
        fun reset(stream: Int, reason: Int) = GattFrame(HubGatt.RESET, stream, 0, byteArrayOf(reason.toByte()))

        /** The frame in [bytes], or null unless it is exactly one well-formed frame of a known type. Never throws. */
        fun decode(bytes: ByteArray): GattFrame? {
            if (bytes.size < HubGatt.HEADER || bytes.size > HubGatt.MAX_FRAME) return null
            val type = bytes[0].toInt() and 0xFF
            val stream = bytes[1].toInt() and 0xFF
            val seq = bytes[2].toInt() and 0xFF
            val payload = bytes.copyOfRange(HubGatt.HEADER, bytes.size)
            if (stream == 0) return null
            if (type != HubGatt.DATA && seq != 0) return null
            val shaped = when (type) {
                HubGatt.OPEN -> payload.size == 1 && (payload[0].toInt() and 0xFF) == HubGatt.VERSION
                HubGatt.OPENED, HubGatt.CLOSE -> payload.isEmpty()
                HubGatt.DATA -> payload.isNotEmpty()
                HubGatt.CREDIT -> payload.size == 2 && GattFrame(type, stream, seq, payload).credit in 1..HubGatt.WINDOW
                HubGatt.RESET -> payload.size == 1
                else -> false
            }
            return if (shaped) GattFrame(type, stream, seq, payload) else null
        }
    }
}

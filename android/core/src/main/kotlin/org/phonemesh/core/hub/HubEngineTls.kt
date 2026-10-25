package org.phonemesh.core.hub

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * Standard TLS (JSSE [SSLEngine], client mode) over a [HubStream], for a hub that is not reached by a socket. The caller
 * ([HubClient]) gives the SSLContext whose only trust is the hub QR's pin, so the handshake itself rejects any other key;
 * [connect] returns only after the handshake finished, and [HubClient] then checks the pin a second time before it writes
 * its first byte. This class never encrypts or sends anything before the handshake ends and never falls back to plain
 * bytes: if TLS fails, the stream is aborted and the failure (an [SSLException], which is an IOException) is thrown.
 * Everything runs on the calling thread within the absolute deadline [until] (System.nanoTime based).
 */
internal class HubEngineTls private constructor(
    private val engine: SSLEngine,
    private val stream: HubStream,
    private val until: Long,
) {
    private var netIn: ByteBuffer = ByteBuffer.allocate(0) // bytes read from the stream, not yet unwrapped (read mode)
    private var plainIn: ByteBuffer = ByteBuffer.allocate(0) // decrypted, not yet read (read mode)
    private var peerClosed = false

    val peerLeaf: X509Certificate
        get() = engine.session.peerCertificates.firstOrNull() as? X509Certificate ?: throw IOException("no certificate")

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (!plainIn.hasRemaining()) {
                if (peerClosed) return -1
                fill()
            }
            val n = minOf(len, plainIn.remaining())
            plainIn.get(b, off, n)
            return n
        }

        override fun close() = shutdown()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val source = ByteBuffer.wrap(b, off, len)
            while (source.hasRemaining()) {
                late()
                val out = ByteBuffer.allocate(engine.session.packetBufferSize)
                val result = engine.wrap(source, out)
                flush(out)
                when (result.status) {
                    SSLEngineResult.Status.OK -> Unit
                    SSLEngineResult.Status.CLOSED -> throw IOException("the secure link is closed")
                    else -> throw IOException("TLS could not send (${result.status})")
                }
            }
        }

        override fun flush() = stream.output.flush()

        override fun close() = shutdown()
    }

    /** Sends close_notify (best effort) and ends the stream. */
    fun shutdown() {
        try {
            if (!engine.isOutboundDone) {
                engine.closeOutbound()
                val out = ByteBuffer.allocate(engine.session.packetBufferSize)
                engine.wrap(ByteBuffer.allocate(0), out)
                flush(out)
            }
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        } finally {
            stream.abort()
        }
    }

    private fun late() {
        if (System.nanoTime() > until) throw IOException("the hub took too long")
    }

    private fun flush(out: ByteBuffer) {
        out.flip()
        if (out.hasRemaining()) {
            val bytes = ByteArray(out.remaining())
            out.get(bytes)
            stream.output.write(bytes)
        }
        stream.output.flush()
    }

    /** Reads at least one more byte from the stream into [netIn]. */
    private fun pull() {
        late()
        val chunk = ByteArray(PULL_BYTES)
        val n = stream.input.read(chunk)
        if (n < 0) throw IOException("the Bluetooth link closed before TLS ended")
        val merged = ByteBuffer.allocate(netIn.remaining() + n)
        merged.put(netIn)
        merged.put(chunk, 0, n)
        merged.flip()
        netIn = merged
    }

    /** Unwraps until some application data is in [plainIn] or the peer closed, reading more of the stream when it must. */
    private fun fill() {
        while (!plainIn.hasRemaining() && !peerClosed) {
            if (!netIn.hasRemaining()) pull()
            val target = ByteBuffer.allocate(engine.session.applicationBufferSize)
            val result = engine.unwrap(netIn, target)
            target.flip()
            when (result.status) {
                SSLEngineResult.Status.OK -> if (target.hasRemaining()) plainIn = target
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> pull()
                SSLEngineResult.Status.CLOSED -> peerClosed = true
                SSLEngineResult.Status.BUFFER_OVERFLOW -> throw IOException("a TLS record was larger than TLS allows")
            }
            // TLS 1.3 post-handshake messages (tickets) may ask us to answer; do it before reading on.
            when (result.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapHandshake()
                else -> Unit
            }
        }
    }

    private fun runTasks() {
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
    }

    private fun wrapHandshake() {
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(ByteBuffer.allocate(0), out)
        flush(out)
    }

    private fun handshake() {
        engine.beginHandshake()
        var status = engine.handshakeStatus
        while (status != SSLEngineResult.HandshakeStatus.FINISHED && status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            late()
            when (status) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> { runTasks(); status = engine.handshakeStatus }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val out = ByteBuffer.allocate(engine.session.packetBufferSize)
                    val result = engine.wrap(ByteBuffer.allocate(0), out)
                    flush(out)
                    if (result.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS closed during the handshake")
                    status = result.handshakeStatus
                }
                else -> { // NEED_UNWRAP (and NEED_UNWRAP_AGAIN on newer runtimes)
                    if (!netIn.hasRemaining()) pull()
                    val scratch = ByteBuffer.allocate(engine.session.applicationBufferSize)
                    val result = engine.unwrap(netIn, scratch)
                    when (result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> pull()
                        SSLEngineResult.Status.CLOSED -> throw SSLException("TLS closed during the handshake")
                        else -> Unit
                    }
                    if (scratch.position() > 0) throw SSLException("application data before the handshake ended")
                    status = result.handshakeStatus
                }
            }
        }
    }

    companion object {
        private const val PULL_BYTES = 4096

        /** Handshakes [context]'s TLS 1.2/1.3 over [stream]. On any failure the stream is aborted and the failure thrown. */
        fun connect(context: SSLContext, host: String, port: Int, stream: HubStream, until: Long): HubEngineTls {
            val engine = context.createSSLEngine(host, port)
            engine.useClientMode = true
            engine.enabledProtocols = engine.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            val tls = HubEngineTls(engine, stream, until)
            try {
                tls.handshake()
            } catch (e: IOException) {
                stream.abort()
                throw e
            } catch (e: RuntimeException) {
                stream.abort()
                throw IOException("TLS failed", e)
            }
            return tls
        }
    }
}

package org.phonemesh.core.hub

import org.phonemesh.core.update.HubResponse
import org.phonemesh.core.update.HubTransport
import org.phonemesh.core.update.UpdateRoute
import org.phonemesh.core.wire.WireV2
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** What a phone needs from a hub. Blocking: never call it on the main thread. Every failure is an [IOException]. */
interface HubWire {
    fun send(port: Int, packet: ByteArray): HubClient.Sent

    /**
     * Like [send], but the hub keeps the packet only for phones that fetch their mail from it and never airs it
     * (decision 64: photo avatars only through a hub). A way that cannot promise that throws, so the packet waits.
     */
    fun sendHeld(port: Int, packet: ByteArray): HubClient.Sent = throw IOException("this way to the hub cannot keep mail off the radio")

    /** Mail after [since] for [codes]; with [hellos] also the hellos, which carry no code. */
    fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean = false): List<HubClient.Mail>

    /** The hub's newest mail seq: lower than a saved cursor means the hub lost its mail. */
    fun latest(): Long

    /**
     * The way to this hub's signed updates, or null. Only a hub reached over its own Wi-Fi network (pinned TLS on the joined
     * network's sockets) can give one: updates never travel over Bluetooth or the radio (decision 45).
     */
    fun updates(): HubTransport? = null
}

/**
 * The phone's side of the hub's API (accounts plan step 16; hub module: HubServer), plain JVM so it is tested against the
 * real server. TLS is pinned to the hub's key from the hub QR (review F25): the certificate chain is ignored, only
 * SHA-256 of the leaf's public key counts, so nobody else on the Wi-Fi can pose as the hub. [sockets] makes the plain
 * sockets: on a phone, the joined Wi-Fi's Network socket factory, so the traffic cannot leave by mobile data.
 * The hub is reached by IPv4 address (the Wi-Fi's gateway), never by name, so nothing can block in a lookup.
 *
 * One request per connection, `Connection: close`. A letter is sent with `POST /send?port=` (the packet is the body)
 * and mail is asked for with `POST /mail?since=&wait=` with the recipient codes in the body, never in the address.
 *
 * Every request has one total budget (connect, TLS, headers, body together), enforced by a watchdog that closes the
 * request's own sockets when it runs out; a socket's idle timeout alone cannot bound a hub that drips bytes. [close]
 * closes every socket in flight and refuses later requests, so leaving the Wi-Fi leaves no thread blocked on it.
 */
class HubClient(
    private val host: String,
    private val hubPort: Int,
    private val pin: String,
    private val sockets: SocketFactory?,
    private val connectMillis: Int = 8_000,
    private val sendMillis: Int = 10_000,
    private val streams: HubStreams? = null,
) : HubWire, AutoCloseable {
    /** A hub reached over an ordered byte stream (the Bluetooth GATT link) instead of a socket. The host is only a label for the Host header; the stream is the route. */
    constructor(hubPort: Int, pin: String, streams: HubStreams) : this("127.0.0.1", hubPort, pin, null, 8_000, 10_000, streams)

    enum class Sent { STORED, ALREADY, REFUSED, BUSY }

    /** One letter from the hub's mailbox: its [seq] (the cursor to ask from next), the port it came in on, its bytes. */
    class Mail(val seq: Long, val port: Int, packet: ByteArray) { val packet: ByteArray = packet.copyOf() }

    private class Reply(val status: Int, val body: ByteArray, val lines: List<Pair<String, String>> = emptyList()) { val text: String get() = String(body, Charsets.UTF_8) }

    /** The sockets of one request, so the watchdog or [close] can shut them from another thread. */
    private class Attempt {
        @Volatile var plain: Socket? = null
        @Volatile var secure: Socket? = null
        @Volatile var stream: HubStream? = null
        @Volatile var engine: HubEngineTls? = null
        @Volatile var timedOut = false

        /**
         * From any thread (the watchdog, close): ends the sockets or the stream, which wakes whatever is blocked on them.
         * The plain socket goes first: closing TLS first can wait on the JDK's handshake lock while a handshake is blocked.
         */
        fun abort() {
            runCatching { plain?.close() }
            runCatching { secure?.close() }
            runCatching { stream?.abort() }
        }

        /** From the request's own thread, when it is over: says goodbye in TLS first, then aborts. */
        fun finish() {
            runCatching { engine?.shutdown() }
            abort()
        }
    }

    /** Trusts one key: the leaf certificate whose public key hashes to the pin from the hub QR. No authority, name or dates. */
    private class PinnedTrust(private val pin: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("this phone takes no clients")

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            if (!MessageDigest.isEqual(HubCard.pinOf(leaf.publicKey.encoded).toByteArray(), pin.toByteArray())) throw CertificateException("not the hub")
        }

        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    init {
        require(isIpv4(host)) { "the hub is reached by address, never by name" }
        require((sockets == null) != (streams == null)) { "a hub is reached by sockets or by streams, never both, never neither" }
    }

    private val tls: SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(pin)), SecureRandom()) }
    private val live: MutableSet<Attempt> = ConcurrentHashMap.newKeySet()
    @Volatile private var closed = false

    /** Closes every request in flight and refuses all later ones. Safe to call from any thread, any number of times. */
    override fun close() {
        closed = true
        live.forEach { it.abort() }
    }

    /**
     * STORED and ALREADY both mean the hub holds the letter. A transport failure, a pin mismatch or an answer that is not
     * exactly one of the hub's is an [IOException]: the outcome is then unknown and the letter must go another way.
     */
    override fun send(port: Int, packet: ByteArray): Sent = post("/send?port=$port", packet)

    override fun sendHeld(port: Int, packet: ByteArray): Sent = post("/send?port=$port&air=0", packet)

    private fun post(path: String, packet: ByteArray): Sent {
        val reply = call("POST", path, packet, sendMillis)
        val text = reply.text.trim()
        return when {
            reply.status == 200 && text == "stored" -> Sent.STORED
            reply.status == 200 && text == "already" -> Sent.ALREADY
            reply.status == 400 -> Sent.REFUSED
            reply.status == 429 -> Sent.BUSY
            else -> throw IOException("the hub answered ${reply.status}")
        }
    }

    /** Mail after [since] for [codes] (16 hex digits each), waiting up to [waitSeconds] (at most 30) for some to arrive. */
    override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<Mail> {
        val wanted = codes.filter { CODE.matches(it) }.distinct().take(MAX_CODES)
        if (wanted.isEmpty() && !hellos) return emptyList()
        val wait = waitSeconds.coerceIn(0, 30)
        val body = wanted.joinToString(LF).toByteArray(Charsets.US_ASCII)
        val query = "since=${since.coerceAtLeast(0)}&wait=$wait" + if (hellos) "&hellos=1" else ""
        val reply = call("POST", "/mail?$query", body, (wait + 15) * 1_000)
        if (reply.status != 200) throw IOException("the hub answered ${reply.status}")
        return reply.text.lineSequence().filter { it.isNotBlank() }.map { letter(it) }.toList()
    }

    override fun latest(): Long {
        val reply = call("GET", "/hello", ByteArray(0), sendMillis)
        if (reply.status != 200 || !reply.text.startsWith("phonemesh-hub 1")) throw IOException("not a Phonemesh hub")
        return reply.text.lineSequence().firstOrNull { it.startsWith("latest ") }?.substring(7)?.trim()?.toLongOrNull()
            ?: throw IOException("the hub gave no newest seq")
    }

    /**
     * The hub's `/updates/v1/` routes over this same pinned Wi-Fi link (hub updates spec 5.4). A `GET` with at most one byte range,
     * answered in memory (at most [MAX_REPLY] bytes, so the downloader's 256 KiB ranges fit); every header line is kept as sent so
     * the downloader can refuse a doubled or odd header. Null on the Bluetooth link: bulk updates are Wi-Fi only.
     */
    override fun updates(): HubTransport? {
        if (sockets == null) return null
        return object : HubTransport {
            override val route = UpdateRoute.PINNED_HUB_WIFI
            override val maxAnswerBytes: Long get() = MAX_REPLY.toLong()

            override fun get(path: String, range: LongRange?): HubResponse {
                if (!path.startsWith("/updates/v1/") || path.any { it.code <= 32 || it.code > 126 || it == '?' || it == '#' }) throw IOException("not an update path")
                val extra = StringBuilder("Accept-Encoding: identity").append(CRLF)
                if (range != null) extra.append("Range: bytes=").append(range.first).append('-').append(range.last).append(CRLF)
                // A hub that answers a range request with the whole (large) file gets its head read and its body left unread:
                // the downloader names that, instead of the link breaking on a "bad length" (U7-15).
                val reply = call("GET", path, ByteArray(0), UPDATE_MILLIS, extra.toString(), headOnlyWhenLong = true)
                val headers = HashMap<String, MutableList<String>>()
                reply.lines.forEach { (k, v) -> headers.getOrPut(k) { ArrayList() }.add(v) }
                return HubResponse(reply.status, headers, ByteArrayInputStream(reply.body))
            }
        }
    }

    private fun letter(line: String): Mail {
        val part = line.trim().split(' ')
        if (part.size != 3) throw IOException("bad mail line")
        val seq = part[0].toLongOrNull()
        val port = part[1].toIntOrNull()
        val hex = part[2]
        if (seq == null || port == null || hex.length % 2 != 0 || hex.length > MAX_HEX || !HEX.matches(hex)) throw IOException("bad mail line")
        return Mail(seq, port, ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }

    private fun call(method: String, path: String, body: ByteArray, totalMillis: Int, extraHeaders: String = "", headOnlyWhenLong: Boolean = false): Reply {
        if (closed) throw IOException("this hub link is closed")
        val attempt = Attempt()
        live.add(attempt)
        val watchdog = WATCHDOG.schedule(Runnable { attempt.timedOut = true; attempt.abort() }, totalMillis.toLong(), TimeUnit.MILLISECONDS)
        val until = System.nanoTime() + totalMillis * 1_000_000L
        try {
            if (closed) throw IOException("this hub link is closed")
            // Over a socket (Wi-Fi) or over a byte stream (Bluetooth): either way standard TLS, pinned, finished before a byte of the request.
            val secure: Secure = if (streams != null) viaStream(streams, attempt, until) else viaSocket(sockets ?: throw IOException("no way to the hub"), attempt, until)
            // The trust manager already checked; this is the second lock on the same door.
            val leaf = secure.leaf
            if (HubCard.pinOf(leaf.publicKey.encoded) != pin) throw IOException("not the hub")
            val head = StringBuilder(method).append(' ').append(path).append(" HTTP/1.1").append(CRLF)
            head.append("Host: ").append(host).append(':').append(hubPort).append(CRLF)
            if (method == "POST") head.append("Content-Type: application/octet-stream").append(CRLF).append("Content-Length: ").append(body.size).append(CRLF)
            head.append(extraHeaders)
            head.append("Connection: close").append(CRLF).append(CRLF)
            val out = secure.output
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            return Exchange(BufferedInputStream(secure.input), until).reply(headOnlyWhenLong)
        } catch (e: IOException) {
            throw if (attempt.timedOut) IOException("the hub took too long") else e
        } catch (e: RuntimeException) {
            throw IOException(if (attempt.timedOut) "the hub took too long" else "request failed", e)
        } finally {
            watchdog.cancel(false)
            live.remove(attempt)
            attempt.finish()
        }
    }

    /** The secure channel of one request, over a socket or a stream: where to read, where to write, and the hub's leaf certificate. */
    private class Secure(val input: InputStream, val output: OutputStream, val leaf: X509Certificate)

    private fun viaSocket(factory: SocketFactory, attempt: Attempt, until: Long): Secure {
        val plain: Socket = factory.createSocket()
        attempt.plain = plain
        if (closed) throw IOException("this hub link is closed")
        plain.connect(InetSocketAddress(host, hubPort), minOf(connectMillis, remaining(until)))
        val secure = tls.socketFactory.createSocket(plain, host, hubPort, true) as SSLSocket
        attempt.secure = secure
        if (closed) throw IOException("this hub link is closed")
        secure.soTimeout = remaining(until)
        secure.useClientMode = true
        secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        secure.startHandshake()
        val leaf = secure.session.peerCertificates.firstOrNull() as? X509Certificate ?: throw IOException("no certificate")
        return Secure(secure.inputStream, secure.outputStream, leaf)
    }

    /** The Bluetooth way: a stream of the GATT link, then the same pinned TLS (JSSE SSLEngine). A TLS failure is thrown as it is; nothing is ever sent in the clear. */
    private fun viaStream(source: HubStreams, attempt: Attempt, until: Long): Secure {
        val stream = source.open(remaining(until))
        attempt.stream = stream
        if (closed) throw IOException("this hub link is closed")
        val engine = HubEngineTls.connect(tls, host, hubPort, stream, until)
        attempt.engine = engine
        return Secure(engine.input, engine.output, engine.peerLeaf)
    }

    private fun remaining(until: Long): Int = ((until - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)

    /** Reads one HTTP/1.1 reply (Content-Length, chunked as the JDK server sends an empty body, or to the end), within a deadline. */
    private class Exchange(private val input: InputStream, private val until: Long) {
        fun reply(headOnlyWhenLong: Boolean = false): Reply {
            val lines = head().split(CRLF)
            val status = lines[0].split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad reply")
            val pairs = lines.drop(1).filter { ':' in it }.map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val headers = pairs.toMap()
            val body = when {
                headers["transfer-encoding"]?.lowercase() == "chunked" -> chunked()
                headOnlyWhenLong && (headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_REPLY -> ByteArray(0)
                headers["content-length"] != null ->
                    exact(headers.getValue("content-length").toIntOrNull()?.takeIf { it in 0..MAX_REPLY } ?: throw IOException("bad length"))
                else -> rest()
            }
            return Reply(status, body, pairs)
        }

        private fun late() {
            if (System.nanoTime() > until) throw IOException("the hub is too slow")
        }

        private fun head(): String {
            val out = ByteArrayOutputStream()
            var last = 0
            while (true) {
                late()
                val b = input.read()
                if (b < 0) throw IOException("the hub closed early")
                out.write(b)
                last = (last shl 8) or b
                if (last == END) break
                if (out.size() > MAX_HEAD) throw IOException("reply head too long")
            }
            return out.toString(Charsets.US_ASCII.name())
        }

        private fun line(): String {
            val sb = StringBuilder()
            while (true) {
                late()
                val b = input.read()
                if (b < 0) throw IOException("the hub closed early")
                if (b == 10) return sb.toString()
                if (b != 13) sb.append(b.toChar())
                if (sb.length > 256) throw IOException("line too long")
            }
        }

        private fun exact(length: Int): ByteArray {
            val out = ByteArray(length)
            var at = 0
            while (at < length) {
                late()
                val n = input.read(out, at, length - at)
                if (n < 0) throw IOException("the hub closed early")
                at += n
            }
            return out
        }

        private fun rest(): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                late()
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                out.write(buffer, 0, n)
                if (out.size() > MAX_REPLY) throw IOException("reply too long")
            }
        }

        private fun chunked(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = line().substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk")
                if (size < 0 || out.size() + size > MAX_REPLY) throw IOException("bad chunk")
                if (size == 0) {
                    while (line().isNotEmpty()) late()
                    return out.toByteArray()
                }
                out.write(exact(size))
                if (line().isNotEmpty()) throw IOException("bad chunk")
            }
        }
    }

    private companion object {
        val CODE = Regex("[0-9a-f]{16}")
        val HEX = Regex("[0-9a-f]*")
        val CRLF = String(charArrayOf(13.toChar(), 10.toChar()))
        val LF = 10.toChar().toString()
        const val END = 0x0D0A0D0A
        const val MAX_CODES = 5000
        const val MAX_HEAD = 8192
        const val MAX_REPLY = 512 * 1024

        /** One update request (a range of at most 256 KiB, or a small catalog): longer than a mail call, still bounded. */
        const val UPDATE_MILLIS = 60_000
        const val MAX_HEX = WireV2.MAX_PACKET * 2

        /** One idle daemon thread at most, ending every request that outlives its budget. */
        val WATCHDOG: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "hub-watchdog").apply { isDaemon = true } }).apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(5, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }

        fun isIpv4(text: String): Boolean {
            val part = text.split('.')
            return part.size == 4 && part.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
        }
    }
}

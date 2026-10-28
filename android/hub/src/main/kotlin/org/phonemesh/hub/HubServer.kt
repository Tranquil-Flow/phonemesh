package org.phonemesh.hub

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The phones' API on the hub's Wi-Fi (accounts plan step 15; review F25): HTTPS with the hub's own key, which phones
 * pin from the hub QR, so nobody else on the Wi-Fi can pose as the hub or read which codes a phone asks for.
 *
 * - `GET /hello`: `phonemesh-hub 1`, the hub's name and the newest mail seq.
 * - `POST /send?port=301`: one packet as the body. 200 `stored` or `already` (both: reached the hub), 400, 429 busy.
 * - `POST /mail?since=N&wait=S&hellos=1&shared=1`: the body lists recipient codes in hex, one per line (a body, not
 *   the address, so code lists are never in a URL or a log). Answers mail after seq N, waiting up to S seconds
 *   (at most 30) for some to arrive: one `seq port hex` line each.
 * - `/send?port=301&air=0`: kept for phones that fetch it here, never aired (decision 64, photo avatars).
 * - `/updates/v1/...`: signed updates the operator imported, read only ([UpdateRoutes]).
 */
class HubServer(
    private val hub: Hub, private val mailbox: Mailbox, private val name: String, private val tls: Tls, port: Int,
    /** The hub's data folder, for the read-only update routes ([UpdateRoutes]); null serves none. */
    updatesDir: File? = null,
    /** How long a download may make no progress before it is closed ([UpdateRoutes]). */
    updateStallMillis: Long = UpdateRoutes.STALL_MILLIS,
    /** How long an update request waits for a free slot; how long one may last in all; who is asking (tests set the last). */
    updateQueueMillis: Long = UpdateRoutes.QUEUE_MILLIS,
    updateRequestMillis: Long = UpdateRoutes.REQUEST_MILLIS,
    updateClientOf: (com.sun.net.httpserver.HttpExchange) -> String = { it.remoteAddress.address.hostAddress },
) {
    private val server = HttpsServer.create(InetSocketAddress(port), 0)
    private val pool = Executors.newFixedThreadPool(32)

    init {
        server.httpsConfigurator = HttpsConfigurator(tls.context)
        server.executor = pool
        server.createContext("/hello") { handle(it) { ex -> reply(ex, 200, "phonemesh-hub 1\nname $name\nlatest ${mailbox.latest()}\n") } }
        server.createContext("/send") { handle(it, ::send) }
        server.createContext("/mail") { handle(it, ::mail) }
        updatesDir?.let { d -> val routes = UpdateRoutes(d, updateStallMillis, updateQueueMillis, requestMillis = updateRequestMillis, clientOf = updateClientOf); server.createContext("/updates/v1/") { handle(it, routes::handle) } }
    }

    val port: Int get() = server.address.port

    fun start() = server.start()

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun send(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return reply(ex, 405, "POST\n")
        val port = query(ex)["port"]?.toIntOrNull() ?: return reply(ex, 400, "port?\n")
        val body = ex.requestBody.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) return reply(ex, 400, "too long\n")
        val air = query(ex)["air"] != "0"
        when (hub.fromPhone(ex.remoteAddress.address.hostAddress, port, body, air)) {
            Hub.Sent.STORED -> reply(ex, 200, "stored\n")
            Hub.Sent.ALREADY -> reply(ex, 200, "already\n")
            Hub.Sent.REFUSED -> reply(ex, 400, "not a Phonemesh packet\n")
            Hub.Sent.BUSY -> reply(ex, 429, "busy\n")
        }
    }

    private fun mail(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return reply(ex, 405, "POST\n")
        val q = query(ex)
        val since = q["since"]?.toLongOrNull() ?: 0
        val wait = (q["wait"]?.toLongOrNull() ?: 0).coerceIn(0, 30)
        val codes = ex.requestBody.readNBytes(MAX_CODES * 17 + 1).toString(Charsets.US_ASCII)
            .lineSequence().map { it.trim().lowercase() }.filter { CODE.matches(it) }.take(MAX_CODES).toSet()
        hub.listened(codes)
        val found = mailbox.await(since, codes, q["hellos"] == "1", q["shared"] == "1", wait * 1000)
        reply(ex, 200, found.joinToString("") { "${it.seq} ${it.port} ${Mailbox.hex(it.bytes)}\n" })
        // Answered in full: what this phone took over Wi-Fi need not go by radio (AE7).
        hub.fetched(found.filter { it.kind == Mailbox.Kind.CODE }.map { it.port to it.bytes })
    }

    private fun handle(ex: HttpExchange, body: (HttpExchange) -> Unit) {
        try {
            body(ex)
        } catch (e: Exception) {
            runCatching { reply(ex, 500, "error\n") }
        } finally {
            ex.close()
        }
    }

    private fun reply(ex: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        ex.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    companion object {
        const val MAX_BODY = 228
        const val MAX_CODES = 5000
        private val CODE = Regex("[0-9a-f]{16}")
    }
}

/**
 * The hub's TLS key: a P-256 key and self-signed certificate made once with the JDK's keytool, kept in [dir]. [pin] is
 * SHA-256 of its public key (base64url), what the hub QR carries and phones check instead of a certificate authority.
 */
class Tls(dir: File) {
    val context: SSLContext
    val pin: String

    init {
        dir.mkdirs()
        val store = File(dir, "hub-tls.p12")
        val passFile = File(dir, "hub-tls.pass")
        if (!store.exists()) {
            val pass = storePassword(ByteArray(24).also { SecureRandom().nextBytes(it) })
            passFile.writeText(pass)
            passFile.setReadable(false, false); passFile.setReadable(true, true)
            val keytool = File(File(System.getProperty("java.home"), "bin"), keytoolName(System.getProperty("os.name"))).path
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "hub", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=phonemesh-hub", "-validity", "36500", "-storetype", "PKCS12",
                "-keystore", store.path, "-storepass", pass).redirectErrorStream(true).start()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: " + process.inputStream.bufferedReader().readText() }
            store.setReadable(false, false); store.setReadable(true, true)
        }
        val pass = passFile.readText().trim().toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, pass) } }
        pin = pinOf(keys.getCertificate("hub").publicKey.encoded)
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, pass) }
        context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }

    companion object {
        fun keytoolName(os: String): String = if (os.startsWith("Windows")) "keytool.exe" else "keytool"

        /** Hex, so it never starts with "-": keytool hands any argument starting with -J to its JVM. */
        fun storePassword(random: ByteArray): String = java.util.HexFormat.of().formatHex(random)

        fun pinOf(publicKey: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey))
    }
}

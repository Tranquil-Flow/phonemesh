package org.phonemesh.hub

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateMetadata
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** The read-only update routes (hub updates spec 5.4) over the hub's pinned HTTPS, filled by a USB import. */
class UpdateRoutesTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: HubServer
    private lateinit var tls: Tls
    private lateinit var dir: File
    private val c: KeyPair = P256.generate()
    private val r: KeyPair = P256.generate()
    private val voice = ByteArray(200_000) { (it * 7 + 1).toByte() }

    private fun pub(k: KeyPair): ByteArray {
        val w = (k.public as ECPublicKey).w
        fun f(v: java.math.BigInteger) = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }.let { ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + f(w.affineX) + f(w.affineY)
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val name get() = hex(sha(voice))

    private fun catalog(): ByteArray {
        val id = "phonemesh-voice-fr-siwis".toByteArray(); val label = "1".toByteArray()
        val entry = byteArrayOf(3, id.size.toByte(), label.size.toByte(), 0) +
            ByteBuffer.allocate(38).putLong(1).putLong(2).putLong(0).putInt(26).putShort(2).putLong(voice.size.toLong()).array() +
            sha(voice) + ByteBuffer.allocate(12).putLong(300_000).putInt(3).array() + sha("s".toByteArray()) + ByteArray(32) + id + label
        val p = byteArrayOf(1) + ByteBuffer.allocate(42).putLong(1).putLong(1).putLong(1).putLong(2).putLong(1).putShort(1).array() + entry + byteArrayOf(0, 0)
        return UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, p, c.private)
    }

    @Before fun start() {
        dir = folder.newFolder("hub")
        val mailbox = Mailbox(File(dir, "mail"), 30)
        tls = Tls(File(dir, "keys"))
        server = HubServer(Hub(mailbox, FakeRadio()), mailbox, "Test hub", tls, 0, updatesDir = dir).also { it.start() }
    }

    @After fun stop() = server.stop()

    private fun importStick(cat: ByteArray = catalog()) {
        val s = folder.newFolder()
        File(s, "catalog.pmup").writeBytes(cat)
        File(s, "objects").mkdirs(); File(s, "objects/$name").writeBytes(voice)
        assertTrue(UpdateImport(dir, pub(r), pub(c)).import(s) is UpdateImport.Result.Imported)
    }

    private class Reply(val code: Int, val body: ByteArray, val headers: Map<String, List<String>>)

    private fun call(path: String, method: String = "GET", range: String? = null): Reply {
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (Tls.pinOf(chain[0].publicKey.encoded) != tls.pin) throw java.security.cert.CertificateException()
                }
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
        }
        val conn = URL("https://127.0.0.1:${server.port}$path").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ctx.socketFactory
        conn.setHostnameVerifier { _, _ -> true }
        conn.requestMethod = method
        range?.let { conn.setRequestProperty("Range", it) }
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        // Header names are case-insensitive (the JDK server writes "Etag"): keep them lower case.
        return Reply(code, body, conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() })
    }

    @Test fun nothingImportedMeansNotFound() {
        assertEquals(404, call("/updates/v1/catalog").code)
        assertEquals(404, call("/updates/v1/recovery").code)
        assertEquals(404, call("/updates/v1/objects/$name").code)
    }

    @Test fun theImportedCatalogAndObjectsAreServedExactly() {
        val cat = catalog()
        importStick(cat)
        val got = call("/updates/v1/catalog")
        assertEquals(200, got.code)
        assertArrayEquals(cat, got.body)
        val obj = call("/updates/v1/objects/$name")
        assertEquals(200, obj.code)
        assertArrayEquals(voice, obj.body)
        assertEquals("\"$name\"", obj.headers["etag"]!!.single())
        assertEquals("bytes", obj.headers["accept-ranges"]!!.single())
        val head = call("/updates/v1/objects/$name", "HEAD")
        assertEquals(200, head.code); assertEquals(voice.size.toString(), head.headers["content-length"]!!.single())
        assertEquals("before any new trust epoch there is no policy", 404, call("/updates/v1/recovery").code)
    }

    @Test fun oneRangeResumesADownloadAndBadRangesAreRefused() {
        importStick()
        val part = call("/updates/v1/objects/$name", range = "bytes=100000-")
        assertEquals(206, part.code)
        assertArrayEquals(voice.copyOfRange(100_000, voice.size), part.body)
        assertEquals("bytes 100000-199999/200000", part.headers["content-range"]!!.single())
        val mid = call("/updates/v1/objects/$name", range = "bytes=10-19")
        assertArrayEquals(voice.copyOfRange(10, 20), mid.body)
        for (bad in listOf("bytes=200000-", "bytes=5-1", "bytes=0-1,4-5", "items=0-1", "bytes=-10")) {
            val reply = call("/updates/v1/objects/$name", range = bad)
            assertEquals(bad, 416, reply.code)
            assertEquals("bytes */200000", reply.headers["content-range"]!!.single())
        }
    }

    @Test fun onlyDigestNamesAndReadingAreAllowed() {
        importStick()
        assertEquals(404, call("/updates/v1/objects/../trust-state.bin").code)
        assertEquals(404, call("/updates/v1/objects/${name.uppercase()}").code)
        assertEquals(404, call("/updates/v1/objects/trust-state.bin").code)
        assertEquals(404, call("/updates/v1/other").code)
        assertEquals(405, call("/updates/v1/catalog", "POST").code)
        assertEquals(405, call("/updates/v1/objects/$name", "DELETE").code)
        // A half-copied file is never served.
        File(dir, "updates/partial/${"a".repeat(64)}.part").writeBytes(ByteArray(10))
        assertEquals(404, call("/updates/v1/objects/${"a".repeat(64)}").code)
    }

    @Test fun mailStillWorksBesideTheUpdateRoutes() {
        importStick()
        val hello = call("/hello")
        assertEquals(200, hello.code)
        assertTrue(String(hello.body).startsWith("phonemesh-hub 1"))
    }
}

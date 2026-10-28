package org.phonemesh.hub

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateMetadata
import java.io.File
import java.net.URL
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** U7-8: several phones copying at once, slots given back at the last byte, and readers too slow or too long cut off. */
class UpdateRoutesSlotsTest {
    @get:Rule val folder = TemporaryFolder()
    private val c: KeyPair = P256.generate()
    private val r: KeyPair = P256.generate()
    private var server: HubServer? = null
    private lateinit var tls: Tls
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @After fun stop() { server?.stop() }

    /** Imports one object of [data] and starts a hub with the given limits; the "phone" is named by the X-Phone request header. */
    private fun start(data: ByteArray, stall: Long = 60_000, queue: Long = 2_000, request: Long = 600_000): String {
        val d = folder.newFolder()
        val name = hex(Bytes.sha256(data))
        val entry = UpdateMetadata.Entry(UpdateMetadata.VOICE, "voice-fr-siwis", "1", "", 1, 1, 0, 26, 2, data.size.toLong(), Bytes.sha256(data), 20_000_000, 3, Bytes.sha256("s".toByteArray()), ByteArray(32))
        val s = folder.newFolder()
        File(s, "catalog.pmup").writeBytes(UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, CatalogSigner.encode(1, 1, 1793145600, 1808697600, 1, listOf(entry), emptyList()), c.private))
        File(s, "objects").mkdirs(); File(s, "objects/$name").writeBytes(data)
        assertTrue(UpdateImport(d, TestKeys.pub(r), TestKeys.pub(c)).import(s) is UpdateImport.Result.Imported)
        val mailbox = Mailbox(File(d, "mail"), 30)
        tls = Tls(File(d, "keys"))
        server = HubServer(Hub(mailbox, FakeRadio()), mailbox, "Slots hub", tls, 0, updatesDir = d, updateStallMillis = stall, updateQueueMillis = queue,
            updateRequestMillis = request, updateClientOf = { it.requestHeaders.getFirst("X-Phone") ?: "?" }).also { it.start() }
        return name
    }

    private fun open(name: String, phone: String, range: String? = null): HttpsURLConnection {
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (Tls.pinOf(chain[0].publicKey.encoded) != tls.pin) throw java.security.cert.CertificateException()
                }
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
        }
        return (URL("https://127.0.0.1:${server!!.port}/updates/v1/objects/$name").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ctx.socketFactory
            setHostnameVerifier { _, _ -> true }
            setRequestProperty("X-Phone", phone)
            range?.let { setRequestProperty("Range", it) }
            readTimeout = 20_000
        }
    }

    /** One phone copying [data] in [chunk]-byte ranges, a new connection per range, asking once per range (no retry). */
    private fun copy(name: String, phone: String, size: Int, chunk: Int, statuses: MutableList<Int>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var at = 0
        while (at < size) {
            val last = minOf(at + chunk, size) - 1
            val conn = open(name, phone, "bytes=$at-$last")
            val code = conn.responseCode
            statuses += code
            if (code != 206) { conn.disconnect(); return out.toByteArray() }
            out.write(conn.inputStream.use { it.readBytes() })
            at = last + 1
        }
        return out.toByteArray()
    }

    @Test fun threePhonesCopyingAtOnceAllFinishWithoutAnyBusyAnswer() {
        val data = ByteArray(3 * 1024 * 1024) { (it * 31 + 7).toByte() }
        val name = start(data)
        val results = Array(3) { ByteArray(0) }; val statuses = List(3) { java.util.Collections.synchronizedList(ArrayList<Int>()) }
        val go = CountDownLatch(1)
        val threads = (0 until 3).map { i -> Thread { go.await(); results[i] = copy(name, "phone-$i", data.size, 256 * 1024, statuses[i]) }.also { it.start() } }
        go.countDown(); threads.forEach { it.join(60_000) }
        for (i in 0 until 3) {
            assertEquals("phone $i saw ${statuses[i]}", listOf(206), statuses[i].distinct())
            assertArrayEquals("phone $i", data, results[i])
        }
    }

    @Test fun aPhonesNextRangeNeverMeetsItsPreviousOne() {
        val data = ByteArray(2 * 1024 * 1024) { it.toByte() }
        val name = start(data, queue = 0)
        val statuses = ArrayList<Int>()
        val got = copy(name, "solo", data.size, 16 * 1024, statuses)
        assertEquals("every one of ${statuses.size} ranges was served, none refused as busy: ${statuses.distinct()}", listOf(206), statuses.distinct())
        assertArrayEquals(data, got)
    }

    @Test fun aTricklingReaderLosesItsSlotAndThePhoneGetsBackIn() {
        val data = ByteArray(24 * 1024 * 1024) { (it * 13).toByte() }
        val name = start(data, stall = 1_000, queue = 100)
        val slow = open(name, "phone-slow")
        val body = slow.inputStream
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val trickle = Thread { try { while (!stop.get()) { body.read(); Thread.sleep(150) } } catch (_: Exception) { } }.also { it.isDaemon = true; it.start() }
        try {
            Thread.sleep(300)
            val busy = open(name, "phone-slow", "bytes=0-99").responseCode
            assertEquals("while the trickler holds the slot the same phone is told to wait", 429, busy)
            val deadline = System.currentTimeMillis() + 15_000
            var code = 429
            while (code == 429 && System.currentTimeMillis() < deadline) { Thread.sleep(300); code = open(name, "phone-slow", "bytes=0-99").responseCode }
            assertEquals("the slot came back after the trickler was cut off", 206, code)
        } finally { stop.set(true); runCatching { slow.disconnect() } }
    }

    @Test fun aRequestThatLastsTooLongIsCutEvenIfItKeepsMoving() {
        val data = ByteArray(24 * 1024 * 1024) { (it * 5).toByte() }
        val name = start(data, request = 1_200, queue = 100)
        val conn = open(name, "phone-long")
        var total = 0L
        val began = System.currentTimeMillis()
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; total += n; Thread.sleep(60) }
            }
        } catch (_: java.io.IOException) { }
        assertTrue("read $total of ${data.size} bytes: it was cut", total < data.size)
        assertTrue("and in about the request time, not the whole transfer: ${System.currentTimeMillis() - began} ms", System.currentTimeMillis() - began < 15_000)
        Thread.sleep(500)
        assertEquals("the slot is free again", 206, open(name, "phone-long", "bytes=0-99").responseCode)
    }
}

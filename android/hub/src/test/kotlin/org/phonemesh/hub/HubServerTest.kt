package org.phonemesh.hub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.session.Start
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.wire.Body
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class HubServerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: HubServer
    private lateinit var tls: Tls
    private lateinit var hub: Hub
    private val radio = FakeRadio()

    @Before fun start() {
        val mailbox = Mailbox(folder.newFolder("mail"), 30)
        hub = Hub(mailbox, radio)
        tls = Tls(folder.newFolder("keys"))
        server = HubServer(hub, mailbox, "Test hub", tls, 0).also { it.start() }
    }

    @After fun stop() = server.stop()

    /** What a phone does (step 16): trust only the certificate whose key matches the pin from the hub QR. */
    private fun pinned(pin: String): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (Tls.pinOf(chain[0].publicKey.encoded) != pin) throw java.security.cert.CertificateException("not the hub")
            }
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }), null)
    }

    private fun call(path: String, body: ByteArray? = null, pin: String = tls.pin): Pair<Int, String> {
        val c = URL("https://127.0.0.1:${server.port}$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = pinned(pin).socketFactory
        c.setHostnameVerifier { _, _ -> true } // the pin, not a name, says who the hub is
        c.connectTimeout = 5000; c.readTimeout = 40_000
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body) } }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return status to text
    }

    @Test fun helloNamesTheHub() {
        val (status, text) = call("/hello")
        assertEquals(200, status)
        assertTrue(text.startsWith("phonemesh-hub 1\nname Test hub\n"))
    }

    @Test fun anotherKeyIsRefused() {
        val wrong = Tls(folder.newFolder("other")).pin
        assertTrue(wrong != tls.pin)
        val failed = runCatching { call("/hello", pin = wrong) }.exceptionOrNull()
        assertTrue("$failed", failed is SSLHandshakeException)
    }

    @Test fun theKeyStaysAcrossRestarts() {
        assertEquals(tls.pin, Tls(folder.root.resolve("keys")).pin)
    }

    @Test fun sendAnswersWhatHappened() {
        assertEquals(200 to "stored\n", call("/send?port=301", Packets.message(1)))
        assertEquals(200 to "already\n", call("/send?port=301", Packets.message(1)))
        assertEquals(400, call("/send?port=301", ByteArray(4)).first)
        assertEquals(400, call("/send?port=301", ByteArray(229)).first)
        assertEquals(405, call("/send?port=301").first)
    }

    /** Two phones with a real session: one hands a sealed message to the hub, the other's long poll gets it and opens it. */
    @Test fun aRealMessageGoesThroughTheHub() {
        val a = SoftKeys.generate(); val b = SoftKeys.generate()
        val ka = KeyPairBytes.generate(); val kb = KeyPairBytes.generate()
        val qa = QrCode(ContactCard.create(a, "Awa", 1), ka.public); val qb = QrCode(ContactCard.create(b, "Ben", 2), kb.public)
        val sa = Start.qr(a, qa, ka, qb)!!; val sb = Start.qr(b, qb, kb, qa)!!
        val sealed = Ratchet.seal(sa, Body.encode(Body.Text("fr", "rendez-vous au puits")))
        val codes = Ratchet.codes(sb).joinToString("\n").toByteArray()
        var polled: Pair<Int, String>? = null
        val poll = thread { polled = call("/mail?since=0&wait=20", codes) }
        Thread.sleep(500)
        assertTrue("the poll waits", poll.isAlive)
        assertEquals(200 to "stored\n", call("/send?port=301", sealed.packet))
        poll.join(10_000)
        val line = polled!!.second.trim().split(' ')
        assertEquals("301", line[1])
        val packet = Mailbox.hex(sealed.packet)
        assertEquals(packet, line[2])
        val opened = Ratchet.open(sb, line[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), b) as Ratchet.Opened.Delivered
        assertEquals("rendez-vous au puits", (Body.decode(opened.body) as Body.Text).text)
        assertEquals("nothing for codes nobody asked about", "", call("/mail?since=0&wait=0", "0102030405060708".toByteArray()).second)
    }
}

package org.phonemesh.hub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Body
import java.io.IOException
import java.net.ServerSocket
import javax.net.SocketFactory
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

/**
 * Accounts plan step 16: the phone's [HubClient] against the real [HubServer] over real TLS and sockets (no fake hub
 * for the main cases). On a phone the plain sockets come from the joined Wi-Fi's Network; here they are the machine's own.
 * Unexecuted: written without a build.
 */
class HubClientTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: HubServer
    private lateinit var tls: Tls
    private lateinit var mailbox: Mailbox
    private val extra = ArrayList<ServerSocket>()

    @Before fun start() {
        mailbox = Mailbox(folder.newFolder("mail"), 30)
        val hub = Hub(mailbox, FakeRadio())
        tls = Tls(folder.newFolder("keys"))
        server = HubServer(hub, mailbox, "Test hub", tls, 0).also { it.start() }
    }

    @After fun stop() {
        server.stop()
        extra.forEach { runCatching { it.close() } }
    }

    private fun client(pin: String = tls.pin) = HubClient("127.0.0.1", server.port, pin, SocketFactory.getDefault())

    /** A "hub" that accepts and never speaks TLS. With [drip] it sends a TLS record header, then one byte every 50 ms. */
    private fun fakeServer(drip: Boolean): ServerSocket {
        val listener = ServerSocket(0).also { extra += it }
        thread(isDaemon = true) {
            try {
                while (true) {
                    val socket = listener.accept()
                    thread(isDaemon = true) {
                        try {
                            if (drip) {
                                val out = socket.getOutputStream()
                                out.write(byteArrayOf(22, 3, 3, 3, 0xE8.toByte()))
                                out.flush()
                                while (true) { Thread.sleep(50); out.write(0); out.flush() }
                            } else Thread.sleep(60_000)
                        } catch (_: Exception) {
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return listener
    }

    /** Decision 64: a held packet is kept for phones that fetch it here and is never aired, not even on a retry. */
    @Test fun aHeldPacketIsKeptForFetchingButNeverAired() {
        val radio = FakeRadio()
        val hub = Hub(mailbox, radio)
        val own = HubServer(hub, mailbox, "Held hub", tls, 0).also { it.start() }
        try {
            val c = HubClient("127.0.0.1", own.port, tls.pin, SocketFactory.getDefault())
            val packet = Packets.message(7)
            assertEquals(HubClient.Sent.STORED, c.sendHeld(301, packet))
            assertEquals("a retry is the same kept packet", HubClient.Sent.ALREADY, c.sendHeld(301, packet))
            repeat(30) { hub.tick() }
            assertTrue("nothing was aired", radio.sent.isEmpty())
            val code = org.phonemesh.core.session.SessionState.hexOf(packet.copyOfRange(1, 9))
            assertTrue("a phone fetching its mail gets it", c.mail(0, listOf(code), 0).any { it.packet.contentEquals(packet) })
            assertEquals("only private mail can be held", HubClient.Sent.REFUSED, c.sendHeld(303, Packets.message(8)))
            // The ordinary path still airs.
            assertEquals(HubClient.Sent.STORED, c.send(301, Packets.message(9)))
            repeat(30) { hub.tick() }
            assertEquals(1, radio.sent.size)
        } finally { own.stop() }
    }

    @Test fun theClientAndTheHubComputeThePinTheSameWay() {
        val key = ByteArray(91) { (it * 7).toByte() }
        assertEquals(Tls.pinOf(key), HubCard.pinOf(key))
    }

    @Test fun theHubCardTheHubPrintsParsesAndPinsTheHub() {
        val printed = HubCard("Village", "password1", server.port, tls.pin).encode()
        assertEquals(tls.pin, HubCard.parse(printed)?.pin)
    }

    @Test fun theHubIsReachedByAddressNeverByName() {
        val failed = runCatching { HubClient("hub.local", 8443, tls.pin, SocketFactory.getDefault()) }.exceptionOrNull()
        assertTrue("$failed", failed is IllegalArgumentException)
    }

    @Test fun sendSaysStoredThenAlreadyAndRefusesWhatIsNotMail() {
        assertEquals(HubClient.Sent.STORED, client().send(301, Packets.message(1)))
        assertEquals(HubClient.Sent.ALREADY, client().send(301, Packets.message(1)))
        assertEquals(HubClient.Sent.REFUSED, client().send(301, ByteArray(4)))
        assertEquals(HubClient.Sent.REFUSED, client().send(301, ByteArray(229)))
        assertEquals(1, mailbox.size())
    }

    @Test fun latestIsTheHubsNewestSeqSoAWipedHubCanBeNoticed() {
        assertEquals(0L, client().latest())
        client().send(301, Packets.message(1))
        assertEquals(1L, client().latest())
    }

    @Test fun anotherKeyIsRefusedAndNothingIsSent() {
        val wrong = Tls(folder.newFolder("other")).pin
        assertTrue(wrong != tls.pin)
        val failed = runCatching { client(pin = wrong).send(301, Packets.message(2)) }.exceptionOrNull()
        assertTrue("$failed", failed is SSLHandshakeException)
        assertEquals(0, mailbox.size())
    }

    @Test fun aHubThatIsNotThereIsAnIOException() {
        val closed = ServerSocket(0).use { it.localPort }
        val failed = runCatching {
            HubClient("127.0.0.1", closed, tls.pin, SocketFactory.getDefault(), connectMillis = 2_000).send(301, Packets.message(3))
        }.exceptionOrNull()
        assertTrue("$failed", failed is IOException)
    }

    @Test fun nothingForTheseCodesIsAnEmptyListNotAnError() {
        assertTrue(client().mail(0, listOf("0102030405060708"), 0).isEmpty())
        assertTrue("codes that are not codes are not even sent", client().mail(0, listOf("zz", ""), 0).isEmpty())
    }

    @Test fun theHellosFlagGoesToTheRealServerWithOrWithoutCodes() {
        assertTrue(client().mail(0, emptyList(), 0, hellos = true).isEmpty())
        assertTrue(client().mail(0, listOf("0102030405060708"), 0, hellos = true).isEmpty())
    }

    @Test fun aSilentHubIsGivenUpOnAtTheTotalDeadline() {
        val silent = fakeServer(drip = false)
        val slow = HubClient("127.0.0.1", silent.localPort, tls.pin, SocketFactory.getDefault(), sendMillis = 700)
        val started = System.nanoTime()
        val failed = runCatching { slow.send(301, Packets.message(4)) }.exceptionOrNull()
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue("$failed", failed is IOException)
        assertTrue("took $took ms", took in 400..3_000)
    }

    /** Bytes that keep coming reset a socket's idle timeout forever; only the total deadline ends this. */
    @Test fun aHubThatDripsBytesIsStillGivenUpOnAtTheTotalDeadline() {
        val dripping = fakeServer(drip = true)
        val slow = HubClient("127.0.0.1", dripping.localPort, tls.pin, SocketFactory.getDefault(), sendMillis = 700)
        val started = System.nanoTime()
        val failed = runCatching { slow.send(301, Packets.message(4)) }.exceptionOrNull()
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue("$failed", failed is IOException)
        assertTrue("took $took ms", took in 400..3_000)
    }

    @Test fun closingTheClientAbortsARequestInFlightAndRefusesLaterOnes() {
        val dripping = fakeServer(drip = true)
        val doomed = HubClient("127.0.0.1", dripping.localPort, tls.pin, SocketFactory.getDefault(), sendMillis = 30_000)
        var failure: Throwable? = null
        val caller = thread { failure = runCatching { doomed.send(301, Packets.message(5)) }.exceptionOrNull() }
        Thread.sleep(400)
        assertTrue("the request is waiting", caller.isAlive)
        doomed.close()
        caller.join(3_000)
        assertTrue("the open socket was closed, not just forgotten", !caller.isAlive)
        assertTrue("$failure", failure is IOException)
        assertTrue(runCatching { doomed.send(301, Packets.message(5)) }.exceptionOrNull() is IOException)
    }

    /**
     * close() shuts the plain socket itself first. Closing the TLS socket first can wait on the JDK's handshake lock while
     * a handshake is blocked reading, so the request only ended once that wait gave up (seen as a slow close under load).
     */
    @Test fun closingShutsThePlainSocketBeforeTheTlsLayer() {
        val dripping = fakeServer(drip = true)
        val firstCloser = java.util.concurrent.atomic.AtomicReference<String?>()
        val factory = object : SocketFactory() {
            override fun createSocket(): java.net.Socket = object : java.net.Socket() {
                override fun close() {
                    val viaTls = Thread.currentThread().stackTrace.any { it.className.contains("SSLSocket") }
                    firstCloser.compareAndSet(null, if (viaTls) "tls" else "plain")
                    super.close()
                }
            }
            override fun createSocket(host: String, port: Int): java.net.Socket = throw UnsupportedOperationException()
            override fun createSocket(host: String, port: Int, local: java.net.InetAddress, localPort: Int): java.net.Socket = throw UnsupportedOperationException()
            override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket = throw UnsupportedOperationException()
            override fun createSocket(host: java.net.InetAddress, port: Int, local: java.net.InetAddress, localPort: Int): java.net.Socket = throw UnsupportedOperationException()
        }
        val doomed = HubClient("127.0.0.1", dripping.localPort, tls.pin, factory, sendMillis = 30_000)
        val caller = thread { runCatching { doomed.send(301, Packets.message(5)) } }
        Thread.sleep(400)
        assertTrue("the request is waiting in the handshake", caller.isAlive)
        doomed.close()
        caller.join(10_000)
        assertTrue(!caller.isAlive)
        assertEquals("plain", firstCloser.get())
    }

    /** Two phones with a real session: one hands a sealed message to the hub, the other's long poll gets it and opens it. */
    @Test fun aRealMessageGoesThroughTheClientAndTheCursorMovesOn() {
        val a = SoftKeys.generate(); val b = SoftKeys.generate()
        val ka = KeyPairBytes.generate(); val kb = KeyPairBytes.generate()
        val qa = QrCode(ContactCard.create(a, org.phonemesh.core.account.Profile("Awa", 1)), ka.public); val qb = QrCode(ContactCard.create(b, org.phonemesh.core.account.Profile("Ben", 2)), kb.public)
        val sa = Start.qr(a, qa, ka, qb)!!; val sb = Start.qr(b, qb, kb, qa)!!
        val sealed = Ratchet.seal(sa, Body.encode(Body.Text("fr", "rendez-vous au puits")))
        val codes = Ratchet.codes(sb)
        var polled: List<HubClient.Mail>? = null
        var failure: Throwable? = null
        val poll = thread { try { polled = client().mail(0, codes, 20) } catch (e: Throwable) { failure = e } }
        Thread.sleep(500)
        assertTrue("the poll waits", poll.isAlive)
        assertEquals(HubClient.Sent.STORED, client().send(301, sealed.packet))
        poll.join(10_000)
        assertNull(failure)
        val mail = checkNotNull(polled).single()
        assertEquals(301, mail.port)
        assertTrue(sealed.packet.contentEquals(mail.packet))
        val opened = Ratchet.open(sb, mail.packet, b) as Ratchet.Opened.Delivered
        assertEquals("rendez-vous au puits", (Body.decode(opened.body) as Body.Text).text)
        assertTrue("nothing after the cursor", client().mail(mail.seq, codes, 0).isEmpty())
        assertEquals(HubClient.Sent.ALREADY, client().send(301, sealed.packet))
    }
}

package org.phonemesh.hub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.hub.GattFrame
import org.phonemesh.core.hub.GattPort
import org.phonemesh.core.hub.GattStreams
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubGatt
import org.phonemesh.core.hub.HubSession
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Body
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

/**
 * NEW-API end-to-end tests of the Bluetooth hub path with everything real except the radio: the phone side is the real
 * HubClient over the real GattStreams and standard TLS (HubEngineTls); the hub side is the real HubServer behind
 * FakeGattBridge, the JVM model of the Swift bridge; between them a simulated GATT link of a chosen frame size that keeps
 * a copy of every frame, so the tests can look at what a radio sniffer would see. NOT RUN when staged.
 */
class BleHubEndToEndTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var mailbox: Mailbox
    private lateinit var tls: Tls
    private lateinit var server: HubServer
    private val links = ArrayList<Link>()
    private val servers = ArrayList<HubServer>()

    /** One simulated GATT link from a phone to a bridge that forwards to [upstreamPort]. [rejectWrite] numbers the write (1-based) the platform refuses. */
    private inner class Link(upstreamPort: Int, frame: Int = 509, private val rejectWrite: Int = -1) {
        val uplink = CopyOnWriteArrayList<ByteArray>()
        val downlink = CopyOnWriteArrayList<ByteArray>()
        private val hubSide = Executors.newSingleThreadExecutor { Thread(it, "sim-hub").apply { isDaemon = true } }
        private val phoneSide = Executors.newSingleThreadExecutor { Thread(it, "sim-phone").apply { isDaemon = true } }
        private val writes = AtomicInteger()
        private val bridge = FakeGattBridge(upstreamPort, { frame }) { f ->
            downlink += f
            phoneSide.execute { streams.onFrame(f) }
        }
        private val port = object : GattPort {
            override fun maxFrame() = frame

            override fun write(frame: ByteArray): Boolean {
                uplink += frame.copyOf()
                if (writes.incrementAndGet() == rejectWrite) return false
                hubSide.execute { bridge.onFrame(frame); streams.written(true) }
                return true
            }
        }
        val streams: GattStreams = GattStreams(port)

        fun client(pin: String = tls.pin, sendMillis: Int = 10_000) = HubClient("127.0.0.1", 8443, pin, null, 8_000, sendMillis, streams)

        init { links += this }

        fun close() {
            streams.linkClosed("test over")
            bridge.close()
            hubSide.shutdownNow(); phoneSide.shutdownNow()
        }

        /** Everything the radio carried toward the hub, as one byte string. */
        fun sniffed(): String = uplink.joinToString("") { String(it, Charsets.ISO_8859_1) }
    }

    @Before fun start() {
        mailbox = Mailbox(folder.newFolder("mail"), 30)
        tls = Tls(folder.newFolder("keys"))
        server = HubServer(Hub(mailbox, FakeRadio()), mailbox, "Test hub", tls, 0).also { it.start(); servers += it }
    }

    @After fun stop() {
        links.forEach { it.close() }
        servers.forEach { it.stop() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun otherHub(): Pair<HubServer, Mailbox> {
        val m = Mailbox(folder.newFolder(), 30)
        val s = HubServer(Hub(m, FakeRadio()), m, "Other hub", Tls(folder.newFolder()), 0).also { it.start(); servers += it }
        return s to m
    }

    // ---- the mailbox itself, byte for byte, through the actual HubWire adapter

    @Test fun aRealMessageGoesThroughTheBluetoothHub() {
        val a = SoftKeys.generate(); val b = SoftKeys.generate()
        val ka = KeyPairBytes.generate(); val kb = KeyPairBytes.generate()
        val qa = QrCode(ContactCard.create(a, org.phonemesh.core.account.Profile("Awa", 1)), ka.public); val qb = QrCode(ContactCard.create(b, org.phonemesh.core.account.Profile("Ben", 2)), kb.public)
        val sa = Start.qr(a, qa, ka, qb)!!; val sb = Start.qr(b, qb, kb, qa)!!
        val sealed = Ratchet.seal(sa, Body.encode(Body.Text("fr", "rendez-vous au puits")))
        val senderPhone = Link(server.port).client()
        val receiverPhone = Link(server.port).client()
        assertEquals(HubClient.Sent.STORED, senderPhone.send(301, sealed.packet))
        assertEquals(HubClient.Sent.ALREADY, senderPhone.send(301, sealed.packet))
        val mail = receiverPhone.mail(0, Ratchet.codes(sb).toList(), 0)
        assertEquals(1, mail.size)
        assertEquals(301, mail[0].port)
        assertEquals(hex(sealed.packet), hex(mail[0].packet))
        val opened = Ratchet.open(sb, mail[0].packet, b) as Ratchet.Opened.Delivered
        assertEquals("rendez-vous au puits", (Body.decode(opened.body) as Body.Text).text)
        assertTrue(receiverPhone.latest() >= mail[0].seq)
        assertTrue("nothing for codes nobody asked about", receiverPhone.mail(0, listOf("0102030405060708"), 0).isEmpty())
    }

    @Test fun theShortestMtuStillCarriesATlsSession() {
        val link = Link(server.port, frame = 20) // ATT MTU 23
        val phone = link.client()
        assertEquals(HubClient.Sent.STORED, phone.send(301, Packets.message(1)))
        assertEquals(1, phone.mail(0, listOf(hex(Packets.code(1))), 0).size)
        assertTrue(link.uplink.all { it.size <= 20 } && link.downlink.all { it.size <= 20 })
        assertTrue("handshake and request needed many small frames", link.uplink.size > 20)
    }

    @Test fun aLongReplyFlowsUnderCreditBackpressure() {
        val link = Link(server.port, frame = 185)
        val phone = link.client()
        val codes = (1..150).map { hex(Packets.code(it)) }
        for (n in 1..150) mailbox.put(301, Packets.message(n), System.currentTimeMillis())
        val mail = phone.mail(0, codes, 0)
        assertEquals(150, mail.size)
        assertEquals((1..150).map { hex(Packets.message(it)) }.toSet(), mail.map { hex(it.packet) }.toSet())
        // More than one window of reply cannot arrive without the phone granting credit back.
        assertTrue(link.uplink.mapNotNull { GattFrame.decode(it) }.any { it.type == HubGatt.CREDIT })
    }

    @Test fun aSendAndALongPollShareOneLinkAtTheSameTime() {
        val link = Link(server.port)
        val phone = link.client()
        val got = AtomicReference<List<HubClient.Mail>>()
        val poll = thread { got.set(phone.mail(0, listOf(hex(Packets.code(5))), 20)) }
        Thread.sleep(800)
        assertTrue("the poll waits", poll.isAlive)
        assertEquals(HubClient.Sent.STORED, phone.send(301, Packets.message(5))) // second stream, same link
        poll.join(10_000)
        assertEquals(1, got.get().size)
    }

    // ---- authentication: the pin, and nothing in the clear

    @Test fun theWrongHubKeyIsRefusedAndItGetsNoApplicationBytes() {
        val (otherServer, otherMailbox) = otherHub()
        val link = Link(otherServer.port)
        val secret = hex(Packets.code(9))
        val failure = runCatching { link.client(pin = tls.pin).mail(0, listOf(secret), 0) }.exceptionOrNull()
        assertTrue("$failure", failure is SSLException)
        assertEquals("the wrong hub stored and answered nothing", 0L, otherMailbox.latest().toLong())
        val seen = link.sniffed()
        for (marker in listOf("POST", "/mail", "/send", "HTTP/1.1", secret, "Content-Length")) assertFalse("$marker was sent in the clear", marker in seen)
        assertTrue("the failure leaves the link usable for another request", !link.streams.isDead)
    }

    @Test fun aDeviceThatIsNotTlsGetsOnlyHandshakeBytesAndNoFallback() {
        val received = java.io.ByteArrayOutputStream()
        val plain = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val accepted = thread(isDaemon = true) {
            try {
                plain.accept().use { c ->
                    c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nstored".toByteArray()) // what an impostor would love to say
                    c.soTimeout = 1_500
                    val buffer = ByteArray(4096)
                    while (true) { val n = c.getInputStream().read(buffer); if (n < 0) break; synchronized(received) { received.write(buffer, 0, n) } }
                }
            } catch (_: IOException) {
            }
        }
        val link = Link(plain.localPort)
        val failure = runCatching { link.client().send(301, Packets.message(3)) }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
        accepted.join(4_000)
        plain.close()
        val first = synchronized(received) { received.toByteArray() }
        assertTrue("only a TLS handshake record may have been sent", first.isEmpty() || first[0].toInt() == 0x16)
        assertFalse(String(first, Charsets.ISO_8859_1).contains("POST"))
        assertFalse(link.sniffed().contains("POST"))
    }

    // ---- failures, deadlines, reconnects

    @Test fun aRefusedPlatformWriteFailsTheRequestAndNeverFallsBack() {
        val link = Link(server.port, rejectWrite = 3)
        val failure = runCatching { link.client().send(301, Packets.message(1)) }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
        assertTrue(link.streams.isDead)
        assertTrue(runCatching { link.client().latest() }.exceptionOrNull() is IOException)
        assertFalse(link.sniffed().contains("POST"))
        assertEquals("nothing reached the hub", 0L, mailbox.latest().toLong())
    }

    @Test fun aHubThatAcceptsButNeverAnswersEndsAtTheTotalDeadline() {
        val silent = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val hold = thread(isDaemon = true) { runCatching { silent.accept().use { Thread.sleep(8_000) } } }
        val link = Link(silent.localPort)
        val start = System.nanoTime()
        val failure = runCatching { link.client(sendMillis = 700).send(301, Packets.message(1)) }.exceptionOrNull()
        val ms = (System.nanoTime() - start) / 1_000_000
        silent.close()
        hold.interrupt()
        assertTrue("$failure", failure is IOException)
        assertTrue("took $ms ms", ms in 500..4_000)
    }

    @Test fun aLateCallbackOfAnOldLinkCannotReachTheNewOne() {
        val first = Link(server.port)
        val session = HubSession()
        val gen1 = session.begin()
        session.available(gen1) { first.client() }
        assertEquals(HubClient.Sent.STORED, session.wire()!!.send(301, Packets.message(1)))
        // The Bluetooth link drops and the app reconnects: a new generation, a new GATT link, a new GattStreams.
        val gen2 = session.begin()
        first.streams.linkClosed("link dropped")
        val before = first.uplink.size
        val second = Link(server.port)
        assertTrue(session.available(gen2) { second.client() })
        // Everything the dead link's callbacks might still deliver is ignored by it, and nothing reaches the new link.
        first.streams.onFrame(GattFrame.opened(1).encode())
        first.streams.onFrame(GattFrame.data(1, 0, byteArrayOf(1, 2, 3)).encode())
        first.streams.written(true)
        assertFalse("the old generation can no longer publish", session.available(gen1) { first.client() })
        assertEquals(before, first.uplink.size)
        assertEquals(HubClient.Sent.ALREADY, session.wire()!!.send(301, Packets.message(1)))
        assertEquals(HubClient.Sent.STORED, session.wire()!!.send(301, Packets.message(2)))
        assertEquals("the old link carried nothing new", before, first.uplink.size)
        assertTrue(second.uplink.isNotEmpty())
        assertTrue(runCatching { first.client().latest() }.exceptionOrNull() is IOException)
    }

    @Test fun leavingTheHubFailsARequestBlockedOnIt() {
        val silent = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val hold = thread(isDaemon = true) { runCatching { silent.accept().use { Thread.sleep(8_000) } } }
        val link = Link(silent.localPort)
        val session = HubSession()
        session.available(session.begin()) { link.client() }
        val wire = session.wire()!!
        val failure = AtomicReference<Throwable?>()
        val request = thread { failure.set(runCatching { wire.mail(0, listOf(hex(Packets.code(1))), 25) }.exceptionOrNull()) }
        Thread.sleep(600)
        session.end() // leave / replaced card
        request.join(3_000)
        silent.close(); hold.interrupt()
        assertFalse("the blocked request ended at once", request.isAlive)
        assertTrue("${failure.get()}", failure.get() is IOException)
    }
}

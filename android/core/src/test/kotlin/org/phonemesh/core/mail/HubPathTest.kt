package org.phonemesh.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubDelivery
import org.phonemesh.core.hub.HubWire
import org.phonemesh.core.session.Phone
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.HubAck
import java.io.IOException

/**
 * Accounts plan step 16, engine side: the real [Postman] and sessions of two phones, with a fake hub in place of the
 * network. The real HTTPS server is exercised by HubClientTest in the hub module; the app's use of these pieces by
 * HubPathsTest (instrumented). Unexecuted: written without a build.
 */
class HubPathTest {
    private enum class Mode { UP, DOWN, BUSY, REFUSE, STORE_THEN_DROP }

    /** A hub that keeps letters by recipient code, like the real mailbox, and can fail in every way the network can. */
    private class FakeHub : HubWire {
        val letters = ArrayList<Triple<Long, Int, ByteArray>>()
        var mode = Mode.UP

        override fun send(port: Int, packet: ByteArray): HubClient.Sent {
            check(port == 301) { "mail goes to port 301" }
            when (mode) {
                Mode.DOWN -> throw IOException("no Wi-Fi")
                Mode.BUSY -> return HubClient.Sent.BUSY
                Mode.REFUSE -> return HubClient.Sent.REFUSED
                else -> Unit
            }
            val held = letters.any { it.third.contentEquals(packet) }
            if (!held) letters += Triple(letters.size + 1L, port, packet.copyOf())
            if (mode == Mode.STORE_THEN_DROP) throw IOException("the answer was lost")
            return if (held) HubClient.Sent.ALREADY else HubClient.Sent.STORED
        }

        override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean): List<HubClient.Mail> {
            if (mode == Mode.DOWN) throw IOException("no Wi-Fi")
            return letters.filter { it.first > since && SessionState.hexOf(it.third.copyOfRange(1, 9)) in codes }
                .map { HubClient.Mail(it.first, it.second, it.third) }
        }

        override fun latest(): Long = letters.size.toLong()
    }

    private class Side(val phone: Phone, val other: Phone) {
        val store = PostmanTest.MapStore()
        val them = AccountId.of(other.keys.identity)
        val postman = Postman(phone.keys, store, quietDelay = { 20_000L })
        init { store.sessions[them] = phone.state }
    }

    private fun pair(): Pair<Side, Side> {
        val (a, b) = Phone.qrPair()
        return Side(a, b) to Side(b, a)
    }

    private fun text(s: String) = Body.Text("fr", s)

    @Test fun reachingTheHubIsNotDeliveryAndOnlyThePeersReceiptDelivers() {
        val (a, b) = pair()
        val hub = FakeHub()
        val out = a.postman.send(a.them, text("au puits"), 1, 0)!!
        assertEquals(HubDelivery.Result.REACHED, HubDelivery.offer(hub, 301, out.packet))
        a.postman.hubAck(HubAck.encode(listOf(out.packet.copyOfRange(1, 9))))
        assertEquals(Postman.Status.HUB, a.store.status(1))
        val got = hub.mail(0, b.postman.expectedCodes(), 0, false)
        assertEquals(1, got.size)
        assertEquals("au puits", (b.postman.receive(got[0].packet, 1_000)!!.body as Body.Text).text)
        assertEquals("the hub holding it is not delivery", Postman.Status.HUB, a.store.status(1))
        val receipt = b.postman.due(21_000).single()
        assertEquals(HubDelivery.Result.REACHED, HubDelivery.offer(hub, 301, receipt.packet))
        val back = hub.mail(0, a.postman.expectedCodes(), 0, false)
        assertEquals(1, back.size)
        assertNull(a.postman.receive(back[0].packet, 22_000))
        assertEquals(Postman.Status.DELIVERED, a.store.status(1))
    }

    @Test fun aHubThatIsDownLeavesTheExactBytesForTheRadio() {
        val (a, b) = pair()
        val hub = FakeHub().apply { mode = Mode.DOWN }
        val out = a.postman.send(a.them, text("par radio"), 1, 0)!!
        val before = a.store.rows.values.single().packet.copyOf()
        assertEquals(HubDelivery.Result.NOT_REACHED, HubDelivery.offer(hub, 301, out.packet))
        assertTrue(before.contentEquals(a.store.rows.values.single().packet))
        assertEquals(Postman.Status.WAITING, a.store.status(1))
        assertEquals(listOf(out.outboxId), a.postman.unsent().map { it.outboxId })
        a.postman.transmitted(out.outboxId)
        assertEquals(Postman.Status.SENT, a.store.status(1))
        assertEquals("par radio", (b.postman.receive(before, 1_000)!!.body as Body.Text).text)
    }

    @Test fun aBusyOrRefusingHubIsNeitherReachedNorDelivered() {
        val (a, _) = pair()
        val hub = FakeHub()
        val out = a.postman.send(a.them, text("plus tard"), 1, 0)!!
        hub.mode = Mode.BUSY
        assertEquals(HubDelivery.Result.BUSY, HubDelivery.offer(hub, 301, out.packet))
        hub.mode = Mode.REFUSE
        assertEquals(HubDelivery.Result.REFUSED, HubDelivery.offer(hub, 301, out.packet))
        assertEquals(Postman.Status.WAITING, a.store.status(1))
        assertTrue(hub.letters.isEmpty())
    }

    @Test fun aLostAnswerIsNotReachedUntilTheHubSaysSoAndNothingIsStoredTwice() {
        val (a, _) = pair()
        val hub = FakeHub().apply { mode = Mode.STORE_THEN_DROP }
        val out = a.postman.send(a.them, text("perdu"), 1, 0)!!
        assertEquals(HubDelivery.Result.NOT_REACHED, HubDelivery.offer(hub, 301, out.packet))
        assertEquals(Postman.Status.WAITING, a.store.status(1))
        hub.mode = Mode.UP
        assertEquals(HubDelivery.Result.REACHED, HubDelivery.offer(hub, 301, out.packet))
        assertEquals(1, hub.letters.size)
    }

    @Test fun theSameLetterFromTheRadioAndTheHubOpensOnce() {
        val (a, b) = pair()
        val hub = FakeHub()
        val out = a.postman.send(a.them, text("une fois"), 1, 0)!!
        HubDelivery.offer(hub, 301, out.packet)
        val asked = b.postman.expectedCodes()
        assertEquals("une fois", (b.postman.receive(out.packet, 1_000)!!.body as Body.Text).text)
        val late = hub.mail(0, asked, 0, false).single()
        assertNull("the copy is not shown twice", b.postman.receive(late.packet, 2_000))
    }

    @Test fun theHubCannotChangeTheDurableBytes() {
        val (a, _) = pair()
        val out = a.postman.send(a.them, text("intact"), 1, 0)!!
        val kept = a.store.rows.values.single().packet.copyOf()
        val vandal = object : HubWire {
            override fun send(port: Int, packet: ByteArray): HubClient.Sent { packet.fill(0); return HubClient.Sent.STORED }
            override fun mail(since: Long, codes: Collection<String>, waitSeconds: Int, hellos: Boolean) = emptyList<HubClient.Mail>()
            override fun latest() = 0L
        }
        assertEquals(HubDelivery.Result.REACHED, HubDelivery.offer(vandal, 301, out.packet))
        assertTrue(kept.contentEquals(out.packet))
        assertTrue(kept.contentEquals(a.store.rows.values.single().packet))
    }

    @Test fun theCodesAskedForAreSixteenHexDigitsAndIncludeTheNextMessage() {
        val (a, b) = pair()
        val codes = b.postman.expectedCodes()
        assertTrue(codes.isNotEmpty())
        assertTrue(codes.all { code -> code.length == 16 && code.all { it in "0123456789abcdef" } })
        val out = a.postman.send(a.them, text("x"), 1, 0)!!
        assertTrue(SessionState.hexOf(out.packet.copyOfRange(1, 9)) in codes)
    }
}

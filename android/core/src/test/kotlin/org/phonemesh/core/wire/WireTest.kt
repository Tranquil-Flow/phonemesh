package org.phonemesh.core.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureWire
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.session.Phone
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.session.SessionState
import org.phonemesh.core.session.Start
import kotlin.random.Random

class WireTest {
    private val ref = byteArrayOf(1, 2, 3, 4)

    @Test fun everyKindRoundTrips() {
        val head = PictureWire.encode(PictureWire.Head(9, PictureCodec.WEBP, 64, 48, 1200, PictureWire.partsFor(1200), 0L, byteArrayOf(1, 2, 3)))
        val part = PictureWire.encode(PictureWire.Part(9, 0, ByteArray(198) { it.toByte() }))
        val bodies = listOf(
            Body.Text("fr", "bonjour"), Body.Text("pt-BR", "é"), Body.Reply(ref, "en-US", "yes"), Body.Reaction(ref, "👍"),
            Body.PictureHead(head), Body.PicturePart(part), Body.Receipt(0xfffffffeL), Body.Hello("Aïcha", 5),
        )
        for (body in bodies) {
            val bytes = Body.encode(body)
            assertArrayEquals(body.toString(), bytes, Body.encode(checkNotNull(Body.decode(bytes)) { "$body" }))
        }
    }

    @Test fun theLongestTextsFit() {
        val (a, _) = Phone.qrPair()
        // No ratchet key: 228 - 9 header - 16 tag - 1 kind - 1 - 2 ("fr") = 199 bytes of text.
        val longest = Body.encode(Body.Text("fr", "x".repeat(199)))
        assertEquals(WireV2.maxBody(false), longest.size)
        assertEquals(WireV2.MAX_PACKET, Ratchet.seal(a.state, longest).packet.size)
        assertTrue(runCatching { Ratchet.seal(a.state, Body.encode(Body.Text("fr", "x".repeat(200)))) }.isFailure)
        // With the key: 228 - 44 - 16 - 1 - 1 - 2 = 164.
        assertEquals(WireV2.maxBody(true), Body.encode(Body.Text("fr", "x".repeat(164))).size)
    }

    @Test fun aPicturePartCarries198Bytes() {
        val (a, _) = Phone.qrPair()
        val part = Body.encode(Body.PicturePart(PictureWire.encode(PictureWire.Part(1, 0, ByteArray(198)))))
        assertEquals(WireV2.MAX_PACKET, Ratchet.seal(a.state, part).packet.size)
        val bigger = Body.encode(Body.PicturePart(PictureWire.encode(PictureWire.Part(1, 0, ByteArray(199)))))
        assertTrue(runCatching { Ratchet.seal(a.state, bigger) }.isFailure)
    }

    @Test fun aReceiptIsSmall() {
        val (a, _) = Phone.qrPair()
        assertEquals(30, Ratchet.seal(a.state, Body.encode(Body.Receipt(3))).packet.size)
    }

    @Test fun badBodiesAreRefused() {
        assertNull(Body.decode(byteArrayOf()))
        assertNull("unknown kind", Body.decode(byteArrayOf(9, 1)))
        assertNull("empty text", Body.decode(byteArrayOf(1, 2, 'f'.code.toByte(), 'r'.code.toByte())))
        assertNull("bad language", Body.decode(byteArrayOf(1, 2, 'F'.code.toByte(), 'R'.code.toByte(), 'x'.code.toByte())))
        assertNull("long emoji", Body.decode(byteArrayOf(3) + ref + ByteArray(9) { 'a'.code.toByte() }))
        assertNull("receipt length", Body.decode(byteArrayOf(6, 0, 0, 0)))
        assertNull("not a picture", Body.decode(byteArrayOf(5, 1, 2)))
    }

    @Test fun hubAcknowledgementsAndTheSharedChat() {
        val codes = List(28) { i -> ByteArray(8) { (i + it).toByte() } }
        val ack = HubAck.encode(codes)
        assertTrue(ack.size <= WireV2.MAX_PACKET)
        assertEquals(codes.map { it.toList() }, HubAck.decode(ack)!!.map { it.toList() })
        assertTrue(runCatching { HubAck.encode(codes + listOf(ByteArray(8))) }.isFailure)
        val sender = AccountId.fromBytes(ByteArray(8) { 3 })
        val name = "Moussa"
        val text = "x".repeat(SharedChatWire.maxText(name, "fr"))
        val bytes = SharedChatWire.encode(SharedChatWire.Message(sender, name, "fr", text))
        assertEquals(WireV2.MAX_PACKET, bytes.size)
        val back = SharedChatWire.decode(bytes)!!
        assertEquals(sender, back.sender); assertEquals(name, back.name); assertEquals(text, back.text)
        assertTrue(runCatching { SharedChatWire.encode(SharedChatWire.Message(sender, name, "fr", text + "x")) }.isFailure)
    }

    /** Random bytes into every decoder: a clean refusal (null) or a value, never an exception. */
    @Test fun decodersSurviveOneHundredThousandRandomInputs() {
        val random = Random(42)
        val keys = org.phonemesh.core.session.SoftKeys.generate()
        val (a, _) = Phone.qrPair()
        val valid = Ratchet.seal(a.state, Body.encode(Body.Text("fr", "seed"))).packet
        repeat(100_000) { i ->
            val bytes = if (i % 3 == 0) valid.copyOf().also { repeat(1 + random.nextInt(4)) { _ -> it[random.nextInt(it.size)] = random.nextInt().toByte() } }
            else random.nextBytes(random.nextInt(0, 240)).also { if (it.isNotEmpty() && i % 2 == 0) it[0] = (1 + random.nextInt(8)).toByte() }
            Body.decode(bytes)
            WireV2.parse(bytes)
            HubAck.decode(bytes)
            SharedChatWire.decode(bytes)
            ContactCard.decode(bytes)
            SessionState.decode(bytes)
            if (i % 50 == 0) {
                Start.hello(keys, bytes, 100)
                Ratchet.open(a.state.copy(), bytes, a.keys)
            }
        }
    }
}

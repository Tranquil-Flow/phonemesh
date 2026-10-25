package org.phonemesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.wire.Body
import org.phonemesh.core.wire.WireV2
import java.util.Random

/**
 * Pictures inside a wire v2 body (port 301): same packet layout and version nibble as port 300, but 198-byte parts and
 * up to 17 of them. The enclosing port and body pick the limits; port 300 decoding keeps 200 bytes and 16 parts.
 */
class PictureWireV2Test {
    private val sketch = byteArrayOf(1, 2, 3)
    private fun picture(size: Int) = ByteArray(size) { (it * 7 + 3).toByte() }
    private fun split(size: Int) = PictureWire.splitV2(9, PictureCodec.WEBP, 64, 48, picture(size), sketch)

    @Test fun theVersionNibbleAndLayoutAreUnchanged() {
        for (packet in split(1000)) {
            assertArrayEquals(PictureWire.encode(packet), PictureWire.encodeV2(packet))
            assertEquals(PictureWire.VERSION, (PictureWire.encodeV2(packet)[0].toInt() and 0xff) ushr 4)
        }
    }

    @Test fun aV2PartFillsAWholePacketAndNoMore() {
        val part = split(198)[1]
        assertEquals(WireV2.maxBody(false), Body.encode(Body.PicturePart(PictureWire.encodeV2(part))).size)
    }

    @Test fun theLargestPictureIsSeventeenPartsAndEveryBodyFitsAPacket() {
        val packets = split(PictureWire.MAX_PICTURE_BYTES)
        assertEquals(1 + 17, packets.size)
        assertEquals(17, PictureWire.partsForV2(PictureWire.MAX_PICTURE_BYTES))
        for (packet in packets) {
            val bytes = PictureWire.encodeV2(packet)
            val body = if (packet is PictureWire.Head) Body.PictureHead(bytes) else Body.PicturePart(bytes)
            assertTrue(Body.encode(body).size <= WireV2.maxBody(false))
        }
    }

    @Test fun v2PacketsRoundTripUnderV2Limits() {
        for (packet in split(1000)) assertEquals(packet, PictureWire.decodeV2(PictureWire.encodeV2(packet)))
    }

    @Test fun port300KeepsItsTwoHundredByteParts() {
        val packets = PictureWire.split(9, PictureCodec.WEBP, 64, 48, picture(1000), sketch)
        assertEquals(1 + 5, packets.size)
        assertEquals(200, (packets[1] as PictureWire.Part).data.size)
        packets.forEach { assertEquals(it, PictureWire.decode(PictureWire.encode(it))) }
        assertNull("a 200-byte part is not a v2 part", PictureWire.decodeV2(PictureWire.encode(packets[1])))
        assertNull("nor is its head: 5 parts of 200 is not 6 parts of 198", PictureWire.decodeV2(PictureWire.encode(packets[0])))
    }

    @Test fun aV2HeadCountsPartsBy198AndPort300RefusesItWhenTheCountsDiffer() {
        val head = split(1000)[0] as PictureWire.Head
        assertEquals(6, head.parts)
        assertNull(PictureWire.decode(PictureWire.encode(head)))
        assertNull(PictureWire.decodeV2(PictureWire.encodeV2(head.copy(parts = 5))))
    }

    @Test fun partIndexesReachSixteenInV2Only() {
        val part = PictureWire.Part(9, 16, ByteArray(198))
        assertEquals(part, PictureWire.decodeV2(PictureWire.encodeV2(part)))
        assertNull(PictureWire.decodeV2(PictureWire.encodeV2(PictureWire.Part(9, 17, ByteArray(1)))))
        assertNull(PictureWire.decode(PictureWire.encode(part)))
        assertNull(PictureWire.decodeV2(PictureWire.encodeV2(PictureWire.Part(9, 0, ByteArray(199)))))
    }

    @Test fun aV2BodyRefusesAPort300SizedPicture() {
        val v1 = PictureWire.split(9, PictureCodec.WEBP, 64, 48, picture(1000), sketch)
        assertTrue(runCatching { Body.encode(Body.PictureHead(PictureWire.encode(v1[0]))) }.isFailure)
        assertTrue(runCatching { Body.encode(Body.PicturePart(PictureWire.encode(v1[1]))) }.isFailure)
        val bytes = Body.encode(Body.PicturePart(PictureWire.encodeV2(split(1000)[1])))
        assertNotNull(Body.decode(bytes))
    }

    @Test fun joinRebuildsThePictureFromPartsInAnyOrder() {
        val original = picture(3000)
        val packets = PictureWire.splitV2(9, PictureCodec.WEBP, 64, 48, original, sketch)
        val head = packets[0] as PictureWire.Head
        val parts = packets.drop(1).map { it as PictureWire.Part }.shuffled(Random(4)).associate { it.index to it.data }
        assertArrayEquals(original, PictureJoin.join(head, parts))
    }

    @Test fun joinRefusesAMissingShortOrCorruptPart() {
        val packets = PictureWire.splitV2(9, PictureCodec.WEBP, 64, 48, picture(1000), sketch)
        val head = packets[0] as PictureWire.Head
        val parts = packets.drop(1).map { it as PictureWire.Part }.associate { it.index to it.data }
        assertNull("a part is missing", PictureJoin.join(head, parts - 2))
        assertNull("a middle part is short", PictureJoin.join(head, parts + (1 to parts.getValue(1).copyOf(100))))
        assertNull("a part is longer than the wire allows", PictureJoin.join(head, parts + (2 to parts.getValue(2) + byteArrayOf(1))))
        assertNull("the CRC-32 fails", PictureJoin.join(head, parts + (0 to parts.getValue(0).copyOf().also { it[0] = (it[0] + 1).toByte() })))
    }

    @Test fun aSketchOnlyPictureJoinsToNothing() {
        val packets = PictureWire.splitV2(3, PictureCodec.SKETCH, 8, 8, ByteArray(0), sketch)
        assertEquals(1, packets.size)
        assertEquals(0, PictureJoin.join(packets[0] as PictureWire.Head, emptyMap())!!.size)
    }
}

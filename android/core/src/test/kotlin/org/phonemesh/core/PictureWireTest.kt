package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class PictureWireTest {
    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }

    @Test fun headRoundTrips() {
        val head = PictureWire.Head(id = 0xBEEF, codec = PictureCodec.WEBP, width = 96, height = 72, totalBytes = 431,
            parts = 3, crc = 0xDEADBEEFL, sketch = bytes(21))
        val packet = PictureWire.encode(head)
        assertTrue(packet.size <= PictureWire.MAX_PACKET_BYTES)
        assertEquals(head, PictureWire.decode(packet))
    }

    @Test fun partRoundTrips() {
        val part = PictureWire.Part(id = 7, index = 2, data = bytes(200))
        val packet = PictureWire.encode(part)
        assertEquals(204, packet.size)
        assertEquals(part, PictureWire.decode(packet))
    }

    @Test fun splitMakesHeadAndPartsThatRebuildThePicture() {
        val picture = bytes(431)
        val packets = PictureWire.split(id = 9, codec = PictureCodec.WEBP, width = 96, height = 64, picture = picture, sketch = bytes(21))
        val head = packets.first() as PictureWire.Head
        assertEquals(3, head.parts)
        assertEquals(431, head.totalBytes)
        assertEquals(CRC32().apply { update(picture) }.value, head.crc)
        val parts = packets.drop(1).map { it as PictureWire.Part }
        assertEquals(listOf(0, 1, 2), parts.map { it.index })
        assertEquals(listOf(200, 200, 31), parts.map { it.data.size })
        assertArrayEquals(picture, parts.fold(ByteArray(0)) { acc, p -> acc + p.data })
        packets.forEach { assertTrue(PictureWire.encode(it).size <= PictureWire.MAX_PACKET_BYTES) }
    }

    @Test fun sketchOnlyHasNoParts() {
        val packets = PictureWire.split(id = 1, codec = PictureCodec.SKETCH, width = 4, height = 3, picture = ByteArray(0), sketch = bytes(21))
        assertEquals(1, packets.size)
        assertEquals(0, (packets[0] as PictureWire.Head).parts)
    }

    @Test fun splitRefusesWhatTheWireCannotCarry() {
        assertThrows(IllegalArgumentException::class.java) {
            PictureWire.split(1, PictureCodec.WEBP, 96, 96, bytes(PictureWire.MAX_PICTURE_BYTES + 1), bytes(21))
        }
        assertThrows(IllegalArgumentException::class.java) { PictureWire.split(1, PictureCodec.WEBP, 96, 96, bytes(10), bytes(33)) }
        assertThrows(IllegalArgumentException::class.java) { PictureWire.split(1, PictureCodec.WEBP, 513, 96, bytes(10), bytes(21)) }
        assertThrows(IllegalArgumentException::class.java) { PictureWire.split(1, PictureCodec.WEBP, 96, 96, ByteArray(0), bytes(21)) }
        assertThrows(IllegalArgumentException::class.java) { PictureWire.split(1, PictureCodec.WEBP, 0, 96, bytes(10), bytes(21)) }
    }

    @Test fun malformedPacketsAreNull() {
        val good = PictureWire.encode(PictureWire.Part(3, 0, bytes(10)))
        assertNull(PictureWire.decode(ByteArray(0)))
        assertNull(PictureWire.decode(good.copyOf(3)))
        assertNull("unknown version", PictureWire.decode(good.copyOf().also { it[0] = 0x21 }))
        assertNull("unknown kind", PictureWire.decode(good.copyOf().also { it[0] = 0x1F }))
        assertNull("empty part", PictureWire.decode(PictureWire.encode(PictureWire.Part(3, 0, bytes(1))).copyOf(4)))
        assertNull("part index beyond limit", PictureWire.decode(good.copyOf().also { it[3] = 16 }))
        assertNull("oversized", PictureWire.decode(ByteArray(PictureWire.MAX_PACKET_BYTES + 1) { 0x11 }))
        val head = PictureWire.encode(PictureWire.Head(1, PictureCodec.WEBP, 96, 96, 300, 2, 1L, bytes(21)))
        assertNull("truncated sketch", PictureWire.decode(head.copyOf(head.size - 1)))
        assertNull("trailing bytes", PictureWire.decode(head + byteArrayOf(0)))
        assertNull("unknown codec", PictureWire.decode(head.copyOf().also { it[3] = 15 }))
        assertNull("zero width", PictureWire.decode(head.copyOf().also { it[4] = 0; it[5] = 0 }))
        assertNull("parts disagree with size", PictureWire.decode(head.copyOf().also { it[10] = 5 }))
    }
}

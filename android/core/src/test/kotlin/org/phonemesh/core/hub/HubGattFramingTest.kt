package org.phonemesh.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** NEW-API tests (HubGatt, GattFrame). Pure JVM; nothing here needs Bluetooth. NOT RUN when staged. */
class HubGattFramingTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun bytes(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** These strings are also in hub-ble-bridge/Tests (Swift): both sides must produce and accept exactly these bytes. */
    @Test fun framesHaveTheDocumentedBytes() {
        assertEquals("01010001", hex(GattFrame.open(1).encode()))
        assertEquals("020100", hex(GattFrame.opened(1).encode()))
        assertEquals("0301056869", hex(GattFrame.data(1, 5, "hi".toByteArray()).encode()))
        assertEquals("0401002000", hex(GattFrame.credit(1, 8192).encode()))
        assertEquals("050700", hex(GattFrame.close(7).encode()))
        assertEquals("06010003", hex(GattFrame.reset(1, 3).encode()))
        assertEquals("03ff00", hex(GattFrame.data(255, 256, ByteArray(0)).encode()).substring(0, 6))
    }

    @Test fun decodeInvertsEncode() {
        val frames = listOf(GattFrame.open(9), GattFrame.opened(9), GattFrame.data(9, 255, byteArrayOf(1, 2, 3)), GattFrame.credit(9, 1), GattFrame.credit(9, HubGatt.WINDOW), GattFrame.close(9), GattFrame.reset(9, 6))
        for (f in frames) {
            val back = GattFrame.decode(f.encode())
            assertNotNull(hex(f.encode()), back)
            assertEquals(hex(f.encode()), hex(back!!.encode()))
        }
        assertEquals(8192, GattFrame.decode(bytes("0401002000"))!!.credit)
    }

    @Test fun malformedFramesAreRefusedNotThrown() {
        val bad = listOf(
            "", "01", "0101", // too short
            "00010000", "07010000", "ff010000", // unknown types
            "01000001", // stream 0
            "01010002", "0101000102", // OPEN: wrong version, wrong size
            "0201000", "020100aa", // OPENED with a payload
            "030100", // DATA with no bytes
            "0401000000", "040100", "0401002001", "04010000", "040100200000", // CREDIT: zero, none, 8193, one byte, three bytes
            "050100aa", // CLOSE with a payload
            "060100", "0601000102", // RESET: no reason, two bytes
            "01010101", "02010100", // non-DATA frames carry seq 0
        )
        for (text in bad) assertNull(text, GattFrame.decode(if (text.length % 2 == 0) bytes(text) else bytes(text + "0")))
        assertNull(GattFrame.decode(ByteArray(HubGatt.MAX_FRAME + 1) { 3 }))
        assertNotNull(GattFrame.decode(byteArrayOf(3, 1, 0) + ByteArray(HubGatt.MAX_FRAME - HubGatt.HEADER) { 9 }))
    }

    @Test fun payloadRoomFollowsTheLinkAndNeverExceedsOneAttribute() {
        assertEquals(17, HubGatt.maxPayload(23 - 3)) // the shortest ATT MTU
        assertEquals(HubGatt.MIN_FRAME - HubGatt.HEADER, HubGatt.maxPayload(0))
        assertEquals(HubGatt.MAX_FRAME - HubGatt.HEADER, HubGatt.maxPayload(100_000))
        assertEquals(244, HubGatt.maxPayload(247))
    }

    @Test fun payloadIsCopiedSoTheCallerCannotChangeAFrame() {
        val source = byteArrayOf(1, 2, 3)
        val frame = GattFrame.data(1, 0, source)
        source[0] = 99
        assertEquals(1, frame.payload[0].toInt())
    }

    @Test fun serviceUuidIsAWellFormedStableFunctionOfThePin() {
        val pinA = "A".repeat(43)
        val pinB = "B".repeat(43)
        val a = HubGatt.serviceUuid(pinA)
        assertTrue(a, Regex("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(a))
        assertEquals(a, UUID.fromString(a).toString())
        assertEquals(a, HubGatt.serviceUuid(pinA))
        assertNotEquals(a, HubGatt.serviceUuid(pinB))
        assertNotEquals(a, HubGatt.RX_UUID)
        assertNotEquals(a, HubGatt.TX_UUID)
        assertNotEquals(HubGatt.RX_UUID, HubGatt.TX_UUID)
        assertEquals(36, HubGatt.RX_UUID.length)
        UUID.fromString(HubGatt.RX_UUID)
        UUID.fromString(HubGatt.TX_UUID)
    }
}

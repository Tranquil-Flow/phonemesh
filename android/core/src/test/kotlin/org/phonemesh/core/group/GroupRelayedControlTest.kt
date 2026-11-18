package org.phonemesh.core.group

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

/** Body-only tests. Authentication, membership, admin selection and effects belong to the caller. */
class GroupRelayedControlTest {
    private val vectors = javaClass.getResourceAsStream("/group/relayed-control-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it && !it.startsWith("#") }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun hex(name: String) = vectors.getValue(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun controls() = listOf(GroupRelayedControl.Ask(1, 2), GroupRelayedControl.Leave(2, 3))
    private fun validBodies() = controls().map { it.encode() } + listOf(hex("ask"), hex("leave"))
    private fun positive(body: ByteArray): GroupRelayedControl {
        val got = GroupRelayedControl.decode(body)
        assertNotNull("a valid control must decode before testing a refusal", got)
        return got!!
    }

    @Test fun askIsTheSecondImplementationsBytes() {
        assertArrayEquals("the Ask body is the chosen seven bytes", hex("ask"), GroupRelayedControl.Ask(1, 2).encode())
        assertArrayEquals(hex("askLast"), GroupRelayedControl.Ask(19, 0xFFFF_FFFEL).encode())
    }

    @Test fun leaveIsTheSecondImplementationsBytes() {
        assertArrayEquals("the Leave body is the chosen seven bytes", hex("leave"), GroupRelayedControl.Leave(2, 3).encode())
        assertArrayEquals(hex("leaveHigh"), GroupRelayedControl.Leave(16, 0x8000_0000L).encode())
    }

    @Test fun decodingPreservesKindIndexAndEpoch() {
        val ask = positive(hex("ask"))
        assertTrue(ask is GroupRelayedControl.Ask)
        assertEquals(10, ask.op)
        assertEquals(1, ask.adminIndex)
        assertEquals(2L, ask.epoch)
        val leave = positive(hex("leave"))
        assertTrue(leave is GroupRelayedControl.Leave)
        assertEquals(11, leave.op)
        assertEquals(2, leave.adminIndex)
        assertEquals(3L, leave.epoch)
    }

    @Test fun unsignedEpochsDoNotBecomeNegative() {
        for ((name, value) in listOf("askLast" to 0xFFFF_FFFEL, "leaveHigh" to 0x8000_0000L)) {
            val got = positive(hex(name))
            assertEquals(value, got.epoch)
            assertArrayEquals(hex(name), got.encode())
        }
    }

    @Test fun allRepresentableRosterSlotsAndEpochEdgesRoundTrip() {
        for (index in 0 until GroupRoster.MAX_MEMBERS) for (epoch in longArrayOf(1, 2, 0x7FFF_FFFF, 0x8000_0000, 0xFFFF_FFFE)) {
            for (control in listOf(GroupRelayedControl.Ask(index, epoch), GroupRelayedControl.Leave(index, epoch))) {
                val body = control.encode()
                val got = positive(body)
                assertEquals(control.javaClass, got.javaClass)
                assertEquals(control.op, got.op)
                assertEquals(index, got.adminIndex)
                assertEquals(epoch, got.epoch)
                assertArrayEquals(body, got.encode())
            }
        }
    }

    @Test fun everyTruncationIsRefused() {
        for (body in validBodies()) {
            positive(body)
            for (size in 0 until body.size) assertNull("truncated at $size", GroupRelayedControl.decode(body.copyOf(size)))
        }
    }

    @Test fun trailingBytesAndConcatenatedControlsAreRefused() {
        for (body in validBodies()) {
            positive(body)
            for (extra in listOf(byteArrayOf(0), byteArrayOf(-1), body))
                assertNull("not exactly seven bytes", GroupRelayedControl.decode(body + extra))
        }
    }

    @Test fun everyOtherVersionIsRefused() {
        for (body in validBodies()) {
            positive(body)
            for (version in 0..255) if (version != 1)
                assertNull("version $version", GroupRelayedControl.decode(body.copyOf().also { it[1] = version.toByte() }))
        }
    }

    @Test fun everyOtherOpcodeIsRefused() {
        for (body in validBodies()) {
            positive(body)
            for (op in 0..255) if (op != 10 && op != 11)
                assertNull("op $op", GroupRelayedControl.decode(body.copyOf().also { it[0] = op.toByte() }))
        }
    }

    @Test fun outOfRangeWireIndicesAreRefused() {
        for (body in validBodies()) {
            positive(body)
            for (index in GroupRoster.MAX_MEMBERS..255)
                assertNull("index $index", GroupRelayedControl.decode(body.copyOf().also { it[2] = index.toByte() }))
        }
    }

    @Test fun emptyAndExhaustedWireEpochsAreRefused() {
        for (body in validBodies()) {
            positive(body)
            for (epoch in intArrayOf(0, -1)) {
                val bad = body.copyOf()
                ByteBuffer.wrap(bad, 3, 4).putInt(epoch)
                assertNull("epoch ${epoch.toLong() and 0xFFFF_FFFFL}", GroupRelayedControl.decode(bad))
            }
        }
    }

    @Test fun encodingRefusesInvalidIndicesAndEpochsWithoutNarrowing() {
        for (index in intArrayOf(Int.MIN_VALUE, -1, GroupRoster.MAX_MEMBERS, 255, 256, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GroupRelayedControl.Ask(index, 1).encode() }
            assertThrows(IllegalArgumentException::class.java) { GroupRelayedControl.Leave(index, 1).encode() }
        }
        for (epoch in longArrayOf(Long.MIN_VALUE, -1, 0, 0xFFFF_FFFF, 0x1_0000_0000, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GroupRelayedControl.Ask(0, epoch).encode() }
            assertThrows(IllegalArgumentException::class.java) { GroupRelayedControl.Leave(0, epoch).encode() }
        }
    }

    @Test fun encodedAndDecodedValuesDoNotAliasCallerArrays() {
        for (control in controls()) {
            val expected = control.encode()
            val first = control.encode()
            val got = positive(first)
            first.fill(0)
            assertArrayEquals(expected, control.encode())
            assertArrayEquals(expected, got.encode())
        }
    }

    @Test fun privateControlsAndRelayedBodiesAreNotInterchangeable() {
        val group = ByteArray(16) { it.toByte() }
        for ((kind, privateBody) in listOf(GroupControl.ASK to GroupControl.Ask(group, 2).encode(), GroupControl.LEAVE to GroupControl.Leave(group, 2).encode())) {
            assertNotNull("existing private positive control", GroupControl.decode(kind, privateBody))
            assertNull(GroupRelayedControl.decode(privateBody))
            for (relayed in validBodies()) {
                positive(relayed)
                assertNull(GroupControl.decode(kind, relayed))
            }
        }
    }

    @Test fun eachBodyFitsOnePacketAndNamesNoSenderOrGroup() {
        for (control in controls()) {
            val bytes = control.encode()
            assertEquals(7, bytes.size)
            assertEquals(GroupRelayedControl.SIZE, bytes.size)
            assertEquals(control.op.toByte(), bytes[0])
            assertEquals(1.toByte(), bytes[1])
            assertEquals(control.adminIndex.toByte(), bytes[2])
            assertEquals(control.epoch.toInt(), ByteBuffer.wrap(bytes, 3, 4).int)
            assertTrue(bytes.size <= GroupCipher.MAX_BODY)
        }
    }

    @Test fun arbitraryInputNeverThrowsAndAcceptedBytesAreCanonical() {
        val random = Random(1014)
        repeat(5000) {
            val body = ByteArray(random.nextInt(33)).also { random.nextBytes(it) }
            val got = GroupRelayedControl.decode(body)
            if (got != null) {
                assertEquals(7, body.size)
                assertTrue(got.op == 10 || got.op == 11)
                assertEquals(1.toByte(), body[1])
                assertTrue(got.adminIndex in 0 until GroupRoster.MAX_MEMBERS)
                assertTrue(got.epoch in 1 until 0xFFFF_FFFFL)
                assertArrayEquals(body, got.encode())
            }
        }
        for (body in validBodies()) assertArrayEquals(body, positive(body).encode())
    }
}

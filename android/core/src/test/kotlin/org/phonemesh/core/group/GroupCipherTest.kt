package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.WireV2
import java.io.File
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPublicKeySpec

/** Draft GK1 (decision 47). Vectors from `.local/crypto-vectors/group_vectors.py`, written from the design note alone. */
class GroupCipherTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/group/group-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val group get() = hex("group")
    private val epoch get() = v.getValue("epoch").toLong()
    private val identity get() = hex("identity")
    private val key get() = P256.privateKey(hex("scalar"))
    private fun sign(m: ByteArray) = P256.sign(key, m)

    @Test fun chainsCodesAndSealedBodiesMatchTheIndependentVectors() {
        var ck = GroupCipher.chain0(group, epoch, hex("secret"), identity)
        assertArrayEquals(hex("chain0"), ck)
        for (seq in 0..2) {
            val s = GroupCipher.step(ck)
            assertArrayEquals("code $seq", hex("code$seq"), s.code)
            val packet = GroupCipher.seal(group, epoch, seq.toLong(), s, hex("plain"), ::sign)
            // ECDSA is randomised: everything before the signature is byte for byte; Python's whole packet opens here.
            assertArrayEquals(hex("packet$seq").copyOfRange(0, packet.size - 64), packet.copyOfRange(0, packet.size - 64))
            assertArrayEquals(hex("plain"), GroupCipher.open(group, epoch, seq.toLong(), s, identity, hex("packet$seq")))
            ck = s.next
        }
        assertArrayEquals(hex("chain3"), ck)
    }

    @Test fun kotlinsPacketForPythonToVerify() {
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val packet = GroupCipher.seal(group, epoch, 0, s, hex("plain"), ::sign)
        File(System.getProperty("java.io.tmpdir"), "group-kotlin-packet.txt").writeText(packet.joinToString("") { "%02x".format(it) })
        assertEquals(9 + hex("plain").size + 16 + 64, packet.size)
    }

    @Test fun aMemberWithTheGroupKeyCannotWriteAsAnother() {
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val other = P256.generate()
        val forged = GroupCipher.seal(group, epoch, 0, s, hex("plain")) { P256.sign(other.private, it) }
        assertNull(GroupCipher.open(group, epoch, 0, s, identity, forged))
    }

    @Test fun everyChangedByteIsRefused() {
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val packet = hex("packet0")
        for (i in packet.indices) for (bit in 0..7) {
            val bad = packet.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertNull("byte $i bit $bit", GroupCipher.open(group, epoch, 0, s, identity, bad))
        }
        assertNull("another epoch", GroupCipher.open(group, epoch + 1, 0, s, identity, packet))
        assertNull("another position", GroupCipher.open(group, epoch, 1, s, identity, packet))
        assertNull("cut", GroupCipher.open(group, epoch, 0, s, identity, packet.copyOf(packet.size - 1)))
    }

    @Test fun theLargestBodyFillsOnePacketAndHubsStoreItAsPrivateMail() {
        assertEquals(139, GroupCipher.MAX_BODY)
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val packet = GroupCipher.seal(group, epoch, 0, s, ByteArray(GroupCipher.MAX_BODY) { 7 }, ::sign)
        assertEquals(WireV2.MAX_PACKET, packet.size)
        assertArrayEquals("a hub sees an ordinary private packet with this code", s.code, WireV2.parse(packet)!!.header.code)
        assertTrue(runCatching { GroupCipher.seal(group, epoch, 0, s, ByteArray(140), ::sign) }.isFailure)
    }

    @Test fun eachMembersChainIsTheirOwnAndAnotherEpochIsAnotherKey() {
        val other = P256.compress(P256.generate().public as ECPublicKey)
        val a = GroupCipher.chain0(group, epoch, hex("secret"), identity)
        assertTrue(!a.contentEquals(GroupCipher.chain0(group, epoch, hex("secret"), other)))
        assertTrue(!a.contentEquals(GroupCipher.chain0(group, epoch + 1, hex("secret"), identity)))
    }

    // ---- reading and writing chains

    private fun pair(): Pair<GroupWriter, GroupReader> {
        val ck = GroupCipher.chain0(group, epoch, hex("secret"), identity)
        return GroupWriter(group, epoch, ck, sign = ::sign) to GroupReader(group, epoch, identity, ck)
    }

    @Test fun inOrderLateAndRepeatedPackets() {
        val (w, r) = pair()
        val p = (0 until 5).map { w.seal("m$it".toByteArray()) }
        assertEquals("m0", String(r.open(p[0])!!))
        assertEquals("m3", String(r.open(p[3])!!))
        assertEquals(listOf(1L, 2L), r.missed())
        assertEquals("late, within the window", "m1", String(r.open(p[1])!!))
        assertNull("the same packet twice", r.open(p[1]))
        assertNull(r.open(p[3]))
        assertEquals("m2", String(r.open(p[2])!!))
        assertEquals("m4", String(r.open(p[4])!!))
        assertEquals(5L, r.next)
        assertTrue(r.missed().isEmpty())
    }

    /** Review C5: packets carry low-S signatures only, so nobody can make a second valid copy of one. */
    @Test fun onlyLowSSignaturesAreTakenAndEveryPacketHasOne() {
        val (w, r) = pair()
        val packets = (0 until 16).map { w.seal("m$it".toByteArray()) }
        for (p in packets) assertTrue(P256.isLowS(p.copyOfRange(p.size - 64, p.size)))
        val p = packets[0]
        val s = java.math.BigInteger(1, p.copyOfRange(p.size - 32, p.size))
        val t = (P256.n - s).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val high = p.copyOfRange(0, p.size - 32) + ByteArray(32 - t.size) + t
        assertNull("the high-S twin", r.open(high))
        assertEquals("m0", String(r.open(p)!!))
    }

    @Test fun aForgedCodeCannotMoveTheChain() {
        val (w, r) = pair()
        val p = (0 until 3).map { w.seal("m$it".toByteArray()) }
        val bad = p[2].copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(r.open(bad))
        assertEquals("nothing skipped by a bad packet", 0L, r.next)
        assertTrue(r.missed().isEmpty())
        assertEquals("m0", String(r.open(p[0])!!))
    }

    @Test fun expectedCodesCoverTheWindowAndMissedPackets() {
        val (w, r) = pair()
        val p = (0 until 25).map { w.seal("m$it".toByteArray()) }
        val codes = r.expectedCodes()
        assertEquals(GroupReader.WINDOW, codes.size)
        assertTrue(codes.any { it.contentEquals(GroupCipher.codeOf(p[19])!!) })
        assertNull("beyond the window", r.open(p[20]))
        assertNotNull(r.open(p[19]))
        assertEquals(19 + GroupReader.WINDOW, r.expectedCodes().size)
        assertNotNull("now in reach", r.open(p[24]))
    }

    @Test fun missedKeysAreBounded() {
        val (w, r) = pair()
        var last: ByteArray? = null
        repeat(10) { round ->
            repeat(GroupReader.WINDOW - 1) { w.seal(byteArrayOf(1)) }
            last = w.seal(byteArrayOf(round.toByte()))
            assertNotNull(r.open(last!!))
        }
        assertEquals(GroupReader.MAX_SKIPPED, r.missed().size)
    }

    @Test fun theWriterTellsTheChainToStoreBeforeSending() {
        val (w, _) = pair()
        val after = w.chainAfterNext()
        w.seal(byteArrayOf(1))
        val again = GroupWriter(group, epoch, after, next = 1, sign = ::sign)
        val r = GroupReader(group, epoch, identity, GroupCipher.chain0(group, epoch, hex("secret"), identity))
        assertEquals("a restarted writer goes on from the stored chain", "x", String(r.open(again.seal("x".toByteArray()))!!))
    }
}

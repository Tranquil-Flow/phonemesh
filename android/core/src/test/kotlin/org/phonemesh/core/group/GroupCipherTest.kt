package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.wire.WireV2
import java.io.File
import java.security.interfaces.ECPublicKey

/** Draft GK3 (decisions 47, 66). Vectors from `.local/crypto-vectors/group_vectors.py`, written from the design alone. */
class GroupCipherTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/group/group-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val group get() = hex("group")
    private val epoch get() = v.getValue("epoch").toLong()
    private val identity get() = hex("identity")
    private val signer get() = hex("signer")
    private val key get() = P256.privateKey(hex("signerScalar"))
    private fun sign(m: ByteArray) = P256.sign(key, m)

    @Test fun chainsCodesAndPacketsMatchTheIndependentVectors() {
        var ck = GroupCipher.chain0(group, epoch, hex("secret"), identity)
        assertArrayEquals(hex("chain0"), ck)
        for (seq in 0..2) {
            val s = GroupCipher.step(ck)
            assertArrayEquals("code $seq", hex("code$seq"), s.code)
            // ECDSA is randomised, and the signature is sealed with the body: Python's packet must open here.
            assertArrayEquals(hex("plain"), GroupCipher.open(group, epoch, seq.toLong(), s, identity, signer, hex("packet$seq")))
            val packet = GroupCipher.seal(group, epoch, seq.toLong(), s, identity, hex("plain"), ::sign)
            assertEquals(hex("packet$seq").size, packet.size)
            assertArrayEquals(hex("packet$seq").copyOfRange(0, 9), packet.copyOfRange(0, 9))
            assertArrayEquals("Kotlin signs with the vector's signing key", hex("plain"), GroupCipher.open(group, epoch, seq.toLong(), s, identity, signer, packet))
            ck = s.next
        }
        assertArrayEquals(hex("chain3"), ck)
    }

    @Test fun kotlinsPacketForPythonToVerify() {
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val packet = GroupCipher.seal(group, epoch, 0, s, identity, hex("plain"), ::sign)
        File(System.getProperty("java.io.tmpdir"), "group-kotlin-packet.txt").writeText(packet.joinToString("") { "%02x".format(it) })
        assertEquals(9 + hex("plain").size + 64 + 16, packet.size)
        val h = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity, hub = true))
        val hubPacket = GroupCipher.seal(group, epoch, 0, h, identity, hex("hubPlain"), ::sign)
        File(System.getProperty("java.io.tmpdir"), "group-kotlin-hub-packet.txt").writeText(hubPacket.joinToString("") { "%02x".format(it) })
    }

    @Test fun aMemberWithTheGroupKeyCannotWriteAsAnother() {
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val other = P256.generate()
        val forged = GroupCipher.seal(group, epoch, 0, s, identity, hex("plain")) { P256.sign(other.private, it) }
        assertNull(GroupCipher.open(group, epoch, 0, s, identity, signer, forged))
        val asOther = GroupCipher.seal(group, epoch, 0, s, identity, hex("plain"), ::sign)
        assertNull("the signature names the sender", GroupCipher.open(group, epoch, 0, s, P256.compress(other.public as ECPublicKey), signer, asOther))
    }

    /** Review C1: the signature is inside the sealed part, so the packet shows no signature to test against keys. */
    @Test fun theSignatureIsSealedWithTheBody() {
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val packet = GroupCipher.seal(group, epoch, 0, s, identity, hex("plain"), ::sign)
        val plain = Aead.open(s.key, s.nonce, packet.copyOfRange(0, 9), packet.copyOfRange(9, packet.size))!!
        val sig = plain.copyOfRange(plain.size - 64, plain.size)
        for (i in 0..packet.size - 64) assertTrue("no signature in clear at $i", !packet.copyOfRange(i, i + 64).contentEquals(sig))
    }

    @Test fun everyChangedByteIsRefused() {
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val packet = hex("packet0")
        for (i in packet.indices) for (bit in 0..7) {
            val bad = packet.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertNull("byte $i bit $bit", GroupCipher.open(group, epoch, 0, s, identity, signer, bad))
        }
        assertNull("another epoch", GroupCipher.open(group, epoch + 1, 0, s, identity, signer, packet))
        assertNull("another position", GroupCipher.open(group, epoch, 1, s, identity, signer, packet))
        assertNull("cut", GroupCipher.open(group, epoch, 0, s, identity, signer, packet.copyOf(packet.size - 1)))
    }

    /** Review C5: a high-S twin of the signature is refused, even sealed with the right key. */
    @Test fun onlyLowSSignaturesAreTaken() {
        val (w, r) = pair()
        val packets = (0 until 16).map { w.seal("m$it".toByteArray()) }
        var ck = GroupCipher.chain0(group, epoch, hex("secret"), identity)
        val steps = packets.map { GroupCipher.step(ck).also { s -> ck = s.next } }
        for ((p, st) in packets.zip(steps)) {
            val plain = Aead.open(st.key, st.nonce, p.copyOfRange(0, 9), p.copyOfRange(9, p.size))!!
            assertTrue(P256.isLowS(plain.copyOfRange(plain.size - 64, plain.size)))
        }
        val p = packets[0]
        val s = steps[0]
        val plain = Aead.open(s.key, s.nonce, p.copyOfRange(0, 9), p.copyOfRange(9, p.size))!!
        val sv = java.math.BigInteger(1, plain.copyOfRange(plain.size - 32, plain.size))
        val t = (P256.n - sv).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val highPlain = plain.copyOfRange(0, plain.size - 32) + ByteArray(32 - t.size) + t
        val high = p.copyOfRange(0, 9) + Aead.seal(s.key, s.nonce, p.copyOfRange(0, 9), highPlain)
        assertNull("the high-S twin", r.open(high))
        assertEquals("m0", String(r.open(p)!!))
    }

    @Test fun theLargestBodyFillsOnePacketAndHubsStoreItAsPrivateMail() {
        assertEquals(139, GroupCipher.MAX_BODY)
        val s = GroupCipher.step(GroupCipher.chain0(group, epoch, hex("secret"), identity))
        val packet = GroupCipher.seal(group, epoch, 0, s, identity, ByteArray(GroupCipher.MAX_BODY) { 7 }, ::sign)
        assertEquals(WireV2.MAX_PACKET, packet.size)
        assertArrayEquals("a hub sees an ordinary private packet with this code", s.code, WireV2.parse(packet)!!.header.code)
        assertTrue(runCatching { GroupCipher.seal(group, epoch, 0, s, identity, ByteArray(140), ::sign) }.isFailure)
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
        return GroupWriter(group, epoch, identity, ck, sign = ::sign) to GroupReader(group, epoch, identity, signer, ck)
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
        repeat(10) { round ->
            repeat(GroupReader.WINDOW - 1) { w.seal(byteArrayOf(1)) }
            assertNotNull(r.open(w.seal(byteArrayOf(round.toByte()))))
        }
        assertEquals(GroupReader.MAX_SKIPPED, r.missed().size)
    }

    /** Review C8: a writer and a reader saved and loaded go on exactly where they were. */
    @Test fun savedChainsGoOnWhereTheyWere() {
        val (w, r) = pair()
        val p = (0 until 4).map { w.seal("m$it".toByteArray()) }
        assertNotNull(r.open(p[2]))
        val savedW = w.encode()
        val again = GroupWriter(group, epoch, identity, savedW.copyOfRange(0, 32), java.nio.ByteBuffer.wrap(savedW, 32, 4).int.toLong(), ::sign)
        val r2 = GroupReader.decode(group, epoch, java.nio.ByteBuffer.wrap(r.encode()))
        assertEquals(listOf(0L, 1L), r2.missed())
        assertEquals("m1", String(r2.open(p[1])!!))
        assertEquals("m3", String(r2.open(p[3])!!))
        assertEquals("a restarted writer goes on from the stored chain", "x", String(r2.open(again.seal("x".toByteArray()))!!))
    }

    /** Review C6: the writer gives its own next codes, which the reader of its chain expects too. */
    @Test fun theWritersOwnCodesAreTheOnesOthersExpect() {
        val (w, r) = pair()
        assertEquals(r.expectedCodes().map { it.toList() }, w.expectedCodes().map { it.toList() })
    }
}

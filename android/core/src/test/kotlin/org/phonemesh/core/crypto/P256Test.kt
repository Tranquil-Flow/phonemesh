package org.phonemesh.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.Vectors.hex
import java.math.BigInteger
import java.security.spec.ECPoint

class P256Test {
    private fun big(text: String) = BigInteger(text, 16)

    @Test fun nistEcdhVectors() {
        val cases = Vectors.lines("p256-ecdh-cavp.txt")
        assertEquals(25, cases.size)
        for (c in cases) {
            val theirs = P256.compress(ECPoint(big(c[2]), big(c[3])))
            assertArrayEquals("count ${c[0]}", hex(c[6]), P256.agree(P256.privateKey(hex(c[1]).let { Bytes.fixed(BigInteger(1, it), 32) }), theirs))
        }
    }

    @Test fun nistPublicKeyValidation() {
        val cases = Vectors.lines("p256-pkv-cavp.txt")
        assertEquals(12, cases.size)
        for (c in cases) assertEquals("${c[0]} ${c[1]}", c[2] == "P", P256.onCurve(big(c[0]), big(c[1])))
    }

    @Test fun nistSignatureVerification() {
        val cases = Vectors.lines("p256-sha256-sigver-cavp.txt")
        assertEquals(15, cases.size)
        for (c in cases) {
            val x = big(c[1]); val y = big(c[2])
            val valid = P256.onCurve(x, y) && P256.verify(P256.compress(ECPoint(x, y)), hex(c[0]),
                Bytes.fixed(big(c[3]), 32) + Bytes.fixed(big(c[4]), 32))
            assertEquals("msg ${c[0].take(16)}", c[5] == "P", valid)
        }
    }

    @Test fun matchesPythonCryptography() {
        val lines = Vectors.lines("crosscheck-primitives.txt")
        val ecdh = lines.filter { it[0] == "ecdh" }
        assertEquals(40, ecdh.size)
        for (c in ecdh) {
            assertArrayEquals(hex(c[6]), P256.agree(P256.privateKey(hex(c[1])), hex(c[2])))
            val point = ECPoint(big(c[4]), big(c[5]))
            assertArrayEquals("compressing", hex(c[3]), P256.compress(point))
            assertEquals("decompressing", point, P256.decompress(hex(c[3])))
        }
        val ecdsa = lines.filter { it[0] == "ecdsa" }
        assertEquals(40, ecdsa.size)
        for (c in ecdsa) {
            assertTrue(P256.verify(hex(c[1]), hex(c[2]), hex(c[3])))
            assertFalse("another message", P256.verify(hex(c[1]), hex(c[2]) + 0, hex(c[3])))
        }
    }

    @Test fun signsAndVerifiesRawSignatures() {
        val pair = P256.generate()
        val key = P256.compress(pair.public as java.security.interfaces.ECPublicKey)
        repeat(20) {
            val message = "card $it".toByteArray()
            val signature = P256.sign(pair.private, message)
            assertEquals(64, signature.size)
            assertTrue(P256.verify(key, message, signature))
            assertArrayEquals(signature, P256.derToRaw(checkNotNull(P256.rawToDer(signature))))
            assertFalse(P256.verify(key, message, signature.copyOf().also { s -> s[63] = (s[63] + 1).toByte() }))
        }
    }

    @Test fun badPointsAreRefused() {
        val good = P256.compress(P256.generate().public as java.security.interfaces.ECPublicKey)
        assertNotNull(P256.decompress(good))
        assertNull("uncompressed prefix", P256.decompress(good.copyOf().also { it[0] = 4 }))
        assertNull("identity has no encoding", P256.decompress(ByteArray(33)))
        assertNull("too short", P256.decompress(good.copyOf(32)))
        assertNull("too long", P256.decompress(good + 0))
        assertNull("x = p", P256.decompress(byteArrayOf(2) + Bytes.fixed(P256.p, 32)))
        assertNull("x > p", P256.decompress(byteArrayOf(3) + ByteArray(32) { -1 }))
        // An x with no point above it.
        var x = BigInteger.ZERO
        while (P256.decompress(byteArrayOf(2) + Bytes.fixed(x, 32)) != null) x = x.add(BigInteger.ONE)
        assertNull(P256.publicKey(byteArrayOf(2) + Bytes.fixed(x, 32)))
        assertNull("no shared secret with a bad key", P256.agree(P256.generate().private, byteArrayOf(2) + Bytes.fixed(x, 32)))
        assertFalse(P256.onCurve(BigInteger.ZERO, BigInteger.ZERO))
    }

    @Test fun outOfRangeSignaturesAreRefused() {
        val n = Bytes.fixed(P256.n, 32)
        val one = Bytes.fixed(BigInteger.ONE, 32)
        assertNull("r = 0", P256.rawToDer(ByteArray(32) + one))
        assertNull("s = 0", P256.rawToDer(one + ByteArray(32)))
        assertNull("r = n", P256.rawToDer(n + one))
        assertNull("s = n", P256.rawToDer(one + n))
        assertNull("63 bytes", P256.rawToDer(ByteArray(63) { 1 }))
        assertNotNull(P256.rawToDer(one + one))
        val key = P256.compress(P256.generate().public as java.security.interfaces.ECPublicKey)
        assertFalse(P256.verify(key, byteArrayOf(1), one + n))
    }

    @Test fun strictDerOnly() {
        val raw = Bytes.fixed(BigInteger.valueOf(0x80), 32) + Bytes.fixed(BigInteger.ONE, 32)
        val der = checkNotNull(P256.rawToDer(raw))
        assertArrayEquals(raw, P256.derToRaw(der))
        assertNull("trailing byte", P256.derToRaw(der + 0))
        assertNull("negative integer", P256.derToRaw(byteArrayOf(0x30, 6, 2, 1, -128, 2, 1, 1)))
        assertNull("padded integer", P256.derToRaw(byteArrayOf(0x30, 7, 2, 2, 0, 1, 2, 1, 1)))
        assertNull("not a sequence", P256.derToRaw(der.copyOf().also { it[0] = 0x31 }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aZeroScalarIsNotAKey() { P256.privateKey(ByteArray(32)) }
}

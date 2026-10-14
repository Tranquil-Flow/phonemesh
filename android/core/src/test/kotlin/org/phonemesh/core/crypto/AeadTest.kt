package org.phonemesh.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.phonemesh.core.crypto.Vectors.hex

class AeadTest {
    @Test fun matchesPythonCryptographyBothWays() {
        val cases = Vectors.lines("crosscheck-primitives.txt").filter { it[0] == "gcm" }
        assertEquals(40, cases.size)
        for (c in cases) {
            val (key, nonce, aad, plain, sealed) = listOf(c[1], c[2], c[3], c[4], c[5]).map(::hex)
            assertArrayEquals(sealed, Aead.seal(key, nonce, aad, plain))
            assertArrayEquals(plain, Aead.open(key, nonce, aad, sealed))
        }
    }

    @Test fun anyChangeIsRefused() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it * 7).toByte() }
        val aad = byteArrayOf(1, 2, 3)
        val sealed = Aead.seal(key, nonce, aad, "bonjour".toByteArray())
        for (i in sealed.indices) {
            assertNull("byte $i", Aead.open(key, nonce, aad, sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
        }
        assertNull("other associated data", Aead.open(key, nonce, byteArrayOf(1, 2, 4), sealed))
        assertNull("other nonce", Aead.open(key, nonce.copyOf().also { it[0] = 9 }, aad, sealed))
        assertNull("other key", Aead.open(key.copyOf().also { it[31] = 0 }, nonce, aad, sealed))
        assertNull("cut short", Aead.open(key, nonce, aad, sealed.copyOf(15)))
    }

    @Test fun theHookSeesEveryKeyAndNonceUsed() {
        val seen = mutableListOf<String>()
        Aead.sealed = { k, n -> seen += hex(k) + hex(n) }
        try {
            Aead.seal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(1))
            Aead.seal(ByteArray(32), ByteArray(12) { 1 }, ByteArray(0), ByteArray(1))
        } finally {
            Aead.sealed = null
        }
        assertEquals(2, seen.toSet().size)
    }
}

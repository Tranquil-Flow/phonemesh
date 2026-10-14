package org.phonemesh.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.core.crypto.Vectors.hex

class HkdfTest {
    @Test fun rfc5869Sha256Vectors() {
        val cases = Vectors.lines("rfc5869-sha256.txt")
        assertEquals(3, cases.size)
        for ((ikm, salt, info, length, prk, okm) in cases.map { it.toTypedArray() }.map { Six(it) }) {
            assertArrayEquals(hex(prk), Hkdf.extract(hex(salt), hex(ikm)))
            assertArrayEquals(hex(okm), Hkdf.expand(hex(prk), hex(info), length.toInt()))
        }
    }

    @Test fun matchesPythonCryptography() {
        val cases = Vectors.lines("crosscheck-primitives.txt").filter { it[0] == "hkdf" }
        assertEquals(40, cases.size)
        for (c in cases) assertArrayEquals(hex(c[5]), Hkdf.derive(hex(c[2]), hex(c[1]), hex(c[3]), c[4].toInt()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMoreThan255Blocks() { Hkdf.expand(ByteArray(32), ByteArray(0), 255 * 32 + 1) }

    private class Six(val v: Array<String>) {
        operator fun component1() = v[0]; operator fun component2() = v[1]; operator fun component3() = v[2]
        operator fun component4() = v[3]; operator fun component5() = v[4]; operator fun component6() = v[5]
    }
}

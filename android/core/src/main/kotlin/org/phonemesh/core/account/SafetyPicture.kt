package org.phonemesh.core.account

import org.phonemesh.core.crypto.Bytes

/**
 * The safety picture both people compare instead of a 60-digit number: four icons out of a fixed list of 16, from
 * SHA-256 of the two identity keys in byte order, so both phones show the same four.
 */
object SafetyPicture {
    const val ICONS = 16
    const val SHOWN = 4

    fun icons(mine: ByteArray, theirs: ByteArray): IntArray {
        val (first, second) = if (compare(mine, theirs) <= 0) mine to theirs else theirs to mine
        val hash = Bytes.sha256(first, second)
        return IntArray(SHOWN) { (hash[it].toInt() and 0xff) % ICONS }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size - b.size
    }
}

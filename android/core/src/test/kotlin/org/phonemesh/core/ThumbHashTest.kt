package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** The Kotlin port must give the reference Java implementation's bytes and pixels exactly. */
class ThumbHashTest {
    private fun picture(w: Int, h: Int, seed: Long, alpha: Boolean): ByteArray {
        val r = Random(seed)
        val base = IntArray(3) { r.nextInt(256) }
        return ByteArray(w * h * 4) { i ->
            val c = i % 4
            val px = (i / 4) % w
            val py = (i / 4) / w
            when (c) {
                3 -> if (alpha && (px + py) % 5 == 0) (r.nextInt(256)).toByte() else 255.toByte()
                else -> ((base[c] + px * (c + 1) * 3 + py * (3 - c) * 2 + r.nextInt(24)) and 255).toByte()
            }
        }
    }

    @Test fun matchesTheReference() {
        val sizes = listOf(1 to 1, 4 to 3, 3 to 4, 32 to 32, 100 to 75, 75 to 100, 100 to 100, 64 to 9)
        var seed = 1L
        for ((w, h) in sizes) for (alpha in listOf(false, true)) {
            val rgba = picture(w, h, seed++, alpha)
            val expected = com.madebyevan.thumbhash.ThumbHash.rgbaToThumbHash(w, h, rgba)
            val actual = ThumbHash.encode(w, h, rgba)
            assertArrayEquals("${w}x$h alpha=$alpha", expected, actual)
            val ref = com.madebyevan.thumbhash.ThumbHash.thumbHashToRGBA(expected)
            val mine = ThumbHash.decode(actual)!!
            assertEquals(ref.width, mine.width); assertEquals(ref.height, mine.height)
            assertArrayEquals(ref.rgba, mine.rgba)
            assertTrue("${actual.size} B", actual.size <= PictureWire.MAX_SKETCH_BYTES)
        }
    }

    @Test fun radioBytesThatAreNotASketchGiveNull() {
        val good = ThumbHash.encode(32, 24, picture(32, 24, 9, false))
        assertNull(ThumbHash.decode(ByteArray(0)))
        assertNull(ThumbHash.decode(good.copyOf(5)))
        assertNull(ThumbHash.decode(good.copyOf(good.size - 1)))
        // Any 32 random bytes either decode to a small picture or give null; never an exception.
        val r = Random(3)
        repeat(2000) { val b = ByteArray(r.nextInt(33)).also(r::nextBytes); ThumbHash.decode(b)?.let { assertTrue(it.width in 1..32 && it.height in 1..32) } }
    }
}

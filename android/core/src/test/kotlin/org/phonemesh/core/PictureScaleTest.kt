package org.phonemesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureScaleTest {
    private fun grey(v: Int) = (255 shl 24) or (v shl 16) or (v shl 8) or v
    private fun channel(p: Int) = p and 255

    @Test fun aFlatPictureStaysTheSame() {
        val flat = IntArray(20 * 10) { grey(120) }
        assertArrayEquals(flat, PictureScale.unsharp(flat, 20, 10))
    }

    @Test fun anEdgeGetsMoreContrastOnBothSides() {
        val w = 20
        val edge = IntArray(w * 4) { i -> if (i % w < w / 2) grey(80) else grey(160) }
        val sharp = PictureScale.unsharp(edge, w, 4, radius = 2, amount = 1f, threshold = 0)
        assertTrue("the dark side darkens near the edge", channel(sharp[w / 2 - 1]) < 80)
        assertTrue("the light side lightens near the edge", channel(sharp[w / 2]) > 160)
        assertEquals("far from the edge nothing changes", 80, channel(sharp[0]))
        assertEquals(255, sharp[0] ushr 24)
    }

    @Test fun smallNoiseUnderTheThresholdIsLeftAlone() {
        val w = 12
        val noisy = IntArray(w * w) { i -> grey(if ((i / w + i % w) % 2 == 0) 100 else 101) }
        assertArrayEquals(noisy, PictureScale.unsharp(noisy, w, w, threshold = 3))
    }

    @Test fun tilesCoverThePictureExactlyOnceWithContextAround() {
        for ((w, h) in listOf(96 to 72, 256 to 192, 64 to 64, 65 to 1, 1 to 1)) {
            val covered = IntArray(w * h)
            val tiles = PictureScale.tiles(w, h, size = 64, pad = 8)
            for (t in tiles) {
                assertTrue(t.x >= 0 && t.y >= 0 && t.x + t.width <= w && t.y + t.height <= h)
                assertTrue(t.width <= 64 + 16 && t.height <= 64 + 16)
                for (y in 0 until t.innerHeight) for (x in 0 until t.innerWidth) covered[(t.y + t.innerY + y) * w + t.x + t.innerX + x]++
            }
            assertTrue("$w x $h covered once", covered.all { it == 1 })
        }
        assertEquals("a small picture is one tile", 1, PictureScale.tiles(96, 72, size = 256, pad = 8).size)
        assertEquals(12, PictureScale.tiles(256, 192, size = 64, pad = 8).size)
    }
}

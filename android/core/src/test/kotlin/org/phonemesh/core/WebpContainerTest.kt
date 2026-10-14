package org.phonemesh.core

import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

class WebpContainerTest {
    private fun chunk(name: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(name.toByteArray(Charsets.US_ASCII))
        for (shift in 0..24 step 8) write((payload.size ushr shift) and 255)
        write(payload)
        if (payload.size % 2 == 1) write(0)
    }.toByteArray()

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { a, b -> a + b }
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            val size = 4 + body.size
            for (shift in 0..24 step 8) write((size ushr shift) and 255)
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(body)
        }.toByteArray()
    }

    private val image = ByteArray(41) { (it * 3).toByte() } // odd: padded
    private val profile = ByteArray(480) { 7 }

    private fun vp8x(flags: Int) = chunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0, 95, 0, 0, 63, 0, 0))

    @Test fun theColourProfileAndMetadataAreDroppedLeavingTheSimpleForm() {
        val android = riff(vp8x(0x20 or 0x08 or 0x04), chunk("ICCP", profile), chunk("VP8 ", image), chunk("EXIF", ByteArray(30)), chunk("XMP ", ByteArray(12)))
        val trimmed = WebpContainer.minimal(android)
        assertArrayEquals(riff(chunk("VP8 ", image)), trimmed)
        assertEquals(12 + 8 + 42, trimmed.size)
    }

    @Test fun transparencyKeepsItsHeaderWithOnlyTheAlphaFlag() {
        val alpha = chunk("ALPH", ByteArray(9) { 1 })
        val android = riff(vp8x(0x20 or 0x10), chunk("ICCP", profile), alpha, chunk("VP8 ", image))
        assertArrayEquals(riff(vp8x(0x10), alpha, chunk("VP8 ", image)), WebpContainer.minimal(android))
    }

    @Test fun anAlreadySimpleFileStaysTheSame() {
        val simple = riff(chunk("VP8L", image))
        assertArrayEquals(simple, WebpContainer.minimal(simple))
    }

    @Test fun anythingUnexpectedIsLeftAlone() {
        val animated = riff(vp8x(0x02), chunk("ANIM", ByteArray(6)), chunk("ANMF", ByteArray(20)))
        assertArrayEquals(animated, WebpContainer.minimal(animated))
        val cut = riff(chunk("VP8 ", image)).copyOf(30)
        assertArrayEquals(cut, WebpContainer.minimal(cut))
        val notWebp = "hello, this is not a picture".toByteArray()
        assertArrayEquals(notWebp, WebpContainer.minimal(notWebp))
    }
}

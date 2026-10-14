package org.phonemesh.core

import java.io.ByteArrayOutputStream

/**
 * Trims a WebP file to what draws the picture. Android's encoder adds an ICC colour profile (about 500 bytes, more than
 * a whole small picture), and may add EXIF or XMP; on the radio every byte is airtime. Without a profile, decoders use
 * sRGB, which is what the encoder wrote. Anything unexpected (animation, a broken file) is returned unchanged.
 */
object WebpContainer {
    private const val ALPHA_FLAG = 0x10

    fun minimal(webp: ByteArray): ByteArray {
        if (webp.size < 20 || !tag(webp, 0, "RIFF") || !tag(webp, 8, "WEBP")) return webp
        val chunks = mutableListOf<Pair<String, IntRange>>()
        var at = 12
        while (at + 8 <= webp.size) {
            val size = le32(webp, at + 4)
            if (size < 0 || at + 8 + size > webp.size) return webp
            chunks += String(webp, at, 4, Charsets.US_ASCII) to (at until at + 8 + size)
            at += 8 + size + (size and 1)
        }
        if (chunks.any { it.first == "ANIM" || it.first == "ANMF" }) return webp
        val image = chunks.firstOrNull { it.first == "VP8 " || it.first == "VP8L" } ?: return webp
        val alpha = chunks.firstOrNull { it.first == "ALPH" }
        val body = ByteArrayOutputStream()
        if (alpha != null) {
            // Transparency needs the extended header; keep it with only its alpha flag.
            val header = chunks.firstOrNull { it.first == "VP8X" } ?: return webp
            if (header.second.count() < 18) return webp
            val copy = webp.copyOfRange(header.second.first, header.second.first + 18)
            copy[8] = (copy[8].toInt() and ALPHA_FLAG).toByte()
            body.write(copy)
            chunk(body, webp, alpha.second)
        }
        chunk(body, webp, image.second)
        val payload = body.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLe32(this, 4 + payload.size)
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(payload)
        }.toByteArray()
    }

    /** A chunk as it was, padded to an even length as RIFF requires. */
    private fun chunk(out: ByteArrayOutputStream, source: ByteArray, range: IntRange) {
        out.write(source, range.first, range.count())
        if (range.count() % 2 == 1) out.write(0)
    }

    private fun tag(bytes: ByteArray, at: Int, name: String) =
        name.indices.all { bytes[at + it] == name[it].code.toByte() }

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
            ((bytes[at + 2].toInt() and 255) shl 16) or ((bytes[at + 3].toInt() and 255) shl 24)

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        for (shift in 0..24 step 8) out.write((value ushr shift) and 255)
    }
}

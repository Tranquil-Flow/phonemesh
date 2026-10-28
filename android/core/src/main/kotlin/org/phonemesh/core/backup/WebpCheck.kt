package org.phonemesh.core.backup

/**
 * The structure of a still WebP picture inside a saved copy (backup spec 5.5: "complete non-sketch data must pass its
 * codec size/structure validation"). A RIFF/WEBP container whose chunks add up exactly to its length, an image chunk
 * (VP8, VP8L, or VP8X holding one) whose own header gives the width and height, and nothing animated. This is a
 * structure check, not a decoder: the viewer still bounds-checks before it creates a bitmap.
 */
object WebpCheck {
    private const val ANIMATION_FLAG = 0x02

    /** The width and height the picture's own header states, or null when it is not a well-formed still WebP. */
    fun dimensions(d: ByteArray): Pair<Int, Int>? {
        if (d.size < 20 || !tag(d, 0, "RIFF") || !tag(d, 8, "WEBP") || le32(d, 4).toLong() != d.size - 8L) return null
        var at = 12
        var first = true
        var canvas: Pair<Int, Int>? = null
        var image: Pair<Int, Int>? = null
        while (at < d.size) {
            if (at + 8 > d.size) return null
            val name = String(d, at, 4, Charsets.US_ASCII)
            val size = le32(d, at + 4)
            val body = at + 8
            if (size < 0 || body.toLong() + size + (size and 1) > d.size) return null
            if (first && name != "VP8X" && name != "VP8 " && name != "VP8L") return null
            when (name) {
                "VP8X" -> {
                    if (!first || size < 10 || (d[body].toInt() and ANIMATION_FLAG) != 0) return null
                    canvas = (le24(d, body + 4) + 1) to (le24(d, body + 7) + 1)
                }
                "VP8 " -> { if (image != null) return null; image = lossy(d, body, size) ?: return null }
                "VP8L" -> { if (image != null) return null; image = lossless(d, body, size) ?: return null }
                "ANIM", "ANMF" -> return null
                else -> Unit
            }
            first = false
            at = body + size + (size and 1)
        }
        val found = image ?: return null
        if (canvas != null && canvas != found) return null
        return found
    }

    private fun lossy(d: ByteArray, at: Int, size: Int): Pair<Int, Int>? {
        if (size < 10 || (d[at].toInt() and 1) != 0) return null
        if ((d[at + 3].toInt() and 255) != 0x9D || (d[at + 4].toInt() and 255) != 0x01 || (d[at + 5].toInt() and 255) != 0x2A) return null
        val w = le16(d, at + 6) and 0x3FFF
        val h = le16(d, at + 8) and 0x3FFF
        return if (w == 0 || h == 0) null else w to h
    }

    private fun lossless(d: ByteArray, at: Int, size: Int): Pair<Int, Int>? {
        if (size < 5 || (d[at].toInt() and 255) != 0x2F) return null
        val bits = le32(d, at + 1)
        if ((bits ushr 29) != 0) return null
        return ((bits and 0x3FFF) + 1) to (((bits ushr 14) and 0x3FFF) + 1)
    }

    private fun tag(b: ByteArray, at: Int, name: String) = name.indices.all { b[at + it] == name[it].code.toByte() }
    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8)
    private fun le24(b: ByteArray, at: Int) = le16(b, at) or ((b[at + 2].toInt() and 255) shl 16)
    private fun le32(b: ByteArray, at: Int) = le24(b, at) or ((b[at + 3].toInt() and 255) shl 24)
}

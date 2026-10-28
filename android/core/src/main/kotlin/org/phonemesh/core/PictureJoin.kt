package org.phonemesh.core

/** Joins the parts of a picture received over wire v2. Pure: a picture that does not check out is null, never an exception. */
object PictureJoin {
    /**
     * The picture's bytes when every part [head] names is in [parts], all but the last are full ([partBytes]: the cut the sender used, never defaulted), and the
     * size and CRC-32 match; empty for a sketch-only picture; null otherwise. Parts may have arrived in any order and
     * extras beyond the head's count are ignored.
     */
    fun join(head: PictureWire.Head, parts: Map<Int, ByteArray>, partBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until head.parts) {
            val part = parts[i] ?: return null
            if (part.isEmpty() || part.size > partBytes || (i < head.parts - 1 && part.size != partBytes)) return null
            out.write(part)
        }
        val bytes = out.toByteArray()
        return bytes.takeIf { it.size == head.totalBytes && PictureWire.crc(it) == head.crc }
    }
}

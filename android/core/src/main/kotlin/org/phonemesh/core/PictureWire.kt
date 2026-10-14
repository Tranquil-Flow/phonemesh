package org.phonemesh.core

import java.util.zip.CRC32

/** How a picture's bytes are coded. Numbers travel on the radio; never reuse one. */
enum class PictureCodec(val wire: Int) {
    WEBP(1),
    /** No picture bytes: the blurred colour sketch in the HEAD is the whole picture. */
    SKETCH(2),
    ;

    companion object {
        fun of(wire: Int): PictureCodec? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Picture packets on Meshtastic port [PORT] (docs/specs/2026-10-12-picture-messages-design.md).
 * Byte 0 is version << 4 | kind, bytes 1-2 the picture id. Pure: no Android, no radio.
 */
object PictureWire {
    /** Private Meshtastic port (256-511); 256 itself is used by MeshTXT. */
    const val PORT = 300
    const val VERSION = 1
    const val PART_BYTES = 200
    const val MAX_PARTS = 16
    const val MAX_PICTURE_BYTES = PART_BYTES * MAX_PARTS
    const val MAX_SIDE = 512
    const val MAX_SKETCH_BYTES = 32
    private const val HEAD_FIXED = 16
    private const val PART_FIXED = 4
    const val MAX_PACKET_BYTES = PART_FIXED + PART_BYTES
    private const val KIND_HEAD = 0
    private const val KIND_PART = 1

    sealed class Packet { abstract val id: Int }

    data class Head(
        override val id: Int, val codec: PictureCodec, val width: Int, val height: Int, val totalBytes: Int, val parts: Int,
        val crc: Long, val sketch: ByteArray,
    ) : Packet() {
        override fun equals(other: Any?) = other is Head && id == other.id && codec == other.codec && width == other.width &&
            height == other.height && totalBytes == other.totalBytes && parts == other.parts && crc == other.crc &&
            sketch.contentEquals(other.sketch)
        override fun hashCode() = (id * 31 + totalBytes) * 31 + sketch.contentHashCode()
    }

    data class Part(override val id: Int, val index: Int, val data: ByteArray) : Packet() {
        override fun equals(other: Any?) = other is Part && id == other.id && index == other.index && data.contentEquals(other.data)
        override fun hashCode() = (id * 31 + index) * 31 + data.contentHashCode()
    }

    fun partsFor(totalBytes: Int): Int = (totalBytes + PART_BYTES - 1) / PART_BYTES

    fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** The HEAD then every PART, in order. Throws IllegalArgumentException for anything the wire cannot carry. */
    fun split(id: Int, codec: PictureCodec, width: Int, height: Int, picture: ByteArray, sketch: ByteArray): List<Packet> {
        require(id in 0..0xffff) { "picture id must fit 16 bits" }
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "picture is ${width}x$height; each side must be 1..$MAX_SIDE" }
        require(sketch.size in 1..MAX_SKETCH_BYTES) { "sketch is ${sketch.size} B; must be 1..$MAX_SKETCH_BYTES" }
        if (codec == PictureCodec.SKETCH) require(picture.isEmpty()) { "a sketch-only picture carries no bytes" }
        else require(picture.size in 1..MAX_PICTURE_BYTES) { "picture is ${picture.size} B; must be 1..$MAX_PICTURE_BYTES" }
        val count = partsFor(picture.size)
        val head = Head(id, codec, width, height, picture.size, count, crc(picture), sketch.copyOf())
        return listOf(head) + (0 until count).map { i ->
            Part(id, i, picture.copyOfRange(i * PART_BYTES, minOf(picture.size, (i + 1) * PART_BYTES)))
        }
    }

    fun encode(packet: Packet): ByteArray = when (packet) {
        is Head -> ByteArray(HEAD_FIXED + packet.sketch.size).also { b ->
            b[0] = ((VERSION shl 4) or KIND_HEAD).toByte()
            put16(b, 1, packet.id)
            b[3] = packet.codec.wire.toByte()
            put16(b, 4, packet.width); put16(b, 6, packet.height); put16(b, 8, packet.totalBytes)
            b[10] = packet.parts.toByte()
            for (i in 0 until 4) b[11 + i] = (packet.crc ushr (24 - 8 * i)).toByte()
            b[15] = packet.sketch.size.toByte()
            packet.sketch.copyInto(b, HEAD_FIXED)
        }
        is Part -> ByteArray(PART_FIXED + packet.data.size).also { b ->
            b[0] = ((VERSION shl 4) or KIND_PART).toByte()
            put16(b, 1, packet.id)
            b[3] = packet.index.toByte()
            packet.data.copyInto(b, PART_FIXED)
        }
    }

    /** Total: anything malformed, oversized, of another version or beyond the limits is null, never an exception. */
    fun decode(bytes: ByteArray): Packet? {
        if (bytes.size < PART_FIXED || bytes.size > MAX_PACKET_BYTES) return null
        val first = bytes[0].toInt() and 0xff
        if (first ushr 4 != VERSION) return null
        val id = get16(bytes, 1)
        return when (first and 0x0f) {
            KIND_HEAD -> head(id, bytes)
            KIND_PART -> {
                val index = bytes[3].toInt() and 0xff
                if (index >= MAX_PARTS || bytes.size == PART_FIXED) null
                else Part(id, index, bytes.copyOfRange(PART_FIXED, bytes.size))
            }
            else -> null
        }
    }

    private fun head(id: Int, b: ByteArray): Head? {
        if (b.size < HEAD_FIXED) return null
        val codec = PictureCodec.of(b[3].toInt() and 0xff) ?: return null
        val width = get16(b, 4)
        val height = get16(b, 6)
        val total = get16(b, 8)
        val parts = b[10].toInt() and 0xff
        var crc = 0L
        for (i in 0 until 4) crc = (crc shl 8) or (b[11 + i].toLong() and 0xff)
        val sketchSize = b[15].toInt() and 0xff
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        if (sketchSize !in 1..MAX_SKETCH_BYTES || b.size != HEAD_FIXED + sketchSize) return null
        val sizeOk = if (codec == PictureCodec.SKETCH) total == 0 else total in 1..MAX_PICTURE_BYTES
        if (!sizeOk || parts != partsFor(total)) return null
        return Head(id, codec, width, height, total, parts, crc, b.copyOfRange(HEAD_FIXED, b.size))
    }

    private fun put16(b: ByteArray, at: Int, v: Int) { b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte() }
    private fun get16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
}

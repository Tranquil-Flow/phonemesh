package org.phonemesh.core.account

import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureJoin
import org.phonemesh.core.PictureWire
import java.security.MessageDigest

/**
 * Photo avatars (profile decision 50 D, owner pick 2026-10-27): a WebP of at most [MAX_SIDE] px a side and [MAX_BYTES]
 * bytes, sent end to end to each contact as picture pieces in their own body kind (`Body.AvatarPiece`), so the
 * existing picture encoding and checks carry it but it never becomes a chat picture. The profile names it by
 * [Avatar.Photo.id], the first [Profile.PHOTO_ID] bytes of its SHA-256; a phone shows it only when the bytes it holds
 * hash to that id, and shows the letter until then.
 */
object AvatarPhotos {
    const val MAX_SIDE = 64
    const val MAX_BYTES = 1024

    fun id(webp: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(webp).copyOf(Profile.PHOTO_ID)

    /** The encoded pieces (head, then parts) for [webp], [width] x [height], with its colour [sketch]. */
    fun pieces(webp: ByteArray, width: Int, height: Int, sketch: ByteArray, group: Boolean = false): List<ByteArray> {
        require(webp.size in 1..MAX_BYTES) { "a photo avatar is 1..$MAX_BYTES bytes" }
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "a photo avatar is at most $MAX_SIDE px a side" }
        val id = id(webp)
        val pictureId = ((id[0].toInt() and 0xFF) shl 8) or (id[1].toInt() and 0xFF)
        val packets = if (group) PictureWire.splitGroup(pictureId, PictureCodec.WEBP, width, height, webp, sketch)
            else PictureWire.splitV2(pictureId, PictureCodec.WEBP, width, height, webp, sketch)
        return packets.map { PictureWire.encodeV2(it) }
    }

    /** A complete, checked photo. */
    class Photo(webp: ByteArray, val width: Int, val height: Int) {
        val webp: ByteArray = webp.copyOf()
        val id: ByteArray get() = id(webp)
    }

    /**
     * The photo [pieces] make ([group]: pieces sized for a group packet), or null while any is missing or when they do not make an avatar: not one head, a
     * different picture's part, not WebP, too big, or a CRC that fails. Duplicates are fine; order does not matter.
     */
    fun join(pieces: Collection<ByteArray>, group: Boolean = false): Photo? {
        val decoded = pieces.mapNotNull { if (group) PictureWire.decodeGroup(it) else PictureWire.decodeV2(it) }
        if (decoded.size != pieces.size) return null
        val head = decoded.filterIsInstance<PictureWire.Head>().distinct().singleOrNull() ?: return null
        if (head.codec != PictureCodec.WEBP || head.totalBytes !in 1..MAX_BYTES) return null
        if (head.width !in 1..MAX_SIDE || head.height !in 1..MAX_SIDE) return null
        val parts = HashMap<Int, ByteArray>()
        for (p in decoded.filterIsInstance<PictureWire.Part>()) {
            if (p.id != head.id || p.index >= head.parts) return null
            val earlier = parts.put(p.index, p.data)
            if (earlier != null && !earlier.contentEquals(p.data)) return null
        }
        if (parts.size != head.parts) return null
        val webp = PictureJoin.join(head, parts, if (group) PictureWire.PART_BYTES_GROUP else PictureWire.PART_BYTES_V2) ?: return null
        val id = id(webp)
        if ((((id[0].toInt() and 0xFF) shl 8) or (id[1].toInt() and 0xFF)) != head.id) return null
        return Photo(webp, head.width, head.height)
    }
}

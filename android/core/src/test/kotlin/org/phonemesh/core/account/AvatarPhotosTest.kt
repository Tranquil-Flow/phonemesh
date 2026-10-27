package org.phonemesh.core.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureWire
import org.phonemesh.core.wire.Body
import java.security.MessageDigest

/** Photo avatars (decision 50 D): bounded, named by their SHA-256, joined only when every piece checks out. */
class AvatarPhotosTest {
    // Not a real WebP: the core never decodes pixels, the app does (and refuses what Android cannot draw).
    private val webp = ByteArray(700) { (it * 31 + 7).toByte() }
    private val sketch = ByteArray(24) { it.toByte() }

    @Test fun aPhotoBecomesAHeadAndFourPartsAndJoinsBack() {
        val pieces = AvatarPhotos.pieces(webp, 64, 64, sketch)
        assertEquals("head + ceil(700 / 198) parts", 1 + 4, pieces.size)
        val photo = AvatarPhotos.join(pieces.reversed() + pieces[2])!!
        assertArrayEquals(webp, photo.webp)
        assertEquals(64, photo.width)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(webp).copyOf(8), photo.id)
        assertEquals(Avatar.Photo(photo.id), Avatar.Photo(AvatarPhotos.id(webp)))
        for (piece in pieces) assertTrue(piece.size <= PictureWire.PART_BYTES_V2 + 4)
    }

    @Test fun missingChangedOrForeignPiecesNeverJoin() {
        val pieces = AvatarPhotos.pieces(webp, 64, 64, sketch)
        assertNull("a part missing", AvatarPhotos.join(pieces.dropLast(1)))
        assertNull("no head", AvatarPhotos.join(pieces.drop(1)))
        val changed = pieces.toMutableList().also { it[2] = it[2].copyOf().apply { this[10] = (this[10].toInt() xor 1).toByte() } }
        assertNull("a changed byte fails the CRC", AvatarPhotos.join(changed))
        val other = AvatarPhotos.pieces(ByteArray(700) { 3 }, 64, 64, sketch)
        assertNull("another photo's part", AvatarPhotos.join(pieces.dropLast(1) + other.last()))
        assertNull("two heads", AvatarPhotos.join(pieces + other.first()))
        assertNull("not picture bytes", AvatarPhotos.join(pieces + byteArrayOf(1, 2, 3)))
        // A chat-sized picture is not an avatar, even when its pieces are well formed.
        val big = PictureWire.splitV2(9, PictureCodec.WEBP, 128, 128, ByteArray(1500) { 1 }, sketch).map { PictureWire.encodeV2(it) }
        assertNull(AvatarPhotos.join(big))
    }

    @Test fun onlyBoundedPhotosAreSent() {
        assertTrue(runCatching { AvatarPhotos.pieces(ByteArray(1025), 64, 64, sketch) }.isFailure)
        assertTrue(runCatching { AvatarPhotos.pieces(ByteArray(0), 64, 64, sketch) }.isFailure)
        assertTrue(runCatching { AvatarPhotos.pieces(webp, 65, 64, sketch) }.isFailure)
        assertNotNull(AvatarPhotos.join(AvatarPhotos.pieces(ByteArray(1024) { 5 }, 48, 48, sketch)))
    }

    @Test fun avatarPiecesAreTheirOwnBodyKind() {
        for (piece in AvatarPhotos.pieces(webp, 64, 64, sketch)) {
            val body = Body.AvatarPiece(piece)
            val encoded = Body.encode(body)
            assertEquals(9, encoded[0].toInt())
            val back = Body.decode(encoded) as Body.AvatarPiece
            assertArrayEquals(piece, back.bytes)
            assertTrue("never a chat picture", Body.decode(encoded) !is Body.PictureHead && Body.decode(encoded) !is Body.PicturePart)
        }
        assertNull(Body.decode(byteArrayOf(9)))
        assertNull(Body.decode(byteArrayOf(9, 1, 2, 3)))
        assertTrue(runCatching { Body.encode(Body.AvatarPiece(byteArrayOf(1, 2))) }.isFailure)
    }
}

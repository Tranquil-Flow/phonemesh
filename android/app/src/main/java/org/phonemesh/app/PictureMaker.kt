package org.phonemesh.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureWire
import org.phonemesh.core.ThumbHash
import org.phonemesh.core.WebpContainer
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Picture sizes: longest side and the byte budget it aims for. About 8, 14, 27 and 40 seconds on the radio at LongFast
 * (owner's choice, 2026-10-14); the largest fills most of a picture's 16 pieces.
 */
enum class PictureSize(val side: Int, val budget: Int, val label: Int) {
    MEDIUM(96, 500, R.string.picture_medium),
    LARGE(128, 1000, R.string.picture_large),
    VERY_LARGE(192, 2000, R.string.picture_very_large),
    MAXIMUM(256, 3000, R.string.picture_maximum),
}

/** What the other phone will get: [bytes] (empty for a sketch only), [sketch], and [preview], the same pixels decoded here. */
class PreparedPicture(
    val codec: PictureCodec, val width: Int, val height: Int, val bytes: ByteArray, val sketch: ByteArray, val preview: Bitmap,
    val quality: Int,
)

/**
 * Turns a photo into a radio picture. It is re-encoded from pixels, so no location or camera data leaves the phone.
 * Uses only what Android has: BitmapFactory, ExifInterface (framework) and the platform WebP encoder.
 */
object PictureMaker {
    /**
     * Reads [uri] at a size close to [maxSide] (twice it, for a clean shrink), upright per its EXIF orientation. Null when
     * the file cannot be opened or is not a picture Android can read. Large photos are read already shrunk.
     */
    fun load(resolver: ContentResolver, uri: Uri, maxSide: Int = LOAD_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Measuring only: decodeStream returns null here by design and fills bounds, so its result is not a failure.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees() } ?: 0
        }.getOrDefault(0)
        if (rotation == 0) return bitmap
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private fun ExifInterface.rotationDegrees(): Int = when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /**
     * The picture to send from [source]: shrunk to [size], grey when [grey], WebP at the best quality that fits the
     * size's budget (smaller still when even the lowest quality does not fit).
     */
    fun prepare(source: Bitmap, size: PictureSize, grey: Boolean): PreparedPicture {
        var side = size.side
        while (true) {
            val small = shrink(source, side, grey)
            val sketch = sketchOf(small)
            val fitted = bestUnder(small, size.budget)
            if (fitted != null || side <= MIN_SIDE) {
                val (bytes, quality) = fitted ?: (webp(small, 0) to 0)
                require(bytes.size <= PictureWire.MAX_PICTURE_BYTES) { "picture too large for the radio" }
                val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: small
                return PreparedPicture(PictureCodec.WEBP, small.width, small.height, bytes, sketch, preview, quality)
            }
            side = (side * 0.85f).roundToInt()
        }
    }

    /** The highest WebP quality whose file fits [budget]; null when not even quality 0 does. */
    private fun bestUnder(bitmap: Bitmap, budget: Int): Pair<ByteArray, Int>? {
        var low = 0
        var high = 100
        var best: Pair<ByteArray, Int>? = null
        while (low <= high) {
            val q = (low + high) / 2
            val bytes = webp(bitmap, q)
            if (bytes.size <= budget) { best = bytes to q; low = q + 1 } else high = q - 1
        }
        return best
    }

    fun webp(bitmap: Bitmap, quality: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        // Below API 30, WEBP is lossy for any quality under 100.
        val encoded = ByteArrayOutputStream().also { bitmap.compress(format, quality.coerceIn(0, 99), it) }.toByteArray()
        // Android adds a ~500-byte colour profile; on the radio that would cost more than the picture itself.
        return WebpContainer.minimal(encoded)
    }

    /** [source] with its longest side [side] (never enlarged), in two halving steps or fewer for a clean result. */
    fun shrink(source: Bitmap, side: Int, grey: Boolean): Bitmap {
        val scale = minOf(1f, side.toFloat() / max(source.width, source.height))
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        var current = source
        while (current.width / 2 >= w * 2 && current.height / 2 >= h * 2) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (grey) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        Canvas(out).drawBitmap(current, null, android.graphics.Rect(0, 0, w, h), paint)
        // Photos have no transparency; saying so keeps the encoder from adding an alpha layer.
        out.setHasAlpha(false)
        return out
    }

    /** The 20-25 byte sketch of [bitmap] (shrunk to fit 100 px first, as ThumbHash requires). */
    fun sketchOf(bitmap: Bitmap): ByteArray {
        val small = if (max(bitmap.width, bitmap.height) > 100) shrink(bitmap, 100, grey = false) else bitmap
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val rgba = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { i, c ->
            rgba[i * 4] = (c shr 16).toByte(); rgba[i * 4 + 1] = (c shr 8).toByte(); rgba[i * 4 + 2] = c.toByte(); rgba[i * 4 + 3] = (c ushr 24).toByte()
        }
        return ThumbHash.encode(small.width, small.height, rgba)
    }

    fun bitmapOf(image: ThumbHash.Image): Bitmap {
        val pixels = IntArray(image.width * image.height) { i ->
            val r = image.rgba[i * 4].toInt() and 255; val g = image.rgba[i * 4 + 1].toInt() and 255
            val b = image.rgba[i * 4 + 2].toInt() and 255; val a = image.rgba[i * 4 + 3].toInt() and 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    /** A received picture's pixels, or its sketch; null when neither decodes. */
    fun decode(codec: Int, bytes: ByteArray?, sketch: ByteArray): Bitmap? {
        if (codec == PictureCodec.WEBP.wire && bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        return ThumbHash.decode(sketch)?.let { bitmapOf(it) }
    }

    private const val LOAD_SIDE = 512
    private const val MIN_SIDE = 32
}

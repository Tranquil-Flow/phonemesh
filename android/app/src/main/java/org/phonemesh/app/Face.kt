package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import org.phonemesh.core.account.Avatar
import org.phonemesh.core.account.Profile
import org.phonemesh.core.account.ProfileColours

/**
 * What a person's round picture shows (spec decisions 48-50): their colour, and their first letter, an emoji or a
 * photo. Nothing here is proof of who they are: the safety pictures are the check.
 */
class Face(val name: String, val colour: Int, val avatar: Avatar, val photo: Bitmap? = null) {
    /** This face with [photo] drawn in place of the letter; itself when there is none. */
    fun withPhoto(photo: Bitmap?): Face = if (photo == null) this else Face(name, colour, avatar, photo)

    /** The letter or emoji to draw; null draws the person icon (no letter in the name, or a phone that cannot draw it). */
    fun mark(canDraw: (String) -> Boolean): String? = when (val a = avatar) {
        is Avatar.Emoji -> a.text.takeIf(canDraw) ?: Profile.initial(name)
        else -> Profile.initial(name)
    }

    override fun equals(other: Any?) = other is Face && name == other.name && colour == other.colour && avatar == other.avatar &&
        (photo == null) == (other.photo == null)
    override fun hashCode() = (name.hashCode() * 31 + colour) * 31 + avatar.hashCode()

    companion object {
        fun of(profile: Profile) = Face(profile.name, profile.colour, profile.avatar)

        /** A contact: their newest profile, or their saved name and colour with a letter until one arrives. */
        fun of(row: History.ContactRow): Face =
            row.profile?.let { Profile.decode(it) }?.let { of(it) } ?: Face(row.name, row.colour and 0xFFFFFF, Avatar.Letter)
    }
}

/**
 * A [Face] as one drawable: the colour as a disc with an outline, then the first letter in a colour that stays
 * readable on it, or an emoji on a light inner disc so it never disappears into a matching colour. Drawn, not laid
 * out, so an avatar adds no text view to a screen.
 */
class FaceDrawable(private val face: Face, private val outline: Int, private val strokePx: Float, canDraw: (String) -> Boolean) : Drawable() {
    private val mark = face.mark(canDraw)
    private val emoji = face.avatar is Avatar.Emoji && mark == (face.avatar as Avatar.Emoji).text
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() or face.colour }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = outline; strokeWidth = strokePx }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INNER }
    private val photo = face.photo?.let { p ->
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(p, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    /** Whether a photo is drawn (then no letter, emoji or person icon is). */
    val photoShown: Boolean get() = photo != null
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt() or ProfileColours.letterOn(face.colour)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    /** The letter or emoji drawn; null when the person icon is drawn instead (the caller adds it). */
    val drawn: String? get() = mark

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(cx, cy, r - strokePx / 2, fill)
        val picture = face.photo
        if (photo != null && picture != null) {
            val inside = r - strokePx
            val scale = 2 * inside / minOf(picture.width, picture.height)
            photo.shader.setLocalMatrix(android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - picture.width * scale / 2, cy - picture.height * scale / 2)
            })
            canvas.drawCircle(cx, cy, inside, photo)
            canvas.drawCircle(cx, cy, r - strokePx / 2, ring)
            return
        }
        if (emoji) canvas.drawCircle(cx, cy, r * 0.74f, inner)
        canvas.drawCircle(cx, cy, r - strokePx / 2, ring)
        val mark = mark ?: return
        text.textSize = if (emoji) r * 0.9f else r * 1.0f
        val metrics = text.fontMetrics
        canvas.drawText(mark, cx, cy - (metrics.ascent + metrics.descent) / 2, text)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; text.alpha = alpha; inner.alpha = alpha; ring.alpha = alpha; photo?.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { text.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** The light disc behind an emoji. */
        const val INNER = 0xFFFAFAFA.toInt()

        /** Whether this phone's fonts draw [text] as one picture (no empty box, no pieces). */
        fun canDraw(text: String): Boolean = Paint().hasGlyph(text)
    }
}

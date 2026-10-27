package org.phonemesh.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The free colour wheel (spec decision 48): the hue goes round the circle, the colour grows stronger towards the edge,
 * and [lightness] (from a slider beside it) makes all of it lighter or darker. Touching or dragging picks a colour and
 * calls [onPick] with it as RGB. TalkBack users pick from the named colours instead.
 */
@SuppressLint("ViewConstructor")
class ColourWheel(context: Context, private val outline: Int, private val onPick: (Int) -> Unit) : View(context) {
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val markerInside = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var hue = 0f
    private var saturation = 0f

    /** 0.25 (dark) .. 1 (full brightness). */
    var lightness = 0.9f
        set(value) { field = value.coerceIn(MIN_LIGHTNESS, 1f); invalidate() }

    /** The colour now picked, RGB. */
    val rgb: Int get() = Color.HSVToColor(floatArrayOf(hue, saturation, lightness)) and 0xFFFFFF

    /** Shows [rgb] on the wheel without calling [onPick]. */
    fun show(rgb: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(0xFF000000.toInt() or rgb, hsv)
        hue = hsv[0]; saturation = hsv[1]; lightness = hsv[2]
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f; val cy = h / 2f; val r = radius()
        val hues = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
        // Android's sweep starts at three o'clock and goes clockwise; angles below use the same convention.
        val sweep = SweepGradient(cx, cy, hues, null)
        val white = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
        wheel.shader = ComposeShader(sweep, white, PorterDuff.Mode.SRC_OVER)
        marker.strokeWidth = Ui.dp(context, 3).toFloat()
        markerInside.strokeWidth = Ui.dp(context, 2).toFloat()
    }

    /** Inside the view by the marker's size, so the marker is never cut at the edge. */
    private fun radius() = min(width, height) / 2f - Ui.dp(context, MARKER_DP + 2)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = radius()
        canvas.drawCircle(cx, cy, r, wheel)
        shade.color = Color.argb(((1f - lightness) * 255).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy, r, shade)
        shade.color = outline
        val angle = Math.toRadians(hue.toDouble())
        val mx = cx + (cos(angle) * saturation * r).toFloat()
        val my = cy + (sin(angle) * saturation * r).toFloat()
        val dot = Ui.dp(context, MARKER_DP).toFloat()
        marker.color = Color.BLACK
        canvas.drawCircle(mx, my, dot, marker)
        markerInside.color = Color.WHITE
        canvas.drawCircle(mx, my, dot - Ui.dp(context, 2), markerInside)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pick(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> { pick(event.x, event.y); performClick() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** Picks the colour at ([x], [y]); a touch outside the circle takes the edge in that direction. */
    fun pick(x: Float, y: Float) {
        val dx = x - width / 2f; val dy = y - height / 2f
        saturation = (hypot(dx, dy) / radius()).coerceIn(0f, 1f)
        hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
        invalidate()
        onPick(rgb)
    }

    companion object {
        const val MIN_LIGHTNESS = 0.25f
        private const val MARKER_DP = 12
    }
}

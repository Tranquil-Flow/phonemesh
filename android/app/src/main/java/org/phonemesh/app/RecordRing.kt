package org.phonemesh.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.phonemesh.core.RecordGesture

/** A red clock ring that fills toward the 15 s limit while recording; [startedAt] 0 hides it. */
class RecordRing(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()

    var startedAt = 0L
        set(value) {
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (startedAt == 0L) return
        val stroke = width * 0.08f
        paint.strokeWidth = stroke
        paint.color = color
        bounds.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        val fraction = RecordGesture.ringFraction(SystemClock.elapsedRealtime() - startedAt)
        paint.alpha = 70
        canvas.drawArc(bounds, 0f, 360f, false, paint)
        paint.alpha = 140
        val cx = width / 2f
        val cy = height / 2f
        val outer = width / 2f - stroke / 2
        for (i in 0 until 12) {
            val angle = Math.toRadians(i * 30.0)
            val sx = cx + (outer - stroke * 1.2f) * kotlin.math.sin(angle).toFloat()
            val sy = cy - (outer - stroke * 1.2f) * kotlin.math.cos(angle).toFloat()
            val ex = cx + (outer - stroke * 2.2f) * kotlin.math.sin(angle).toFloat()
            val ey = cy - (outer - stroke * 2.2f) * kotlin.math.cos(angle).toFloat()
            canvas.drawLine(sx, sy, ex, ey, paint)
        }
        paint.alpha = 255
        canvas.drawArc(bounds, -90f, 360f * fraction, false, paint)
        // A clock needs only coarse progress updates. Leave time between traversals for input and idle observers.
        if (fraction < 1f) postInvalidateDelayed(CLOCK_TICK_MS)
    }

    private companion object {
        const val CLOCK_TICK_MS = 250L
    }
}

/**
 * Three dots that swell in turn on the microphone: the words are being written. They move only while shown,
 * and stay three even dots when animations are off.
 */
class WritingDots(context: Context, color: Int) : View(context) {
    val isRunning: Boolean get() = running
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var running = false
    private var startedAt = 0L
    // Coarse steps, like the ring: time is left between them for input and idle observers.
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, TICK_MS)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && StoryView.animationsEnabled()) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.uptimeMillis()
        postDelayed(tick, TICK_MS)
    }

    private fun stop() {
        running = false
        removeCallbacks(tick)
    }

    override fun onDraw(canvas: Canvas) {
        val turn = (SystemClock.uptimeMillis() - startedAt) % LOOP_MS * 3f / LOOP_MS
        for (i in 0 until 3) {
            // Each dot has a third of the loop to swell and shrink back.
            val swell = if (running) kotlin.math.sin(Math.PI * (turn - i).coerceIn(0f, 1f)).toFloat() else 0.5f
            canvas.drawCircle(width * (0.26f + 0.24f * i), height / 2f, width * (0.07f + 0.04f * swell), paint)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LOOP_MS = 900L
        const val TICK_MS = 50L
    }
}

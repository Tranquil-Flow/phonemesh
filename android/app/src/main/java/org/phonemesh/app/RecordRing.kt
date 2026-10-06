package org.phonemesh.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.phonemesh.core.RecordGesture

/** A red ring that fills toward the one-part limit while recording; [startedAt] 0 hides it. */
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
        paint.alpha = 255
        canvas.drawArc(bounds, -90f, 360f * fraction, false, paint)
        // Delayed, not per-frame: a frame loop keeps the main thread from ever idling (tests wait for idle).
        if (fraction < 1f) postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        const val FRAME_MS = 33L
    }
}

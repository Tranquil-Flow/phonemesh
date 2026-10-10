package org.phonemesh.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.phonemesh.core.FlagSpec
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Draws a [FlagSpec] into a rectangle; null draws a plain globe. No image files. Main thread only. */
object FlagPainter {
    private const val GLOBE = 0xFF4FC3F7.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private val path = Path()

    fun draw(canvas: Canvas, flag: FlagSpec?, rect: RectF, alpha: Int = 255) {
        if (flag == null) { globe(canvas, rect, alpha); return }
        val n = flag.stripes.size
        flag.stripes.forEachIndexed { i, color ->
            if (flag.vertical) band(canvas, color, alpha, rect.left + rect.width() * i / n, rect.top, rect.left + rect.width() * (i + 1) / n, rect.bottom)
            else band(canvas, color, alpha, rect.left, rect.top + rect.height() * i / n, rect.right, rect.top + rect.height() * (i + 1) / n)
        }
        // The star goes in the canton or the hoist band when there is one, else in the middle.
        box.set(rect)
        flag.canton?.let { color ->
            val side = rect.height() * 0.6f
            box.set(rect.left, rect.top, rect.left + side, rect.top + side)
            band(canvas, color, alpha, box.left, box.top, box.right, box.bottom)
        }
        flag.hoist?.let { color ->
            box.set(rect.left, rect.top, rect.left + rect.width() * 0.4f, rect.bottom)
            band(canvas, color, alpha, box.left, box.top, box.right, box.bottom)
        }
        flag.star?.let { color -> star(canvas, color, alpha, box.centerX(), box.centerY(), min(box.width(), box.height()) * 0.32f) }
        flag.starRing?.let { ring ->
            val r = rect.height() * 0.32f
            repeat(ring.count) { i ->
                val a = i * 2 * Math.PI / ring.count
                star(canvas, ring.color, alpha, rect.centerX() + (cos(a) * r).toFloat(), rect.centerY() + (sin(a) * r).toFloat(), rect.height() * 0.06f)
            }
        }
    }

    private fun band(canvas: Canvas, color: Int, alpha: Int, left: Float, top: Float, right: Float, bottom: Float) {
        fill.color = color
        fill.alpha = alpha
        canvas.drawRect(left, top, right, bottom, fill)
    }

    private fun star(canvas: Canvas, color: Int, alpha: Int, cx: Float, cy: Float, r: Float) {
        path.rewind()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) r else r * 0.4f
            val a = -Math.PI / 2 + i * Math.PI / 5
            val x = cx + (cos(a) * radius).toFloat()
            val y = cy + (sin(a) * radius).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        fill.color = color
        fill.alpha = alpha
        canvas.drawPath(path, fill)
    }

    private fun globe(canvas: Canvas, rect: RectF, alpha: Int) {
        val r = min(rect.width(), rect.height()) * 0.42f
        val cx = rect.centerX()
        val cy = rect.centerY()
        fill.color = GLOBE
        fill.alpha = alpha
        canvas.drawCircle(cx, cy, r, fill)
        line.color = HowToKit.C.INK
        line.alpha = alpha
        line.strokeWidth = r * 0.08f
        canvas.drawCircle(cx, cy, r, line)
        box.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        canvas.drawOval(box, line)
        canvas.drawLine(cx - r, cy, cx + r, cy, line)
    }
}

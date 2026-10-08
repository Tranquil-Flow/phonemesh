package org.phonemesh.app

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.phonemesh.core.StoryFrame
import org.phonemesh.core.StoryRing
import org.phonemesh.core.StoryTimeline
import kotlin.math.roundToInt

/** The approved wax-print drawing in scene units (300 × 170). Paths and paints are reused. */
class StoryScene {
    private object Wax {
        val OUTLINE = 0xFF2B1A10.toInt()
        const val LINE = 2.4f
        val SKY_TOP = 0xFFFF9F43.toInt()
        val SKY_BOTTOM = 0xFFFFE29A.toInt()
        val SUN_HALO = 0xFFFFD54F.toInt()
        val SUN = 0xFFFFEB3B.toInt()
        val SUN_CORE = 0xFFFF9F43.toInt()
        val HILL_FAR = 0xFF66A640.toInt()
        val HILL_NEAR = 0xFF2E7D32.toInt()
        val GROUND = 0xFFC4622D.toInt()
        val GROUND_LINE = 0xFF8D3D14.toInt()
        val WALL = 0xFFF3D19C.toInt()
        val ROOF = 0xFF8D3D14.toInt()
        val DOOR = 0xFF5D3A1A.toInt()
        val YELLOW = 0xFFFFD54F.toInt()
        val RED = 0xFFC62828.toInt()
        val GREEN = 0xFF1B5E20.toInt()
        val INDIGO = 0xFF283593.toInt()
        val ORANGE = 0xFFFF7043.toInt()
        val SKIN_HER = 0xFFB97A4C.toInt()
        val SKIN_HIM = 0xFF7A4A2C.toInt()
        val WRAP = 0xFFD84315.toInt()
        val RADIO = 0xFFFFCA28.toInt()
        val RADIO_SCREEN = 0xFFFFF3C4.toInt()
        val PHONE = 0xFF1D1D1D.toInt()
        val PHONE_SCREEN = 0xFFFF7043.toInt()
        val LIGHT_ON = 0xFF76FF03.toInt()
        val LIGHT_OFF = 0xFF555555.toInt()
        val BUBBLE = 0xFFFFF8E1.toInt()
        val MOUTH = 0xFF4A1410.toInt()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sky = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 140f, Wax.SKY_TOP, Wax.SKY_BOTTOM, Shader.TileMode.CLAMP)
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Wax.LINE
        strokeCap = Paint.Cap.ROUND
        color = Wax.GREEN
        pathEffect = DashPathEffect(floatArrayOf(5f, 6f), 0f)
    }
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Wax.OUTLINE
        textSize = 13f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private val rect = RectF()
    private val arm = Path()
    private val roof = Path()
    private val hillFar = Path().apply {
        moveTo(0f, 124f); quadTo(60f, 104f, 120f, 120f); quadTo(180f, 136f, 240f, 114f); quadTo(300f, 92f, 300f, 116f)
        lineTo(300f, 140f); lineTo(0f, 140f); close()
    }
    private val hillNear = Path().apply {
        moveTo(0f, 132f); quadTo(70f, 116f, 150f, 130f); quadTo(230f, 144f, 300f, 126f)
        lineTo(300f, 140f); lineTo(0f, 140f); close()
    }
    private val zigzagYellow = Path()
    private val zigzagRed = Path()
    private val groundLines = Path().apply {
        moveTo(20f, 148f); lineTo(30f, 148f); moveTo(60f, 152f); lineTo(74f, 152f)
        moveTo(226f, 150f); lineTo(238f, 150f); moveTo(268f, 152f); lineTo(278f, 152f)
    }
    private val herBody = Path().apply { moveTo(16f, 140f); cubicTo(16f, 114f, 27f, 96f, 40f, 96f); cubicTo(53f, 96f, 64f, 114f, 64f, 140f) }
    private val hisBody = Path().apply { moveTo(236f, 140f); cubicTo(236f, 114f, 247f, 96f, 260f, 96f); cubicTo(273f, 96f, 284f, 114f, 284f, 140f) }
    private val headwrap = Path().apply { moveTo(25f, 60f); quadTo(40f, 36f, 55f, 60f); quadTo(49f, 49f, 40f, 49f); quadTo(31f, 49f, 25f, 60f); close() }
    private val cap = Path().apply { moveTo(246f, 58f); quadTo(260f, 42f, 274f, 58f); close() }
    private val collar = Path().apply { moveTo(252f, 98f); lineTo(260f, 108f); lineTo(268f, 98f) }
    private val smile = Path().apply { moveTo(250f, 70f); quadTo(252.5f, 72f, 255f, 70f) }
    private val arc = Path().apply { moveTo(112f, 80f); quadTo(150f, 30f, 188f, 80f) }
    private val waves = arrayOf(
        Path().apply { moveTo(228f, 54f); cubicTo(224f, 58f, 224f, 64f, 228f, 68f) },
        Path().apply { moveTo(221f, 50f); cubicTo(214f, 57f, 214f, 65f, 221f, 72f) },
        Path().apply { moveTo(214f, 46f); cubicTo(204f, 56f, 204f, 66f, 214f, 76f) },
    )
    private val barX = floatArrayOf(72f, 78f, 84f, 90f)
    private val barTop = floatArrayOf(56f, 52f, 55f, 50f)
    private val barBottom = floatArrayOf(64f, 68f, 65f, 70f)

    init {
        // SVG userSpaceOnUse tiles start at multiples of 14, not at the band's y=156.
        var y = 154f
        while (y < 170f) {
            var x = 0f
            while (x < 300f) {
                zigzagYellow.moveTo(x, y + 14f); zigzagYellow.lineTo(x + 10f, y + 2f); zigzagYellow.lineTo(x + 20f, y + 14f); zigzagYellow.close()
                zigzagRed.moveTo(x + 5f, y + 14f); zigzagRed.lineTo(x + 10f, y + 8f); zigzagRed.lineTo(x + 15f, y + 14f); zigzagRed.close()
                x += 20f
            }
            y += 14f
        }
    }

    fun draw(canvas: Canvas, f: StoryFrame) {
        backdrop(canvas)
        rings(canvas, f.sendRings, 112f)
        rings(canvas, f.receiveRings, 188f)
        if (f.arcAlpha > 0f) {
            dashed.alpha = alpha(f.arcAlpha)
            canvas.drawPath(arc, dashed)
        }
        her(canvas, f)
        arm(canvas, Wax.SKIN_HER, 46f, 104f, 56f + f.herPhoneDx * .5f, 96f + f.herPhoneDy * .3f, 60f + f.herPhoneDx, 70f + f.herPhoneDy)
        phone(canvas, 55f, 54f, f.herPhoneDx, f.herPhoneDy, f.herPhoneAngle, 60f, 63f)
        bars(canvas, f)
        radio(canvas, 92f, 98f, f.herLight)
        canvas.drawLine(110f, 98f, 112f, 80f, stroke(Wax.OUTLINE, Wax.LINE))
        radio(canvas, 184f, 190f, f.hisLight)
        canvas.drawLine(190f, 98f, 188f, 80f, stroke(Wax.OUTLINE, Wax.LINE))
        him(canvas)
        arm(canvas, Wax.SKIN_HIM, 254f, 104f, 244f + f.hisPhoneDx * .5f, 96f + f.hisPhoneDy * .3f, 240f + f.hisPhoneDx, 68f + f.hisPhoneDy)
        phone(canvas, 235f, 52f, f.hisPhoneDx, f.hisPhoneDy, f.hisPhoneAngle, 240f, 61f)
        for (i in waves.indices) if (f.wavesAlpha[i] > 0f) canvas.drawPath(waves[i], stroke(Wax.GREEN, 2.6f, f.wavesAlpha[i]))
        bubble(canvas, f)
    }

    private fun backdrop(canvas: Canvas) {
        canvas.drawRect(0f, 0f, 300f, 140f, sky)
        canvas.drawRect(0f, 140f, 300f, 170f, paint(Wax.GROUND))
        canvas.drawCircle(150f, 38f, 24f, paint(Wax.SUN_HALO, .55f))
        canvas.drawCircle(150f, 38f, 17f, paint(Wax.SUN))
        canvas.drawCircle(150f, 38f, 10f, paint(Wax.SUN_CORE))
        canvas.drawRect(0f, 156f, 300f, 170f, paint(Wax.OUTLINE))
        canvas.save()
        canvas.clipRect(0f, 156f, 300f, 170f)
        canvas.drawPath(zigzagYellow, paint(Wax.YELLOW))
        canvas.drawPath(zigzagRed, paint(Wax.RED))
        canvas.restore()
        canvas.drawPath(hillFar, paint(Wax.HILL_FAR))
        canvas.drawPath(hillNear, paint(Wax.HILL_NEAR))
        house(canvas, 134f, 121f)
        house(canvas, 156f, 123f)
        canvas.drawPath(groundLines, stroke(Wax.GROUND_LINE, 1.5f))
    }

    private fun house(canvas: Canvas, x: Float, y: Float) {
        canvas.drawRect(x, y, x + 12f, y + 10f, paint(Wax.WALL))
        canvas.drawRect(x, y, x + 12f, y + 10f, stroke(Wax.DOOR, 1.2f))
        roof.rewind()
        roof.moveTo(x - 2f, y); roof.lineTo(x + 6f, y - 7f); roof.lineTo(x + 14f, y); roof.close()
        canvas.drawPath(roof, paint(Wax.ROOF))
        canvas.drawPath(roof, stroke(Wax.DOOR, 1.2f))
        canvas.drawRect(x + 4.5f, y + 4f, x + 7.5f, y + 10f, paint(Wax.DOOR))
    }

    private fun rings(canvas: Canvas, rings: List<StoryRing>, x: Float) {
        for (i in rings.indices) {
            val ring = rings[i]
            if (ring.alpha > 0f && ring.radius > 0f) canvas.drawCircle(x, 80f, ring.radius, stroke(Wax.GREEN, 2f, ring.alpha))
        }
    }

    private fun her(canvas: Canvas, f: StoryFrame) {
        canvas.drawPath(herBody, paint(Wax.INDIGO))
        canvas.save()
        canvas.clipPath(herBody)
        var cx = 18f
        while (cx < 70f) {
            var cy = 90f
            while (cy < 146f) {
                canvas.drawCircle(cx, cy, 4.5f, paint(Wax.ORANGE))
                canvas.drawCircle(cx, cy, 2.6f, paint(Wax.YELLOW))
                canvas.drawCircle(cx, cy, 1f, paint(Wax.INDIGO))
                cy += 12f
            }
            cx += 12f
        }
        canvas.restore()
        canvas.drawPath(herBody, stroke(Wax.OUTLINE, Wax.LINE))
        outlinedCircle(canvas, 40f, 64f, 14f, Wax.SKIN_HER)
        canvas.drawPath(headwrap, paint(Wax.WRAP))
        canvas.drawPath(headwrap, stroke(Wax.OUTLINE, Wax.LINE))
        outlinedCircle(canvas, 53f, 52f, 4f, Wax.WRAP)
        canvas.drawCircle(46f, 63f, 1.5f, paint(Wax.OUTLINE))
        rect.set(48f - f.mouthRx, 70.5f - f.mouthRy, 48f + f.mouthRx, 70.5f + f.mouthRy)
        canvas.drawOval(rect, paint(Wax.MOUTH))
    }

    private fun him(canvas: Canvas) {
        canvas.drawPath(hisBody, paint(Wax.YELLOW))
        canvas.save()
        canvas.clipPath(hisBody)
        var x = 224f
        while (x < 284f) {
            canvas.drawRect(x, 96f, x + 4f, 140f, paint(Wax.GREEN))
            canvas.drawRect(x + 6f, 96f, x + 8f, 140f, paint(Wax.RED))
            canvas.drawRect(x + 10f, 96f, x + 14f, 140f, paint(Wax.OUTLINE))
            x += 16f
        }
        var y = 90f
        while (y < 140f) {
            canvas.drawRect(236f, y + 7f, 284f, y + 8.5f, paint(Wax.RED))
            y += 10f
        }
        canvas.restore()
        canvas.drawPath(hisBody, stroke(Wax.OUTLINE, Wax.LINE))
        canvas.drawPath(collar, stroke(Wax.YELLOW, 2f))
        outlinedCircle(canvas, 260f, 64f, 14f, Wax.SKIN_HIM)
        canvas.drawPath(cap, paint(Wax.OUTLINE))
        canvas.drawPath(cap, stroke(Wax.OUTLINE, Wax.LINE))
        canvas.drawLine(248f, 58f, 234f, 58f, stroke(Wax.OUTLINE, 3.5f))
        canvas.drawCircle(254f, 63f, 1.5f, paint(Wax.OUTLINE))
        canvas.drawPath(smile, stroke(Wax.MOUTH, 1.8f))
    }

    private fun arm(canvas: Canvas, color: Int, x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) {
        arm.rewind()
        arm.moveTo(x0, y0)
        arm.quadTo(cx, cy, x1, y1)
        canvas.drawPath(arm, stroke(color, 6f))
    }

    /** Matches the SVG's translate followed by rotation around the phone's pivot. */
    private fun phone(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, angle: Float, px: Float, py: Float) {
        canvas.save()
        canvas.translate(dx, dy)
        canvas.rotate(angle, px, py)
        rect.set(x, y, x + 10f, y + 18f)
        canvas.drawRoundRect(rect, 2.5f, 2.5f, paint(Wax.PHONE))
        canvas.drawRoundRect(rect, 2.5f, 2.5f, stroke(Wax.OUTLINE, Wax.LINE))
        rect.set(x + 2f, y + 3f, x + 8f, y + 12f)
        canvas.drawRoundRect(rect, 1f, 1f, paint(Wax.PHONE_SCREEN))
        canvas.restore()
    }

    private fun bars(canvas: Canvas, f: StoryFrame) {
        if (f.barsAlpha <= 0f) return
        val paint = stroke(Wax.INDIGO, 3f, f.barsAlpha)
        for (i in 0 until 4) {
            val s = f.barScales[i]
            canvas.drawLine(barX[i], 60f + (barTop[i] - 60f) * s, barX[i], 60f + (barBottom[i] - 60f) * s, paint)
        }
    }

    private fun radio(canvas: Canvas, x: Float, lightX: Float, on: Boolean) {
        rect.set(x, 98f, x + 24f, 138f)
        canvas.drawRoundRect(rect, 5f, 5f, paint(Wax.RADIO))
        canvas.drawRoundRect(rect, 5f, 5f, stroke(Wax.OUTLINE, Wax.LINE))
        rect.set(x + 4f, 103f, x + 20f, 112f)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint(Wax.RADIO_SCREEN))
        canvas.drawRoundRect(rect, 1.5f, 1.5f, stroke(Wax.OUTLINE, 1.8f))
        canvas.drawCircle(x + 12f, 124f, 5f, stroke(Wax.OUTLINE, 1.92f))
        canvas.drawCircle(lightX, 133f, 2.6f, paint(if (on) Wax.LIGHT_ON else Wax.LIGHT_OFF))
    }

    private fun bubble(canvas: Canvas, f: StoryFrame) {
        if (f.bubbleAlpha <= 0f) return
        canvas.save()
        canvas.translate(f.bubbleX, f.bubbleY)
        canvas.scale(f.bubbleScale, f.bubbleScale)
        rect.set(-28f, -12f, 28f, 12f)
        canvas.drawRoundRect(rect, 7f, 7f, paint(Wax.BUBBLE, f.bubbleAlpha))
        canvas.drawRoundRect(rect, 7f, 7f, stroke(Wax.OUTLINE, Wax.LINE, f.bubbleAlpha))
        if (f.letters > 0) {
            letters.alpha = alpha(f.bubbleAlpha)
            canvas.drawText(StoryTimeline.WORD, 0, f.letters, 0f, 5f, letters)
        }
        canvas.restore()
    }

    private fun outlinedCircle(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        canvas.drawCircle(x, y, r, paint(color))
        canvas.drawCircle(x, y, r, stroke(Wax.OUTLINE, Wax.LINE))
    }

    private fun paint(color: Int, opacity: Float = 1f): Paint = fill.apply {
        this.color = color
        alpha = alpha(opacity)
    }

    private fun stroke(color: Int, width: Float, opacity: Float = 1f): Paint = line.apply {
        this.color = color
        strokeWidth = width
        alpha = alpha(opacity)
    }

    private fun alpha(opacity: Float) = (opacity.coerceIn(0f, 1f) * 255).roundToInt()
}

package org.phonemesh.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import org.phonemesh.core.FlagSpec
import org.phonemesh.core.RegionScene
import org.phonemesh.core.RegionTimeline

/**
 * The region cards' moving picture: the flag on the radio's screen (how-to option R2), in the how-to pictures'
 * panel and colours. It loops while shown and holds its still frame when animations are off.
 */
class RegionPicture(context: Context) : View(context) {
    private var scene: RegionScene? = null
    private var oldFlag: FlagSpec? = null
    private var newFlag: FlagSpec? = null
    private var startedAt = 0L
    private var shown = false
    private var running = false
    private val kit = HowToKit(HowToKit.Words.of(context))
    private val box = RectF()
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    val isRunning: Boolean get() = running

    init {
        tag = "region_picture"
        val radius = Ui.dp(context, 12).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Showing the same picture again keeps it where it is, so a render never restarts it. */
    fun show(scene: RegionScene, oldFlag: FlagSpec?, newFlag: FlagSpec?) {
        if (scene == this.scene && oldFlag == this.oldFlag && newFlag == this.newFlag) return
        this.scene = scene
        this.oldFlag = oldFlag
        this.newFlag = newFlag
        startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width / 2)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        update()
    }

    override fun onDetachedFromWindow() {
        shown = false
        update()
        super.onDetachedFromWindow()
    }

    private fun update() {
        val run = shown && StoryView.animationsEnabled()
        if (run == running) return
        running = run
        Choreographer.getInstance().removeFrameCallback(tick)
        if (run) Choreographer.getInstance().postFrameCallback(tick)
    }

    override fun onDraw(canvas: Canvas) {
        val scene = scene ?: return
        val t = if (StoryView.animationsEnabled()) {
            ((SystemClock.uptimeMillis() - startedAt) % RegionTimeline.DURATION_MS) / RegionTimeline.DURATION_MS.toFloat()
        } else {
            RegionTimeline.still(scene)
        }
        val f = RegionTimeline.at(scene, t)
        val s = width / 220f
        canvas.save()
        canvas.scale(s, s)
        kit.backdrop(canvas)
        // The radio: a 34 x 50 body at scale K, its screen at (3..31, 5..23).
        val tipX = X + 27 * K
        val tipY = Y - 13 * K
        f.rings.forEach { ring -> canvas.drawCircle(tipX, tipY, ring * 60f, kit.line(HowToKit.C.GREEN, 2.4f, 1f - ring)) }
        canvas.drawLine(X + 24 * K, Y, tipX, tipY, kit.line())
        kit.rect(canvas, X, Y, 34 * K, 50 * K, 6 * K, HowToKit.C.RADIO)
        box.set(X + 3 * K, Y + 5 * K, X + 31 * K, Y + 23 * K)
        canvas.drawRoundRect(box, 2 * K, 2 * K, kit.fill(HowToKit.C.SCREEN))
        box.inset(K, K)
        if (f.oldFlag > 0f) FlagPainter.draw(canvas, oldFlag, box, (f.oldFlag * 255).toInt())
        if (f.newFlag > 0f) FlagPainter.draw(canvas, newFlag, box, (f.newFlag * 255).toInt())
        if (f.question > 0f) {
            canvas.drawCircle(box.centerX(), box.centerY(), 6 * K, kit.fill(HowToKit.C.WHITE, f.question))
            kit.text(canvas, "?", box.centerX(), box.centerY() + 3.5f * K, 10 * K)
        }
        box.inset(-K, -K)
        canvas.drawRoundRect(box, 2 * K, 2 * K, kit.line(width = 1.6f))
        canvas.drawCircle(X + 17 * K, Y + 35 * K, 6 * K, kit.line(width = 2f))
        canvas.drawCircle(X + 28 * K, Y + 44 * K, 2.6f * K, kit.fill(if (f.ledOn) HowToKit.C.LIGHT_ON else HowToKit.C.LIGHT_OFF))
        if (f.cross > 0f) {
            // The radio is not sending: its waves, crossed out.
            for (i in 0 until 3) {
                val r = 9f + i * 7f
                box.set(WAVES_X - r, WAVES_Y - r, WAVES_X + r, WAVES_Y + r)
                canvas.drawArc(box, -45f, 90f, false, kit.line(HowToKit.C.GREEN, 2.6f))
            }
            canvas.drawLine(WAVES_X + 2f, WAVES_Y - 18f, WAVES_X + 30f, WAVES_Y + 18f, kit.line(HowToKit.C.RED, 3.2f))
        }
        kit.band(canvas)
        canvas.restore()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), kit.line(width = 2f * s))
    }

    private companion object {
        const val K = 1.4f
        const val X = 110f - 17f * K
        const val Y = 27f
        const val WAVES_X = 138f
        const val WAVES_Y = 50f
    }
}

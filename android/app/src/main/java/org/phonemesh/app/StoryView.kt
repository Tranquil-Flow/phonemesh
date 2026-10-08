package org.phonemesh.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import org.phonemesh.core.StoryTimeline

/** The welcome story. Its screen calls [start] in onStart and [stop] in onStop. */
class StoryView(context: Context) : View(context) {
    /** Called on the main thread after the new moment has been published. */
    var onMoment: ((Int) -> Unit)? = null
    var moment: Int = StoryTimeline.OPENING
        private set
    val isRunning: Boolean get() = running
    private val scene = StoryScene()
    private var time = 0f
    private var startedAt = 0L
    private var running = false
    private var frozen: Float? = null
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val elapsed = (SystemClock.uptimeMillis() - startedAt) % StoryTimeline.DURATION_MS
            show(elapsed.toFloat() / StoryTimeline.DURATION_MS)
            // A moment observer may stop or freeze the picture synchronously.
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        val radius = Ui.dp(context, 16).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Resumes from the last frame, or shows the reduced-motion/frozen frame without a callback. */
    fun start() {
        if (running) return
        val still = frozen ?: if (animationsEnabled()) null else StoryTimeline.STILL
        if (still != null) {
            show(still)
            return
        }
        running = true
        startedAt = SystemClock.uptimeMillis() - (time * StoryTimeline.DURATION_MS).toLong()
        Choreographer.getInstance().postFrameCallback(tick)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(tick)
    }

    /** Holds the picture at [t] (0..1). Null lets the next [start] resume. */
    fun freezeAt(t: Float?) {
        frozen = t?.coerceIn(0f, 1f)
        frozen?.let {
            stop()
            show(it)
        }
    }

    private fun show(t: Float) {
        time = t
        val now = StoryTimeline.moment(t)
        if (now != moment) {
            moment = now
            onMoment?.invoke(now)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * StoryTimeline.HEIGHT / StoryTimeline.WIDTH).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val frame = StoryTimeline.at(time)
        val scale = width / frame.camW
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-frame.camX, -frame.camY)
        scene.draw(canvas, frame)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Android's Remove animations switch. */
        val systemAnimations: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() }
        /** Shared with how-to pictures; tests can force still frames without changing device settings. */
        @Volatile var animationsEnabled: () -> Boolean = systemAnimations
    }
}

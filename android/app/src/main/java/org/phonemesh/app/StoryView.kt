package org.phonemesh.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import org.phonemesh.core.StoryFrame
import org.phonemesh.core.StoryTimeline

/**
 * The welcome story. Its screen calls [start] in onStart and [stop] in onStop. While running it shows
 * [StoryTimeline.paced]: the action at its own speed, with each numbered step resting so that it stays readable.
 */
class StoryView(context: Context) : View(context) {
    /** Called on the main thread after the new moment has been published. */
    var onMoment: ((Int) -> Unit)? = null
    var moment: Int = StoryTimeline.OPENING
        private set
    val isRunning: Boolean get() = running
    private val scene = StoryScene()
    private var frame = StoryTimeline.at(0f)
    /** Where the picture is in the paced loop, in ms. */
    private var elapsed = 0L
    private var startedAt = 0L
    private var running = false
    private var frozen: Float? = null
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            elapsed = (SystemClock.uptimeMillis() - startedAt) % StoryTimeline.LOOP_MS
            show(StoryTimeline.paced(elapsed))
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
            hold(still)
            return
        }
        running = true
        startedAt = SystemClock.uptimeMillis() - elapsed
        Choreographer.getInstance().postFrameCallback(tick)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(tick)
    }

    /**
     * Holds the picture at story time [t] (0..1 over the action, as [StoryTimeline.at]; rests are not part of it).
     * Null lets the next [start] resume, from the first instant the paced loop shows that story time.
     */
    fun freezeAt(t: Float?) {
        frozen = t?.coerceIn(0f, 1f)
        frozen?.let {
            stop()
            hold(it)
        }
    }

    private fun hold(t: Float) {
        elapsed = StoryTimeline.msOf(t) % StoryTimeline.LOOP_MS
        show(StoryTimeline.at(t))
    }

    private fun show(next: StoryFrame) {
        frame = next
        val now = next.moment
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

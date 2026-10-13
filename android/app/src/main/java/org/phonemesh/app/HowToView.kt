package org.phonemesh.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import org.phonemesh.core.HowToScene

/** A looping picture only while shown and inside its viewport. Hiding preserves its last frame. */
class HowToView(context: Context, scene: HowToScene) : View(context) {
    var scene: HowToScene = scene
        set(value) {
            field = value
            values = FloatArray(value.names.size)
            elapsed = 0L
            loops = 0L
            startedAt = SystemClock.uptimeMillis()
            invalidate()
        }
    var onLoop: (() -> Unit)? = null
    val isRunning: Boolean get() = running
    private var values = FloatArray(scene.names.size)
    private val kit = HowToKit(HowToKit.Words.of(context), context)
    private val read = HowToValues()
    private var running = false
    private var shown = false
    private var framePosted = false
    private var frozen: Float? = null
    private var elapsed = 0L
    private var startedAt = 0L
    private var loops = 0L
    private val onScreen = Rect()
    private var observedTree: ViewTreeObserver? = null
    private val scrollObserver = ViewTreeObserver.OnScrollChangedListener { updateRunning() }
    private val layoutObserver = ViewTreeObserver.OnGlobalLayoutListener { updateRunning() }
    private val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = HowToKit.C.INK
    }
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            updateRunning()
            if (!running) return
            elapsed = SystemClock.uptimeMillis() - startedAt
            val loop = elapsed / scene.durationMs
            if (loop != loops) {
                loops = loop
                onLoop?.invoke() // May switch scenes, hide, freeze or detach this view.
            }
            updateRunning()
            if (running) {
                invalidate()
                postFrame()
            }
        }
    }

    init {
        val radius = Ui.dp(context, 12).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedTree = viewTreeObserver.also {
            it.addOnScrollChangedListener(scrollObserver)
            it.addOnGlobalLayoutListener(layoutObserver)
        }
        updateRunning()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        updateRunning()
    }

    private fun updateRunning() {
        val visible = shown && isAttachedToWindow && width > 0 && height > 0 &&
            getGlobalVisibleRect(onScreen) && !onScreen.isEmpty
        if (visible && frozen == null && StoryView.animationsEnabled()) {
            if (!running) {
                running = true
                startedAt = SystemClock.uptimeMillis() - elapsed
                postFrame()
            }
        } else {
            stop()
            if (visible) invalidate()
        }
    }

    private fun postFrame() {
        if (!running || framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(tick)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(tick)
        framePosted = false
    }

    /** Holds the picture at [t] (0..1); null resumes from the last moving frame when visible. */
    fun freezeAt(t: Float?) {
        frozen = t?.coerceIn(0f, 1f)
        updateRunning()
        invalidate()
    }

    private fun time(): Float = frozen
        ?: if (!StoryView.animationsEnabled()) scene.still
        else (elapsed % scene.durationMs).toFloat() / scene.durationMs

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Twice as wide as tall, and smaller rather than cut when the height is limited.
        var height = MeasureSpec.getSize(widthMeasureSpec) / 2
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) height = minOf(height, MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(height * 2, height)
    }

    override fun onDraw(canvas: Canvas) {
        val t = time()
        kit.ms = (t * scene.durationMs).toLong()
        scene.at(t, values)
        read.set(scene, values)
        val scale = width / 220f
        canvas.save()
        canvas.scale(scale, scale)
        kit.backdrop(canvas)
        HowToDrawings.draw(scene.name, canvas, kit, read)
        kit.band(canvas)
        canvas.restore()
        border.strokeWidth = 2f * scale
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
    }

    override fun onDetachedFromWindow() {
        shown = false
        stop()
        observedTree?.let {
            if (it.isAlive) {
                it.removeOnScrollChangedListener(scrollObserver)
                it.removeOnGlobalLayoutListener(layoutObserver)
            }
        }
        observedTree = null
        super.onDetachedFromWindow()
    }
}

package org.phonemesh.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.phonemesh.core.StoryTimeline
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The how-to pictures' shared drawings, in the mockup's 220 × 110 units (phonemesh-howto.html). Colours are the
 * wax-print ones and stay the same in the dark theme. [ms] is the scene's elapsed time: the free-running loops
 * (rings, blinking light, voice bars, waves, dots) are drawn from it. [fade] multiplies every colour's opacity.
 * Paths and paints are built once; drawing allocates nothing.
 */
class HowToKit(val words: Words, private val context: Context? = null) {
    /** Words drawn inside pictures, in the app's language. */
    data class Words(val bonjour: String, val waiting: String, val sent: String, val delivered: String, val resend: String, val on: String, val allow: String, val clear: String, val send: String, val chat: Chat = Chat.EMPTY) {
        companion object {
            fun of(context: Context) = Words(
                StoryTimeline.WORD, context.getString(R.string.status_waiting), context.getString(R.string.status_sent),
                context.getString(R.string.status_delivered), context.getString(R.string.resend), context.getString(R.string.howto_on),
                context.getString(R.string.howto_allow_button), context.getString(R.string.draft_clear), context.getString(R.string.send),
                Chat.of(context),
            )
        }
    }

    /**
     * The chat-gesture pictures' words: every label is the app's own string, so a picture shows the screen's words;
     * times and dates are formatted as the phone formats them.
     */
    data class Chat(
        val reply: String, val react: String, val copy: String, val share: String, val forward: String, val delete: String,
        val cancel: String, val forwardTo: String, val group: String, val pin: String, val deleteChat: String, val later: String,
        val planTitle: String, val today: String, val tomorrow: String, val pickDay: String, val schedule: String,
        val market: String, val coming: String, val market8: String, val hello: String, val seeYou: String,
        /** Times in the pictures: 17:45, 18:00, 18:15, 08:45, 08:00, as the phone writes them. */
        val t1745: String, val t1800: String, val t1815: String, val t0845: String, val t0800: String,
        val sendsAt1815: String, val plannedFor1815: String,
        /** The demo calendar: Monday 12 October 2026 and Thursday the 15th. */
        val header12: String, val header15: String, val month: String, val weekdays: List<String>, val firstColumn: Int,
        val chosenDay: String, val is24Hour: Boolean, val year: String,
    ) {
        companion object {
            val EMPTY = Chat("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", emptyList(), 0, "", true, "")

            fun of(context: Context): Chat {
                val locale = context.resources.configuration.locales[0]
                val zone = java.time.ZoneId.systemDefault()
                fun millis(day: Int, h: Int, m: Int) = java.time.LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()
                val time = android.text.format.DateFormat.getTimeFormat(context)
                fun t(h: Int, m: Int) = time.format(java.util.Date(millis(12, h, m)))
                fun date(skeleton: String, day: Int) = Ui.date(context, millis(day, 12, 0), skeleton, zone)
                val calendar = java.util.Calendar.getInstance(locale)
                val symbols = java.text.DateFormatSymbols.getInstance(locale).shortWeekdays
                // Calendar weekdays run Sunday = 1 .. Saturday = 7; the columns start on the locale's first day.
                val order = (0 until 7).map { (calendar.firstDayOfWeek - 1 + it) % 7 + 1 }
                val firstOfMonth = java.time.LocalDate.of(2026, 10, 1).dayOfWeek.value % 7 + 1
                return Chat(
                    context.getString(R.string.reply), context.getString(R.string.react), context.getString(R.string.copy),
                    context.getString(R.string.share_text), context.getString(R.string.forward), context.getString(R.string.delete),
                    // Android writes a dialog's buttons in capitals, in the app's accent.
                    context.getString(R.string.cancel).uppercase(locale), context.getString(R.string.forward_to), context.getString(R.string.group_name),
                    context.getString(R.string.pin_chat), context.getString(R.string.delete_chat), context.getString(R.string.later),
                    context.getString(R.string.plan_title), context.getString(R.string.today), context.getString(R.string.tomorrow),
                    context.getString(R.string.plan_pick_day), context.getString(R.string.plan_go),
                    context.getString(R.string.howto_msg_market), context.getString(R.string.howto_msg_coming),
                    context.getString(R.string.howto_msg_market8), context.getString(R.string.howto_msg_hello),
                    context.getString(R.string.howto_msg_tomorrow),
                    t(17, 45), t(18, 0), t(18, 15), t(8, 45), t(8, 0),
                    context.getString(R.string.sends_at, t(18, 15)), context.getString(R.string.planned_for, t(18, 15)),
                    date("EEEMMMd", 12), date("EEEMMMd", 15), date("LLLLyyyy", 12),
                    order.map { symbols[it].trim('.').take(1).uppercase(locale) }, order.indexOf(firstOfMonth),
                    date("EEEEdMMMM", 15), android.text.format.DateFormat.is24HourFormat(context), "2026",
                )
            }
        }
    }

    object C {
        const val INK = 0xFF2B1A10.toInt()
        const val PANEL = 0xFFFFE9B8.toInt()
        const val RADIO = 0xFFFFCA28.toInt()
        const val SCREEN = 0xFFFFF3C4.toInt()
        const val ORANGE = 0xFFFF7043.toInt()
        const val BLUE = 0xFF283593.toInt()
        const val GREEN = 0xFF1B5E20.toInt()
        const val LIGHT_ON = 0xFF76FF03.toInt()
        const val LIGHT_OFF = 0xFF555555.toInt()
        const val BUBBLE = 0xFFFFF8E1.toInt()
        const val MINT = 0xFFE3F3E6.toInt()
        const val SKIN = 0xFFB97A4C.toInt()
        const val RED = 0xFFC62828.toInt()
        const val PHONE = 0xFF1D1D1D.toInt()
        const val BUTTON = 0xFF666666.toInt()
        const val GREY = 0xFF9E9E9E.toInt()
        const val BLUETOOTH = 0xFF1F5FBF.toInt()
        const val HOLD_TRACK = 0xFFE6C98A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val LINE = 2.2f
    }

    var ms = 0L
    var fade = 1f
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val icons = android.util.SparseArray<android.graphics.drawable.Drawable>()
    private val r = RectF()
    private val path = Path()
    private val dash46 = DashPathEffect(floatArrayOf(4f, 6f), 0f)
    private val dash34 = DashPathEffect(floatArrayOf(3f, 4f), 0f)
    private val finger = Path().apply {
        // The mockup's finger path, moved so its tip is at (0, 0).
        moveTo(-4f, 20f); lineTo(-4f, 4f)
        arcTo(RectF(-4f, 0f, 4f, 8f), 180f, 180f)
        lineTo(4f, 18f); lineTo(7f, 16.5f)
        quadTo(10f, 14.2f, 12f, 18f)
        lineTo(4f, 40f); lineTo(-10f, 40f); lineTo(-16f, 26f)
        quadTo(-14.8f, 21.2f, -10f, 23f); close()
    }
    private val mast = Path().apply {
        moveTo(40f, 100f); lineTo(50f, 34f); lineTo(60f, 100f)
        moveTo(43f, 82f); lineTo(57f, 82f); moveTo(45f, 66f); lineTo(55f, 66f); moveTo(47f, 50f); lineTo(53f, 50f)
        moveTo(42f, 92f); lineTo(58f, 80f); moveTo(44f, 76f); lineTo(56f, 64f); moveTo(46f, 60f); lineTo(54f, 52f)
    }
    private val bluetooth = Path().apply {
        moveTo(-5f, -5f); lineTo(5f, 5f); lineTo(0f, 10f); lineTo(0f, -10f); lineTo(5f, -5f); lineTo(-5f, 5f)
    }

    fun fill(color: Int, opacity: Float = 1f): Paint = fillPaint.apply {
        this.color = color
        alpha = alpha(opacity)
    }

    fun line(color: Int = C.INK, width: Float = C.LINE, opacity: Float = 1f): Paint = strokePaint.apply {
        this.color = color
        strokeWidth = width
        pathEffect = null
        alpha = alpha(opacity)
    }

    private fun alpha(opacity: Float) = ((opacity * fade).coerceIn(0f, 1f) * 255).roundToInt()

    /** Runs [block] with every colour at [opacity] (nested fades multiply). */
    inline fun fade(opacity: Float, block: () -> Unit) {
        if (opacity <= 0f) return
        val before = fade
        fade = before * opacity
        try { block() } finally { fade = before }
    }

    /** A 0..1 position in a loop of [periodMs], started [delayMs] early (CSS animation-delay: -delay). */
    fun cycle(periodMs: Long, delayMs: Long = 0): Float = ((ms + delayMs) % periodMs).toFloat() / periodMs

    /** 0.35 → 1 → 0.35 over [periodMs] (the mockup's btp pulse). */
    fun pulse(periodMs: Long): Float = .35f + .65f * (.5f - .5f * cos(2 * PI * cycle(periodMs)).toFloat())

    /** The opaque colour [t] of the way from [a] to [b], clamped to 0..1. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val from = (a shr shift) and 0xFF
            val to = (b shr shift) and 0xFF
            return (from + (to - from) * u).roundToInt()
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    // ---- Shapes

    fun backdrop(c: Canvas) {
        c.drawRect(0f, 0f, 220f, 110f, fill(C.PANEL))
    }

    fun band(c: Canvas) {
        // The SVG pattern is anchored at (0, 0), with each tile inheriting the brown stroke.
        c.save()
        c.clipRect(0f, 104f, 220f, 110f)
        for (y in 102..108 step 6) {
            var x = 0f
            while (x < 220f) {
                c.save()
                c.clipRect(x, y.toFloat(), x + 12f, y + 6f)
                c.drawRect(x, y.toFloat(), x + 12f, y + 6f, fill(C.BLUE))
                c.drawRect(x, y.toFloat(), x + 12f, y + 6f, line())
                circle(c, x + 6f, y + 3f, 2.6f, C.ORANGE)
                circle(c, x + 6f, y + 3f, 1.2f, C.RADIO)
                c.restore()
                x += 12f
            }
        }
        c.restore()
    }

    fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int, stroke: Float = C.LINE) {
        r.set(x, y, x + w, y + h)
        c.drawRoundRect(r, radius, radius, fill(color))
        if (stroke > 0f) c.drawRoundRect(r, radius, radius, line(width = stroke))
    }

    fun circle(c: Canvas, x: Float, y: Float, radius: Float, color: Int, stroke: Float = C.LINE) {
        c.drawCircle(x, y, radius, fill(color))
        if (stroke > 0f) c.drawCircle(x, y, radius, line(width = stroke))
    }

    fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = C.INK, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = true) {
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textPaint.color = color
        textPaint.alpha = alpha(1f)
        textPaint.textSize = size
        textPaint.textAlign = align
        c.drawText(value, x, y, textPaint)
    }

    /** Text no wider than [width]: shrunk to fit, so a longer translation never runs out of its box. */
    fun fitted(c: Canvas, value: String, x: Float, y: Float, size: Float, width: Float, color: Int = C.INK, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = true) {
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textPaint.textSize = size
        val measured = textPaint.measureText(value)
        text(c, value, x, y, if (measured > width) size * width / measured else size, color, align, bold)
    }

    /**
     * One of the app's own icons (res/drawable, 24 units), centred on (x, y) at [size] units and tinted [color];
     * [tint] false keeps its colours (sun, moon). Without a context (unit tests) nothing is drawn.
     */
    fun icon(c: Canvas, res: Int, x: Float, y: Float, size: Float, color: Int = C.INK, tint: Boolean = true) {
        val drawable = icons[res] ?: context?.getDrawable(res)?.mutate()?.also { icons.put(res, it) } ?: return
        if (tint) drawable.setTint(color) else drawable.setTintList(null)
        drawable.alpha = alpha(1f)
        drawable.setBounds(0, 0, 24, 24)
        c.save()
        c.translate(x - size / 2, y - size / 2)
        c.scale(size / 24f, size / 24f)
        drawable.draw(c)
        c.restore()
    }

    /** The mockup's `phone(x, y)`: 30 × 54, screen 24 × 40 at (3, 5). */
    fun phone(c: Canvas, x: Float, y: Float, screen: Int = C.SCREEN, home: Boolean = true) {
        rect(c, x, y, 30f, 54f, 5f, C.PHONE)
        r.set(x + 3f, y + 5f, x + 27f, y + 45f)
        c.drawRoundRect(r, 1.5f, 1.5f, fill(screen))
        if (home) c.drawCircle(x + 15f, y + 49.5f, 1.8f, fill(C.BUTTON))
    }

    /** The phone's screen filled from the bottom to [level] (0..1). */
    fun phoneLevel(c: Canvas, x: Float, y: Float, level: Float, color: Int = C.ORANGE) {
        if (level <= 0f) return
        r.set(x + 3f, y + 45f - 40f * level.coerceAtMost(1f), x + 27f, y + 45f)
        c.drawRoundRect(r, 1.5f, 1.5f, fill(color))
    }

    /** The mockup's `radio(x, y, s, led)`: [light] 0 is off, 1 is on; [blink] blinks once a second instead. */
    fun radio(c: Canvas, x: Float, y: Float, scale: Float = 1f, light: Float = 0f, blink: Boolean = false) {
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        c.drawLine(20f, 0f, 23f, -13f, line())
        rect(c, 0f, 0f, 26f, 40f, 5f, C.RADIO)
        rect(c, 4f, 5f, 18f, 9f, 1.5f, C.SCREEN, stroke = 1.6f)
        c.drawCircle(13f, 27f, 5f, line(width = 2f))
        val on = if (blink) cycle(1_000) < .5f else light >= .5f
        c.drawCircle(20f, 35f, 2.6f, fill(if (on) C.LIGHT_ON else C.LIGHT_OFF))
        c.restore()
    }

    /** The drawn finger (option H1), its tip at (x + dx, y + dy). */
    fun finger(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        c.save()
        c.translate(x + dx, y + dy)
        c.drawPath(finger, fill(C.SKIN))
        c.drawPath(finger, line(width = 2f))
        c.restore()
    }

    /** The microphone button: a circle with the microphone sign. */
    fun mic(c: Canvas, cx: Float, cy: Float, radius: Float = 18f, color: Int = C.RADIO, sign: Int = C.INK) {
        circle(c, cx, cy, radius, color)
        c.save()
        c.translate(cx, cy)
        c.scale(radius / 18f, radius / 18f)
        r.set(-4f, -10f, 4f, 3f)
        c.drawRoundRect(r, 4f, 4f, fill(sign))
        r.set(-8f, -8f, 8f, 8f)
        c.drawArc(r, 0f, 180f, false, line(sign, 2f))
        c.drawLine(0f, 8f, 0f, 12f, line(sign, 2f))
        c.restore()
    }

    /** The green check, popping in with [v] (0 hidden, 1 full size). */
    fun ok(c: Canvas, x: Float, y: Float, v: Float) {
        if (v <= 0f) return
        c.save()
        c.translate(x, y)
        val s = .3f + .7f * v
        c.scale(s, s)
        fade(v) {
            c.drawCircle(0f, 0f, 9f, fill(C.GREEN))
            path.rewind(); path.moveTo(-4f, 0f); path.lineTo(-1f, 3f); path.lineTo(4f, -3f)
            c.drawPath(path, line(C.WHITE, 2.4f))
        }
        c.restore()
    }

    /** The red cross sign, popping in with [v]. */
    fun no(c: Canvas, x: Float, y: Float, v: Float = 1f) {
        if (v <= 0f) return
        c.save()
        c.translate(x, y)
        val s = .3f + .7f * v
        c.scale(s, s)
        fade(v) {
            c.drawCircle(0f, 0f, 9f, fill(C.RED))
            c.drawLine(-3.5f, -3.5f, 3.5f, 3.5f, line(C.WHITE, 2.4f))
            c.drawLine(3.5f, -3.5f, -3.5f, 3.5f, line(C.WHITE, 2.4f))
        }
        c.restore()
    }

    /** Voice bars that grow and shrink (CSS .vb, 0.7 s, each bar 0.23 s apart). */
    fun bars(c: Canvas, x: Float, y: Float, n: Int, gap: Float, height: Float, color: Int = C.BLUE) {
        for (i in 0 until n) {
            val p = cycle(700, (i * 230L) % 700)
            val s = .3f + .7f * (.5f - .5f * cos(2 * PI * p).toFloat())
            c.drawLine(x + i * gap, y - height / 2 * s, x + i * gap, y + height / 2 * s, line(color, 3.2f))
        }
    }

    /** Three sound waves to the right of (x, y) (CSS .wave, 1.2 s, 0.3 s apart). */
    fun waves(c: Canvas, x: Float, y: Float) {
        for (i in 0 until 3) {
            val p = cycle(1_200, 1_200 - i * 300L)
            val o = if (p < .3f) p / .3f else (1f - p) / .7f
            val w = 4f + 3f * i
            val h = 5f + 4f * i
            path.rewind()
            path.moveTo(x + 6f * i, y - h)
            path.quadTo(x + 6f * i + w, y, x + 6f * i, y + h)
            c.drawPath(path, line(C.GREEN, 2.6f, o))
        }
    }

    /** Radio rings spreading from (cx, cy) to [radius] (CSS .ring, 2.4 s, three rings 0.8 s apart). */
    fun rings(c: Canvas, cx: Float, cy: Float, radius: Float, width: Float = 2.4f) {
        for (i in 0 until 3) {
            val p = cycle(2_400, 2_400 - i * 800L)
            val e = 1f - (1f - p) * (1f - p)
            c.drawCircle(cx, cy, radius * (.15f + .85f * e), line(C.GREEN, width, 1f - e))
        }
    }

    /** The voice pack: an orange box with sound bars, or a grey one with a [label]. */
    fun box(c: Canvas, x: Float, y: Float, color: Int = C.ORANGE, label: String? = null) {
        rect(c, x, y, 16f, 16f, 3.5f, color, stroke = 1.8f)
        if (label != null) {
            text(c, label, x + 8f, y + 12.5f, 11f, C.WHITE)
        } else {
            c.drawLine(x + 5f, y + 6f, x + 5f, y + 10f, line(C.WHITE, 1.8f))
            c.drawLine(x + 8f, y + 4f, x + 8f, y + 12f, line(C.WHITE, 1.8f))
            c.drawLine(x + 11f, y + 6f, x + 11f, y + 10f, line(C.WHITE, 1.8f))
        }
    }

    fun bluetoothSign(c: Canvas, x: Float, y: Float, scale: Float = 1f, color: Int = C.BLUETOOTH, opacity: Float = 1f) {
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        c.drawPath(bluetooth, line(color, 2.4f / scale, opacity))
        c.restore()
    }

    /** The phone mast sending Wi-Fi waves (the owner's choice E4). */
    fun mastWifi(c: Canvas) {
        c.drawPath(mast, line())
        circle(c, 50f, 32f, 3.5f, C.INK)
        for (i in 0 until 3) {
            val p = cycle(1_200, 1_200 - i * 300L)
            val o = if (p < .3f) p / .3f else (1f - p) / .7f
            wifiArc(c, 50f, 30f, 8f + 7f * i, -6f - 7f * i, 11f + 10f * i, 3f, C.INK, o)
        }
    }

    /** An arc over the top from (cx - half, cy + dy) to (cx + half, cy + dy), of radius [radius]. */
    fun wifiArc(c: Canvas, cx: Float, cy: Float, half: Float, dy: Float, radius: Float, width: Float, color: Int, opacity: Float) {
        val centerY = cy + dy + sqrt(radius * radius - half * half)
        val a = Math.toDegrees(asin((half / radius).toDouble())).toFloat()
        r.set(cx - radius, centerY - radius, cx + radius, centerY + radius)
        c.drawArc(r, 270f - a, 2 * a, false, line(color, width, opacity))
    }

    /** Download dots travelling from the mast (64, 34) towards the phone (CSS dot, 1.6 s, three dots). */
    fun dots(c: Canvas) {
        for (i in 0 until 3) {
            val p = cycle(1_600, i * 533L)
            val o = when {
                p < .1f -> p / .1f
                p < .8f -> 1f
                else -> (1f - p) / .2f
            }
            c.drawCircle(64f + 78f * p, 34f + 30f * p, 3.2f, fill(C.BLUE, o))
        }
    }

    fun bubble(c: Canvas, x: Float, y: Float, width: Float, label: String, color: Int = C.BUBBLE, size: Float = 11f) {
        rect(c, x, y, width, 26f, 9f, color)
        text(c, label, x + width / 2, y + 17f, size)
    }

    /** The ring that fills while the microphone is held, [v] 0..1. */
    fun holdRing(c: Canvas, cx: Float, cy: Float, v: Float) {
        c.drawCircle(cx, cy, 23f, line(C.HOLD_TRACK, 5f))
        if (v > 0f) {
            r.set(cx - 23f, cy - 23f, cx + 23f, cy + 23f)
            c.drawArc(r, -90f, 360f * v, false, line(C.GREEN, 5f))
        }
    }

    /** A dashed line, for links and limits. */
    fun dashed(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float, opacity: Float = 1f, short: Boolean = false) {
        val paint = line(color, width, opacity)
        paint.pathEffect = if (short) dash34 else dash46
        c.drawLine(x0, y0, x1, y1, paint)
        paint.pathEffect = null
    }

    /** The bin, its lid turned by [lid] degrees around its left end. */
    fun bin(c: Canvas, x: Float, y: Float, lid: Float = 0f) {
        c.save()
        c.translate(x, y)
        path.rewind(); path.moveTo(2f, 0f); path.lineTo(18f, 0f); path.lineTo(16f, 20f); path.lineTo(4f, 20f); path.close()
        c.drawPath(path, fill(C.BUBBLE))
        c.drawPath(path, line())
        c.rotate(lid, -1f, -4f)
        c.drawLine(-1f, -4f, 21f, -4f, line())
        c.drawLine(7f, -8f, 13f, -8f, line())
        c.restore()
    }

    /** "Delivered": two ticks. */
    fun ticks(c: Canvas, x: Float, y: Float, color: Int = C.GREEN) {
        path.rewind()
        path.moveTo(x - 9f, y); path.lineTo(x - 6f, y + 3f); path.lineTo(x, y - 4f)
        path.moveTo(x - 2f, y + 3f); path.lineTo(x + 7f, y - 6f)
        c.drawPath(path, line(color, 2.6f))
    }

    /** A small clock face, as the "waiting" sign. */
    fun clock(c: Canvas, x: Float, y: Float, radius: Float = 7f) {
        c.drawCircle(x, y, radius, line(width = 2f))
        c.drawLine(x, y - 4f, x, y, line(width = 2f))
        c.drawLine(x, y, x + 3f, y + 2f, line(width = 2f))
    }

    fun person(c: Canvas, x: Float, skin: Int, wrap: Int, body: Int, wax: Boolean) {
        path.rewind()
        path.moveTo(x - 20f, 104f); path.cubicTo(x - 20f, 82f, x - 11f, 68f, x, 68f); path.cubicTo(x + 11f, 68f, x + 20f, 82f, x + 20f, 104f)
        c.drawPath(path, fill(if (wax) C.BLUE else body))
        c.drawPath(path, line())
        circle(c, x, 50f, 15f, skin)
        path.rewind()
        path.moveTo(x - 16f, 46f); path.quadTo(x, 22f, x + 16f, 46f); path.quadTo(x + 10f, 34f, x, 34f); path.quadTo(x - 10f, 34f, x - 16f, 46f); path.close()
        c.drawPath(path, fill(wrap))
        c.drawPath(path, line())
    }
}

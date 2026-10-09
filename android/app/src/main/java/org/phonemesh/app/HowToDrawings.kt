package org.phonemesh.app

import android.graphics.Canvas
import android.graphics.Paint
import org.phonemesh.core.HowToScene

/** Reads a scene's track values by name while one frame is drawn. */
class HowToValues {
    private var scene: HowToScene? = null
    private var values = FloatArray(0)

    fun set(scene: HowToScene, values: FloatArray) {
        this.scene = scene
        this.values = values
    }

    operator fun get(track: String): Float = values[checkNotNull(scene).index(track)]
}

/** One drawing per how-to scene, ported from the mockup's `S` object, in its 220 × 110 units. */
object HowToDrawings {
    private val scenes: Map<String, (Canvas, HowToKit, HowToValues) -> Unit> = mapOf(
        "download" to { c, k, v ->
            k.mastWifi(c)
            k.fade(v["dots"]) { k.dots(c) }
            k.phone(c, 140f, 44f, home = false)
            k.phoneLevel(c, 140f, 44f, v["fill"])
            k.ok(c, 180f, 50f, v["ok"])
        },
        "friend" to { c, k, v ->
            k.phone(c, 40f, 44f, HowToKit.C.ORANGE)
            k.phone(c, 150f, 44f)
            k.phoneLevel(c, 150f, 44f, v["fill"])
            arc(c, k)
            k.circle(c, 110f, 24f, 13f, HowToKit.C.WHITE)
            k.bluetoothSign(c, 110f, 24f, opacity = k.pulse(6_000))
            k.fade(v["box.alpha"]) { k.box(c, 52f + v["box.dx"], 56f + v["box.dy"]) }
            k.ok(c, 190f, 40f, v["ok"])
        },
        "power" to { c, k, v ->
            k.radio(c, 86f, 26f, 1.5f, light = v["led"])
            k.fade(v["on"]) {
                k.text(c, k.words.on, 150f, 44f, 18f, HowToKit.C.GREEN, Paint.Align.LEFT)
                c.drawLine(128f, 74f, 136f, 71f, k.line(HowToKit.C.GREEN, 2.4f))
                c.drawLine(128f, 80f, 137f, 80f, k.line(HowToKit.C.GREEN, 2.4f))
                c.drawLine(128f, 86f, 136f, 89f, k.line(HowToKit.C.GREEN, 2.4f))
            }
            k.finger(c, 105.5f, 66.5f, v["finger.dx"], v["finger.dy"])
        },
        "close" to { c, k, v ->
            k.phone(c, 40f, 42f)
            k.bluetoothSign(c, 55.5f, 62f, .9f, opacity = k.pulse(5_000))
            k.radio(c, 96f + v["radio.dx"], 56f, blink = true)
            k.dashed(c, 74f, 98f, 96f, 98f, HowToKit.C.GREEN, 2.4f, short = true)
            k.ok(c, 85f, 40f, v["ok"])
        },
        "search" to { c, k, v ->
            k.fade(v["rings"]) { k.rings(c, 45f, 64f, 56f, 2.5f) }
            k.phone(c, 30f, 40f)
            c.drawCircle(45f, 62f, 6f, k.line(width = 2f))
            c.drawLine(49.5f, 66.5f, 53.5f, 70.5f, k.line(width = 2.4f))
            k.radio(c, 160f, 54f, blink = true)
            if (v["link"] > 0f) c.drawLine(64f, 64f, 64f + 92f * v["link"], 64f, k.line(HowToKit.C.GREEN, 3f))
            k.ok(c, 110f, 52f, v["ok"])
        },
        "pair" to { c, k, v ->
            c.save()
            c.translate(18f, 34f)
            c.scale(1.3f, 1.3f)
            c.drawLine(20f, 0f, 23f, -13f, k.line())
            k.rect(c, 0f, 0f, 40f, 44f, 5f, HowToKit.C.RADIO)
            k.rect(c, 4f, 5f, 32f, 13f, 1.5f, HowToKit.C.PHONE, stroke = 1.4f)
            k.text(c, "4829", 20f, 15f, 8f, HowToKit.C.LIGHT_ON)
            c.drawCircle(20f, 30f, 5f, k.line(width = 1.8f))
            c.restore()
            arrow(c, k, 84f, 66f)
            k.phone(c, 150f, 30f)
            "4829".forEachIndexed { i, digit ->
                k.rect(c, 154f + i * 6, 48f, 5f, 9f, 0f, HowToKit.C.WHITE, stroke = .8f)
                k.fade(v["d${i + 1}"]) { k.text(c, digit.toString(), 156.5f + i * 6, 55.5f, 7f) }
            }
            k.ok(c, 190f, 30f, v["ok"])
            k.finger(c, 165f, 67f, v["finger.dx"], v["finger.dy"])
        },
        "hello" to { c, k, v ->
            k.fade(v["bars"]) { k.bars(c, 46f, 64f, 6, 8f, 22f) }
            k.holdRing(c, 150f, 64f, v["hold"])
            k.mic(c, 150f, 64f, color = HowToKit.C.PHONE, sign = HowToKit.C.WHITE)
            popped(c, k, v["ok"], 113f, 27f) { k.bubble(c, -35f, -13f, 70f, k.words.bonjour, size = 13f) }
            k.finger(c, 150f, 64f, v["finger.dx"], v["finger.dy"])
        },
        "lost" to { c, k, v ->
            k.phone(c, 22f, 40f)
            k.no(c, 52f, 40f, v["bad"])
            k.ok(c, 52f, 40f, v["ok"])
            c.save()
            c.translate(v["radio.dx"], 0f)
            k.radio(c, 160f, 56f, light = v["led"])
            k.finger(c, 173f, 83f, v["finger.dx"], v["finger.dy"])
            c.restore()
        },
        "cancel" to { c, k, v ->
            k.fade(v["recording"]) {
                c.drawCircle(18f, 20f, 5f, k.fill(HowToKit.C.RED))
                k.bars(c, 32f, 20f, 6, 7f, 14f)
            }
            k.bin(c, 36f, 74f, v["lid"])
            for (i in 0 until 3) {
                // Positive CSS animation delays start each following chevron later, not earlier.
                val opacity = .25f + .75f * (.5f - .5f * kotlin.math.cos(2 * Math.PI * k.cycle(5_000, 5_000L - i * 300L)).toFloat())
                chevron(c, k, 96f + 18f * i, 75f, opacity)
            }
            k.mic(c, 185f, 75f, 16f, HowToKit.C.RED, HowToKit.C.WHITE)
            k.finger(c, 185f, 75f, v["finger.dx"], v["finger.dy"])
        },
        "lock" to { c, k, v ->
            k.dashed(c, 110f, 66f, 110f, 38f, HowToKit.C.INK, 2.2f, opacity = .4f, short = true)
            k.rect(c, 101f, 20f, 18f, 15f, 3f, HowToKit.C.RADIO)
            shackle(c, k, 110f, 20f + v["shackle"])
            k.fade(1f - v["stop"]) { k.mic(c, 110f, 86f, 16f, HowToKit.C.RED, HowToKit.C.WHITE) }
            k.fade(v["stop"]) {
                k.circle(c, 110f, 86f, 16f, HowToKit.C.RED)
                k.rect(c, 104f, 80f, 12f, 12f, 2f, HowToKit.C.WHITE, stroke = 0f)
            }
            k.finger(c, 110f, 86f, v["finger.dx"], v["finger.dy"])
        },
        "listen" to { c, k, v ->
            c.save()
            c.translate(0f, v["fly.dy"])
            k.fade(v["fly.alpha"]) { k.bubble(c, 34f, 16f, 140f, k.words.bonjour, HowToKit.C.MINT) }
            c.restore()
            k.fade(v["sound"]) { k.waves(c, 180f, 29f) }
            k.circle(c, 70f, 80f, 14f, HowToKit.C.RADIO)
            speaker(c, k, 70f, 80f)
            c.drawArc(listenSoundBounds, -56.44269f, 112.88538f, false, k.line(width = 2f))
            k.circle(c, 110f, 80f, 14f, HowToKit.C.BUBBLE)
            c.drawLine(105f, 75f, 115f, 85f, k.line(width = 2.4f))
            c.drawLine(115f, 75f, 105f, 85f, k.line(width = 2.4f))
            k.circle(c, 150f, 80f, 14f, HowToKit.C.GREEN)
            sendArrow(c, k, 150f, 80f)
            k.finger(c, 70f, 80f, v["finger.dx"], v["finger.dy"])
        },
        "delivery" to { c, k, v ->
            k.bubble(c, 22f, 20f, 130f, k.words.bonjour, HowToKit.C.MINT)
            k.fade(v["waiting"]) {
                k.clock(c, 140f, 58f)
                k.text(c, k.words.waiting, 126f, 62f, 12f, align = Paint.Align.RIGHT)
            }
            k.fade(v["sent"]) {
                c.drawLine(133f, 58f, 145f, 58f, k.line(width = 2.4f))
                c.drawLine(141f, 53f, 146f, 58f, k.line(width = 2.4f))
                c.drawLine(141f, 63f, 146f, 58f, k.line(width = 2.4f))
                k.text(c, k.words.sent, 126f, 62f, 12f, align = Paint.Align.RIGHT)
            }
            k.fade(v["delivered"]) {
                k.ticks(c, 140f, 58f)
                k.text(c, k.words.delivered, 126f, 62f, 12f, HowToKit.C.GREEN, Paint.Align.RIGHT)
            }
            k.radio(c, 176f, 58f, .8f)
            k.fade(v["rings"]) { k.rings(c, 194f, 56f, 26f, 2.2f) }
        },
        "failed" to { c, k, v ->
            k.bubble(c, 30f, 14f, 130f, k.words.bonjour, HowToKit.C.MINT)
            k.fade(v["failed"]) {
                c.drawCircle(176f, 27f, 9f, k.fill(HowToKit.C.RED))
                c.drawLine(176f, 22f, 176f, 27f, k.line(HowToKit.C.WHITE, 2.6f))
                c.drawLine(176f, 31f, 176f, 31.5f, k.line(HowToKit.C.WHITE, 2.6f))
            }
            k.fade(v["waiting"]) { k.clock(c, 176f, 27f, 8f) }
            k.fade(v["delivered"]) { k.ticks(c, 176f, 27f) }
            k.rect(c, 50f, 64f, 120f, 30f, 15f, HowToKit.C.BUBBLE)
            c.save()
            c.rotate(v["spin"], 70f, 79f)
            retry(c, k, 70f, 79f)
            c.restore()
            k.text(c, k.words.resend, 122f, 84f, 13f)
            k.finger(c, 110f, 79f, v["finger.dx"], v["finger.dy"])
        },
        "arrive" to { c, k, v ->
            k.radio(c, 186f, 14.4f, .6f)
            val pulse = v["pulse"]
            if (pulse > 0f) c.drawCircle(194f, 24f, 14f * (.4f + 1.3f * pulse), k.line(HowToKit.C.GREEN, 2.6f, 1f - pulse))
            c.save()
            c.translate(v["message.dx"], 0f)
            k.fade(v["message.alpha"]) {
                k.rect(c, 16f, 50f, 150f, 34f, 10f, HowToKit.C.BUBBLE)
                k.circle(c, 36f, 67f, 11f, HowToKit.C.RADIO)
                speaker(c, k, 36f, 67f)
                k.text(c, k.words.bonjour, 104f, 71f, 11f)
            }
            c.restore()
            k.fade(v["sound"]) { k.waves(c, 170f, 67f) }
            k.finger(c, 36f, 67f, v["finger.dx"], v["finger.dy"])
        },
    )

    fun has(name: String) = name in scenes

    fun draw(name: String, canvas: Canvas, kit: HowToKit, v: HowToValues) =
        checkNotNull(scenes[name]) { "No drawing for how-to scene $name" }(canvas, kit, v)

    /** The faint dashed arc between two phones (friend share). */
    private fun arc(c: Canvas, k: HowToKit) {
        val paint = k.line(opacity = .3f)
        paint.pathEffect = shareDash
        c.drawPath(shareArc, paint)
        paint.pathEffect = null
    }

    private val shareDash = android.graphics.DashPathEffect(floatArrayOf(4f, 6f), 0f)
    private val shareArc = android.graphics.Path().apply { moveTo(62f, 60f); quadTo(110f, 34f, 158f, 60f) }

    /** A right arrow nudging back and forth (CSS arr). */
    private fun arrow(c: Canvas, k: HowToKit, x: Float, y: Float) {
        val nudge = 3f - 3f * kotlin.math.cos(2 * Math.PI * k.cycle(2_000)).toFloat()
        c.drawLine(x + nudge, y, x + 18f + nudge, y, k.line(width = 2.6f))
        c.drawLine(x + 12f + nudge, y - 6f, x + 18f + nudge, y, k.line(width = 2.6f))
        c.drawLine(x + 12f + nudge, y + 6f, x + 18f + nudge, y, k.line(width = 2.6f))
    }

    private val shape = android.graphics.Path()
    private val shapeBounds = android.graphics.RectF()
    // SVG M77 75 a6 6 0 0 1 0 10: the circular arc's centre is (77 - sqrt(11), 80).
    private val listenSoundBounds = android.graphics.RectF(67.68338f, 74f, 79.68338f, 86f)

    /** A red left-pointing triangle showing slide to cancel. */
    private fun chevron(c: Canvas, k: HowToKit, x: Float, y: Float, opacity: Float) {
        shape.rewind(); shape.moveTo(x, y - 5f); shape.lineTo(x - 7f, y); shape.lineTo(x, y + 5f); shape.close()
        c.drawPath(shape, k.fill(HowToKit.C.RED, opacity))
    }

    /** The lock's shackle, its feet at y. */
    private fun shackle(c: Canvas, k: HowToKit, x: Float, y: Float) {
        shape.rewind(); shape.moveTo(x - 5.5f, y); shape.lineTo(x - 5.5f, y - 5f)
        shapeBounds.set(x - 5.5f, y - 10.5f, x + 5.5f, y + .5f)
        shape.arcTo(shapeBounds, 180f, 180f)
        shape.lineTo(x + 5.5f, y)
        c.drawPath(shape, k.line())
    }

    /** The speaker sign centred on (x, y). */
    private fun speaker(c: Canvas, k: HowToKit, x: Float, y: Float) {
        shape.rewind()
        shape.moveTo(x - 6f, y - 3f); shape.lineTo(x - 6f, y + 3f); shape.lineTo(x - 2f, y + 3f); shape.lineTo(x + 3f, y + 7f)
        shape.lineTo(x + 3f, y - 7f); shape.lineTo(x - 2f, y - 3f); shape.close()
        c.drawPath(shape, k.fill(HowToKit.C.INK))
    }

    /** The white send arrow centred on (x, y). */
    private fun sendArrow(c: Canvas, k: HowToKit, x: Float, y: Float) {
        shape.rewind(); shape.moveTo(x - 6f, y - 6f); shape.lineTo(x + 6f, y); shape.lineTo(x - 6f, y + 6f); shape.lineTo(x - 4f, y); shape.close()
        c.drawPath(shape, k.fill(HowToKit.C.WHITE))
    }

    /** The round try-again arrow centred on (x, y). */
    private fun retry(c: Canvas, k: HowToKit, x: Float, y: Float) {
        shapeBounds.set(x - 6f, y - 6f, x + 6f, y + 6f)
        c.drawArc(shapeBounds, -30f, -300f, false, k.line(width = 2.2f))
        c.drawLine(x + 6f, y - 8f, x + 6f, y - 3f, k.line(width = 2.2f))
        c.drawLine(x + 6f, y - 3f, x + 1f, y - 3f, k.line(width = 2.2f))
    }

    /** Draws [block] around (x, y), popping in with [v] like the green check. */
    private inline fun popped(c: Canvas, k: HowToKit, v: Float, x: Float, y: Float, block: () -> Unit) {
        if (v <= 0f) return
        c.save()
        c.translate(x, y)
        val s = .3f + .7f * v
        c.scale(s, s)
        k.fade(v) { block() }
        c.restore()
    }
}

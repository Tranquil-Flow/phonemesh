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

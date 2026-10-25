package org.phonemesh.app

import android.graphics.Canvas
import android.graphics.Paint
import org.phonemesh.core.HowToScene
import kotlin.math.roundToInt

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
            // The draft bar as the chat shows it: Clear, the round Listen button, Send.
            k.rect(c, 14f, 65f, 70f, 30f, 7f, HowToKit.C.BUBBLE)
            c.drawLine(22f, 76f, 30f, 84f, k.line(width = 2.4f))
            c.drawLine(30f, 76f, 22f, 84f, k.line(width = 2.4f))
            k.text(c, k.words.clear, 58f, 84f, 11f)
            k.circle(c, 110f, 80f, 15f, HowToKit.C.RADIO)
            shape.rewind(); shape.moveTo(105f, 72f); shape.lineTo(118f, 80f); shape.lineTo(105f, 88f); shape.close()
            c.drawPath(shape, k.fill(HowToKit.C.INK))
            k.rect(c, 136f, 65f, 70f, 30f, 7f, HowToKit.C.PHONE)
            sendArrow(c, k, 150f, 80f)
            k.text(c, k.words.send, 182f, 84f, 11f, HowToKit.C.WHITE)
            k.finger(c, 110f, 80f, v["finger.dx"], v["finger.dy"])
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
        "bt" to { c, k, v ->
            k.phone(c, 40f, 40f)
            k.bluetoothSign(c, 132f, 36f, 1.2f, k.mix(HowToKit.C.GREY, HowToKit.C.BLUETOOTH, v["switch"]))
            k.rect(c, 112f, 62f, 44f, 22f, 11f, k.mix(0xFFBDBDBD.toInt(), HowToKit.C.BLUETOOTH, v["switch"]))
            k.circle(c, 123f + 20f * v["switch"], 73f, 9f, HowToKit.C.WHITE)
            k.finger(c, 134f, 73f, v["finger.dx"], v["finger.dy"])
        },
        "short" to { c, k, v ->
            k.dashed(c, 110f, 8f, 110f, 100f, HowToKit.C.INK, 2.2f, opacity = .3f)
            k.mic(c, 55f, 62f)
            k.no(c, 80f, 36f, v["bad"])
            k.finger(c, 55f, 62f, v["tap.dx"], v["tap.dy"])
            k.holdRing(c, 165f, 62f, v["hold"])
            k.mic(c, 165f, 62f)
            k.ok(c, 192f, 36f, v["ok"])
            k.finger(c, 165f, 62f, v["finger.dx"], v["finger.dy"])
        },
        "notheard" to { c, k, v ->
            waxDress(c, k)
            k.circle(c, 50f, 50f, 15f, HowToKit.C.SKIN)
            shape.rewind()
            shape.moveTo(34f, 46f); shape.quadTo(50f, 22f, 66f, 46f)
            shape.quadTo(60f, 34f, 50f, 34f); shape.quadTo(40f, 34f, 34f, 46f); shape.close()
            c.drawPath(shape, k.fill(0xFFD84315.toInt()))
            c.drawPath(shape, k.line())
            val open = 1f + .6f * kotlin.math.sin(2 * Math.PI * k.cycle(350)).toFloat()
            c.drawOval(55f, 57f - 1.4f * open, 61f, 57f + 1.4f * open, k.fill(0xFF4A1410.toInt()))
            k.fade(v["far"]) { k.bars(c, 80f, 52f, 3, 6f, 10f) }
            k.phone(c, 74f + v["phone.dx"], 32f + v["phone.dy"])
            k.fade(v["bars"]) { k.bars(c, 118f, 56f, 5, 8f, 24f) }
            k.ok(c, 176f, 40f, v["ok"])
        },
        "mic" to { c, k, v ->
            k.mic(c, 55f, 56f, 20f)
            c.drawLine(38f, 40f, 72f, 74f, k.line(HowToKit.C.RED, 4f))
            val nudge = 3f - 3f * kotlin.math.cos(2 * Math.PI * k.cycle(5_000)).toFloat()
            c.drawLine(92f + nudge, 56f, 118f + nudge, 56f, k.line(width = 3f))
            c.drawLine(110f + nudge, 48f, 118f + nudge, 56f, k.line(width = 3f))
            c.drawLine(110f + nudge, 64f, 118f + nudge, 56f, k.line(width = 3f))
            k.rect(c, 132f, 38f, 62f, 36f, 6f, k.mix(HowToKit.C.BUBBLE, HowToKit.C.RADIO, v["keyboard"]))
            for (i in 0 until 5) {
                k.rect(c, 139f + i * 11, 45f, 7f, 6f, 1f, HowToKit.C.INK, stroke = 0f)
                k.rect(c, 139f + i * 11, 54f, 7f, 6f, 1f, HowToKit.C.INK, stroke = 0f)
            }
            k.rect(c, 150f, 63f, 26f, 5f, 1f, HowToKit.C.INK, stroke = 0f)
            k.finger(c, 163f, 62f, v["finger.dx"], v["finger.dy"])
        },
        "allow" to { c, k, v ->
            k.rect(c, 34f, 12f, 152f, 86f, 12f, HowToKit.C.WHITE)
            k.bluetoothSign(c, 110f, 34f)
            c.drawLine(60f, 56f, 160f, 56f, k.line(opacity = .3f, width = 3f))
            k.rect(c, 44f, 72f, 62f, 20f, 10f, 0xFFEEEEEE.toInt(), stroke = 1.4f)
            k.rect(c, 114f, 72f, 62f, 20f, 10f, k.mix(HowToKit.C.GREEN, 0xFF43A047.toInt(), v["allow"]), stroke = 1.4f)
            k.text(c, k.words.allow, 145f, 86f, 11f, HowToKit.C.WHITE)
            k.ok(c, 176f, 22f, v["ok"])
            k.finger(c, 145f, 82f, v["finger.dx"], v["finger.dy"])
        },
        "dlfail" to { c, k, v ->
            k.mastWifi(c)
            k.no(c, 76f, 22f, v["fail"])
            k.fade(v["dots"]) { k.dots(c) }
            k.phone(c, 140f, 44f)
            k.circle(c, 110f, 88f, 13f, HowToKit.C.BUBBLE)
            retry(c, k, 110f, 88f)
            k.ok(c, 180f, 50f, v["ok"])
            k.finger(c, 110f, 88f, v["finger.dx"], v["finger.dy"])
        },
        "damaged" to { c, k, v ->
            k.fade(v["shake.alpha"]) {
                val dx = v["shake.dx"]
                k.box(c, 30f + dx, 40f, HowToKit.C.GREY)
                c.drawLine(34f + dx, 40f, 38f + dx, 46f, k.line(width = 1.6f))
                c.drawLine(38f + dx, 46f, 35f + dx, 50f, k.line(width = 1.6f))
                c.drawLine(35f + dx, 50f, 39f + dx, 56f, k.line(width = 1.6f))
                k.no(c, 50f + dx, 38f)
            }
            k.phone(c, 140f, 44f)
            k.phoneLevel(c, 140f, 44f, v["fill"])
            k.fade(v["new.alpha"]) { k.box(c, 147f, 14f + v["new.dy"]) }
            k.ok(c, 180f, 50f, v["ok"])
        },
        "refused" to { c, k, v ->
            k.phone(c, 20f, 40f)
            k.box(c, 54f + v["box.dx"], 58f, HowToKit.C.GREY, "?")
            k.phone(c, 140f, 40f)
            shield(c, k, 155f, 52f)
            k.no(c, 128f, 50f, v["bad"])
        },
        "older" to { c, k, v ->
            k.phone(c, 20f, 40f)
            k.box(c, 27f, 54f, HowToKit.C.GREY, "1")
            k.box(c, 54f + v["box.dx"], 58f, HowToKit.C.GREY, "1")
            k.phone(c, 140f, 40f)
            k.box(c, 147f, 54f, HowToKit.C.ORANGE, "2")
            k.no(c, 128f, 50f, v["bad"])
            k.ok(c, 178f, 44f, v["ok"])
        },
        "space" to { c, k, v ->
            k.rect(c, 40f, 20f, 44f, 80f, 6f, HowToKit.C.PHONE)
            k.rect(c, 44f, 26f, 36f, 64f, 1.5f, HowToKit.C.SCREEN, stroke = 0f)
            c.save()
            c.translate(v["trash.dx"], v["trash.dy"])
            k.fade(v["trash.alpha"]) {
                k.rect(c, 47f, 29f, 30f, 13f, 2f, 0xFF7E57C2.toInt(), stroke = 1.4f)
                c.drawLine(51f, 39f, 56f, 34f, k.line(HowToKit.C.WHITE, 1.4f))
                c.drawLine(56f, 34f, 60f, 38f, k.line(HowToKit.C.WHITE, 1.4f))
                c.drawLine(60f, 38f, 63f, 35f, k.line(HowToKit.C.WHITE, 1.4f))
                c.drawLine(63f, 35f, 68f, 39f, k.line(HowToKit.C.WHITE, 1.4f))
            }
            c.restore()
            k.rect(c, 47f, 45f, 30f, 13f, 2f, 0xFF26A69A.toInt(), stroke = 1.4f)
            k.rect(c, 47f, 61f, 30f, 13f, 2f, 0xFF26A69A.toInt(), stroke = 1.4f)
            k.rect(c, 47f, 75f, 30f, 12f, 1f, HowToKit.C.ORANGE, stroke = 1.4f)
            k.bin(c, 140f, 74f)
            k.box(c, 150f + v["voice.dx"], 22f + v["voice.dy"])
            k.finger(c, 62f, 35f, v["finger.dx"], v["finger.dy"])
            k.ok(c, 100f, 20f, v["ok"])
        },
        "wait" to { c, k, v ->
            k.rect(c, 30f, 26f, 160f, 14f, 7f, HowToKit.C.BUBBLE)
            if (v["bar"] > 0f) k.rect(c, 30f, 26f, 160f * v["bar"], 14f, 7f, HowToKit.C.GREEN, stroke = 0f)
            k.rect(c, 60f, 62f, 100f, 30f, 15f, k.mix(0xFFD9CDB0.toInt(), HowToKit.C.RADIO, v["button"]))
            k.fade(v["sand"]) {
                c.drawLine(104f, 70f, 116f, 70f, k.line(width = 2f))
                c.drawLine(104f, 84f, 116f, 84f, k.line(width = 2f))
                shape.rewind(); shape.moveTo(106f, 70f)
                shape.quadTo(106f, 77f, 110f, 77f); shape.quadTo(114f, 77f, 114f, 70f)
                shape.moveTo(106f, 84f)
                shape.quadTo(106f, 77f, 110f, 77f); shape.quadTo(114f, 77f, 114f, 84f)
                c.drawPath(shape, k.line(width = 2f))
            }
            k.fade(v["check"]) {
                c.drawLine(100f, 77f, 107f, 84f, k.line(width = 3f))
                c.drawLine(107f, 84f, 119f, 72f, k.line(width = 3f))
            }
            k.finger(c, 110f, 77f, v["finger.dx"], v["finger.dy"])
        },
        "toolong" to { c, k, v ->
            k.circle(c, 186f, 22f, 13f, k.mix(HowToKit.C.BUBBLE, 0xFFEF9A9A.toInt(), v["red"]))
            c.drawLine(183f, 5f, 189f, 5f, k.line())
            c.drawLine(186f, 9f, 186f, 5f, k.line())
            c.save()
            c.rotate(v["sweep"], 186f, 22f)
            c.drawLine(186f, 22f, 186f, 13f, k.line(width = 2.4f))
            c.restore()
            for (i in 0 until 15) c.drawLine(26f + i * 8, 62f - BAR_HEIGHTS[i], 26f + i * 8, 62f + BAR_HEIGHTS[i], k.line(HowToKit.C.BLUE, 3.2f))
            c.save()
            c.translate(v["fall.dx"], v["fall.dy"])
            c.rotate(v["fall.rot"], 146f, 62f)
            k.fade(v["fall.alpha"]) {
                for (i in 15 until 22) c.drawLine(26f + i * 8, 62f - BAR_HEIGHTS[i], 26f + i * 8, 62f + BAR_HEIGHTS[i], k.line(HowToKit.C.BLUE, 3.2f))
            }
            c.restore()
            c.drawRect(20f + v["reveal"], 38f, 220f + v["reveal"], 86f, k.fill(HowToKit.C.PANEL))
            k.dashed(c, 142f, 34f, 142f, 92f, HowToKit.C.RED, 2.4f)
            k.fade(v["snip"]) { scissors(c, k, 142f, 98f) }
        },
        // ---- The chat gestures (owner approved round 10, 2026-10-12); every label is the app's own word.
        "hold" to { c, k, v ->
            card(c, k, 10f, 30f, 118f, NAME_KOFI, k.words.chat.market, k.mix(HowToKit.C.BUBBLE, LIT, v["light"]))
            pressRing(c, k, 68f, 46f, v["press"], v["press.alpha"])
            pop(c, k, v["menu"], 174f, 49.8f) { menu(c, k, 134f, 2f, 80f, messageMenu(k)) { v["row$it"] } }
            k.finger(c, 68f, 46f, v["finger.dx"], v["finger.dy"])
        },
        "reply" to { c, k, v ->
            card(c, k, 10f, 8f, 118f, NAME_KOFI, k.words.chat.market, k.mix(HowToKit.C.BUBBLE, LIT, v["light"]))
            pop(c, k, v["menu"], 174f, 49.8f) { menu(c, k, 134f, 2f, 80f, messageMenu(k)) { if (it == 0) v["row0"] else 0f } }
            k.fade(v["quote"]) {
                k.rect(c, 10f, 66f, 118f, 20f, 5f, HowToKit.C.WHITE, stroke = 1.3f)
                k.icon(c, R.drawable.ic_reply, 19f, 76f, 8f, MUTED)
                k.fitted(c, k.words.chat.market, 27f, 78.5f, 7.5f, 86f, MUTED, Paint.Align.LEFT, bold = false)
                k.icon(c, R.drawable.ic_close, 120f, 76f, 7f)
            }
            if (v["link"] > 0f) {
                shape.rewind(); shape.moveTo(56f, 40f); shape.quadTo(56f, 62f, 98f, 64f)
                val paint = k.line(HowToKit.C.GREEN, 2f, v["link"])
                paint.pathEffect = dash34
                c.drawPath(shape, paint)
                paint.pathEffect = null
            }
            c.save()
            c.translate(0f, v["answer.dy"])
            k.fade(v["answer"]) {
                k.rect(c, 92f, 50f, 118f, 48f, 10f, HowToKit.C.MINT)
                k.rect(c, 98f, 55f, 106f, 18f, 5f, HowToKit.C.WHITE, stroke = 1.3f)
                k.fitted(c, k.words.chat.market, 103f, 67.5f, 8f, 98f, MUTED, Paint.Align.LEFT, bold = false)
                k.fitted(c, k.words.chat.coming, 100f, 89f, 10f, 104f, align = Paint.Align.LEFT)
            }
            c.restore()
            k.finger(c, 160f, 13f, v["finger.dx"], v["finger.dy"])
        },
        "react" to { c, k, v ->
            card(c, k, 10f, 14f, 120f, NAME_KOFI, k.words.chat.market, HowToKit.C.BUBBLE)
            k.radio(c, 92f, 64f, .75f)
            k.fade(v["tx"]) { k.rings(c, 102f, 62f, 24f, 2.2f) }
            pop(c, k, v["window"], 176f, 44f) {
                k.rect(c, 138f, 4f, 76f, 80f, 7f, HowToKit.C.WHITE, stroke = 1.4f)
                k.fitted(c, k.words.chat.react, 144f, 15f, 8f, 64f, align = Paint.Align.LEFT)
                AppCore.REACTIONS.take(6).forEachIndexed { i, emoji ->
                    val x = 143f + (i % 3) * 23f
                    val y = 21f + (i / 3) * 25f
                    k.rect(c, x, y, 20f, 22f, 4f, if (i == 0) k.mix(HowToKit.C.WHITE, LIT, v["lit"]) else HowToKit.C.WHITE, stroke = 1.3f)
                    k.text(c, emoji, x + 10f, y + 15f, 11f)
                }
                dialogCancel(c, k, 208f, 79f)
            }
            k.fade(v["fly.alpha"]) { k.text(c, AppCore.REACTIONS[0], 153f + v["fly.dx"], 36f + v["fly.dy"], 11f) }
            k.fade(v["chip"]) { k.text(c, AppCore.REACTIONS[0], 22f, 63f, 10f) }
            k.ok(c, 46f, 59f, v["ok"])
            k.finger(c, 153f, 32f, v["finger.dx"], v["finger.dy"])
        },
        "forward" to { c, k, v ->
            k.rect(c, 6f, 6f, 94f, 94f, 8f, HowToKit.C.WHITE)
            groupAvatar(c, k, 17f, 17f, 12f)
            k.fitted(c, k.words.chat.group, 26f, 20f, 7.5f, 70f, align = Paint.Align.LEFT)
            card(c, k, 12f, 30f, 80f, NAME_AMA, k.words.chat.market8, HowToKit.C.BUBBLE)
            pressRing(c, k, 52f, 46f, v["press"], v["press.alpha"])
            pop(c, k, v["menu"], 170f, 49.8f) { menu(c, k, 128f, 2f, 84f, messageMenu(k)) { if (it == 4) v["row"] else 0f } }
            pop(c, k, v["list"], 166f, 49f, from = .6f) {
                k.rect(c, 118f, 20f, 96f, 58f, 7f, HowToKit.C.WHITE, stroke = 1.4f)
                k.fitted(c, k.words.chat.forwardTo, 124f, 31f, 7.5f, 86f, align = Paint.Align.LEFT)
                k.rect(c, 122f, 36f, 88f, 16f, 3.5f, k.mix(HowToKit.C.WHITE, LIT, v["pick"]), stroke = 0f)
                personAvatar(c, k, 131f, 44f, 11f)
                k.text(c, NAME_KOFI, 140f, 46.6f, 7.5f, align = Paint.Align.LEFT, bold = false)
                personAvatar(c, k, 131f, 62f, 11f)
                k.text(c, NAME_AMA, 140f, 64.6f, 7.5f, align = Paint.Align.LEFT, bold = false)
                dialogCancel(c, k, 208f, 75.5f)
            }
            k.fade(v["kofi"]) {
                k.rect(c, 112f, 6f, 102f, 94f, 8f, HowToKit.C.WHITE)
                personAvatar(c, k, 123f, 17f, 12f)
                k.text(c, NAME_KOFI, 132f, 20f, 7.5f, align = Paint.Align.LEFT)
                c.save()
                c.translate(0f, v["sent.dy"])
                k.fade(v["sent"]) {
                    k.rect(c, 146f, 28f, 62f, 20f, 7f, HowToKit.C.MINT)
                    k.fitted(c, k.words.chat.market8, 177f, 41f, 8f, 56f)
                }
                c.restore()
                dashedBox(c, k, 118f, 56f, 90f, 20f, 6f, 1.5f)
                k.fade(v["draft"]) { k.fitted(c, k.words.chat.market8, 163f, 69f, 8f, 84f) }
                k.circle(c, 128f, 88f, 6f, HowToKit.C.RADIO, stroke = 1.4f)
                triangle(c, k, 126.5f, 85.5f, 130.5f, 88f, HowToKit.C.INK)
                k.rect(c, 164f, 81f, 44f, 13f, 4f, k.mix(HowToKit.C.PHONE, HowToKit.C.GREEN, v["send"]), stroke = 0f)
                triangle(c, k, 169f, 84.5f, 174f, 87.5f, HowToKit.C.WHITE)
                k.fitted(c, k.words.send, 191f, 90.2f, 6.6f, 30f, HowToKit.C.WHITE)
            }
            k.fade(v["ghost"]) {
                c.save()
                c.translate(106f * v["ghost.move"], 22f * v["ghost.move"])
                k.rect(c, 12f, 30f, 80f, 32f, 9f, HowToKit.C.BUBBLE)
                k.fitted(c, k.words.chat.market8, 52f, 49f, 8f, 72f)
                c.restore()
            }
            k.ok(c, 206f, 12f, v["ok"])
            k.finger(c, 52f, 46f, v["finger.dx"], v["finger.dy"])
        },
        "delete" to { c, k, v ->
            k.rect(c, 8f, 8f, 88f, 92f, 8f, HowToKit.C.WHITE)
            val g = v["gone"]
            c.save()
            c.translate(52f + 70f * g, 27f + 52f * g)
            c.scale(1f - .7f * g, 1f - .7f * g)
            c.translate(-52f, -27f)
            k.fade(v["gone.alpha"]) {
                k.rect(c, 14f, 16f, 76f, 22f, 7f, HowToKit.C.BUBBLE)
                k.fitted(c, k.words.chat.hello, 52f, 30.5f, 9f, 70f)
            }
            c.restore()
            c.save()
            c.translate(0f, v["up"])
            k.rect(c, 18f, 44f, 72f, 22f, 7f, HowToKit.C.MINT)
            k.fitted(c, k.words.chat.seeYou, 54f, 58.5f, 9f, 66f)
            c.restore()
            pressRing(c, k, 52f, 27f, v["press"], v["press.alpha"])
            k.bin(c, 112f, 76f, v["lid"])
            k.dashed(c, 146f, 8f, 146f, 100f, HowToKit.C.INK, 2.2f, opacity = .3f)
            k.phone(c, 166f, 34f)
            k.rect(c, 169f, 43f, 20f, 8f, 2.5f, k.mix(HowToKit.C.BUBBLE, LIT, v["other"]), stroke = 1f)
            k.rect(c, 174f, 54f, 16f, 8f, 2.5f, HowToKit.C.MINT, stroke = 1f)
            k.ok(c, 202f, 34f, v["ok"])
            k.finger(c, 52f, 27f, v["finger.dx"], v["finger.dy"])
        },
        "pin" to { c, k, v ->
            k.rect(c, 30f, 4f, 160f, 98f, 8f, HowToKit.C.WHITE)
            groupAvatar(c, k, 46f, 22f, 14f)
            k.fitted(c, k.words.chat.group, 58f, 20f, 8f, 120f, align = Paint.Align.LEFT)
            k.rect(c, 58f, 25f, 70f, 3.5f, 1.7f, LINE_GREY, stroke = 0f)
            val move = v["move"]
            c.save()
            c.translate(0f, 30f * move)
            k.rect(c, 34f, 38f, 152f, 28f, 6f, HowToKit.C.WHITE, stroke = 0f)
            personAvatar(c, k, 46f, 52f, 14f)
            k.text(c, NAME_AMA, 58f, 50f, 8f, align = Paint.Align.LEFT)
            k.rect(c, 58f, 55f, 80f, 3.5f, 1.7f, LINE_GREY, stroke = 0f)
            c.restore()
            c.save()
            c.translate(0f, -30f * move)
            k.rect(c, 34f, 68f, 152f, 28f, 6f, k.mix(HowToKit.C.WHITE, PINNED, v["lit"]), stroke = 0f)
            personAvatar(c, k, 46f, 82f, 14f)
            k.text(c, NAME_KOFI, 58f, 80f, 8f, align = Paint.Align.LEFT)
            k.rect(c, 58f, 85f, 60f, 3.5f, 1.7f, LINE_GREY, stroke = 0f)
            pop(c, k, v["pin"], 83f, 77.5f, from = .3f) { k.icon(c, R.drawable.ic_pin, 83f, 77.5f, 8f, MUTED) }
            c.restore()
            pressRing(c, k, 110f, 82f, v["press"], v["press.alpha"])
            pop(c, k, v["menu"], 110f, 44f) {
                k.rect(c, 50f, 22f, 120f, 44f, 7f, HowToKit.C.WHITE, stroke = 1.4f)
                k.rect(c, 55f, 27f, 110f, 13f, 3.5f, k.mix(HowToKit.C.WHITE, LIT, v["row"]), stroke = 1.3f)
                k.icon(c, R.drawable.ic_pin, 63f, 33.5f, 7.5f)
                k.fitted(c, k.words.chat.pin, 70f, 36.2f, 6.8f, 92f, align = Paint.Align.LEFT)
                k.rect(c, 55f, 43f, 110f, 13f, 3.5f, HowToKit.C.WHITE, stroke = 1.3f)
                k.icon(c, R.drawable.ic_delete, 63f, 49.5f, 7.5f)
                k.fitted(c, k.words.chat.deleteChat, 70f, 52.2f, 6.8f, 92f, align = Paint.Align.LEFT)
                dialogCancel(c, k, 164f, 63f)
            }
            k.ok(c, 204f, 16f, v["ok"])
            k.finger(c, 110f, 82f, v["finger.dx"], v["finger.dy"])
        },
        "later" to { c, k, v ->
            k.rect(c, 10f, 58f, 100f, 40f, 8f, HowToKit.C.WHITE, stroke = 0f)
            dashedBox(c, k, 10f, 58f, 100f, 40f, 8f, 1.8f)
            k.fade(v["draft"]) {
                k.fitted(c, k.words.chat.hello, 18f, 73f, 9.5f, 86f, align = Paint.Align.LEFT, bold = false)
                k.rect(c, 52f, 80f, 54f, 14f, 4f, k.mix(HowToKit.C.WHITE, LIT, v["button"]), stroke = 1.4f)
                k.icon(c, R.drawable.ic_clock, 60f, 87f, 7f)
                k.fitted(c, k.words.chat.later, 84f, 90.2f, 7f, 38f)
            }
            val times = k.words.chat.let { listOf(it.t1745, it.t1800, it.t1815) }
            pop(c, k, v["window"], 165f, 52f, from = .5f) {
                timeWindow(c, k, 116f, time = times[v["time"].roundToInt().coerceIn(0, 2)],
                    plus = k.mix(HowToKit.C.WHITE, LIT, v["plus"]), go = k.mix(GO, HowToKit.C.GREEN, v["go"]))
            }
            c.save()
            c.translate(0f, v["wait.dy"])
            k.fade(v["wait"]) {
                k.rect(c, 10f, 8f, 124f, 34f, 8f, HowToKit.C.WHITE, stroke = 1.6f)
                k.circle(c, 23f, 25f, 7f, HowToKit.C.WHITE, stroke = 1.6f)
                c.save()
                c.rotate(v["hand"], 23f, 25f)
                c.drawLine(23f, 25f, 23f, 20.5f, k.line(width = 1.6f))
                c.restore()
                c.drawLine(23f, 25f, 26f, 26.5f, k.line(width = 1.4f))
                k.fitted(c, k.words.chat.sendsAt1815, 36f, 22f, 8f, 80f, align = Paint.Align.LEFT)
                k.fitted(c, k.words.chat.hello, 36f, 34f, 8f, 80f, MUTED, Paint.Align.LEFT, bold = false)
                k.icon(c, R.drawable.ic_close, 124f, 25f, 7f)
            }
            c.restore()
            pop(c, k, v["sent"], 72f, 25f, from = .6f) {
                k.rect(c, 10f, 8f, 124f, 34f, 9f, HowToKit.C.MINT)
                k.fitted(c, k.words.chat.hello, 18f, 22f, 9.5f, 90f, align = Paint.Align.LEFT, bold = false)
                k.fitted(c, k.words.chat.plannedFor1815, 18f, 35f, 7.2f, 90f, MUTED, Paint.Align.LEFT, bold = false)
                k.ticks(c, 121f, 30f)
            }
            k.fade(v["tx"]) { k.waves(c, 138f, 25f) }
            k.finger(c, 84f, 87f, v["finger.dx"], v["finger.dy"])
        },
        "later_pick" to { c, k, v ->
            val w = k.words.chat
            val chosen = v["chosen"] >= .5f
            timeWindow(c, k, 6f, time = if (v["time"] >= .5f) w.t0800 else w.t1745,
                today = k.mix(HowToKit.C.WHITE, HowToKit.C.RADIO, v["today"]),
                day = if (chosen) HowToKit.C.RADIO else k.mix(HowToKit.C.WHITE, LIT, v["calLit"]),
                dayText = if (chosen) w.chosenDay else null,
                timeFill = k.mix(HowToKit.C.WHITE, LIT, v["timeLit"]))
            pop(c, k, v["clock"], 163f, 52f, from = .6f) { androidClock(c, k, v["header"].roundToInt(), v["minutes"] >= .5f) }
            pop(c, k, v["calendar"], 163f, 52f, from = .6f) { androidCalendar(c, k, if (v["day"] >= .5f) 15 else 12) }
            k.ok(c, 100f, 9f, v["ok"])
            k.finger(c, 55f, 58f, v["finger.dx"], v["finger.dy"])
        },
        "playfail" to { c, k, v ->
            k.rect(c, 16f, 40f, 190f, 40f, 10f, k.mix(HowToKit.C.BUBBLE, 0xFFFFE082.toInt(), v["light"]))
            k.circle(c, 38f, 60f, 12f, HowToKit.C.RADIO)
            speaker(c, k, 38f, 60f)
            k.no(c, 50f, 48f)
            k.text(c, k.words.bonjour, 130f, 65f, 12f)
            k.fade(v["arrow"]) {
                shape.rewind(); shape.moveTo(76f, 92f); shape.quadTo(126f, 106f, 176f, 92f)
                c.drawPath(shape, k.line(HowToKit.C.GREEN, 2.6f))
                c.drawLine(168f, 86f, 176f, 92f, k.line(HowToKit.C.GREEN, 2.6f))
                c.drawLine(176f, 92f, 167f, 96f, k.line(HowToKit.C.GREEN, 2.6f))
            }
        },
    )

    fun has(name: String) = name in scenes || AccountsHowToDrawings.has(name)

    fun draw(name: String, canvas: Canvas, kit: HowToKit, v: HowToValues) {
        if (AccountsHowToDrawings.has(name)) AccountsHowToDrawings.draw(name, canvas, kit, v)
        else checkNotNull(scenes[name]) { "No drawing for how-to scene $name" }(canvas, kit, v)
    }

    // ---- The chat gestures' shared parts

    private const val NAME_KOFI = "Kofi"
    private const val NAME_AMA = "Ama"
    /** A button lit under the finger. */
    private const val LIT = 0xFFFFE082.toInt()
    private const val MUTED = 0xFF6D6D6D.toInt()
    private const val LINE_GREY = 0x4D2B1A10
    private const val PINNED = 0xFFFFF4CC.toInt()
    private const val GO = 0xFF111111.toInt()
    /** The app's own accent, as Android's date and time windows show it. */
    private const val ACCENT = 0xFF0B6E2C.toInt()
    private const val FACE = 0xFFEEEEEE.toInt()
    private const val DIGIT = 0xFF212121.toInt()
    private val dash34 = android.graphics.DashPathEffect(floatArrayOf(3f, 4f), 0f)
    private val dash44 = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)

    /** A message: the sender's name over the words. */
    private fun card(c: Canvas, k: HowToKit, x: Float, y: Float, width: Float, who: String, words: String, fill: Int) {
        k.rect(c, x, y, width, 32f, 9f, fill)
        k.text(c, who, x + 8f, y + 12f, 7.5f, align = Paint.Align.LEFT)
        k.fitted(c, words, x + 8f, y + 25f, 9.5f, width - 14f, align = Paint.Align.LEFT, bold = false)
    }

    /** The ring that fills under a finger held on a message or a chat. */
    private fun pressRing(c: Canvas, k: HowToKit, cx: Float, cy: Float, progress: Float, opacity: Float) {
        if (progress <= 0f || opacity <= 0f) return
        shapeBounds.set(cx - 12f, cy - 12f, cx + 12f, cy + 12f)
        c.drawArc(shapeBounds, -90f, 360f * progress, false, k.line(HowToKit.C.GREEN, 3f, opacity))
    }

    /** Draws [block] grown from [from] to full size around (cx, cy) as [v] goes 0 → 1, fading in with it. */
    private inline fun pop(c: Canvas, k: HowToKit, v: Float, cx: Float, cy: Float, from: Float = .4f, block: () -> Unit) {
        if (v <= 0f) return
        c.save()
        val s = from + (1f - from) * v
        c.translate(cx, cy)
        c.scale(s, s)
        c.translate(-cx, -cy)
        k.fade(v) { block() }
        c.restore()
    }

    /** The message long-press menu, as `ConversationActivity.showActions` lists it. */
    private fun messageMenu(k: HowToKit): List<Pair<Int, String>> = k.words.chat.let {
        listOf(R.drawable.ic_reply to it.reply, R.drawable.ic_react to it.react, R.drawable.ic_copy to it.copy,
            R.drawable.ic_send to it.share, R.drawable.ic_forward to it.forward, R.drawable.ic_delete to it.delete)
    }

    /** `Actions.menu`: a window of outlined buttons, each an icon and a word, then Cancel. */
    private inline fun menu(c: Canvas, k: HowToKit, x: Float, y: Float, width: Float, items: List<Pair<Int, String>>, lit: (Int) -> Float) {
        k.rect(c, x, y, width, 6f + items.size * 13.6f + 8f, 7f, HowToKit.C.WHITE, stroke = 1.4f)
        items.forEachIndexed { i, (icon, label) ->
            val row = y + 4f + i * 13.6f
            k.rect(c, x + 4f, row, width - 8f, 11.6f, 3.5f, k.mix(HowToKit.C.WHITE, LIT, lit(i)), stroke = 1.3f)
            k.icon(c, icon, x + 11f, row + 5.8f, 7.5f)
            k.fitted(c, label, x + 18f, row + 8.3f, 6.8f, width - 24f, align = Paint.Align.LEFT)
        }
        dialogCancel(c, k, x + width - 6f, y + 6f + items.size * 13.6f + 5.5f)
    }

    /** `Looks.avatar` for the group: a yellow rounded square with the people sign. */
    private fun groupAvatar(c: Canvas, k: HowToKit, x: Float, y: Float, size: Float) {
        k.rect(c, x - size / 2, y - size / 2, size, size, size / 4, HowToKit.C.RADIO, stroke = 1.5f)
        k.icon(c, R.drawable.ic_group, x, y, size * .62f)
    }

    /** `Looks.avatar` for a person: a white circle with the person sign. */
    private fun personAvatar(c: Canvas, k: HowToKit, x: Float, y: Float, size: Float) {
        k.circle(c, x, y, size / 2, HowToKit.C.WHITE, stroke = 1.5f)
        k.icon(c, R.drawable.ic_person, x, y, size * .6f)
    }

    /** The dashed outline of a draft. */
    private fun dashedBox(c: Canvas, k: HowToKit, x: Float, y: Float, width: Float, height: Float, radius: Float, stroke: Float) {
        shapeBounds.set(x, y, x + width, y + height)
        val paint = k.line(width = stroke)
        paint.pathEffect = dash44
        c.drawRoundRect(shapeBounds, radius, radius, paint)
        paint.pathEffect = null
    }

    /** A play triangle from its top-left corner (x0, y0) to its point (x1, y1). */
    private fun triangle(c: Canvas, k: HowToKit, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        shape.rewind(); shape.moveTo(x0, y0); shape.lineTo(x0, 2 * y1 - y0); shape.lineTo(x1, y1); shape.close()
        c.drawPath(shape, k.fill(color))
    }

    /** `PlanDialog`, "Send later", as a small drawing with its left edge at x. */
    private fun timeWindow(
        c: Canvas, k: HowToKit, x: Float, time: String, today: Int = HowToKit.C.RADIO, day: Int = HowToKit.C.WHITE,
        dayText: String? = null, timeFill: Int = HowToKit.C.WHITE, plus: Int = HowToKit.C.WHITE, go: Int = GO,
    ) {
        val w = k.words.chat
        k.rect(c, x, 4f, 98f, 96f, 8f, HowToKit.C.WHITE, stroke = 1.4f)
        k.fitted(c, w.planTitle, x + 5f, 13.5f, 7.2f, 88f, align = Paint.Align.LEFT)
        k.rect(c, x + 4f, 18f, 44f, 12f, 3f, today, stroke = 1.3f)
        k.fitted(c, w.today, x + 26f, 26.4f, 6f, 40f)
        k.rect(c, x + 50f, 18f, 44f, 12f, 3f, HowToKit.C.WHITE, stroke = 1.3f)
        k.fitted(c, w.tomorrow, x + 72f, 26.4f, 6f, 40f)
        k.rect(c, x + 4f, 33f, 90f, 12f, 3f, day, stroke = 1.3f)
        k.icon(c, R.drawable.ic_calendar, x + 12f, 39f, 7f)
        k.fitted(c, dayText ?: w.pickDay, x + 20f, 41.4f, 6f, 70f, align = Paint.Align.LEFT, bold = dayText != null)
        k.rect(c, x + 4f, 49f, 18f, 18f, 3f, HowToKit.C.WHITE, stroke = 1.3f)
        k.text(c, "−", x + 13f, 61.5f, 10f)
        k.rect(c, x + 25f, 49f, 48f, 18f, 3f, timeFill, stroke = 1.3f)
        k.icon(c, R.drawable.ic_plan_sun, x + 33f, 58f, 8f, tint = false)
        k.fitted(c, time, x + 54f, 61.6f, 10f, 30f)
        k.rect(c, x + 76f, 49f, 18f, 18f, 3f, plus, stroke = 1.3f)
        k.text(c, "+", x + 85f, 61.5f, 10f)
        k.rect(c, x + 4f, 71f, 90f, 14f, 3f, go, stroke = 0f)
        k.icon(c, R.drawable.ic_clock, x + 33f, 78f, 7f, HowToKit.C.WHITE)
        k.fitted(c, w.schedule, x + 52f, 80.4f, 6.8f, 34f, HowToKit.C.WHITE)
        dialogCancel(c, k, x + 93f, 95f)
    }

    /** Android's clock window: [step] 0 shows 17:45, 1 the hour 8 picked, 2 08:00; [minutes] the minute face. */
    private fun androidClock(c: Canvas, k: HowToKit, step: Int, minutes: Boolean) {
        val w = k.words.chat
        k.rect(c, 112f, 4f, 102f, 96f, 2f, HowToKit.C.WHITE, stroke = 1f)
        k.rect(c, 112f, 4f, 102f, 22f, 0f, ACCENT, stroke = 0f)
        k.fitted(c, listOf(w.t1745, w.t0845, w.t0800)[step.coerceIn(0, 2)], 163f, 20f, 14f, 90f, HowToKit.C.WHITE, bold = false)
        k.circle(c, 163f, 60f, 29f, FACE, stroke = 0f)
        if (!minutes) {
            // 17 sits on the inner ring of a 24-hour clock, or at 5 on a 12-hour one.
            if (step < 1) clockHand(c, k, 5 * PI_6, if (w.is24Hour) 15f else 24f) else clockHand(c, k, 8 * PI_6, 24f)
            for (i in 0 until 12) {
                clockNumber(c, k, i * PI_6, 24f, (if (i == 0) 12 else i).toString(), 5.5f, DIGIT)
                if (w.is24Hour) clockNumber(c, k, i * PI_6, 15f, if (i == 0) "00" else (12 + i).toString(), 4.2f, 0xFF616161.toInt())
            }
        } else {
            clockHand(c, k, if (step < 2) 9 * PI_6 else 0f, 24f)
            for (i in 0 until 12) clockNumber(c, k, i * PI_6, 24f, "%02d".format(i * 5), 5.5f, DIGIT)
        }
        pickerButtons(c, k)
    }

    private const val PI_6 = (Math.PI / 6).toFloat()

    private fun clockHand(c: Canvas, k: HowToKit, angle: Float, radius: Float) {
        val x = 163f + radius * kotlin.math.sin(angle)
        val y = 60f - radius * kotlin.math.cos(angle)
        c.drawLine(163f, 60f, x, y, k.line(ACCENT, 1f))
        c.drawCircle(163f, 60f, 1.3f, k.fill(ACCENT))
        c.drawCircle(x, y, 4.6f, k.fill(ACCENT, .35f))
    }

    private fun clockNumber(c: Canvas, k: HowToKit, angle: Float, radius: Float, label: String, size: Float, color: Int) =
        k.text(c, label, 163f + radius * kotlin.math.sin(angle), 60f - radius * kotlin.math.cos(angle) + size * .36f, size, color, bold = false)

    /** Android's calendar window for October 2026, today the 12th, [selected] chosen. */
    private fun androidCalendar(c: Canvas, k: HowToKit, selected: Int) {
        val w = k.words.chat
        k.rect(c, 112f, 4f, 102f, 96f, 2f, HowToKit.C.WHITE, stroke = 1f)
        k.rect(c, 112f, 4f, 102f, 22f, 0f, ACCENT, stroke = 0f)
        k.text(c, w.year, 118f, 12f, 5f, 0xB3FFFFFF.toInt(), Paint.Align.LEFT, bold = false)
        k.fitted(c, if (selected == 15) w.header15 else w.header12, 118f, 22.5f, 9f, 90f, HowToKit.C.WHITE, Paint.Align.LEFT, bold = false)
        k.fitted(c, w.month, 163f, 34f, 5.5f, 90f, DIGIT)
        w.weekdays.forEachIndexed { col, letter -> k.text(c, letter, 124.5f + col * 13f, 42f, 4.6f, 0xFF757575.toInt(), bold = false) }
        for (n in 1..31) {
            val cell = n - 1 + w.firstColumn
            val x = 124.5f + (cell % 7) * 13f
            val y = 49f + (cell / 7) * 8.5f
            if (n == selected) c.drawCircle(x, y, 4.2f, k.fill(ACCENT))
            val color = when {
                n == selected -> HowToKit.C.WHITE
                n < 12 -> 0xFFBDBDBD.toInt()
                n == 12 -> ACCENT
                else -> DIGIT
            }
            k.text(c, n.toString(), x, y + 1.6f, 4.6f, color, bold = n == 12)
        }
        pickerButtons(c, k)
    }

    /** A dialog's Cancel button, as Android draws it: capitals in the app's accent, its right edge at x. */
    private fun dialogCancel(c: Canvas, k: HowToKit, x: Float, y: Float) =
        k.fitted(c, k.words.chat.cancel, x, y, 6f, 40f, ACCENT, Paint.Align.RIGHT)

    /** Cancel and OK, in capitals as Android's own windows write them. */
    private fun pickerButtons(c: Canvas, k: HowToKit) {
        dialogCancel(c, k, 196f, 97f)
        k.text(c, "OK", 205f, 97f, 6f, ACCENT, bold = false)
    }

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

    /** The not-heard figure's dress uses the same anchored wax tiles as the picture's band. */
    private fun waxDress(c: Canvas, k: HowToKit) {
        shape.rewind()
        shape.moveTo(30f, 104f); shape.cubicTo(30f, 82f, 39f, 68f, 50f, 68f)
        shape.cubicTo(61f, 68f, 70f, 82f, 70f, 104f)
        c.save()
        c.clipPath(shape)
        // Reuse the qualified, origin-anchored motif instead of duplicating its palette and geometry.
        for (offset in 6..36 step 6) {
            c.save()
            c.translate(0f, -offset.toFloat())
            k.band(c)
            c.restore()
        }
        c.restore()
        c.drawPath(shape, k.line())
    }

    /** The mockup's 22 voice-bar heights for the too-long picture. */
    private val BAR_HEIGHTS = floatArrayOf(6f, 10f, 14f, 8f, 12f, 16f, 6f, 10f, 12f, 8f, 14f, 6f, 10f, 16f, 8f, 12f, 6f, 10f, 14f, 8f, 12f, 6f)

    /** The green shield with a white tick, its top centre at (x, y). */
    private fun shield(c: Canvas, k: HowToKit, x: Float, y: Float) {
        shape.rewind()
        shape.moveTo(x, y); shape.lineTo(x + 8f, y + 3f); shape.lineTo(x + 8f, y + 10f)
        shape.quadTo(x + 8f, y + 17f, x, y + 20f); shape.quadTo(x - 8f, y + 17f, x - 8f, y + 10f); shape.lineTo(x - 8f, y + 3f); shape.close()
        c.drawPath(shape, k.fill(HowToKit.C.GREEN))
        c.drawLine(x - 4f, y + 10f, x - 1f, y + 13f, k.line(HowToKit.C.WHITE, 2f))
        c.drawLine(x - 1f, y + 13f, x + 4f, y + 7f, k.line(HowToKit.C.WHITE, 2f))
    }

    /** Red scissors, their pivot at (x, y). */
    private fun scissors(c: Canvas, k: HowToKit, x: Float, y: Float) {
        c.drawCircle(x - 4f, y + 2f, 3f, k.line(HowToKit.C.RED, 2f))
        c.drawCircle(x + 4f, y + 2f, 3f, k.line(HowToKit.C.RED, 2f))
        c.drawLine(x - 2f, y, x + 4f, y - 9f, k.line(HowToKit.C.RED, 2f))
        c.drawLine(x + 2f, y, x - 4f, y - 9f, k.line(HowToKit.C.RED, 2f))
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

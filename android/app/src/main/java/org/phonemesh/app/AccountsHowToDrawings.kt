package org.phonemesh.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import org.phonemesh.app.HowToKit.C

/** Cached, localized labels; add this as the trailing `accounts` member of HowToKit.Words. */
data class AccountsHowToWords(val add: String, val sendLink: String, val chat: String, val sms: String, val awa: String) {
    companion object {
        val EMPTY = AccountsHowToWords("", "", "", "", "")
        fun of(context: Context) = AccountsHowToWords(
            context.getString(R.string.add_title), context.getString(R.string.add_send_link),
            context.getString(R.string.howto_share_chat), context.getString(R.string.howto_share_sms),
            context.getString(R.string.howto_name_awa),
        )
    }
}

/** Approved accounts pictures; caller retains backdrop, band, clock and frame ownership. */
object AccountsHowToDrawings {
    private const val PURPLE = 0xFF8E24AA.toInt()
    private const val SHIRT = 0xFF43A047.toInt()
    private const val ACCEPT = 0xFF2E7D32.toInt()
    private const val LIT = 0xFF69F0AE.toInt()
    private const val YELLOW = 0xFFFFE082.toInt()
    private const val PINK = 0xFFFFCDD2.toInt()
    private val six = intArrayOf(4, 28, 43, 12, 62, 19)
    private val cellTracks = Array(6) { "cell$it" }
    private val qrModules = intArrayOf(4,1, 5,2, 4,4, 6,4, 5,5, 7,5, 4,6, 6,7, 7,7, 5,7, 1,4, 2,5, 7,3)
    private val coneA = Path().apply { moveTo(78f,22f); lineTo(118f,30f); lineTo(118f,70f); lineTo(78f,62f); close() }
    private val coneB = Path().apply { moveTo(142f,22f); lineTo(102f,30f); lineTo(102f,70f); lineTo(142f,62f); close() }
    private val shoulders = Path().apply { moveTo(-15f,27f); cubicTo(-15f,12f,15f,12f,15f,27f); close() }
    private val hairA = Path().apply {
        moveTo(-8.5f,8f); cubicTo(-8.5f,-3f,8.5f,-3f,8.5f,8f)
        quadTo(4.5f,4f,0f,5f); quadTo(-4.5f,4f,-8.5f,8f); close()
    }
    private val hairB = Path().apply {
        moveTo(-9f,9f); cubicTo(-9f,-3f,9f,-3f,9f,9f); lineTo(8f,11f)
        quadTo(7f,5f,0f,5f); quadTo(-7f,5f,-8f,11f); close()
    }
    private val cap = Path().apply { moveTo(-9.5f,7f); cubicTo(-9.5f,-2.3f,9.5f,-2.3f,9.5f,7f); close() }
    private val smile = Path().apply { moveTo(-5f,15f); quadTo(0f,18f,5f,15f) }
    private val tail = Path().apply { moveTo(0f,0f); lineTo(-7f,7f); lineTo(-5f,0f); close() }

    fun has(name: String): Boolean = when (name) {
        "add_qr", "add_link", "add_accept", "same_picture" -> true
        else -> false
    }

    fun draw(name: String, canvas: Canvas, kit: HowToKit, v: HowToValues) {
        when (name) {
            "add_qr" -> qrScene(canvas, kit, v)
            "add_link" -> linkScene(canvas, kit, v)
            "add_accept" -> acceptScene(canvas, kit, v)
            "same_picture" -> sameScene(canvas, kit, v)
            else -> error("No accounts drawing for how-to scene $name")
        }
    }

    private inline fun at(c: Canvas, x: Float, y: Float, scale: Float = 1f, draw: () -> Unit) {
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        try { draw() } finally { c.restore() }
    }

    /** Illustrative QR modules, not an actual account code or copied vector resource. */
    private fun qr(c: Canvas, k: HowToKit, x: Float, y: Float, size: Float) = at(c, x, y, size / 9f) {
        c.drawRect(0f, 0f, 9f, 9f, k.fill(C.WHITE))
        for (i in 0..2) {
            val fx = if (i == 1) 5.5f else .5f
            val fy = if (i == 2) 5.5f else .5f
            c.drawRect(fx, fy, fx + 3f, fy + 3f, k.line(C.PHONE, .7f))
            c.drawRect(fx + 1f, fy + 1f, fx + 2f, fy + 2f, k.fill(C.PHONE))
        }
        for (i in qrModules.indices step 2) {
            val xx = qrModules[i].toFloat(); val yy = qrModules[i + 1].toFloat()
            c.drawRect(xx, yy, xx + 1f, yy + 1f, k.fill(C.PHONE))
        }
    }

    private fun scanner(c: Canvas, k: HowToKit, code: Float, line: Float) {
        c.drawRect(3f, 5f, 27f, 45f, k.fill(0xFF3A3A3A.toInt()))
        k.fade(code) { qr(c, k, 8f, 15f, 14f) }
        for (side in 0..1) for (end in 0..1) {
            val x = if (side == 0) 5f else 25f
            val y = if (end == 0) 8f else 42f
            c.drawLine(x, y, x + if (side == 0) 4f else -4f, y, k.line(C.WHITE, 1.2f))
            c.drawLine(x, y, x, y + if (end == 0) 4f else -4f, k.line(C.WHITE, 1.2f))
        }
        c.drawLine(6f, 13f + line, 24f, 13f + line, k.line(LIT, 1.2f))
    }

    private fun qrScene(c: Canvas, k: HowToKit, v: HowToValues) {
        c.save()
        c.translate(36f * v["a.move"], -2f * v["a.move"])
        c.rotate(14f * v["a.move"], 54f, 53f)
        at(c, 30f, 10f, 1.6f) {
            k.phone(c, 0f, 0f)
            k.fade(v["a.qr"]) { qr(c, k, 5f, 9f, 20f) }
            k.fade(v["a.scan"]) { scanner(c, k, v["a.code"], v["a.line"]) }
        }
        k.fade(v["a.cone"]) { c.drawPath(coneA, k.fill(LIT, .25f)) }
        c.restore()
        c.save()
        c.translate(-36f * v["b.move"], -2f * v["b.move"])
        c.rotate(-14f * v["b.move"], 166f, 53f)
        at(c, 142f, 10f, 1.6f) {
            k.phone(c, 0f, 0f)
            k.fade(v["b.qr"]) { qr(c, k, 5f, 9f, 20f) }
            k.fade(v["b.scan"]) { scanner(c, k, v["b.code"], v["b.line"]) }
        }
        k.fade(v["b.cone"]) { c.drawPath(coneB, k.fill(LIT, .25f)) }
        c.restore()
        k.ok(c, 166f, 8f, v["b.ok"])
        k.ok(c, 54f, 8f, v["a.ok"])
    }

    private fun linkScene(c: Canvas, k: HowToKit, v: HowToValues) {
        val words = k.words.accounts
        at(c, 20f, 10f, 1.6f) {
            k.phone(c, 0f, 0f)
            k.fitted(c, words.add, 15f, 13f, 3.8f, 22f)
            qr(c, k, 8f, 15f, 14f)
            k.rect(c, 4.5f, 33f, 21f, 7f, 1.5f, k.mix(C.WHITE, YELLOW, v["button"]), .8f)
            k.fitted(c, words.sendLink, 15f, 37.5f, 2.7f, 20f)
            k.fade(v["sheet"]) {
                k.rect(c, 3f, 24f, 24f, 21f, 2f, C.WHITE, .8f)
                k.circle(c, 10f, 33f, 4f, 0xFF25D366.toInt(), .8f)
                k.circle(c, 20f, 33f, 4f, 0xFF42A5F5.toInt(), .8f)
                k.fitted(c, words.chat, 10f, 41.5f, 2.8f, 10f, bold = false)
                k.fitted(c, words.sms, 20f, 41.5f, 2.8f, 9f, bold = false)
            }
        }
        k.finger(c, 42f, 74f, v["finger.dx"], v["finger.dy"])
        at(c, 150f, 10f, 1.6f) {
            k.phone(c, 0f, 0f)
            k.fade(v["message"]) {
                k.rect(c, 4f, 12f, 22f, 10f, 3f, C.BUBBLE, .8f)
                c.drawLine(7f, 17f, 23f, 17f, k.line(C.BLUE, 1.2f))
            }
            k.fade(v["open"]) {
                c.drawRect(3f, 5f, 27f, 45f, k.fill(C.SCREEN))
                k.circle(c, 15f, 18f, 5f, 0xFFE53935.toInt(), .8f)
                k.fitted(c, words.awa, 15f, 30f, 4.6f, 22f)
            }
        }
        // Keep the travelling link above the phone until it becomes its received bubble.
        k.fade(v["fly.alpha"]) {
            k.rect(c, 76f + v["fly.dx"], 34f, 36f, 14f, 5f, C.BUBBLE, 1.2f)
            c.drawLine(82f + v["fly.dx"], 41f, 106f + v["fly.dx"], 41f, k.line(C.BLUE, 1.6f))
        }
        // Link bubble is at y29..45; the original mockup's y58 tip missed it.
        k.finger(c, 172f, 35f, v["right.dx"], v["right.dy"])
    }

    private fun avatar(c: Canvas, k: HowToKit, x: Float, y: Float, r: Float, colour: Int) {
        k.circle(c, x, y, r, colour, 1.6f)
        k.icon(c, R.drawable.ic_person, x, y, r * 1.12f, C.WHITE)
    }

    private fun speech(c: Canvas, k: HowToKit, x: Float, y: Float, width: Float, height: Float, colour: Int, reverse: Boolean = false) {
        k.rect(c, x, y, width, height, 5f, colour, 1.4f)
        at(c, if (reverse) x + width - 6f else x + 8f, y + height) {
            if (reverse) c.scale(-1f, 1f)
            c.drawPath(tail, k.fill(colour)); c.drawPath(tail, k.line(width = 1.4f))
        }
        for (i in 0..2) k.circle(c, x + width / 2 + (i - 1) * width / 6, y + height / 2, height / 10, C.INK, 0f)
    }

    private fun acceptScene(c: Canvas, k: HowToKit, v: HowToValues) {
        k.fade(v["card"]) {
            at(c, 0f, v["card.dy"]) {
                k.rect(c, 40f, 6f, 140f, 92f, 9f, C.WHITE)
                avatar(c, k, 68f, 34f, 15f, PURPLE)
                speech(c, k, 84f, 18f, 86f, 26f, C.BUBBLE)
                k.rect(c, 50f, 62f, 58f, 28f, 6f, k.mix(ACCEPT, LIT, v["accept"]), 1.6f)
                k.icon(c, R.drawable.ic_check, 79f, 76f, 22f, C.WHITE)
                k.rect(c, 114f, 62f, 58f, 28f, 6f, C.WHITE, 1.6f)
                k.icon(c, R.drawable.ic_close, 143f, 76f, 22f)
            }
        }
        k.finger(c, 82f, 80f, v["finger.dx"], v["finger.dy"])
        k.fade(v["talk"]) {
            avatar(c, k, 52f, 54f, 17f, PURPLE)
            avatar(c, k, 168f, 54f, 17f, SHIRT)
            k.fade(v["out.alpha"]) { speech(c, k, 70f + v["out.dx"], 34f, 39f, 22f, C.BUBBLE) }
            k.fade(v["back.alpha"]) { speech(c, k, 111f + v["back.dx"], 56f, 39f, 22f, 0xFFE8F5E9.toInt(), true) }
        }
    }

    private fun person(c: Canvas, k: HowToKit, x: Float, right: Boolean, stranger: Boolean = false) = at(c, x, 4f) {
        c.drawPath(shoulders, k.fill(if (stranger) C.GREY else if (right) PURPLE else SHIRT))
        c.drawPath(shoulders, k.line(width = 1.4f))
        k.circle(c, 0f, 10f, 8.5f, if (stranger) 0xFFE8B98A.toInt() else if (right) 0xFF7B4A2A.toInt() else 0xFFC68642.toInt(), 1.4f)
        if (stranger) {
            c.drawPath(cap, k.fill(C.RED)); c.drawPath(cap, k.line(width = 1.2f))
            c.drawLine(8f, 7f, 15f, 7f, k.line(C.RED, 2.4f))
            c.drawPath(smile, k.line(width = 1.2f))
        } else {
            c.drawPath(if (right) hairB else hairA, k.fill(if (right) 0xFF1A1A1A.toInt() else C.INK))
            if (right) k.circle(c, 0f, 0f, 3.5f, 0xFF1A1A1A.toInt(), 0f)
        }
    }

    private fun sameScene(c: Canvas, k: HowToKit, v: HowToValues) {
        person(c, k, 48f, false)
        // Cross-fade the actual person, rather than merely changing the pictures.
        k.fade(1f - v["stranger"]) { person(c, k, 172f, true) }
        k.fade(v["stranger"]) { person(c, k, 172f, true, true) }
        for (side in 0..1) {
            val x0 = if (side == 0) 8f else 132f
            k.rect(c, x0, 28f, 80f, 74f, 7f, C.PHONE)
            k.rect(c, x0 + 4f, 32f, 72f, 66f, 2f, C.WHITE, 0f)
            for (i in six.indices) {
                val x = x0 + 6f + (i % 3) * 23.5f
                val y = 34f + (i / 3) * 24f
                val colour = if (i == 3) k.mix(k.mix(C.WHITE, YELLOW, v[cellTracks[i]]), PINK, v["mismatch"])
                    else k.mix(C.WHITE, YELLOW, v[cellTracks[i]])
                k.rect(c, x, y, 21f, 22f, 3f, colour, 1f)
                if (side == 1 && i == 3) {
                    k.fade(1f - v["swap"]) { k.icon(c, SafetyIcons.drawables[six[i]], x + 10.5f, y + 10.5f, 16f, tint = false) }
                    k.fade(v["swap"]) { k.icon(c, SafetyIcons.drawables[0], x + 10.5f, y + 10.5f, 16f, tint = false) }
                } else k.icon(c, SafetyIcons.drawables[six[i]], x + 10.5f, y + 10.5f, 16f, tint = false)
            }
            k.rect(c, x0 + 7f, 83f, 31f, 13f, 4f, k.mix(ACCEPT, LIT, if (side == 1) v["accept"] else 0f), 1.2f)
            k.icon(c, R.drawable.ic_check, x0 + 22.5f, 89.5f, 12f, C.WHITE)
            k.rect(c, x0 + 42f, 83f, 31f, 13f, 4f, k.mix(C.WHITE, PINK, if (side == 1) v["reject"] else 0f), 0f)
            c.drawRoundRect(x0 + 42f, 83f, x0 + 73f, 96f, 4f, 4f, k.line(C.RED, 1.4f))
            k.icon(c, R.drawable.ic_close, x0 + 57.5f, 89.5f, 12f, C.RED)
        }
        k.fade(v["same"]) { comparison(c, k, C.GREEN, false) }
        k.fade(v["different"]) { comparison(c, k, C.RED, true) }
        k.finger(c, 155f, 94f, v["finger.dx"], v["finger.dy"])
        k.finger(c, 190f, 94f, v["reject.dx"], v["reject.dy"])
        badge(c, k, 110f, 86f, v["ok"], v["ok.scale"], false)
        badge(c, k, 110f, 86f, v["bad"], v["bad.scale"], true)
    }

    private fun comparison(c: Canvas, k: HowToKit, colour: Int, different: Boolean) {
        c.drawLine(100f, 52f, 120f, 52f, k.line(colour, 4f))
        c.drawLine(100f, 62f, 120f, 62f, k.line(colour, 4f))
        if (different) c.drawLine(104f, 68f, 116f, 46f, k.line(colour, 4f))
    }

    private fun badge(c: Canvas, k: HowToKit, x: Float, y: Float, opacity: Float, scale: Float, bad: Boolean) {
        k.fade(opacity) {
            at(c, x, y, scale) {
                k.circle(c, 0f, 0f, 9f, if (bad) C.RED else C.GREEN, 0f)
                k.icon(c, if (bad) R.drawable.ic_close else R.drawable.ic_check, 0f, 0f, 14f, C.WHITE)
            }
        }
    }
}

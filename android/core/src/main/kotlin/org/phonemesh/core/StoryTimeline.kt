package org.phonemesh.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** One ring around a radio's antenna, in scene units; alpha 0..1. */
data class StoryRing(val radius: Float, val alpha: Float)

/**
 * Everything the welcome picture needs at one instant. Positions are scene units: the scene is
 * [StoryTimeline.WIDTH] × [StoryTimeline.HEIGHT], and the camera shows camW wide (height in proportion) from (camX, camY).
 */
data class StoryFrame(
    val moment: Int,
    val camX: Float, val camY: Float, val camW: Float,
    val herPhoneDx: Float, val herPhoneDy: Float, val herPhoneAngle: Float,
    val hisPhoneDx: Float, val hisPhoneDy: Float, val hisPhoneAngle: Float,
    val mouthRx: Float, val mouthRy: Float,
    val barsAlpha: Float, val barScales: List<Float>,
    val bubbleX: Float, val bubbleY: Float, val bubbleScale: Float, val bubbleAlpha: Float, val letters: Int,
    val arcAlpha: Float,
    val sendRings: List<StoryRing>, val receiveRings: List<StoryRing>,
    val herLight: Boolean, val hisLight: Boolean,
    val wavesAlpha: List<Float>,
)

/** The welcome story's one timeline, from the approved mockup (wax-print round). t runs 0..1 over [DURATION_MS]. */
object StoryTimeline {
    const val DURATION_MS = 10_000L
    const val WIDTH = 300f
    const val HEIGHT = 170f
    const val WORD = "Bonjour"
    /** Largest ring radius, as drawn in the approved mockup. */
    const val RING_MAX = 34f
    /** The "Remove animations" frame: the bubble at the top of the arc, moment 3. */
    const val STILL = 0.54f
    /** The opening and closing caption; the numbered moments are 0 until [MOMENTS] - 1. */
    const val OPENING = -1
    const val MOMENTS = 5

    /** Keyframes: t, camera x, y, width. */
    private val CAMERA = arrayOf(
        floatArrayOf(0f, 0f, 0f, 300f), floatArrayOf(.06f, 0f, 22f, 170f), floatArrayOf(.36f, 0f, 22f, 170f),
        floatArrayOf(.46f, 30f, 6f, 240f), floatArrayOf(.60f, 30f, 6f, 240f), floatArrayOf(.68f, 130f, 22f, 170f),
        floatArrayOf(.93f, 130f, 22f, 170f), floatArrayOf(1f, 0f, 0f, 300f),
    )

    fun moment(t: Float): Int = when {
        t < .06f -> OPENING
        t < .30f -> 0
        t < .46f -> 1
        t < .62f -> 2
        t < .72f -> 3
        t < .95f -> 4
        else -> OPENING
    }

    fun at(time: Float): StoryFrame {
        val t = time.coerceIn(0f, 1f)
        val her = ease(window(t, .06f, .14f, .40f, .48f))
        val his = ease(window(t, .60f, .68f, .93f, .99f))
        val talk = window(t, .13f, .15f, .29f, .31f)
        val syllable = abs(sin(t * 150f)) * (.6f + .4f * abs(sin(t * 37f)))
        val typed = min(segment(t, .28f, .37f), 1f - segment(t, .70f, .76f))
        val sending = window(t, .44f, .46f, .56f, .60f)
        val receiving = window(t, .54f, .57f, .66f, .69f)
        val blink = floor(t * 80f).toInt() % 2 == 0
        val waves = window(t, .72f, .75f, .89f, .93f)
        val bubble = bubble(t)
        val camera = camera(t)
        return StoryFrame(
            moment = moment(t),
            camX = camera[0], camY = camera[1], camW = camera[2],
            herPhoneDx = lerp(6f, 0f, her), herPhoneDy = lerp(46f, 0f, her), herPhoneAngle = lerp(20f, 0f, her),
            hisPhoneDx = lerp(-6f, 0f, his), hisPhoneDy = lerp(48f, 0f, his), hisPhoneAngle = lerp(-20f, 0f, his),
            mouthRx = 2.6f - talk * .6f * syllable,
            mouthRy = .8f + talk * 2.6f * syllable,
            barsAlpha = window(t, .14f, .17f, .28f, .34f),
            barScales = List(4) { i -> .4f + .6f * abs(sin(t * 90f + i * 1.7f)) },
            bubbleX = bubble[0], bubbleY = bubble[1], bubbleScale = bubble[2],
            bubbleAlpha = window(t, .28f, .31f, .72f, .76f),
            letters = (WORD.length * typed).roundToInt().coerceIn(0, WORD.length),
            arcAlpha = window(t, .45f, .48f, .60f, .63f),
            sendRings = List(3) { i ->
                val f = cycle((t - .44f) / .16f * 2.4f + i / 3f)
                StoryRing(f * RING_MAX, sending * (1f - f))
            },
            receiveRings = List(3) { i ->
                val f = cycle((t - .54f) / .15f * 2.4f + i / 3f)
                StoryRing((1f - f) * RING_MAX, receiving * f)
            },
            herLight = t > .44f && t < .62f && blink,
            hisLight = t > .58f && t < .68f && blink,
            wavesAlpha = List(3) { i -> waves * (.35f + .65f * abs(sin(t * 40f - i * .9f))) },
        )
    }

    /** x, y, scale: into her radio, along the arc, then up to his phone. */
    private fun bubble(t: Float): FloatArray = when {
        t < .46f -> {
            val k = ease(segment(t, .38f, .46f))
            floatArrayOf(lerp(84f, 112f, k), lerp(40f, 80f, k), lerp(1f, .6f, k))
        }
        t < .62f -> {
            val k = ease(segment(t, .47f, .61f))
            val v = 1f - k
            floatArrayOf(v * v * 112f + 2 * v * k * 150f + k * k * 188f, v * v * 80f + 2 * v * k * 30f + k * k * 80f, .6f)
        }
        else -> {
            val k = ease(segment(t, .62f, .70f))
            floatArrayOf(lerp(188f, 224f, k), lerp(80f, 38f, k), lerp(.6f, 1f, k))
        }
    }

    private fun camera(t: Float): FloatArray {
        for (i in 0 until CAMERA.size - 1) {
            val a = CAMERA[i]
            val b = CAMERA[i + 1]
            if (t <= b[0]) {
                val k = ease(segment(t, a[0], b[0]))
                return floatArrayOf(lerp(a[1], b[1], k), lerp(a[2], b[2], k), lerp(a[3], b[3], k))
            }
        }
        return floatArrayOf(CAMERA[0][1], CAMERA[0][2], CAMERA[0][3])
    }

    private fun segment(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun window(t: Float, a: Float, b: Float, c: Float, d: Float) = min(segment(t, a, b), 1f - segment(t, c, d))
    private fun ease(x: Float) = if (x < .5f) 2 * x * x else 1 - (-2 * x + 2).pow(2) / 2
    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
    private fun cycle(x: Float) = ((x % 1f) + 1f) % 1f
}

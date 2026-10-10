package org.phonemesh.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
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

/**
 * Where the paced loop is at one instant: the story time [t], and while a step rests, how many ms ([restMs]) of
 * its [restLength] ms have passed. Both are 0 while the action plays.
 */
data class StoryBeat(val t: Float, val restMs: Long, val restLength: Long) {
    val resting: Boolean get() = restLength > 0
}

/**
 * The welcome story's one timeline, from the approved mockup (wax-print round). Story time t runs 0..1 over
 * [DURATION_MS] of action ([at]). On screen the action plays at that speed and each numbered step then rests,
 * so that it stays for [STEP_MS]: [paced] gives the picture for a time in that longer loop of [LOOP_MS].
 */
object StoryTimeline {
    /** The action alone, without the rests. */
    const val DURATION_MS = 10_000L
    /** How long each numbered step stays on screen: its action, then a rest for what is left. The one number to change. */
    const val STEP_MS = 4_000L
    /** One breath of the resting picture lasts about this long; a rest holds a whole number of them. */
    const val BREATH_MS = 1_600L
    /** The most a raised phone lifts while its step rests, in scene units. */
    const val PHONE_BOB = 1.5f
    /** The most the bubble lifts while its step rests, in scene units. */
    const val BUBBLE_BOB = 2f
    /** The most her open mouth, the sound bars and his sound waves ease off while their step rests (a share of 1). */
    const val PULSE = .3f
    /** The most the radio rings draw in while their step rests (a share of their radius). */
    const val RING_PULSE = .1f
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

    /** Story times where the numbered moments start, and where the last one ends. */
    private val BOUNDS = floatArrayOf(.06f, .30f, .46f, .62f, .72f, .95f)

    /**
     * Where each step rests, in ms of action: the last instant before its picture starts to change into the
     * next step's. 1: before the sound bars fade and the typing starts. 2: the word is whole (the camera's pull
     * back, which starts 100 ms earlier, has moved 2 % of its way). 3: before the camera leaves for him, the
     * bubble at his radio. 4: before the letters go. 5: before the sound waves fade and the camera pulls out.
     * What is left of the step's action plays after the rest, still under its caption.
     */
    private val HOLD_MS = longArrayOf(2_800, 3_700, 6_000, 7_000, 8_900)
    /** A step whose action already lasts [STEP_MS] or longer does not rest. */
    private val REST_MS = LongArray(MOMENTS) { max(0L, STEP_MS - ((BOUNDS[it + 1] - BOUNDS[it]) * DURATION_MS).roundToLong()) }
    /** The loop on screen: the action and every step's rest. */
    val LOOP_MS = DURATION_MS + REST_MS.sum()

    fun moment(t: Float): Int {
        val next = BOUNDS.indexOfFirst { t < it }
        return if (next <= 0) OPENING else next - 1
    }

    /** The story time shown [ms] into the paced loop (any ms; the loop repeats), and the rest it is in, if any. */
    fun beat(ms: Long): StoryBeat {
        var action = Math.floorMod(ms, LOOP_MS)
        for (step in 0 until MOMENTS) {
            val rested = action - HOLD_MS[step]
            if (rested < 0) break
            if (rested < REST_MS[step]) return StoryBeat(HOLD_MS[step] / DURATION_MS.toFloat(), rested, REST_MS[step])
            action -= REST_MS[step]
        }
        return StoryBeat(action / DURATION_MS.toFloat(), 0, 0)
    }

    /** When the paced loop first shows story time [t]: for a time a step rests on, the start of that rest. */
    fun msOf(t: Float): Long {
        val action = (t.coerceIn(0f, 1f) * DURATION_MS).roundToLong()
        return action + (0 until MOMENTS).filter { HOLD_MS[it] < action }.sumOf { REST_MS[it] }
    }

    /**
     * The picture [ms] into the paced loop. While the action plays it is exactly [at]. While a step rests it is
     * the held picture, breathing: a raised phone and the bubble lift a little and come back, her open mouth, the
     * sound bars, the sound waves and the radio rings ease off and come back, and a lit radio light keeps blinking.
     * The breath is 0 when a rest starts and when it ends, so the action goes on from the picture it stopped at.
     */
    fun paced(ms: Long): StoryFrame {
        val beat = beat(ms)
        if (!beat.resting) return at(beat.t)
        val breaths = max(1, (beat.restLength / BREATH_MS.toFloat()).roundToInt())
        val breath = ((1 - cos(2 * PI * breaths * beat.restMs / beat.restLength)) / 2).toFloat()
        return frame(beat.t, beat.t + beat.restMs / DURATION_MS.toFloat(), breath)
    }

    /** The action's picture at story time [time] (0..1). */
    fun at(time: Float): StoryFrame = time.coerceIn(0f, 1f).let { frame(it, it, 0f) }

    /** [blinkTime] runs on through a rest; [breath] is 0..1, and 0 leaves the action's picture untouched. */
    private fun frame(t: Float, blinkTime: Float, breath: Float): StoryFrame {
        val pulse = 1f - PULSE * breath
        val ringPulse = 1f - RING_PULSE * breath
        val her = ease(window(t, .06f, .14f, .40f, .48f))
        val his = ease(window(t, .60f, .68f, .93f, .99f))
        val talk = window(t, .13f, .15f, .29f, .31f)
        val syllable = abs(sin(t * 150f)) * (.6f + .4f * abs(sin(t * 37f)))
        val typed = min(segment(t, .28f, .37f), 1f - segment(t, .70f, .76f))
        val sending = window(t, .44f, .46f, .56f, .60f)
        val receiving = window(t, .54f, .57f, .66f, .69f)
        val blink = floor(blinkTime * 80f).toInt() % 2 == 0
        val waves = window(t, .72f, .75f, .89f, .93f)
        val bubble = bubble(t)
        val camera = camera(t)
        return StoryFrame(
            moment = moment(t),
            camX = camera[0], camY = camera[1], camW = camera[2],
            herPhoneDx = lerp(6f, 0f, her), herPhoneDy = lerp(46f, 0f, her) - PHONE_BOB * breath * her, herPhoneAngle = lerp(20f, 0f, her),
            hisPhoneDx = lerp(-6f, 0f, his), hisPhoneDy = lerp(48f, 0f, his) - PHONE_BOB * breath * his, hisPhoneAngle = lerp(-20f, 0f, his),
            mouthRx = 2.6f - talk * .6f * syllable * pulse,
            mouthRy = .8f + talk * 2.6f * syllable * pulse,
            barsAlpha = window(t, .14f, .17f, .28f, .34f),
            barScales = List(4) { i -> (.4f + .6f * abs(sin(t * 90f + i * 1.7f))) * pulse },
            bubbleX = bubble[0], bubbleY = bubble[1] - BUBBLE_BOB * breath, bubbleScale = bubble[2],
            bubbleAlpha = window(t, .28f, .31f, .72f, .76f),
            letters = (WORD.length * typed).roundToInt().coerceIn(0, WORD.length),
            arcAlpha = window(t, .45f, .48f, .60f, .63f),
            sendRings = List(3) { i ->
                val f = cycle((t - .44f) / .16f * 2.4f + i / 3f)
                StoryRing(f * RING_MAX * ringPulse, sending * (1f - f))
            },
            receiveRings = List(3) { i ->
                val f = cycle((t - .54f) / .15f * 2.4f + i / 3f)
                StoryRing((1f - f) * RING_MAX * ringPulse, receiving * f)
            },
            herLight = t > .44f && t < .62f && blink,
            hisLight = t > .58f && t < .68f && blink,
            wavesAlpha = List(3) { i -> waves * (.35f + .65f * abs(sin(t * 40f - i * .9f))) * pulse },
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

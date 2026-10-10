package org.phonemesh.core

import kotlin.math.floor

enum class RegionScene { SET, CHANGE, HELPER, WORKING }

/** Everything the region picture needs at one moment. Flag and mark values are opacities 0..1; rings are radii 0..1. */
data class RegionFrame(
    val oldFlag: Float,
    val newFlag: Float,
    val question: Float,
    val cross: Float,
    val ledOn: Boolean,
    val rings: List<Float>,
)

/** Timing for the region cards' pictures (how-to option R2: the flag on the radio's screen). */
object RegionTimeline {
    const val DURATION_MS = 6_000L

    fun still(scene: RegionScene): Float = when (scene) {
        RegionScene.SET, RegionScene.CHANGE -> 0.8f
        RegionScene.HELPER, RegionScene.WORKING -> 0.3f
    }

    fun at(scene: RegionScene, t: Float): RegionFrame {
        val p = t - floor(t)
        return when (scene) {
            RegionScene.SET -> RegionFrame(0f, ramp(p, 0.25f, 0.40f), 0f, 0f, p >= 0.5f, rings(p, 0.5f))
            RegionScene.CHANGE -> RegionFrame(if (p < 0.40f) 1f else 0f, if (p >= 0.44f) 1f else 0f, 0f, 0f, true, emptyList())
            RegionScene.HELPER -> RegionFrame(0f, 1f, 0.6f + 0.4f * pulse(p), 1f, false, emptyList())
            RegionScene.WORKING -> RegionFrame(0f, 1f, 0f, 0f, p < 0.20f || p in 0.56f..0.64f || p >= 0.73f, emptyList())
        }
    }

    private fun ramp(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)

    /** A smooth 0→1→0 over the loop, 0 at both ends. */
    private fun pulse(p: Float) = if (p < 0.5f) p * 2f else (1f - p) * 2f

    /** Three rings, a third of a cycle apart, after [from]; none before it and none at the loop's end. */
    private fun rings(p: Float, from: Float): List<Float> {
        if (p < from || p >= 0.98f) return emptyList()
        val local = (p - from) / (1f - from)
        return (0 until 3).map { (local * 1.5f - it / 3f) }.filter { it in 0f..1f }
    }
}

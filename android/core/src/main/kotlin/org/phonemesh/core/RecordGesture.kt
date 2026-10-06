package org.phonemesh.core

import kotlin.math.hypot

/**
 * The microphone gestures, from finger movement since touch-down (up and left are negative).
 * Hold to talk; slide up to lock; slide left to cancel; a quick still tap also locks.
 */
object RecordGesture {
    const val LOCK_DP = 80f
    const val CANCEL_DP = 100f
    const val SLOP_DP = 12f
    const val TAP_MS = 300L

    /** About one radio part of speech (200 bytes, about 18 s); recording stops by itself here. */
    const val LIMIT_MS = 18_000L

    enum class Phase { HOLDING, LOCKED, CANCELLED, FINISHED }

    fun onMove(dxPx: Float, dyPx: Float, density: Float): Phase {
        val left = -dxPx / density
        val up = -dyPx / density
        return when {
            left >= CANCEL_DP && left > up -> Phase.CANCELLED
            up >= LOCK_DP && up >= left -> Phase.LOCKED
            else -> Phase.HOLDING
        }
    }

    fun onRelease(dxPx: Float, dyPx: Float, density: Float, heldMs: Long): Phase {
        val moved = onMove(dxPx, dyPx, density)
        if (moved != Phase.HOLDING) return moved
        val still = hypot(dxPx / density, dyPx / density) < SLOP_DP
        return if (heldMs < TAP_MS && still) Phase.LOCKED else Phase.FINISHED
    }

    fun cancelProgress(dxPx: Float, density: Float): Float = (-dxPx / density / CANCEL_DP).coerceIn(0f, 1f)

    fun ringFraction(elapsedMs: Long): Float = (elapsedMs.toFloat() / LIMIT_MS).coerceIn(0f, 1f)
}

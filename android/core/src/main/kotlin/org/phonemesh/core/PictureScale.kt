package org.phonemesh.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ways to show a small received picture larger, without the radio: the sharpening step of "Sharper" (no AI, invents
 * nothing new) and the tiles an AI model works through so a large model stays within a phone's memory.
 */
object PictureScale {
    /**
     * Unsharp mask on ARGB pixels: each channel moves away from its blurred self by [amount], where the difference is
     * above [threshold] (flat areas and their noise are left as they are). Alpha is kept.
     */
    fun unsharp(argb: IntArray, width: Int, height: Int, radius: Int = 2, amount: Float = 1.0f, threshold: Int = 3): IntArray {
        require(argb.size == width * height) { "pixels do not match the size" }
        if (width == 0 || height == 0 || radius <= 0 || amount == 0f) return argb.copyOf()
        val out = IntArray(argb.size)
        val blurred = Array(3) { channel -> blur(IntArray(argb.size) { (argb[it] shr (16 - 8 * channel)) and 255 }, width, height, radius) }
        for (i in argb.indices) {
            var pixel = argb[i] and (255 shl 24)
            for (channel in 0 until 3) {
                val shift = 16 - 8 * channel
                val value = (argb[i] shr shift) and 255
                val diff = value - blurred[channel][i]
                val next = if (abs(diff) < threshold) value else (value + amount * diff).roundToInt().coerceIn(0, 255)
                pixel = pixel or (next shl shift)
            }
            out[i] = pixel
        }
        return out
    }

    /** Three passes of a box blur approach a Gaussian; each pass is separable, so this stays fast on a phone. */
    private fun blur(values: IntArray, width: Int, height: Int, radius: Int): IntArray {
        var current = values
        repeat(3) {
            current = boxPass(current, width, height, radius, horizontal = true)
            current = boxPass(current, width, height, radius, horizontal = false)
        }
        return current
    }

    private fun boxPass(values: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean): IntArray {
        val out = IntArray(values.size)
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val span = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val clamped = i.coerceIn(0, length - 1)
                return values[if (horizontal) line * width + clamped else clamped * width + line]
            }
            var sum = 0
            for (i in -radius..radius) sum += at(i)
            for (i in 0 until length) {
                out[if (horizontal) line * width + i else i * width + line] = (sum + span / 2) / span
                sum += at(i + radius + 1) - at(i - radius)
            }
        }
        return out
    }

    /**
     * A part of the picture the model works on: [x], [y], [width], [height] with up to [pad] pixels of its
     * neighbours around it for context; only the inner part ([innerX] ... within the tile) is kept from the result.
     */
    data class Tile(val x: Int, val y: Int, val width: Int, val height: Int, val innerX: Int, val innerY: Int, val innerWidth: Int, val innerHeight: Int)

    /** Tiles of at most [size] pixels a side (plus [pad] around) that together cover the picture exactly once. */
    fun tiles(width: Int, height: Int, size: Int, pad: Int): List<Tile> {
        require(width > 0 && height > 0 && size > 0 && pad >= 0)
        val out = mutableListOf<Tile>()
        var top = 0
        while (top < height) {
            val innerHeight = minOf(size, height - top)
            var left = 0
            while (left < width) {
                val innerWidth = minOf(size, width - left)
                val x = maxOf(0, left - pad)
                val y = maxOf(0, top - pad)
                val right = minOf(width, left + innerWidth + pad)
                val bottom = minOf(height, top + innerHeight + pad)
                out += Tile(x, y, right - x, bottom - y, left - x, top - y, innerWidth, innerHeight)
                left += innerWidth
            }
            top += innerHeight
        }
        return out
    }
}

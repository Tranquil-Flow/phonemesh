package org.phonemesh.core.wire

/**
 * Spec decision 29: a reaction can be any one emoji from the phone's keyboard, not only the six fixed ones. It must fit
 * the reaction limit both wires share ([SharedPost.MAX_EMOJI], [Body.MAX_EMOJI]: 8 UTF-8 bytes), which every v0.1.0
 * phone reads. That covers single emoji, skin tones, flags and keycaps; joined emoji (families, ❤️‍🔥) are longer.
 */
object Reactions {
    const val MAX_BYTES = 8

    /** [typed] without surrounding spaces, if it is exactly one emoji within [MAX_BYTES]; otherwise null. */
    fun single(typed: String): String? {
        val text = typed.trim()
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return text.takeIf { oneEmoji(it.codePoints().toArray()) }
    }

    private fun oneEmoji(cp: IntArray): Boolean {
        if (cp.size == 2 && cp.all { it in FLAG_LETTERS }) return true
        if (cp[0] in '0'.code..'9'.code || cp[0] == '#'.code || cp[0] == '*'.code) {
            val i = if (cp.size > 1 && cp[1] == VARIATION) 2 else 1
            return i == cp.size - 1 && cp[i] == KEYCAP
        }
        if (!pictograph(cp[0])) return false
        var i = 1
        if (i < cp.size && cp[i] == VARIATION) i++
        if (i < cp.size && cp[i] in SKIN_TONES) i++
        return i == cp.size
    }

    private fun pictograph(c: Int) =
        c >= 0x2000 && Character.getType(c) == Character.OTHER_SYMBOL.toInt() && c !in FLAG_LETTERS && c !in SKIN_TONES

    private val FLAG_LETTERS = 0x1F1E6..0x1F1FF
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
    private const val VARIATION = 0xFE0F
    private const val KEYCAP = 0x20E3
}

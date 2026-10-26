package org.phonemesh.core.wire

import org.phonemesh.core.account.EmojiAvatars

/**
 * Spec decision 29: a reaction can be any one emoji from the phone's keyboard, not only the six fixed ones, within the
 * reaction limit both wires share ([SharedPost.MAX_EMOJI], [Body.MAX_EMOJI]: 32 UTF-8 bytes). One emoji means one
 * element, or elements joined by zero-width joiners (families, ❤️‍🔥, 🧑🏽‍🌾). An element is a pictograph with an optional
 * emoji variation selector and skin tone, a flag (two regional letters, or a black flag with tag letters such as
 * Scotland's), a keycap, or another listed emoji start (©️ and ↔️ with the emoji selector, ◽ without:
 * [EmojiAvatars.unusualStarts]). The limit was 8 bytes until the owner noted no phone runs v0.1.0 (2026-10-26).
 */
object Reactions {
    const val MAX_BYTES = 32

    /** [typed] without surrounding spaces, if it is exactly one emoji within [MAX_BYTES]; otherwise null. */
    fun single(typed: String): String? {
        val text = typed.trim()
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val cp = text.codePoints().toArray()
        var at = 0
        while (true) {
            at = element(cp, at) ?: return null
            if (at == cp.size) return text
            if (cp[at] != JOINER) return null
            at++
        }
    }

    /** The index just past one emoji element starting at [at], or null if none starts there. */
    private fun element(cp: IntArray, at: Int): Int? {
        if (at >= cp.size) return null
        val c = cp[at]
        if (c in FLAG_LETTERS) return if (at + 1 < cp.size && cp[at + 1] in FLAG_LETTERS) at + 2 else null
        if (c in '0'.code..'9'.code || c == '#'.code || c == '*'.code) {
            var i = at + 1
            if (i < cp.size && cp[i] == VARIATION) i++
            return if (i < cp.size && cp[i] == KEYCAP) i + 1 else null
        }
        if (!pictograph(c)) {
            val needsSelector = EmojiAvatars.unusualStarts[c] ?: return null
            val selected = at + 1 < cp.size && cp[at + 1] == VARIATION
            if (needsSelector && !selected) return null
            return if (selected) at + 2 else at + 1
        }
        var i = at + 1
        if (c == BLACK_FLAG && i < cp.size && cp[i] in TAG_LETTERS) {
            while (i < cp.size && cp[i] in TAG_LETTERS) i++
            return if (i < cp.size && cp[i] == CANCEL_TAG) i + 1 else null
        }
        if (i < cp.size && cp[i] == VARIATION) i++
        if (i < cp.size && cp[i] in SKIN_TONES) i++
        return i
    }

    private fun pictograph(c: Int) =
        c >= 0x2000 && Character.getType(c) == Character.OTHER_SYMBOL.toInt() && c !in FLAG_LETTERS && c !in SKIN_TONES

    private val FLAG_LETTERS = 0x1F1E6..0x1F1FF
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
    private val TAG_LETTERS = 0xE0020..0xE007E
    private const val CANCEL_TAG = 0xE007F
    private const val BLACK_FLAG = 0x1F3F4
    private const val JOINER = 0x200D
    private const val VARIATION = 0xFE0F
    private const val KEYCAP = 0x20E3
}

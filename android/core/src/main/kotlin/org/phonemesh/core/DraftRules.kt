package org.phonemesh.core

enum class DraftFit { EMPTY, FITS, TOO_LONG, INVALID }

/** Whether a draft fits one radio part once tagged, exactly as MessageCodec.encode would judge it. */
object DraftRules {
    fun fit(text: String, languageTag: String): DraftFit {
        if (text.isBlank()) return DraftFit.EMPTY
        try {
            MessageCodec.validateText(text)
        } catch (_: IllegalArgumentException) {
            return DraftFit.INVALID
        }
        val bytes = "[pm1:${MessageCodec.canonical(languageTag)}] $text".toByteArray(Charsets.UTF_8).size
        return if (bytes <= MessageCodec.MAX_BYTES) DraftFit.FITS else DraftFit.TOO_LONG
    }

    /**
     * Keeps fitting, empty or invalid text unchanged. Otherwise keeps the longest fitting start,
     * ending after a whole word when possible, else between code points (never inside a character).
     */
    fun trimToFit(text: String, languageTag: String): String {
        if (fit(text, languageTag) != DraftFit.TOO_LONG) return text
        var best: String? = null
        for (i in text.indices) {
            if (!text[i].isWhitespace()) continue
            val head = text.substring(0, i).trimEnd()
            if (head.isEmpty()) continue
            if (fit(head, languageTag) != DraftFit.FITS) break
            best = head
        }
        if (best != null) return best
        var end = 0
        while (end < text.length) {
            val next = end + Character.charCount(text.codePointAt(end))
            if (fit(text.substring(0, next), languageTag) != DraftFit.FITS) break
            end = next
        }
        return text.substring(0, end)
    }
}

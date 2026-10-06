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
}

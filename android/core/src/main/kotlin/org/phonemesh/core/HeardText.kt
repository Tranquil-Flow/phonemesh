package org.phonemesh.core

/**
 * What speech recognition heard, without the sound tags Whisper writes for silence, noise or music ("[Music]",
 * "[BLANK_AUDIO]", "(rires)", "*music*", "♪"). Spoken words never come back in brackets, parentheses or between
 * asterisks, so every such group is dropped. An empty result means nothing was heard.
 */
object HeardText {
    private val TAGS = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*\s][^*]*\*|[♪♫]""")
    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE_MARK = Regex("""\s+([.,!?;:])""")

    fun clean(text: String): String =
        text.replace(TAGS, " ").replace(SPACES, " ").replace(SPACE_BEFORE_MARK, "$1").trim()
}

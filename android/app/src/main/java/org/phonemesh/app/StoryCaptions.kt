package org.phonemesh.app

/** The welcome captions: index 0 is the opening and closing; 1..5 are numbered moments. */
object StoryCaptions {
    val TEXT: List<Int> = listOf(
        R.string.caption_opening, R.string.caption_speak, R.string.caption_text,
        R.string.caption_radio, R.string.caption_voice, R.string.caption_listen,
    )

    /** StoryTimeline.moment (-1, 0..4) to a caption index. */
    fun index(moment: Int): Int = moment + 1
}

package org.phonemesh.app

/** The how-to pictures' sentences (one per scene, read aloud by their speaker button) and the pairing switch. */
object HowTo {
    /**
     * The "type the number" picture ships only if the real-radio test (radio region plan) shows the Heltec V4
     * asks for a pairing code. Until then nothing shows it; turning it on is this one line.
     */
    val showPair: Boolean = false

    private val sentences = mapOf(
        "download" to R.string.howto_download, "friend" to R.string.howto_friend, "power" to R.string.howto_power,
        "close" to R.string.howto_close, "search" to R.string.howto_search, "pair" to R.string.howto_pair,
        "hello" to R.string.howto_hello, "lost" to R.string.howto_lost,
        "cancel" to R.string.howto_cancel, "lock" to R.string.howto_lock, "listen" to R.string.howto_listen,
        "delivery" to R.string.howto_delivery, "failed" to R.string.howto_failed, "arrive" to R.string.howto_arrive,
    )

    fun sentence(scene: String): Int = requireNotNull(sentences[scene]) { "No sentence for how-to scene $scene" }
}

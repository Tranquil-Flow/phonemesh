package org.phonemesh.app

/** The how-to pictures' sentences (one per scene, read aloud by their speaker button) and the pairing switch. */
object HowTo {
    /**
     * The "type the number" picture ships only if the real-radio test shows the Heltec V4
     * asks for a pairing code. Until then nothing shows it; turning it on is this one line.
     */
    val showPair: Boolean = false

    private val sentences = mapOf(
        "download" to R.string.howto_download, "friend" to R.string.howto_friend, "power" to R.string.howto_power,
        "close" to R.string.howto_close, "search" to R.string.howto_search, "pair" to R.string.howto_pair,
        "hello" to R.string.howto_hello, "lost" to R.string.howto_lost,
        "cancel" to R.string.howto_cancel, "lock" to R.string.howto_lock, "listen" to R.string.howto_listen,
        "delivery" to R.string.howto_delivery, "failed" to R.string.howto_failed, "arrive" to R.string.howto_arrive,
        "bt" to R.string.howto_bt, "short" to R.string.howto_short, "notheard" to R.string.howto_notheard,
        "mic" to R.string.howto_mic, "allow" to R.string.howto_allow, "dlfail" to R.string.howto_dlfail,
        "damaged" to R.string.howto_damaged, "refused" to R.string.howto_refused, "older" to R.string.howto_older,
        "space" to R.string.howto_space, "wait" to R.string.howto_wait, "toolong" to R.string.howto_toolong,
        "playfail" to R.string.howto_playfail,
        "hold" to R.string.howto_hold, "reply" to R.string.howto_reply, "react" to R.string.howto_react,
        "forward" to R.string.howto_forward, "delete" to R.string.howto_delete, "pin" to R.string.howto_pin,
        "later" to R.string.howto_later, "later_pick" to R.string.howto_later_pick,
    )

    fun sentence(scene: String): Int = requireNotNull(sentences[scene]) { "No sentence for how-to scene $scene" }
}

package org.phonemesh.core

/** Every picture's timing, ported from `.local/visual-guide-mockups/phonemesh-howto.html`. */
object HowToScenes {
    // CSS check pops, held until .96/.97 then returned to their first frame.
    private const val OK60 = "0:0 .58:0 .66:1 .96:1 1:0"
    private const val OK70 = "0:0 .68:0 .76:1 .96:1 1:0"
    private const val OK78 = "0:0 .76:0 .84:1 .96:1 1:0"
    private const val OK88 = "0:0 .86:0 .92:1 .97:1 1:0"

    private fun scene(name: String, seconds: Float, still: Float, shows: Map<String, Float>, vararg tracks: Pair<String, String>) =
        HowToScene(name, (seconds * 1000).toLong(), still, linkedMapOf(*tracks), shows)

    val SETUP = listOf("download", "friend", "power", "close", "search", "pair", "hello", "lost")

    private val setup = listOf(
        // Dots travel from the mast into the phone, which fills; a green check.
        scene("download", 5f, .86f, mapOf("fill" to 1f, "ok" to 1f),
            "dots" to "0:1 .74:1 .78:0 .97:0 1:1",
            "fill" to "0:0 .75:1 .95:1 1:0",
            "ok" to OK78),
        // Two voice-box hops over Bluetooth, from the friend's phone to yours.
        scene("friend", 6f, .65f, mapOf("box.alpha" to 1f, "box.dx" to 45f),
            "box.dx" to "0:0 .22:45 .38:90 .42:90 .43:0 .65:45 .81:90 .97:90 1:0",
            "box.dy" to "0:0 .22:-16 .38:0 .43:0 .65:-16 .81:0 1:0",
            "box.alpha" to "0:0 .06:1 .38:1 .42:0 .43:0 .49:1 .81:1 .85:0 1:0",
            "fill" to "0:0 .1:0 .88:1 .95:1 1:0",
            "ok" to OK88),
        // The finger presses the button, the light blinks, then ON appears.
        scene("power", 5f, .28f, mapOf("finger.dx" to 0f, "finger.dy" to 3f),
            "finger.dx" to "0:40 .22:0 .34:0 .52:40 1:40",
            "finger.dy" to "0:44 .22:0 .28:3 .34:0 .52:44 1:44",
            "led" to "0:0 .29:0 .3:1 .4:1 .41:0 .5:0 .51:1 .6:1 .61:0 .7:0 .71:1 .8:1 .81:0 .9:0 .91:1 .97:1 1:0",
            "on" to "0:0 .31:0 .36:1 .97:1 1:0"),
        // Bring the radio next to the phone.
        scene("close", 5f, .7f, mapOf("radio.dx" to 0f, "ok" to 1f),
            "radio.dx" to "0:80 .08:80 .5:0 .94:0 1:80",
            "ok" to OK60),
        // Search rings disappear when the radio and phone link.
        scene("search", 5f, .8f, mapOf("link" to 1f, "ok" to 1f),
            "rings" to "0:1 .6:1 .66:0 .97:0 1:1",
            "link" to "0:0 .62:0 .74:1 .95:1 1:0",
            "ok" to OK70),
        // Pairing timing only; the drawing stays disabled pending real-radio evidence.
        scene("pair", 6f, .7f, mapOf("d4" to 1f, "finger.dx" to 0f),
            "d1" to "0:0 .3:0 .33:1 .97:1 1:0",
            "d2" to "0:0 .4:0 .43:1 .97:1 1:0",
            "d3" to "0:0 .5:0 .53:1 .97:1 1:0",
            "d4" to "0:0 .6:0 .63:1 .97:1 1:0",
            "finger.dx" to "0:30 .15:0 .75:0 .88:30 1:30",
            "finger.dy" to "0:44 .15:0 .75:0 .88:44 1:44",
            "ok" to OK78),
        // Hold the microphone while the voice bars move, then show the words.
        scene("hello", 6f, .6f, mapOf("finger.dx" to 0f, "bars" to 1f),
            "bars" to "0:0 .18:0 .24:1 .72:1 .76:0 1:0",
            "hold" to "0:0 .15:0 .75:1 .76:0 1:0",
            "finger.dx" to "0:30 .15:0 .75:0 .88:30 1:30",
            "finger.dy" to "0:44 .15:0 .75:0 .88:44 1:44",
            "ok" to OK78),
        // Turn a lost radio on and bring it close; the red sign becomes a check.
        scene("lost", 6f, .18f, mapOf("finger.dx" to 0f, "finger.dy" to 3f),
            "bad" to "0:1 .6:1 .64:0 .97:0 1:1",
            "ok" to OK70,
            "radio.dx" to "0:0 .4:0 .62:-62 .95:-62 1:0",
            "led" to "0:0 .19:0 .2:1 .97:1 1:0",
            "finger.dx" to "0:30 .14:0 .22:0 .36:30 1:30",
            "finger.dy" to "0:40 .14:0 .18:3 .22:0 .36:40 1:40"),
    )

    val TALKING = listOf("cancel", "lock", "listen", "delivery", "failed", "arrive")

    private val talking = listOf(
        // Holding the microphone, slide left to the bin; its lid opens and recording ends.
        scene("cancel", 5f, .6f, mapOf("finger.dx" to -125f, "lid" to -35f),
            "recording" to "0:0 .1:0 .14:1 .55:1 .62:0 1:0",
            "lid" to "0:0 .5:0 .58:-35 .72:-35 .8:0 1:0",
            "finger.dx" to "0:25 .12:0 .25:0 .6:-125 .68:-125 .84:-110 .97:-110 1:25",
            "finger.dy" to "0:44 .12:0 .25:0 .6:0 .68:0 .84:50 .97:50 1:44"),
        // Slide up to lock, let go, then return and tap the visible stop square.
        scene("lock", 5f, .6f, mapOf("finger.dy" to -62f, "stop" to 1f),
            "shackle" to "0:-6 .52:-6 .58:0 .96:0 1:-6",
            "stop" to "0:0 .57:0 .6:1 .96:1 1:0",
            "finger.dx" to "0:25 .12:0 .6:0 .74:30 .76:30 .82:0 .9:0 .96:30 .97:30 1:25",
            "finger.dy" to "0:30 .12:0 .22:0 .52:-62 .6:-62 .74:30 .76:30 .82:0 .86:2 .9:0 .96:30 1:30"),
        // Listen, then tap the green arrow; the draft flies up as a sent message.
        scene("listen", 6f, .3f, mapOf("finger.dx" to 0f, "sound" to 1f),
            "sound" to "0:0 .19:0 .22:1 .46:1 .5:0 1:0",
            "fly.dy" to "0:0 .7:0 .86:-34 .96:0 1:0",
            "fly.alpha" to "0:1 .7:1 .86:0 .96:0 1:1",
            "finger.dx" to "0:20 .12:0 .48:0 .62:80 .7:80 .82:100 .97:100 1:20",
            "finger.dy" to "0:40 .12:0 .16:2 .2:0 .48:0 .62:0 .66:2 .7:0 .82:40 1:40"),
        // The sign beside a message becomes a clock, a sent arrow, then delivered ticks.
        scene("delivery", 6f, .8f, mapOf("delivered" to 1f),
            "waiting" to "0:1 .3:1 .33:0 .97:0 1:1",
            "sent" to "0:0 .3:0 .33:1 .63:1 .66:0 1:0",
            "delivered" to "0:0 .63:0 .66:1 .97:1 1:0",
            "rings" to "0:0 .33:0 .36:1 .62:1 .66:0 1:0"),
        // Tap Send again; the red sign becomes a clock, then delivered ticks.
        scene("failed", 6f, .28f, mapOf("finger.dx" to 0f, "finger.dy" to 2f),
            "failed" to "0:1 .3:1 .33:0 .97:0 1:1",
            "waiting" to "0:0 .3:0 .33:1 .7:1 .73:0 1:0",
            "delivered" to "0:0 .7:0 .73:1 .97:1 1:0",
            "spin" to "0:0 .3:0 .7:720 .97:720 1:0",
            "finger.dx" to "0:30 .24:0 .32:0 .48:30 1:30",
            "finger.dy" to "0:40 .24:0 .28:2 .32:0 .48:40 1:40"),
        // The radio pulses; a message slides in; tap its speaker to hear it.
        scene("arrive", 6f, .48f, mapOf("finger.dx" to 0f, "finger.dy" to 2f),
            "pulse" to "0:0 .22:1 .97:1 1:0",
            "message.dx" to "0:-40 .12:-40 .28:0 .97:0 1:-40",
            "message.alpha" to "0:0 .12:0 .28:1 .97:1 1:0",
            "sound" to "0:0 .49:0 .52:1 .9:1 .94:0 1:0",
            "finger.dx" to "0:30 .3:30 .44:0 .52:0 .64:30 1:30",
            "finger.dy" to "0:40 .3:40 .44:0 .48:2 .52:0 .64:40 1:40"),
    )

    /** Scene lists in Help-page order; later tasks add the remaining groups. */
    val groups: List<List<String>> get() = listOf(SETUP, TALKING)

    val all: Map<String, HowToScene> = (setup + talking).associateBy { it.name }

    fun get(name: String): HowToScene = requireNotNull(all[name]) { "No how-to scene $name" }
}

package org.phonemesh.core

/** Every picture's timing, ported from the approved how-to mockup's CSS keyframes. */
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
        // Tap the round Listen button, then Send (61 units to its right); the draft flies up as a sent message.
        scene("listen", 6f, .3f, mapOf("finger.dx" to 0f, "sound" to 1f),
            "sound" to "0:0 .19:0 .22:1 .46:1 .5:0 1:0",
            "fly.dy" to "0:0 .7:0 .86:-34 .96:0 1:0",
            "fly.alpha" to "0:1 .7:1 .86:0 .96:0 1:1",
            "finger.dx" to "0:20 .12:0 .48:0 .62:61 .7:61 .82:81 .97:81 1:20",
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

    val PROBLEMS = listOf("bt", "short", "notheard", "mic")
    val RARE = listOf("allow", "dlfail", "damaged", "refused", "older", "space", "wait", "toolong", "playfail")

    private val problems = listOf(
        // Tap the switch; it turns blue and the Bluetooth sign lights.
        scene("bt", 5f, .38f, mapOf("switch" to 1f, "finger.dx" to 0f),
            "switch" to "0:0 .28:0 .36:1 .97:1 1:0",
            "finger.dx" to "0:30 .24:0 .4:0 .5:30 1:30",
            "finger.dy" to "0:40 .24:0 .28:2 .32:0 .4:0 .5:40 1:40"),
        // A quick tap gets a cross; holding fills the ring and gets a check.
        scene("short", 5f, .7f, mapOf("bad" to 1f, "finger.dx" to 0f),
            "tap.dx" to "0:26 .16:0 .22:0 .36:26 1:26",
            "tap.dy" to "0:40 .16:0 .22:0 .36:40 1:40",
            "bad" to "0:0 .26:0 .34:1 .96:1 1:0",
            "hold" to "0:0 .15:0 .75:1 .76:0 1:0",
            "finger.dx" to "0:30 .15:0 .75:0 .88:30 1:30",
            "finger.dy" to "0:44 .15:0 .75:0 .88:44 1:44",
            "ok" to OK78),
        // Bring the phone close to her mouth; the faint bars become big ones.
        scene("notheard", 5f, .7f, mapOf("phone.dx" to 0f, "bars" to 1f),
            "phone.dx" to "0:60 .1:60 .42:0 .95:0 1:60",
            "phone.dy" to "0:8 .1:8 .42:0 .95:0 1:8",
            "far" to "0:1 .08:1 .14:0 .95:0 1:1",
            "bars" to "0:0 .44:0 .5:1 .9:1 .94:0 1:0",
            "ok" to OK78),
        // A crossed-out microphone points to the keyboard, which the finger taps.
        scene("mic", 5f, .55f, mapOf("keyboard" to 1f, "finger.dx" to 0f),
            "keyboard" to "0:0 .51:0 .54:1 .97:1 1:0",
            "finger.dx" to "0:30 .3:30 .48:0 .56:0 .72:30 1:30",
            "finger.dy" to "0:40 .3:40 .48:0 .52:2 .56:0 .72:40 1:40"),
    )

    private val rare = listOf(
        // The phone's own question, with Allow lit as the finger taps it.
        scene("allow", 5f, .4f, mapOf("finger.dy" to 2f, "allow" to 1f),
            "allow" to "0:0 .39:0 .4:1 .48:1 .52:0 1:0",
            "ok" to OK60,
            "finger.dx" to "0:30 .2:30 .36:0 .44:0 .58:30 1:30",
            "finger.dy" to "0:40 .2:40 .36:0 .4:2 .44:0 .58:40 1:40"),
        // A failed signal, tap the round arrow, then download dots return.
        scene("dlfail", 6f, .42f, mapOf("finger.dy" to 2f),
            "fail" to "0:1 .46:1 .5:0 .97:0 1:1",
            "dots" to "0:0 .5:0 .54:1 .88:1 .92:0 1:0",
            "ok" to OK88,
            "finger.dx" to "0:30 .24:30 .38:0 .46:0 .6:30 1:30",
            "finger.dy" to "0:40 .24:40 .38:0 .42:2 .46:0 .6:40 1:40"),
        // The broken box shakes away; a new one drops into the phone.
        scene("damaged", 6f, .5f, mapOf("new.alpha" to 1f, "shake.alpha" to 0f),
            "shake.dx" to "0:0 .05:-3 .1:3 .15:-3 .2:3 .25:-3 .3:0 1:0",
            "shake.alpha" to "0:1 .3:1 .36:0 .97:0 1:1",
            "new.dy" to "0:-30 .4:-30 .6:22 .97:22 1:-30",
            "new.alpha" to "0:0 .4:0 .46:1 .6:1 .64:0 1:0",
            "fill" to "0:0 .6:0 .88:1 .97:1 1:0",
            "ok" to OK88),
        // A grey box bounces off the receiver's shield.
        scene("refused", 5f, .45f, mapOf("bad" to 1f),
            "box.dx" to "0:0 .35:82 .55:20 .97:20 1:0",
            "bad" to "0:0 .36:0 .42:1 .96:1 1:0"),
        // Box 1 bounces off because box 2 is already inside.
        scene("older", 5f, .7f, mapOf("bad" to 1f, "ok" to 1f),
            "box.dx" to "0:0 .35:82 .55:20 .97:20 1:0",
            "bad" to "0:0 .36:0 .42:1 .96:1 1:0",
            "ok" to OK60),
        // Send a photo to the bin, then the voice fits.
        scene("space", 6f, .36f, mapOf("finger.dy" to 2f),
            "trash.dx" to "0:0 .34:0 .52:90 .97:90 1:0",
            "trash.dy" to "0:0 .34:0 .52:26 .97:26 1:0",
            "trash.alpha" to "0:1 .34:1 .52:.6 .56:0 .97:0 1:1",
            "voice.dx" to "0:0 .14:-40 .24:-30 .62:-30 .78:-92 .97:-92 1:0",
            "voice.dy" to "0:0 .62:0 .78:10 .97:10 1:0",
            "finger.dx" to "0:30 .2:30 .32:0 .4:0 .52:30 1:30",
            "finger.dy" to "0:40 .2:40 .32:0 .36:2 .4:0 .52:40 1:40",
            "ok" to OK88),
        // The bar fills, the hourglass goes, the button wakes up and is tapped.
        scene("wait", 6f, .88f, mapOf("finger.dy" to 2f, "button" to 1f, "check" to 1f),
            "bar" to "0:0 .7:1 .97:1 1:0",
            "button" to "0:0 .7:0 .74:1 .97:1 1:0",
            "sand" to "0:1 .7:1 .72:0 .97:0 1:1",
            "check" to "0:0 .6:0 .63:1 .97:1 1:0",
            "finger.dx" to "0:30 .72:30 .84:0 .97:0 1:30",
            "finger.dy" to "0:40 .72:40 .84:0 .88:2 .92:0 .97:0 1:40"),
        // At the red line scissors cut the rest of the voice bars, which fall away.
        scene("toolong", 6f, .7f, mapOf("snip" to 1f, "red" to 1f),
            "red" to "0:0 .46:0 .5:1 .97:1 1:0",
            "sweep" to "0:0 .48:360 .97:360 1:0",
            "reveal" to "0:0 .6:200 .97:200 1:0",
            "fall.dx" to "0:0 .62:0 .82:6 .97:6 1:0",
            "fall.dy" to "0:0 .62:0 .82:40 .97:40 1:0",
            "fall.rot" to "0:0 .62:0 .82:12 .97:12 1:0",
            "fall.alpha" to "0:1 .62:1 .82:0 .97:0 1:1",
            "snip" to "0:0 .46:0 .5:1 .96:1 1:0"),
        // A failed speaker; the words light up and an arrow points at them.
        scene("playfail", 5f, .7f, mapOf("arrow" to 1f),
            "light" to "0:0 .4:0 .5:1 .97:1 1:0",
            "arrow" to "0:0 .31:0 .36:1 .97:1 1:0"),
    )

    /** The chat gestures (owner approved round 10, 2026-10-12). `hold` is also a tip; the rest live in Help. */
    val MORE = listOf("hold", "reply", "react", "forward", "delete", "pin", "later", "later_pick")

    private fun rows(): Array<Pair<String, String>> = Array(6) { i ->
        // The hold menu's six buttons light one after another once it is open.
        val a = (56 + i * 6.5f) / 100
        "row$i" to "0:0 ${"%.3f".format(java.util.Locale.ROOT, a)}:0 ${"%.3f".format(java.util.Locale.ROOT, a + .015f)}:1 ${"%.3f".format(java.util.Locale.ROOT, a + .05f)}:1 ${"%.3f".format(java.util.Locale.ROOT, a + .065f)}:0 1:0"
    }

    private val more = listOf(
        scene("hold", 6f, 0.72f, mapOf("menu" to 1f, "row2" to 1f, "finger.dx" to 30f),
            "finger.dx" to "0:30 .12:0 .5:0 .64:30 1:30",
            "finger.dy" to "0:40 .12:0 .5:0 .64:40 1:40",
            "press" to "0:0 .12:0 .46:1 .5:1 .51:0 1:0",
            "press.alpha" to "0:1 .46:1 .5:0 .99:0 1:1",
            "light" to "0:0 .46:0 .5:1 .96:1 1:0",
            "menu" to "0:0 .46:0 .54:1 .96:1 1:0",
            *rows()),
        scene("reply", 7f, 0.8f, mapOf("answer" to 1f, "link" to 1f, "finger.dx" to 50f),
            "menu" to "0:0 .06:0 .12:1 .32:1 .36:0 1:0",
            "row0" to "0:0 .23:0 .25:1 .34:1 .36:0 1:0",
            "finger.dx" to "0:50 .08:50 .2:0 .24:0 .28:0 .42:50 1:50",
            "finger.dy" to "0:84 .08:84 .2:0 .24:2 .28:0 .42:84 1:84",
            "quote" to "0:0 .36:0 .4:1 .58:1 .62:0 1:0",
            "answer" to "0:0 .6:0 .7:1 .96:1 1:0",
            "answer.dy" to "0:0 .01:24 .6:24 .7:0 1:0",
            "link" to "0:0 .7:0 .76:1 .96:1 1:0",
            "light" to "0:0 .7:0 .76:1 .96:1 1:0"),
        scene("react", 6f, 0.9f, mapOf("chip" to 1f, "ok" to 1f, "finger.dx" to 30f),
            "window" to "0:0 .08:0 .14:1 .4:1 .44:0 1:0",
            "finger.dx" to "0:30 .14:30 .26:0 .3:0 .34:0 .5:30 1:30",
            "finger.dy" to "0:40 .14:40 .26:0 .3:2 .34:0 .5:40 1:40",
            "lit" to "0:0 .29:0 .31:1 .42:1 .44:0 1:0",
            "fly.alpha" to "0:0 .4:0 .42:1 .56:1 .58:0 1:0",
            "fly.dx" to "0:0 .42:0 .48:-74 .56:-131 .6:-131 .61:0 1:0",
            "fly.dy" to "0:0 .42:0 .48:30 .56:27 .6:27 .61:0 1:0",
            "chip" to "0:0 .56:0 .58:1 .96:1 1:0",
            "tx" to "0:0 .58:0 .62:1 .86:1 .9:0 1:0",
            "ok" to OK78),
        scene("delete", 6f, 0.9f, mapOf("up" to -28f, "ok" to 1f, "other" to 1f, "finger.dx" to 30f),
            "finger.dx" to "0:30 .12:0 .3:0 .42:30 1:30",
            "finger.dy" to "0:40 .12:0 .3:0 .42:40 1:40",
            "press" to "0:0 .12:0 .28:1 .32:1 .33:0 1:0",
            "press.alpha" to "0:1 .28:1 .32:0 .99:0 1:1",
            "gone" to "0:0 .32:0 .52:1 .96:1 1:0",
            "gone.alpha" to "0:1 .32:1 .52:.8 .56:0 .96:0 1:1",
            "lid" to "0:0 .3:0 .36:-35 .54:-35 .6:0 1:0",
            "up" to "0:0 .56:0 .64:-28 .96:-28 1:0",
            "other" to "0:0 .62:0 .68:1 .96:1 1:0",
            "ok" to OK78),
        scene("pin", 7f, 0.9f, mapOf("move" to 1f, "pin" to 1f, "finger.dx" to 30f),
            "finger.dx" to "0:30 .1:0 .26:0 .36:4 .39:4 .42:4 .54:30 1:30",
            "finger.dy" to "0:40 .1:0 .26:0 .36:-48 .39:-46 .42:-48 .54:40 1:40",
            "press" to "0:0 .1:0 .26:1 .29:1 .3:0 1:0",
            "press.alpha" to "0:1 .26:1 .29:0 .99:0 1:1",
            "lit" to "0:0 .26:0 .29:1 .96:1 1:0",
            "menu" to "0:0 .26:0 .31:1 .44:1 .48:0 1:0",
            "row" to "0:0 .38:0 .4:1 .46:1 .48:0 1:0",
            "move" to "0:0 .5:0 .62:1 .96:1 1:0",
            "pin" to "0:0 .62:0 .68:1 .96:1 1:0",
            "ok" to OK78),
        scene("forward", 9f, 0.9f, mapOf("sent" to 1f, "kofi" to 1f, "finger.dx" to 150f),
            "finger.dx" to "0:150 .04:0 .16:0 .22:98 .24:98 .26:98 .34:98 .42:112 .44:112 .46:112 .58:112 .72:138 .75:138 .78:138 .9:150 1:150",
            "finger.dy" to "0:70 .04:0 .16:0 .22:20 .24:22 .26:20 .34:20 .42:-2 .44:0 .46:-2 .58:-2 .72:41 .75:43 .78:41 .9:70 1:70",
            "press" to "0:0 .04:0 .16:1 .18:1 .19:0 1:0",
            "press.alpha" to "0:1 .16:1 .18:0 .99:0 1:1",
            "menu" to "0:0 .16:0 .2:1 .28:1 .31:0 1:0",
            "row" to "0:0 .23:0 .25:1 .3:1 .32:0 1:0",
            "list" to "0:0 .3:0 .34:1 .48:1 .52:0 1:0",
            "pick" to "0:0 .43:0 .45:1 .5:1 .52:0 1:0",
            "kofi" to "0:0 .5:0 .56:1 .96:1 1:0",
            "ghost" to "0:0 .48:0 .5:1 .6:1 .62:0 1:0",
            "ghost.move" to "0:0 .5:0 .6:1 .62:1 .63:0 1:0",
            "draft" to "0:0 .6:0 .62:1 .79:1 .81:0 1:0",
            "send" to "0:0 .74:0 .75:1 .8:1 .82:0 1:0",
            "sent" to "0:0 .8:0 .86:1 .96:1 1:0",
            "sent.dy" to "0:0 .01:26 .8:26 .86:0 1:0",
            "ok" to OK88),
        scene("later", 8f, 0.9f, mapOf("sent" to 1f, "finger.dx" to 134f),
            "finger.dx" to "0:134 .04:134 .1:0 .12:0 .14:0 .2:120 .22:120 .24:120 .26:120 .28:120 .3:120 .38:84 .4:84 .42:84 .52:134 1:134",
            "finger.dy" to "0:20 .04:20 .1:0 .12:2 .14:0 .2:-29 .22:-27 .24:-29 .26:-29 .28:-27 .3:-29 .38:-9 .4:-7 .42:-9 .52:20 1:20",
            "draft" to "0:1 .45:1 .465:0 .985:0 1:1",
            "button" to "0:0 .095:0 .1:1 .14:1 .145:0 1:0",
            "window" to "0:0 .14:0 .18:1 .44:1 .47:0 1:0",
            "time" to "0:0 .23:0 .23:1 .29:1 .29:2 .99:2 1:0",
            "plus" to "0:0 .215:0 .22:1 .24:1 .245:0 .275:0 .28:1 .3:1 .305:0 1:0",
            "go" to "0:0 .395:0 .4:1 .44:1 .445:0 1:0",
            "wait" to "0:0 .46:0 .51:1 .77:1 .8:0 1:0",
            "wait.dy" to "0:30 .46:30 .51:0 .8:0 .81:30 1:30",
            "hand" to "0:0 .48:0 .78:720 .79:0 1:0",
            "sent" to "0:0 .78:0 .84:1 .96:1 1:0",
            "tx" to "0:0 .805:0 .82:1 .96:1 .975:0 1:0"),
        scene("later_pick", 10f, 0.9f, mapOf("chosen" to 1f, "time" to 1f, "finger.dx" to 40f),
            "finger.dx" to "0:40 .04:40 .1:0 .12:0 .14:0 .22:87 .24:87 .26:87 .3:108 .32:108 .34:108 .37:150 .39:150 .41:150 .46:0 .48:0 .5:0 .6:108.5 .62:108.5 .64:108.5 .68:150 .7:150 .72:150 .82:40 1:40",
            "finger.dy" to "0:46 .04:46 .1:0 .12:2 .14:0 .22:16 .24:18 .26:16 .3:-20 .32:-18 .34:-20 .37:36 .39:38 .41:36 .46:-19 .48:-17 .5:-19 .6:8 .62:10 .64:8 .68:36 .7:38 .72:36 .82:46 1:46",
            "today" to "0:1 .73:1 .735:0 .99:0 1:1",
            "calLit" to "0:0 .475:0 .48:1 .51:1 .515:0 1:0",
            "chosen" to "0:0 .73:0 .735:1 .99:1 1:0",
            "timeLit" to "0:0 .105:0 .11:1 .15:1 .155:0 1:0",
            "time" to "0:0 .42:0 .42:1 .99:1 1:0",
            "clock" to "0:0 .14:0 .17:1 .4:1 .425:0 1:0",
            "header" to "0:0 .24:0 .24:1 .32:1 .32:2 .99:2 1:0",
            "minutes" to "0:0 .28:0 .28:1 .99:1 1:0",
            "calendar" to "0:0 .51:0 .54:1 .72:1 .745:0 1:0",
            "day" to "0:0 .62:0 .62:1 .99:1 1:0",
            "ok" to OK88),
    )

    /** Scene lists in Help-page order. */
    val groups: List<List<String>> get() = listOf(SETUP, TALKING, MORE, PROBLEMS, RARE)

    val all: Map<String, HowToScene> = (setup + talking + more + problems + rare).associateBy { it.name }

    fun get(name: String): HowToScene = requireNotNull(all[name]) { "No how-to scene $name" }
}

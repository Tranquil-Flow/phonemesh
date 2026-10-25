package org.phonemesh.core

/** Approved phonemesh-howto-add.html timings. No Android dependencies. */
object AccountsHowToScenes {
    val NAMES = listOf("add_qr", "add_link", "add_accept", "same_picture")

    private fun scene(name: String, ms: Long, still: Float, shows: Map<String, Float>, vararg tracks: Pair<String, String>) =
        HowToScene(name, ms, still, linkedMapOf(*tracks), shows)

    /** Visibility windows in percent; interior fades match the mockup's 1.5%. */
    private fun window(a: Float, b: Float): String =
        "0:0 ${(a - 1.5f) / 100}:0 ${a / 100}:1 ${b / 100}:1 ${(b + 1.5f) / 100}:0 1:0"

    private fun cells(): Array<Pair<String, String>> = (0 until 6).map { i ->
        val a = (4 + i * 5) / 100f
        val b = (8 + i * 5) / 100f
        val second = if (i < 3) {
            val c = (58 + i * 6) / 100f
            val d = (62 + i * 6) / 100f
            " ${c - .005f}:0 $c:1 $d:1 ${d + .005f}:0"
        } else ""
        "cell$i" to "0:0 ${a - .005f}:0 $a:1 $b:1 ${b + .005f}:0$second 1:0"
    }.toTypedArray()

    val all: List<HowToScene> = listOf(
        scene("add_qr", 10_000L, .82f,
            mapOf("a.scan" to 1f, "a.code" to 1f, "a.cone" to 1f, "a.move" to 1f, "a.ok" to 1f, "b.ok" to 1f),
            "a.move" to "0:0 .5:0 .58:1 .86:1 .94:0 1:0",
            "b.move" to "0:0 .06:0 .14:1 .42:1 .5:0 1:0",
            // Fix CSS win(0,50)/win(50,100)'s conflicting endpoint keys: both ends must agree.
            "a.qr" to "0:1 .5:1 .515:0 .97:0 1:1",
            "b.qr" to "0:0 .485:0 .5:1 .985:1 1:0",
            "a.scan" to window(52f, 92f), "b.scan" to window(8f, 48f),
            "a.code" to window(66f, 88f), "b.code" to window(22f, 44f),
            "a.cone" to window(62f, 84f), "b.cone" to window(18f, 40f),
            "a.line" to "0:0 .66:0 .74:20 .86:0 1:0",
            "b.line" to "0:0 .22:0 .3:20 .42:0 1:0",
            "a.ok" to "0:0 .78:0 .82:1 .96:1 1:0",
            "b.ok" to "0:0 .34:0 .38:1 .96:1 1:0"),
        scene("add_link", 7_000L, .71f,
            mapOf("message" to 1f, "right.dx" to 0f, "right.dy" to 2f, "finger.dx" to 40f),
            "button" to "0:0 .115:0 .12:1 .22:1 .225:0 1:0",
            "sheet" to window(22f, 44f),
            "finger.dx" to "0:40 .08:0 .16:0 .26:-8 .32:-8 .42:40 1:40",
            "finger.dy" to "0:30 .08:0 .12:2 .16:0 .26:-14 .29:-12 .32:-14 .42:30 1:30",
            "fly.alpha" to "0:0 .42:0 .44:1 .58:1 .6:0 1:0",
            "fly.dx" to "0:0 .44:0 .58:100 .6:100 .6:0 1:0",
            "message" to window(58f, 74f), "open" to window(76f, 98f),
            "right.dx" to "0:60 .6:60 .68:0 .74:0 .84:60 1:60",
            "right.dy" to "0:40 .6:40 .68:0 .71:2 .74:0 .84:40 1:40"),
        scene("add_accept", 8_000L, .28f,
            mapOf("card" to 1f, "accept" to 1f, "finger.dx" to 0f, "finger.dy" to 2f),
            "card" to "0:0 .04:0 .1:1 .46:1 .52:0 1:0",
            "card.dy" to "0:-14 .04:-14 .1:0 .52:0 .52:-14 1:-14",
            "accept" to "0:0 .235:0 .24:1 .34:1 .345:0 1:0",
            "finger.dx" to "0:40 .16:40 .24:0 .32:0 .42:40 1:40",
            "finger.dy" to "0:40 .16:40 .24:0 .28:2 .32:0 .42:40 1:40",
            "talk" to window(54f, 96f),
            "out.alpha" to "0:0 .58:0 .6:1 .7:1 .72:0 1:0",
            "out.dx" to "0:0 .6:0 .7:64 .72:64 .72:0 1:0",
            "back.alpha" to "0:0 .74:0 .76:1 .86:1 .88:0 1:0",
            "back.dx" to "0:0 .76:0 .86:-64 .88:-64 .88:0 1:0"),
        // Reduced-motion still deliberately teaches rejection, not just the successful half.
        scene("same_picture", 13_000L, .87f,
            mapOf("stranger" to 1f, "swap" to 1f, "different" to 1f, "bad" to 0f,
                "reject" to 1f, "reject.dx" to 0f, "reject.dy" to 2f,
                "mismatch" to 1f, "ok" to 0f, "finger.dx" to 40f),
            *cells(),
            "mismatch" to "0:0 .755:0 .76:1 .97:1 .975:0 1:0",
            "stranger" to window(54f, 97f), "swap" to window(53f, 98f),
            "same" to "0:0 .035:0 .04:1 .34:1 .345:0 .575:0 .58:1 .75:1 .755:0 1:0",
            "different" to window(76f, 97f),
            "accept" to "0:0 .395:0 .4:1 .47:1 .475:0 1:0",
            "reject" to "0:0 .855:0 .86:1 .92:1 .925:0 1:0",
            "finger.dx" to "0:40 .36:40 .41:0 .46:0 .52:40 1:40",
            "finger.dy" to "0:30 .36:30 .41:0 .43:2 .46:0 .52:30 1:30",
            "reject.dx" to "0:40 .8:40 .85:0 .9:0 .95:40 1:40",
            "reject.dy" to "0:30 .8:30 .85:0 .87:2 .9:0 .95:30 1:30",
            "ok" to "0:0 .45:0 .48:1 .54:1 .56:0 1:0",
            "ok.scale" to "0:.3 .45:.3 .48:1.3 .54:1.3 .56:.3 1:.3",
            "bad" to "0:0 .88:0 .91:1 .97:1 .99:0 1:0",
            "bad.scale" to "0:.3 .88:.3 .91:1.3 .97:1.3 .99:.3 1:.3"),
    )
}

package org.phonemesh.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec decision 29: any one emoji from the phone's keyboard, within the wire's reaction limit. */
class ReactionsTest {
    @Test fun theSixFixedReactionsStillPass() {
        for (emoji in listOf("👍", "❤️", "😂", "😮", "😢", "🙏")) assertEquals(emoji, Reactions.single(emoji))
    }

    @Test fun skinTonesFlagsAndKeycapsAreOneEmoji() {
        for (emoji in listOf("👍🏽", "🙏🏿", "🇹🇬", "🇫🇷", "#️⃣", "7️⃣", "🎉", "🌧️", "⚽", "☕")) assertEquals(emoji, Reactions.single(emoji))
    }

    @Test fun spacesAroundTheEmojiAreDropped() {
        assertEquals("😂", Reactions.single("  😂 \n"))
    }

    @Test fun textSeveralEmojiAndNothingAreRefused() {
        for (typed in listOf("", "  ", "a", "ok", "1", "👍👍", "😂x", "x😂", "🏽", "🇹", "Ω", "中")) assertNull(typed, Reactions.single(typed))
    }

    @Test fun joinedEmojiAndTagFlagsUpTo32BytesAreOneEmoji() {
        // Owner, 2026-10-26: no phone runs v0.1.0, so reactions use the full 32-byte limit of decision 29.
        assertEquals(32, Reactions.MAX_BYTES)
        assertEquals(SharedPost.MAX_EMOJI, Reactions.MAX_BYTES)
        assertEquals(Body.MAX_EMOJI, Reactions.MAX_BYTES)
        val scotland = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        for (emoji in listOf("👨‍👩‍👧", "👨‍👩‍👧‍👦", "❤️‍🔥", "🧑🏽‍🌾", "🏳️‍🌈", "👩🏿‍🤝‍👨🏻", scotland)) assertEquals(emoji, Reactions.single(emoji))
    }

    @Test fun brokenJoinsAndTooLongJoinsAreRefused() {
        val tooLong = List(5) { "👨" }.joinToString("\u200D") + "\u200D👩" // 6 joined people: 39 bytes
        for (typed in listOf("👍\u200D", "\u200D👍", "👍\u200D\u200D👍", "👍\u200Dx", tooLong, "\uD83C\uDFF4\uDB40\uDC67", "\uDB40\uDC7F")) {
            assertNull(typed, Reactions.single(typed))
        }
    }

    @Test fun everyAcceptedReactionEncodesOnBothWires() {
        for (emoji in listOf("👍🏽", "🇹🇬", "#️⃣", "❤️", "👨‍👩‍👧‍👦")) {
            Body.encode(Body.Reaction(ByteArray(Body.REF), Reactions.single(emoji)!!))
        }
    }
}

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

    @Test fun emojiOverTheWireLimitAreRefusedSoOlderPhonesCanReadEveryReaction() {
        assertEquals(SharedPost.MAX_EMOJI, Reactions.MAX_BYTES)
        assertEquals(Body.MAX_EMOJI, Reactions.MAX_BYTES)
        for (typed in listOf("👨‍👩‍👧", "❤️‍🔥", "🧑🏽‍🌾")) assertNull(typed, Reactions.single(typed))
    }

    @Test fun everyAcceptedReactionEncodesOnBothWires() {
        for (emoji in listOf("👍🏽", "🇹🇬", "#️⃣", "❤️")) {
            Body.encode(Body.Reaction(ByteArray(Body.REF), Reactions.single(emoji)!!))
        }
    }
}

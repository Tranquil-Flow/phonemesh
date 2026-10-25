package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

/** Approved add/safety choreography, through the production registry. */
class AccountsHowToScenesTest {
    private fun key(scene: String, track: String, t: Float, expected: Float) =
        assertEquals("$scene.$track at $t", expected, HowToScenes.get(scene).value(track, t), 1e-4f)

    @Test fun allFourApprovedScenesHaveTheirExactDurations() {
        val expected = linkedMapOf("add_qr" to 10_000L, "add_link" to 7_000L, "add_accept" to 8_000L, "same_picture" to 13_000L)
        expected.forEach { (name, ms) -> assertEquals(name, ms, HowToScenes.get(name).durationMs) }
        assertEquals(expected.keys.toList(), HowToScenes.groups.last())
    }

    @Test fun qrShowsBothPhonesTakingTurnsAndLinkShowsTheRecipientsTap() {
        key("add_qr", "b.move", .14f, 1f)
        key("add_qr", "b.ok", .38f, 1f)
        key("add_qr", "a.move", .58f, 1f)
        key("add_qr", "a.ok", .82f, 1f)
        key("add_link", "sheet", .29f, 1f)
        key("add_link", "finger.dy", .29f, -12f)
        key("add_link", "fly.dx", .58f, 100f)
        key("add_link", "right.dy", .71f, 2f)
        key("add_link", "open", .84f, 1f)
    }

    @Test fun acceptingShowsTheRequestAndTheExplicitTapBeforeConversation() {
        key("add_accept", "card", .28f, 1f)
        key("add_accept", "finger.dy", .28f, 2f)
        key("add_accept", "out.dx", .7f, 64f)
        key("add_accept", "back.dx", .86f, -64f)
    }

    @Test fun safetyTeachesBothOutcomesAndReducedMotionKeepsTheRejectionVisible() {
        key("same_picture", "finger.dy", .43f, 2f)
        key("same_picture", "ok", .48f, 1f)
        key("same_picture", "stranger", .54f, 1f)
        key("same_picture", "swap", .54f, 1f)
        key("same_picture", "different", .76f, 1f)
        key("same_picture", "reject.dy", .87f, 2f)
        key("same_picture", "reject.dx", .87f, 0f)
        key("same_picture", "ok", .87f, 0f)
        key("same_picture", "bad", .91f, 1f)
        key("same_picture", "bad.scale", .91f, 1.3f)
        val still = HowToScenes.get("same_picture").still
        listOf("stranger", "swap", "different", "reject", "mismatch").forEach { key("same_picture", it, still, 1f) }
        key("same_picture", "reject.dy", still, 2f)
    }
}

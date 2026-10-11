package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TipsTest {
    private val none = TipFacts(sent = false, failed = false, received = false, direct = true)
    private val all = TipFacts(sent = true, failed = true, received = true, direct = true)

    @Test fun recordingTeachesCancelThenLock() {
        assertEquals(Tip.CANCEL, Tips.next(TipPlace.RECORDING, none, emptySet()))
        assertEquals(Tip.LOCK, Tips.next(TipPlace.RECORDING, none, setOf("cancel")))
        assertNull(Tips.next(TipPlace.RECORDING, none, setOf("cancel", "lock")))
    }

    @Test fun aDraftTeachesListen() {
        assertEquals(Tip.LISTEN, Tips.next(TipPlace.DRAFT, none, emptySet()))
        assertNull(Tips.next(TipPlace.DRAFT, none, setOf("listen")))
    }

    @Test fun theChatTeachesOnlyWhatHasHappened() {
        assertNull(Tips.next(TipPlace.CHAT, none, emptySet()))
        assertEquals(Tip.DELIVERY, Tips.next(TipPlace.CHAT, TipFacts(true, false, false, true), emptySet()))
        assertEquals(Tip.ARRIVE, Tips.next(TipPlace.CHAT, TipFacts(false, false, true, true), emptySet()))
    }

    @Test fun aFailureComesFirstThenArrivalThenDelivery() {
        assertEquals(Tip.FAILED, Tips.next(TipPlace.CHAT, all, emptySet()))
        assertEquals(Tip.ARRIVE, Tips.next(TipPlace.CHAT, all, setOf("failed")))
        assertEquals(Tip.DELIVERY, Tips.next(TipPlace.CHAT, all, setOf("failed", "arrive")))
        assertNull(Tips.next(TipPlace.CHAT, all, setOf("failed", "arrive", "delivery")))
    }

    @Test fun aGroupChatNeverPromisesDelivered() {
        assertNull(Tips.next(TipPlace.CHAT, TipFacts(true, false, false, direct = false), emptySet()))
        val group = all.copy(direct = false)
        assertEquals(Tip.FAILED, Tips.next(TipPlace.CHAT, group, emptySet()))
        assertEquals(Tip.ARRIVE, Tips.next(TipPlace.CHAT, group, setOf("failed")))
        assertNull(Tips.next(TipPlace.CHAT, group, setOf("failed", "arrive")))
        assertEquals("unseen in the group, so a private chat still shows it",
            Tip.DELIVERY, Tips.next(TipPlace.CHAT, all, setOf("failed", "arrive")))
    }

    @Test fun eachTipIsATalkingScene() =
        assertEquals(HowToScenes.TALKING.toSet(), Tip.entries.map { it.key }.toSet())

    @Test fun theTipShownInFewerMomentsComesFirstSoCancelAndLockTakeTurns() {
        assertEquals(Tip.LOCK, Tips.next(TipPlace.RECORDING, none, emptySet(), mapOf("cancel" to 1)))
        assertEquals("a tie goes to the first", Tip.CANCEL, Tips.next(TipPlace.RECORDING, none, emptySet(), mapOf("cancel" to 1, "lock" to 1)))
        assertEquals(Tip.LOCK, Tips.next(TipPlace.RECORDING, none, emptySet(), mapOf("cancel" to 2, "lock" to 1)))
        assertEquals(Tip.ARRIVE, Tips.next(TipPlace.CHAT, all, emptySet(), mapOf("failed" to 2, "arrive" to 1, "delivery" to 1)))
        assertEquals("a tip that is not due does not count", Tip.DELIVERY,
            Tips.next(TipPlace.CHAT, TipFacts(true, false, false, true), emptySet(), mapOf("delivery" to 2)))
    }

    @Test fun aTipIsOfferedInThreeMomentsAtMost() {
        assertEquals(3, Tips.MAX_SHOWINGS)
        assertEquals(Tip.CANCEL, Tips.next(TipPlace.RECORDING, none, emptySet(), mapOf("cancel" to 2, "lock" to 3)))
        assertNull(Tips.next(TipPlace.RECORDING, none, emptySet(), mapOf("cancel" to 3, "lock" to 3)))
        assertNull(Tips.next(TipPlace.DRAFT, none, emptySet(), mapOf("listen" to 99)))
        assertEquals(Tip.LISTEN, Tips.next(TipPlace.DRAFT, none, emptySet(), mapOf("cancel" to 3, "future" to 3)))
    }

    @Test fun aLearnedTipNeverReturnsHoweverFewItsShowings() {
        assertEquals(Tip.CANCEL, Tips.next(TipPlace.RECORDING, none, setOf("lock"), mapOf("cancel" to 2)))
        assertNull(Tips.next(TipPlace.RECORDING, none, setOf("lock"), mapOf("cancel" to 3)))
        assertNull(Tips.next(TipPlace.RECORDING, none, setOf("cancel", "lock"), emptyMap()))
    }

    @Test fun unrelatedShownKeysDoNotHideATip() {
        assertEquals(Tip.CANCEL, Tips.next(TipPlace.RECORDING, all, setOf("listen", "future")))
        assertEquals(Tip.FAILED, Tips.next(TipPlace.CHAT, all, setOf("cancel", "lock")))
    }
}
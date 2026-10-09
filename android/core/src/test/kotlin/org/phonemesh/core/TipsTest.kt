package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TipsTest {
    private val none = TipFacts(sent = false, failed = false, received = false)
    private val all = TipFacts(sent = true, failed = true, received = true)

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
        assertEquals(Tip.DELIVERY, Tips.next(TipPlace.CHAT, TipFacts(true, false, false), emptySet()))
        assertEquals(Tip.ARRIVE, Tips.next(TipPlace.CHAT, TipFacts(false, false, true), emptySet()))
    }

    @Test fun aFailureComesFirstThenArrivalThenDelivery() {
        assertEquals(Tip.FAILED, Tips.next(TipPlace.CHAT, all, emptySet()))
        assertEquals(Tip.ARRIVE, Tips.next(TipPlace.CHAT, all, setOf("failed")))
        assertEquals(Tip.DELIVERY, Tips.next(TipPlace.CHAT, all, setOf("failed", "arrive")))
        assertNull(Tips.next(TipPlace.CHAT, all, setOf("failed", "arrive", "delivery")))
    }

    @Test fun eachTipIsATalkingScene() =
        assertEquals(HowToScenes.TALKING.toSet(), Tip.entries.map { it.key }.toSet())

    @Test fun unrelatedShownKeysDoNotHideATip() {
        assertEquals(Tip.CANCEL, Tips.next(TipPlace.RECORDING, all, setOf("listen", "future")))
        assertEquals(Tip.FAILED, Tips.next(TipPlace.CHAT, all, setOf("cancel", "lock")))
    }
}
package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class PictureSenderTest {
    private val packets = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))

    @Test fun sendsOneAtATimeAndFinishesWhenAllAreAcknowledged() {
        val sender = PictureSender(packets)
        assertEquals(PictureSender.Action.Send(0, 1), sender.start())
        assertEquals(PictureSender.Action.Send(1, 1), sender.onOutcome(0, acknowledged = true))
        assertEquals(PictureSender.Action.Send(2, 1), sender.onOutcome(1, acknowledged = true))
        assertEquals(2, sender.acknowledged)
        assertEquals(PictureSender.Action.Finished(true), sender.onOutcome(2, acknowledged = true))
        assertEquals(3, sender.acknowledged)
    }

    @Test fun retriesAPacketThenGivesUp() {
        val sender = PictureSender(packets, tries = 3)
        sender.start()
        assertEquals(PictureSender.Action.Send(0, 2), sender.onOutcome(0, acknowledged = false))
        assertEquals(PictureSender.Action.Send(0, 3), sender.onOutcome(0, acknowledged = false))
        assertEquals(PictureSender.Action.Finished(false), sender.onOutcome(0, acknowledged = false))
        assertTrue(sender.finished)
    }

    @Test fun aRetryThatWorksCarriesOn() {
        val sender = PictureSender(packets)
        sender.start()
        sender.onOutcome(0, acknowledged = false)
        assertEquals(PictureSender.Action.Send(1, 1), sender.onOutcome(0, acknowledged = true))
    }

    @Test fun reportsForAnotherPacketOrAfterTheEndAreIgnored() {
        val sender = PictureSender(packets)
        sender.start()
        assertNull(sender.onOutcome(2, acknowledged = true))
        sender.onOutcome(0, true); sender.onOutcome(1, true); sender.onOutcome(2, true)
        assertNull(sender.onOutcome(2, acknowledged = false))
    }

    @Test fun cancelEndsIt() {
        val sender = PictureSender(packets)
        sender.start()
        assertEquals(PictureSender.Action.Finished(false), sender.cancel())
        assertNull(sender.onOutcome(0, acknowledged = true))
    }
}

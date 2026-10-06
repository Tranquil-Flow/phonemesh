package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.DeliveryStatus.*
import org.phonemesh.core.RadioReport.ACKNOWLEDGED
import org.phonemesh.core.RadioReport.QUEUED
import org.phonemesh.core.RadioReport.SUBMITTING

/** The spec's delivery table, row by row. */
class DeliveryRulesTest {
    private fun run(group: Boolean, vararg reports: RadioReport) =
        reports.fold(WAITING) { status, report -> DeliveryRules.next(status, report, group) }

    @Test fun submittingIsAClockForBoth() {
        assertEquals(WAITING, run(false, SUBMITTING))
        assertEquals(WAITING, run(true, SUBMITTING))
    }
    @Test fun queuedPrivateStillWaits() = assertEquals(WAITING, run(false, SUBMITTING, QUEUED))
    @Test fun queuedGroupIsSentAndFinal() {
        assertEquals(SENT, run(true, SUBMITTING, QUEUED))
        assertEquals(SENT, run(true, SUBMITTING, QUEUED, RadioReport.UNCONFIRMED))
    }
    @Test fun onlyPrivateGetsATick() {
        assertEquals(DELIVERED, run(false, SUBMITTING, QUEUED, ACKNOWLEDGED))
        assertEquals(SENT, run(true, SUBMITTING, QUEUED, ACKNOWLEDGED))
    }
    @Test fun unconfirmedAfterQueuedWarnsForPrivate() =
        assertEquals(UNCONFIRMED, run(false, SUBMITTING, QUEUED, RadioReport.UNCONFIRMED))
    @Test fun unconfirmedBeforeQueuedWarnsForBoth() {
        assertEquals(UNCONFIRMED, run(false, SUBMITTING, RadioReport.UNCONFIRMED))
        assertEquals(UNCONFIRMED, run(true, SUBMITTING, RadioReport.UNCONFIRMED))
    }
    @Test fun failedIsAFailureForBoth() {
        assertEquals(FAILED, run(false, SUBMITTING, RadioReport.FAILED))
        assertEquals(FAILED, run(true, SUBMITTING, QUEUED, RadioReport.FAILED))
    }
    @Test fun finalStatesChangeOnlyByResending() {
        assertEquals(DELIVERED, DeliveryRules.next(DELIVERED, RadioReport.UNCONFIRMED, false))
        assertEquals(FAILED, DeliveryRules.next(FAILED, QUEUED, false))
        assertEquals(UNCONFIRMED, DeliveryRules.next(UNCONFIRMED, ACKNOWLEDGED, false))
    }
    @Test fun reopenTurnsOnlyClocksIntoWarnings() {
        assertEquals(UNCONFIRMED, DeliveryRules.onReopen(WAITING))
        assertEquals(SENT, DeliveryRules.onReopen(SENT))
        assertEquals(DELIVERED, DeliveryRules.onReopen(DELIVERED))
        assertEquals(FAILED, DeliveryRules.onReopen(FAILED))
    }
    @Test fun onlyWarningsCanBeResent() {
        assertTrue(UNCONFIRMED.canResend)
        assertTrue(FAILED.canResend)
        listOf(WAITING, SENT, DELIVERED).forEach { assertFalse(it.name, it.canResend) }
    }
}

package org.phonemesh.ble

import org.junit.Assert.*
import org.junit.Test

/** Covers ConnectionStatusReporter directly, not transport source text. */
class ConnectionStatusTest {
    @Test fun readinessIsOnlyChangedByConnectionBearingOutcomes() {
        val r = ConnectionStatusReporter()
        assertFalse(r.isReady)
        assertTrue(r.radioReady("config complete").changesReadiness)
        assertTrue(r.isReady)
        assertFalse(r.diagnostic("Draining fromRadio").changesReadiness)
        assertFalse(r.diagnostic("Node discovered !12345678").changesReadiness)
        assertFalse(r.diagnostic("Radio queue status res=0").changesReadiness)
        assertTrue(r.isReady)
        assertTrue(r.connectionLost("BLE radio disconnected").changesReadiness)
        assertFalse(r.isReady)
    }

    @Test fun stopScanPreservesAnEstablishedConnection() {
        val r = ConnectionStatusReporter()
        r.radioReady("ready")
        val status = r.scanStopped()
        assertTrue(status.ready)
        assertFalse(status.changesReadiness)
        assertTrue(r.isReady)
        assertTrue(status.detail.contains("still ready"))
    }

    @Test fun scanFailureDoesNotPretendTheConnectionWasLost() {
        val r = ConnectionStatusReporter()
        r.radioReady("ready")
        val failed = r.scanFailed(2)
        assertTrue(failed.ready)
        assertTrue(r.isReady)
        assertFalse(failed.changesReadiness)
    }

    @Test fun scanStoppedBeforeConnectingStillReportsNotReady() {
        val r = ConnectionStatusReporter()
        val stopped = r.scanStopped()
        assertFalse(stopped.ready)
        assertTrue(r.connecting("Connecting to radio").changesReadiness)
        assertFalse(r.isReady)
    }
}

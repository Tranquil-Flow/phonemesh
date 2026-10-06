package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.phonemesh.ble.DeliveryState
import org.phonemesh.core.RadioReport

/** AppCore maps the radio's DeliveryState to core's RadioReport by name. */
class ReportMappingTest {
    @Test fun everyRadioStateHasAReport() {
        assertEquals(DeliveryState.values().size, RadioReport.values().size)
        DeliveryState.values().forEach { assertEquals(it.name, RadioReport.valueOf(it.name).name) }
    }
}

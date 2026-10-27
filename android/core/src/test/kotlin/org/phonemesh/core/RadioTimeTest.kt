package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Airtime meter (AM1-AM3 A). Expected seconds come from an independent Python implementation of Semtech AN1200.13
 * with firmware 2.7.26's framing (23 bytes below 128 application bytes, 24 from 128), not from this code.
 */
class RadioTimeTest {
    private val sf = LoraAirtime.preset("SHORT_FAST")!!
    private val mf = LoraAirtime.preset("MEDIUM_FAST")!!
    private val lf = LoraAirtime.preset("LONG_FAST")!!
    private val ls = LoraAirtime.preset("LONG_SLOW")!!

    private fun check(payloads: List<Int>, short: Double, medium: Double, long: Double, slow: Double) {
        assertEquals("$payloads SF", short, RadioTime.seconds(payloads, sf), 1e-6)
        assertEquals("$payloads MF", medium, RadioTime.seconds(payloads, mf), 1e-6)
        assertEquals("$payloads LF", long, RadioTime.seconds(payloads, lf), 1e-6)
        assertEquals("$payloads LS", slow, RadioTime.seconds(payloads, ls), 1e-6)
    }

    @Test fun goldenVectorsMatchTheIndependentCalculator() {
        check(listOf(69), 0.083584, 0.272896, 0.927744, 5.906432) // a 40-byte French private text
        check(listOf(36), 0.060544, 0.201216, 0.681984, 4.071424) // a receipt
        check(listOf(228), 0.201344, 0.641536, 2.115584, 14.29504) // a full packet
        check(listOf(67, 228, 228, 134), 0.618496, 1.982464, 6.578176, 43.548672) // a 500-byte picture
        check(listOf(127), 0.127104, 0.406016, 1.378304, 8.790016)
        check(listOf(128), 0.127104, 0.406016, 1.378304, 9.05216) // the two-byte length shows on LongSlow
    }

    @Test fun theFramingIs23BytesBelow128And24From128() {
        assertEquals(23, RadioTime.onAir(0))
        assertEquals(127 + 23, RadioTime.onAir(127))
        assertEquals(128 + 24, RadioTime.onAir(128))
        assertEquals(228 + 24, RadioTime.onAir(228))
        assertTrue(runCatching { RadioTime.onAir(-1) }.isFailure)
        assertEquals("the text port's number is one byte", 40 + 22, RadioTime.onAir(40, RadioTime.TEXT_PORT))
        assertEquals(200 + 23, RadioTime.onAir(200, RadioTime.TEXT_PORT))
        assertEquals(40 + 23, RadioTime.onAir(40, 303))
    }

    @Test fun peopleReadWholeSecondsThenWholeMinutesAndNeverAGuess() {
        assertEquals(RadioTime.Shown.Seconds(1), RadioTime.shown(0.08))
        assertEquals(RadioTime.Shown.Seconds(7), RadioTime.shown(6.578))
        assertEquals(RadioTime.Shown.Seconds(59), RadioTime.shown(58.2))
        assertEquals(RadioTime.Shown.Minutes(1), RadioTime.shown(60.0))
        assertEquals(RadioTime.Shown.Minutes(4), RadioTime.shown(221.5))
        assertEquals("an unknown radio setting is unknown time", RadioTime.Shown.Unknown, RadioTime.shown(null))
        assertEquals(RadioTime.Shown.Unknown, RadioTime.shown(Double.NaN))
    }
}

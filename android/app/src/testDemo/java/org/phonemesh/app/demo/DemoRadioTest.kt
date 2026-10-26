package org.phonemesh.app.demo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RegionResult

class DemoRadioTest {
    private val clock = ManualClock()
    private val mesh = DemoMesh(clock)
    private val state = MapState()
    private val app = RecordingListener()
    private val other = Ear().also { it.clock = clock }
    private val radio = DemoRadio(app, mesh, clock, post = { it() }, state = state)

    init { mesh.join("!kofi", other) }

    private fun connect() {
        radio.connect(DemoRadio.ADDRESS)
        clock.advance(5_000)
        assertTrue("ready after connect", app.connected)
    }

    @Test fun scanningFindsTheDemoRadioAndConnectingMakesItReady() {
        radio.scan()
        clock.advance(2_000)
        assertEquals(listOf(DemoRadio.ADDRESS to DemoRadio.NAME), app.devices)
        radio.stopScan()
        assertEquals(1, app.scanStopped)
        assertNull("no region before the handshake", radio.region())
        connect()
        assertEquals("UNSET", radio.region())
        assertEquals(DemoRadio.NAME, radio.ownName())
        assertEquals("LONG_FAST", radio.modemPreset())
    }

    @Test fun aWrongAddressFailsTheConnection() {
        radio.connect("00:11:22:33:44:55")
        clock.advance(10_000)
        assertEquals(1, app.connectFailed)
        assertFalse(app.connected)
    }

    @Test fun writingTheRegionRestartsTheRadioAndItComesBackWithTheNewRegion() {
        connect()
        var result: RegionResult? = null
        radio.setRegion("EU_868") { result = it }
        clock.advance(5_000)
        assertEquals(RegionResult.SENT, result)
        assertFalse("the radio restarts after a region write", app.connected)
        connect()
        assertEquals("EU_868", radio.region())
        // The region lives in the radio: a new demo radio object (app restart) still has it.
        val again = DemoRadio(RecordingListener(), mesh, clock, post = { it() }, state = state)
        again.connect(DemoRadio.ADDRESS)
        clock.advance(5_000)
        assertEquals("EU_868", again.region())
        try { radio.setRegion("NOT_A_REGION") { }; fail("unknown region accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun broadcastsNeedAReadyRadioAndReachTheMeshAfterTheRadioQueuesThem() {
        try { radio.sendBroadcastData(MeshRadio.ACCOUNT_PORT, ByteArray(10), 0); fail("sent without a radio") } catch (_: IllegalStateException) { }
        connect()
        val payload = ByteArray(90) { 7 }
        val id = radio.sendBroadcastData(MeshRadio.ACCOUNT_PORT, payload, 0)
        clock.advance(10_000)
        assertEquals(listOf(id to DeliveryState.SUBMITTING, id to DeliveryState.QUEUED), app.deliveries)
        assertArrayEquals(payload, other.heard.single().payload)
        assertEquals(DemoRadio.NODE, other.heard.single().from)
    }

    @Test fun broadcastsFromOtherRadiosArriveOnTheirPortOnlyWhileConnected() {
        mesh.broadcast("!kofi", MeshRadio.SHARED_CHAT_PORT, ByteArray(20))
        clock.advance(5_000)
        assertTrue(app.broadcasts.isEmpty())
        connect()
        mesh.broadcast("!kofi", MeshRadio.SHARED_CHAT_PORT, ByteArray(20))
        mesh.broadcast("!kofi", 1, "hello".toByteArray())
        clock.advance(5_000)
        assertEquals(MeshRadio.SHARED_CHAT_PORT, app.broadcasts.single().port)
        assertEquals("!kofi", app.broadcasts.single().sender)
        assertEquals(Triple("!kofi", "hello", false), app.texts.single())
    }

    @Test fun anAddressedPacketToAnUnreachableRadioFails() {
        connect()
        mesh.cut(DemoRadio.NODE, "!kofi")
        val id = radio.sendData(MeshRadio.PICTURE_PORT, ByteArray(30), "!kofi", 0)
        clock.advance(120_000)
        assertEquals(DeliveryState.FAILED, app.deliveries.last { it.first == id }.second)
        mesh.restore(DemoRadio.NODE, "!kofi")
        val ok = radio.sendData(MeshRadio.PICTURE_PORT, ByteArray(30), "!kofi", 0)
        clock.advance(120_000)
        assertEquals(DeliveryState.ACKNOWLEDGED, app.deliveries.last { it.first == ok }.second)
    }

    @Test fun switchingTheRadioOffDropsTheLinkAndScanningFindsNothingUntilItIsOn() {
        connect()
        radio.switchOff()
        assertFalse(app.connected)
        radio.scan()
        clock.advance(3_000)
        radio.stopScan()
        assertTrue(app.devices.isEmpty())
        radio.connect(DemoRadio.ADDRESS)
        clock.advance(40_000)
        assertFalse(app.connected)
        radio.switchOn()
        connect()
    }
}

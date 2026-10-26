package org.phonemesh.app.demo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoMeshTest {
    private val clock = ManualClock()
    private val mesh = DemoMesh(clock)
    private val a = Ear().also { it.clock = clock }
    private val b = Ear().also { it.clock = clock }
    private val c = Ear().also { it.clock = clock }

    init {
        mesh.join("!a", a); mesh.join("!b", b); mesh.join("!c", c)
    }

    @Test fun aBroadcastReachesEveryOtherNodeAfterItsAirtimeAndNeverItsSender() {
        val payload = ByteArray(120) { it.toByte() }
        val airtime = mesh.airtimeMillis(payload.size)
        assertTrue("LongFast airtime for 120 bytes is hundreds of ms, was $airtime", airtime in 300L..2_000L)
        mesh.broadcast("!a", 301, payload)
        clock.advance(airtime - 1)
        assertTrue(b.heard.isEmpty())
        clock.advance(1)
        assertEquals(1, b.heard.size)
        assertEquals(1, c.heard.size)
        assertTrue(a.heard.isEmpty())
        assertEquals("!a", b.heard[0].from)
        assertEquals(301, b.heard[0].port)
        assertEquals(null, b.heard[0].to)
        assertArrayEquals(payload, b.heard[0].payload)
    }

    @Test fun oneRadioAirsItsPacketsOneAfterAnother() {
        mesh.broadcast("!a", 301, ByteArray(100))
        mesh.broadcast("!a", 301, ByteArray(100))
        clock.advance(10_000)
        assertEquals(2, b.heard.size)
        assertTrue(b.heard[1].at - b.heard[0].at >= mesh.airtimeMillis(100))
    }

    @Test fun aCutLinkDropsPacketsUntilItIsRestored() {
        mesh.cut("!a", "!b")
        mesh.broadcast("!a", 301, ByteArray(10))
        clock.advance(5_000)
        assertTrue(b.heard.isEmpty())
        assertEquals(1, c.heard.size)
        mesh.restore("!a", "!b")
        mesh.broadcast("!a", 301, ByteArray(10))
        clock.advance(5_000)
        assertEquals(1, b.heard.size)
    }

    @Test fun aNodeThatIsOffNeitherSendsNorHears() {
        mesh.setOn("!b", false)
        mesh.broadcast("!a", 301, ByteArray(10))
        mesh.broadcast("!b", 301, ByteArray(10))
        clock.advance(5_000)
        assertTrue(b.heard.isEmpty())
        assertEquals(1, c.heard.size)
        mesh.setOn("!b", true)
        mesh.broadcast("!a", 301, ByteArray(10))
        clock.advance(5_000)
        assertEquals(1, b.heard.size)
    }

    @Test fun anAddressedPacketReachesOnlyItsNodeAndReportsWhetherItArrived() {
        var arrived: Boolean? = null
        mesh.send("!a", "!b", 300, ByteArray(50)) { arrived = it }
        clock.advance(10_000)
        assertEquals(true, arrived)
        assertEquals("!b", b.heard.single().to)
        assertTrue(c.heard.isEmpty())
        mesh.cut("!a", "!b")
        mesh.send("!a", "!b", 300, ByteArray(50)) { arrived = it }
        clock.advance(60_000)
        assertEquals(false, arrived)
    }
}

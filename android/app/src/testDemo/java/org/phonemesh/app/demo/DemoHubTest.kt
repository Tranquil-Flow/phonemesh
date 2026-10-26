package org.phonemesh.app.demo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.WireV2
import java.io.IOException

class DemoHubTest {
    @get:Rule val folder = TemporaryFolder()
    private val clock = ManualClock()
    private val mesh = DemoMesh(clock)
    private val kofi = Ear().also { it.clock = clock; mesh.join("!kofi", it) }

    private fun hello(): ByteArray =
        Start.link(SoftKeys.generate(), org.phonemesh.core.account.Profile("Yaw", 0x43A047), ContactCard.create(SoftKeys.generate(), org.phonemesh.core.account.Profile("Esi", 0x1E88E5)), KeyPairBytes.generate())!!.hello

    private fun joined(hub: DemoHub): Int {
        var changes = 0
        hub.wifiLink.join(hub.card) { changes++ }
        hub.bluetoothLink.join(hub.card) { changes++ }
        clock.advance(5_000)
        return changes
    }

    @Test fun onlyTheDemoHubsOwnCardJoinsAndBothWaysThenAnswer() {
        val hub = DemoHub(folder.root, mesh, clock)
        // Same shape as the demo hub's card, another hub's key.
        val stranger = HubCard("Other", "", 443, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 }))
        hub.wifiLink.join(stranger) { }
        clock.advance(5_000)
        assertNull(hub.wifiLink.wire())
        assertNotNull("the hub card is a real hub card", HubCard.parse(hub.card.encode()))
        assertTrue(joined(hub) >= 2)
        assertNotNull(hub.wifiLink.wire())
        assertNotNull(hub.bluetoothLink.wire())
        hub.wifiLink.leave()
        assertNull(hub.wifiLink.wire())
    }

    @Test fun mailFromThePhoneIsStoredOnceAndAiredByTheHubsRadio() {
        val hub = DemoHub(folder.root, mesh, clock)
        joined(hub)
        val packet = hello()
        val wire = hub.wifiLink.wire()!!
        assertEquals(HubClient.Sent.STORED, wire.send(WireV2.PORT, packet))
        assertEquals(HubClient.Sent.ALREADY, wire.send(WireV2.PORT, packet))
        assertEquals(HubClient.Sent.REFUSED, wire.send(WireV2.PORT, ByteArray(5)))
        clock.advance(30_000)
        val aired = kofi.heard.filter { it.from == DemoHub.NODE && it.port == WireV2.PORT }
        assertArrayEquals(packet, aired.single().payload)
    }

    @Test fun mailTheHubHearsOnTheMeshWaitsInItsMailbox() {
        val hub = DemoHub(folder.root, mesh, clock)
        joined(hub)
        val packet = hello()
        mesh.broadcast("!kofi", WireV2.PORT, packet)
        clock.advance(10_000)
        val wire = hub.wifiLink.wire()!!
        val mail = wire.mail(0, emptyList(), 0, hellos = true)
        assertArrayEquals(packet, mail.single().packet)
        assertEquals(mail.single().seq, wire.latest())
        assertTrue(wire.mail(mail.single().seq, emptyList(), 0, hellos = true).isEmpty())
    }

    @Test fun aHubThatIsOffOrOutOfReachDoesNotAnswer() {
        val hub = DemoHub(folder.root, mesh, clock)
        var changes = 0
        hub.wifiLink.join(hub.card) { changes++ }
        hub.bluetoothLink.join(hub.card) { changes++ }
        clock.advance(5_000)
        val wire = hub.wifiLink.wire()!!
        val before = changes
        hub.setWifi(false)
        assertNull(hub.wifiLink.wire())
        assertNotNull(hub.bluetoothLink.wire())
        try { wire.send(WireV2.PORT, hello()); fail("an old wire still answered after Wi-Fi went") } catch (_: IOException) { }
        hub.setPower(false)
        assertNull(hub.bluetoothLink.wire())
        assertTrue("the app hears every change", changes > before)
        mesh.broadcast("!kofi", WireV2.PORT, hello())
        clock.advance(10_000)
        hub.setPower(true)
        hub.setWifi(true)
        assertTrue("an off hub hears nothing", hub.wifiLink.wire()!!.mail(0, emptyList(), 0, hellos = true).isEmpty())
    }

    @Test fun joiningAgainWithTheSameCardKeepsTheWayAndDoesNotAskAgain() {
        // AppCore calls join on every hub change (keepHubBluetooth); a fresh join each time would never settle.
        val hub = DemoHub(folder.root, mesh, clock)
        var changes = 0
        hub.bluetoothLink.join(hub.card) { changes++ }
        clock.advance(5_000)
        val wire = hub.bluetoothLink.wire()!!
        val settled = changes
        repeat(3) { hub.bluetoothLink.join(hub.card) { changes++ } }
        clock.advance(5_000)
        assertTrue("the same wire stays", hub.bluetoothLink.wire() === wire)
        assertEquals("no new change for a join that changes nothing", settled, changes)
        // While a first join is still under way, a second one does not restart it.
        val other = DemoHub(folder.newFolder("b"), DemoMesh(clock), clock)
        other.wifiLink.join(other.card) { }
        clock.advance(600)
        other.wifiLink.join(other.card) { }
        clock.advance(700)
        assertNotNull(other.wifiLink.wire())
    }

    @Test fun theHubKeepsItsCardAndMailAcrossRestarts() {
        val first = DemoHub(folder.root, mesh, clock)
        joined(first)
        val packet = hello()
        first.wifiLink.wire()!!.send(WireV2.PORT, packet)
        first.close()
        val second = DemoHub(folder.root, DemoMesh(clock), clock)
        assertEquals(first.card, second.card)
        joined(second)
        assertArrayEquals(packet, second.wifiLink.wire()!!.mail(0, emptyList(), 0, hellos = true).single().packet)
    }
}

package org.phonemesh.app

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubSession

/** Exercises actual callback publication without requesting a network or opening any socket. */
@RunWith(AndroidJUnit4::class)
class WifiHubLinkTest {
    private val card = HubCard("Fixture", "password1", 8443, HubCard.pinOf(ByteArray(91) { it.toByte() }))

    private val connectivity get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(ConnectivityManager::class.java)
    private fun network() = connectivity.activeNetwork
        ?: error("fixture requires the emulator's existing active network; no join is performed")

    private fun session(link: WifiHubLink): HubSession = WifiHubLink::class.java.getDeclaredField("session").let {
        it.isAccessible = true
        it.get(link) as HubSession
    }

    private fun available(link: WifiHubLink, gen: Long, network: Network) {
        val properties = connectivity.getLinkProperties(network)
            ?: error("fixture requires the emulator's existing link properties")
        val callback: () -> Unit = {}
        val method = WifiHubLink::class.java.getDeclaredMethod("arrive", java.lang.Long.TYPE,
            Network::class.java, LinkProperties::class.java, HubCard::class.java, kotlin.jvm.functions.Function0::class.java)
        method.isAccessible = true
        method.invoke(link, gen, network, properties, card, callback)
    }

    @Test fun repeatedPropertiesOfTheSameNetworkKeepThePublishedClient() {
        val link = WifiHubLink(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val gen = session(link).begin()
            val network = network()
            available(link, gen, network)
            val original = link.wire()
            assertNotNull(original)
            available(link, gen, network)
            assertSame("ordinary link-property callbacks must not abort pinned requests", original, link.wire())
        } finally { link.leave() }
    }

    @Test fun aCallbackAfterLeaveCannotPublishAClient() {
        val link = WifiHubLink(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val gen = session(link).begin()
            link.leave()
            available(link, gen, network())
            assertNull(link.wire())
        } finally { link.leave() }
    }

    private class FakeAccess(val n: Network, var props: LinkProperties) : WifiHubLink.Access {
        var name: String? = "Fixture"
        var candidates: List<Network> = listOf(n)
        var failRead = false
        var failObserve = false
        var onObserve: ((ConnectivityManager.NetworkCallback) -> Unit)? = null
        var requests = 0
        val observers = mutableListOf<ConnectivityManager.NetworkCallback>()
        val removed = mutableListOf<ConnectivityManager.NetworkCallback>()
        override fun networks(): List<Network> {
            if (failRead) throw SecurityException("fixture denial")
            return candidates
        }
        override fun connectedSsid(n: Network) = name
        override fun properties(n: Network) = props
        override fun observe(callback: ConnectivityManager.NetworkCallback) {
            if (failObserve) throw SecurityException("fixture denial")
            observers.add(callback)
            onObserve?.invoke(callback)
        }
        override fun request(request: android.net.NetworkRequest, callback: ConnectivityManager.NetworkCallback) { requests++ }
        override fun unregister(callback: ConnectivityManager.NetworkCallback) { removed.add(callback) }
    }
    private fun fake(): FakeAccess {
        val n = network()
        return FakeAccess(n, connectivity.getLinkProperties(n) ?: error("missing emulator properties"))
    }
    @Test fun currentWifiInspectionPermissionIsDeclaredWithoutRuntimePrompt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        org.junit.Assert.assertTrue("current SSID inspection needs the normal ACCESS_WIFI_STATE permission",
            android.Manifest.permission.ACCESS_WIFI_STATE in permissions)
    }

    @Test fun exactConnectedSsidUsesExistingNetworkWithoutRequest() {
        val f = fake(); val link = WifiHubLink(f)
        try {
            link.join(card) {}
            org.junit.Assert.assertEquals(0, f.requests)
            org.junit.Assert.assertEquals(1, f.observers.size)
            assertNotNull(link.wire())
            val wire = link.wire()
            f.observers.single().onLinkPropertiesChanged(f.n, f.props)
            assertSame(wire, link.wire())
        } finally { link.leave() }
    }
    @Test fun unknownDifferentOrUnavailableSsidUsesOriginalRequest() {
        for (name in listOf(null, "<unknown ssid>", "fixture", "Fixture ", "Other")) {
            val f = fake(); f.name = name; val link = WifiHubLink(f)
            try {
                link.join(card) {}
                org.junit.Assert.assertEquals(1, f.requests)
                org.junit.Assert.assertEquals(0, f.observers.size)
                assertNull(link.wire())
            } finally { link.leave() }
        }
    }
    @Test fun deniedReadOrObserverRegistrationFallsBackWithoutPublishing() {
        for (read in listOf(true, false)) {
            val f = fake(); f.failRead = read; f.failObserve = !read; val link = WifiHubLink(f)
            try { link.join(card) {}; org.junit.Assert.assertEquals(1, f.requests); assertNull(link.wire()) }
            finally { link.leave() }
        }
    }
    @Test fun ambiguousNetworksDoNotReuseGlobalWifiIdentity() {
        val f = fake()
        f.candidates = listOf(f.n, Network.fromNetworkHandle(f.n.networkHandle + (1L shl 32)))
        val link = WifiHubLink(f)
        try { link.join(card) {}; org.junit.Assert.assertEquals(1, f.requests); assertNull(link.wire()) }
        finally { link.leave() }
    }
    @Test fun observerIsScopedAndLossCannotBeUndoneByLateProperties() {
        val f = fake(); val link = WifiHubLink(f)
        try {
            link.join(card) {}
            val cb = f.observers.single(); val wire = link.wire()
            val other = Network.fromNetworkHandle(f.n.networkHandle + (1L shl 32))
            cb.onAvailable(other); cb.onLinkPropertiesChanged(other, f.props); cb.onLost(other)
            assertSame(wire, link.wire())
            cb.onLost(f.n)
            assertNull(link.wire())
            org.junit.Assert.assertTrue(f.removed.contains(cb))
            cb.onAvailable(f.n); cb.onLinkPropertiesChanged(f.n, f.props)
            assertNull(link.wire())
            link.join(card) {}
            assertNotNull(link.wire())
            val replacement = link.wire()
            cb.onLost(f.n)
            assertSame(replacement, link.wire())
        } finally { link.leave() }
    }
    @Test fun leaveAndReplacedCardRetireObserverAndCloseClient() {
        val f = fake(); val link = WifiHubLink(f)
        try {
            link.join(card) {}
            val old = f.observers.single(); val wire = link.wire()!!
            link.join(card.copy(port = 8444)) {}
            val fresh = link.wire()
            org.junit.Assert.assertTrue(f.removed.contains(old))
            old.onAvailable(f.n); old.onLost(f.n)
            assertSame(fresh, link.wire())
            assertClosed(wire)
            val current = f.observers.last()
            link.leave(); current.onAvailable(f.n)
            assertNull(link.wire())
            org.junit.Assert.assertTrue(f.removed.contains(current))
        } finally { link.leave() }
    }

    private fun assertClosed(wire: org.phonemesh.core.hub.HubWire) {
        // Never call send as a negative close oracle: a broken close must not touch a real socket.
        val field = org.phonemesh.core.hub.HubClient::class.java.getDeclaredField("closed")
        field.isAccessible = true
        org.junit.Assert.assertTrue("retired client must be closed", field.getBoolean(wire))
    }

    @Test fun missingGatewayCannotPublishAndObserverIsRemoved() {
        val f = fake(); f.props = LinkProperties(); val link = WifiHubLink(f)
        try {
            link.join(card) {}
            assertNull(link.wire())
            org.junit.Assert.assertEquals(1, f.requests)
            org.junit.Assert.assertEquals(f.observers, f.removed)
        } finally { link.leave() }
    }

    @Test fun routeLossClosesClientAndRetiresObservation() {
        val f = fake(); val link = WifiHubLink(f)
        try {
            link.join(card) {}
            val wire = link.wire()!!; val cb = f.observers.single()
            cb.onLinkPropertiesChanged(f.n, LinkProperties())
            assertNull(link.wire()); assertClosed(wire)
            org.junit.Assert.assertTrue(f.removed.contains(cb))
        } finally { link.leave() }
    }

    @Test fun failedReuseCannotPublishFromItsQueuedCallbackAfterFallback() {
        val f = fake(); f.onObserve = { f.name = "Other" }; val link = WifiHubLink(f)
        try {
            link.join(card) {}
            assertNull(link.wire()); org.junit.Assert.assertEquals(1, f.requests)
            val stale = f.observers.single(); org.junit.Assert.assertTrue(f.removed.contains(stale))
            f.name = "Fixture"
            stale.onLinkPropertiesChanged(f.n, f.props)
            assertNull("an unregistered reuse observer must not publish under the fallback generation", link.wire())
        } finally { link.leave() }
    }

    @Test fun synchronousObserverAndChangedLeaveCannotLeakObservation() {
        val f = fake(); f.onObserve = { it.onLinkPropertiesChanged(f.n, f.props) }
        val link = WifiHubLink(f); var changes = 0
        try {
            link.join(card) { changes++; link.leave() }
            assertNull(link.wire())
            org.junit.Assert.assertEquals(1, changes)
            org.junit.Assert.assertEquals(f.observers, f.removed)
        } finally { link.leave() }
    }

    @Test fun capabilitiesOnlyIdentityLossRetiresTheClient() {
        val f = fake(); val link = WifiHubLink(f)
        try {
            link.join(card) {}
            val wire = link.wire()!!; val cb = f.observers.single(); f.name = "Other"
            val capabilities = connectivity.getNetworkCapabilities(f.n) ?: error("missing emulator capabilities")
            org.junit.Assert.assertTrue("fixture must retain Wi-Fi so identity loss is the only cause",
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
            cb.onCapabilitiesChanged(f.n, capabilities)
            assertNull(link.wire()); assertClosed(wire)
            org.junit.Assert.assertTrue(f.removed.contains(cb))
        } finally { link.leave() }
    }

    @Test fun lostWifiCapabilitiesCannotKeepAReusedClient() {
        val f = fake(); val link = WifiHubLink(f)
        try {
            link.join(card) {}
            val wire = link.wire()!!
            // Public SDK capabilities are read-only. An empty instance represents loss of Wi-Fi.
            f.observers.single().onCapabilitiesChanged(f.n, android.net.NetworkCapabilities())
            assertNull(link.wire()); assertClosed(wire)
        } finally { link.leave() }
    }
}

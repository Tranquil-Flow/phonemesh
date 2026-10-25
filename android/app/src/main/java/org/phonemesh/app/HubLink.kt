package org.phonemesh.app

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import org.phonemesh.core.hub.ConnectedWifiIdentity
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubSession
import org.phonemesh.core.hub.HubWire
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The phone's way to one hub (accounts plan step 16). [wire] is null until the hub's Wi-Fi is joined and its address known. */
interface HubLink {
    fun wire(): HubWire?

    /** Joins [card]'s Wi-Fi, in the foreground; [changed] runs, on any thread, whenever [wire] may have changed. */
    fun join(card: HubCard, changed: () -> Unit)

    /** Leaves the Wi-Fi request and closes every socket of the hub client, so nothing stays blocked on it. */
    fun leave()
}

/**
 * Joins the hub's Wi-Fi with Android's network request (WifiNetworkSpecifier, Android 10+), the way test T2 verified: the
 * phone keeps mobile data for the internet and the hub's Wi-Fi is a local network only this app uses. The hub is the
 * Wi-Fi's gateway. All traffic uses that Network's socket factory, so it can never leave by mobile data, and TLS is
 * pinned to the hub QR's key by [HubClient]. Before Android 10 there is no join: [wire] stays null and the radio is used.
 *
 * Every callback (available, link properties, lost, unavailable) goes through a [HubSession] generation taken at join, so
 * a late callback of an old join or an old card cannot publish a client after [leave] or a replaced card.
 */
class WifiHubLink internal constructor(private val access: Access) : HubLink {
    constructor(app: Context) : this(AndroidAccess(app))

    internal interface Access {
        fun networks(): List<Network>
        fun connectedSsid(n: Network): String?
        fun properties(n: Network): LinkProperties?
        fun observe(callback: ConnectivityManager.NetworkCallback)
        fun request(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback)
        fun unregister(callback: ConnectivityManager.NetworkCallback)
    }

    private class AndroidAccess(app: Context) : Access {
        private val connectivity = app.getSystemService(ConnectivityManager::class.java)
        private val wifi = app.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        override fun networks() = connectivity.allNetworks.toList()
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun connectedSsid(n: Network): String? {
            val current = wifi.connectionInfo ?: return null
            val links = connectivity.allNetworks.mapNotNull { found ->
                val caps = connectivity.getNetworkCapabilities(found) ?: return null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val info = connectivity.getNetworkInfo(found) ?: return null
                if (!info.isConnected || info.type != ConnectivityManager.TYPE_WIFI) return@mapNotNull null
                val addresses = connectivity.getLinkProperties(found)?.linkAddresses
                    ?.map { it.address }?.filterIsInstance<Inet4Address>()
                    ?.map { ByteBuffer.wrap(it.address).order(ByteOrder.LITTLE_ENDIAN).int }?.toSet()
                ConnectedWifiIdentity.Link(found.networkHandle, true, false, info.extraInfo, addresses)
            }
            return ConnectedWifiIdentity.ssidFor(n.networkHandle, current.ssid, current.ipAddress, links)
        }
        override fun properties(n: Network) = connectivity.getLinkProperties(n)
        @SuppressLint("MissingPermission")
        override fun observe(callback: ConnectivityManager.NetworkCallback) {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        }
        @SuppressLint("MissingPermission")
        override fun request(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
            connectivity.requestNetwork(request, callback, JOIN_MS)
        }
        override fun unregister(callback: ConnectivityManager.NetworkCallback) = connectivity.unregisterNetworkCallback(callback)
    }
    private val session = HubSession()
    private var joined: HubCard? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null
    private var gatewayAddress: String? = null
    private var publishedGeneration = -1L

    override fun wire(): HubWire? = session.wire()

    @Synchronized override fun join(card: HubCard, changed: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (joined == card && callback != null) { changed(); return }
        leave()
        val gen = session.begin()
        val next = reuse(card, gen, changed) ?: request(card, gen, changed)
        if (next != null) { joined = card; callback = next; if (session.wire() != null) changed() }
    }

    @Synchronized override fun leave() {
        session.end()
        callback?.let { try { access.unregister(it) } catch (_: RuntimeException) { } }
        callback = null
        joined = null
        network = null
    }

    @Synchronized private fun arrive(gen: Long, n: Network, properties: LinkProperties?, card: HubCard, changed: () -> Unit) {
        val gateway = properties?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: return
        if (publishedGeneration == gen && network == n && gatewayAddress == gateway && session.wire() != null) return
        if (session.available(gen) { HubClient(gateway, card.port, card.pin, n.socketFactory) }) {
            network = n
            gatewayAddress = gateway
            publishedGeneration = gen
            changed()
        }
    }

    @Synchronized private fun gone(gen: Long, n: Network?, changed: () -> Unit) {
        if (n != null && n != network) return
        if (!session.lost(gen)) return
        network = null
        // Not found, refused or cancelled: forget the request so the next foreground start asks again.
        if (n == null) { callback = null; joined = null }
        changed()
    }

    @Synchronized private fun reusedLost(gen: Long, n: Network,
        owner: ConnectivityManager.NetworkCallback, changed: () -> Unit) {
        if (callback !== owner || publishedGeneration != gen || network != n) return
        leave() // Retire generation and observer; a later foreground/discovery join may reacquire.
        changed()
    }

    @Synchronized private fun reusedProperties(gen: Long, n: Network, properties: LinkProperties?,
        card: HubCard, owner: ConnectivityManager.NetworkCallback, changed: () -> Unit) {
        // A failed setup can fall back within the same session generation. Its already-queued observer
        // must not publish into that fallback or into another observation of the same Network.
        if (callback !== owner || publishedGeneration != gen || network != n) return
        val matches = try { access.connectedSsid(n) == card.wifi } catch (_: RuntimeException) { false }
        if (!matches || properties?.routes?.none { it.isDefaultRoute && it.gateway is Inet4Address } != false) {
            reusedLost(gen, n, owner, changed)
        } else arrive(gen, n, properties, card, changed)
    }

    private fun reuse(card: HubCard, gen: Long, changed: () -> Unit): ConnectivityManager.NetworkCallback? {
        val n = try {
            access.networks().filter { access.connectedSsid(it) == card.wifi }.singleOrNull()
        } catch (_: RuntimeException) { null } ?: return null
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(found: Network) { /* Initial publication is below; never adopt another Network. */ }
            override fun onLinkPropertiesChanged(found: Network, properties: LinkProperties) {
                if (found == n) reusedProperties(gen, n, properties, card, this, changed)
            }
            override fun onCapabilitiesChanged(found: Network, capabilities: NetworkCapabilities) {
                if (found != n) return
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    reusedLost(gen, n, this, changed)
                } else {
                    val properties = try { access.properties(n) } catch (_: RuntimeException) { null }
                    reusedProperties(gen, n, properties, card, this, changed)
                }
            }
            override fun onLost(found: Network) { if (found == n) reusedLost(gen, n, this, changed) }
        }
        try {
            access.observe(next)
            // Register first, then re-read to close the discovery/registration gap.
            if (access.connectedSsid(n) != card.wifi) {
                access.unregister(next)
                return null
            }
            val properties = access.properties(n)
            if (properties?.routes?.none { it.isDefaultRoute && it.gateway is Inet4Address } != false) {
                access.unregister(next)
                return null
            }
            arrive(gen, n, properties, card) {}
            return next
        } catch (_: RuntimeException) {
            try { access.unregister(next) } catch (_: RuntimeException) { }
            return null
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission") // CHANGE_NETWORK_STATE is declared; lint cannot see the manifest merge for requestNetwork
    private fun request(card: HubCard, gen: Long, changed: () -> Unit): ConnectivityManager.NetworkCallback? {
        val specifier = try {
            WifiNetworkSpecifier.Builder().setSsid(card.wifi).apply { if (card.password.isNotEmpty()) setWpa2Passphrase(card.password) }.build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = arrive(gen, n, access.properties(n), card, changed)
            override fun onLinkPropertiesChanged(n: Network, properties: LinkProperties) = arrive(gen, n, properties, card, changed)
            override fun onLost(n: Network) = gone(gen, n, changed)
            override fun onUnavailable() = gone(gen, null, changed)
        }
        return try {
            access.request(request, next)
            next
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val JOIN_MS = 120_000
        fun ssid(raw: String?): String? {
            if (raw == null || raw.isEmpty() || raw == "<unknown ssid>") return null
            val value = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') raw.substring(1, raw.length - 1) else raw
            return value.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        }
    }
}

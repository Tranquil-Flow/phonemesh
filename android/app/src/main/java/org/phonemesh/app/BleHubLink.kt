package org.phonemesh.app

import android.content.Context
import org.phonemesh.ble.GattHubCentral
import org.phonemesh.core.hub.GattStreams
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubClient
import org.phonemesh.core.hub.HubSession
import org.phonemesh.core.hub.HubWire

/**
 * The phone's way to the hub over Bluetooth (accounts decision 19): the same [HubLink] contract as [WifiHubLink] and the
 * same [HubWire] at the end of it, so /send and /mail behave exactly as over Wi-Fi. The link is one GATT connection
 * ([GattHubCentral]) carrying opaque bytes; [HubClient] runs standard TLS inside it, pinned to the hub QR's key, and no
 * application byte is sent before that handshake matched. Advertising the hub's service UUID proves nothing.
 *
 * Every callback of a central goes through a [HubSession] generation taken at join, like [WifiHubLink]: a late callback of
 * an old join or an old card cannot publish a client after [leave] or a replaced card, and [HubSession.end] closes the
 * published client so a request blocked on the old hub fails at once.
 *
 * [join] with the card already joined (or being joined) does nothing and does not call [changed]: AppCore re-joins from
 * its own change handler, so a callback here would loop. [changed] runs, on any thread, only when a wire appeared or a
 * wire that existed went away. After a failed attempt the next [join] tries again (the next screen, or a Wi-Fi change).
 * This is the Bluetooth hub link, not roaming: nothing here runs while the app is closed.
 */
class BleHubLink(private val app: Context) : HubLink {
    private val session = HubSession()
    private var joined: HubCard? = null
    private var central: GattHubCentral? = null

    override fun wire(): HubWire? = session.wire()

    @Synchronized override fun join(card: HubCard, changed: () -> Unit) {
        if (joined == card && central != null) return
        leave()
        val gen = session.begin()
        val next = GattHubCentral(app, card.pin, object : GattHubCentral.Listener {
            override fun onReady(streams: GattStreams) = arrived(gen, streams, card, changed)
            override fun onEnded(streams: GattStreams?, reason: String) = gone(gen, changed)
        })
        joined = card
        central = next
        next.start()
    }

    @Synchronized override fun leave() {
        session.end()
        central?.close()
        central = null
        joined = null
    }

    @Synchronized private fun arrived(gen: Long, streams: GattStreams, card: HubCard, changed: () -> Unit) {
        if (session.available(gen) { HubClient(card.port, card.pin, streams) }) changed()
    }

    @Synchronized private fun gone(gen: Long, changed: () -> Unit) {
        val had = session.wire() != null
        if (!session.lost(gen)) return
        central = null
        joined = null
        if (had) changed()
    }
}

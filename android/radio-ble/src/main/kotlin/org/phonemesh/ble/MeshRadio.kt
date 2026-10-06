package org.phonemesh.ble

/**
 * The radio link the app talks to. [BleMeshTransport] is the real one; tests use a fake.
 * Every [Listener] callback is delivered on the main thread.
 */
interface MeshRadio : AutoCloseable {
    interface Listener {
        fun onState(connected: Boolean, detail: String)

        /** [direct] is true when the message was addressed to this radio, false for a broadcast. */
        fun onText(sender: String, text: String, channel: Int, direct: Boolean)

        /** A radio seen while scanning; [rssi] is its signal strength in dBm. */
        fun onDevice(address: String, name: String, rssi: Int)

        /**
         * Per-packet delivery progress, correlated by the id returned from [send]. ACKNOWLEDGED means
         * the destination radio confirmed the packet, never that it was read or played.
         */
        fun onDelivery(packetId: Long, state: DeliveryState, detail: String) {}

        /** [peers] changed: a name arrived or the session ended. Coalesced, at most every 500 ms. */
        fun onPeersChanged() {}
    }

    fun scan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun peers(): List<RadioPeer>
    fun send(text: String, destinationId: String, channel: Int): Long
}

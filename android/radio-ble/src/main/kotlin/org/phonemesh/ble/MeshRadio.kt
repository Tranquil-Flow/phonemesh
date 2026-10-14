package org.phonemesh.ble

/**
 * The radio link the app talks to. [BleMeshTransport] is the real one; tests use a fake.
 * Every [Listener] callback is delivered on the main thread.
 */
/**
 * A received text's radio [packetId], the id of the message it answers ([replyTo], 0 for none) and whether it is a
 * reaction (Meshtastic's `Data.reply_id` and `Data.emoji`): what the official apps use for replies and tapbacks.
 */
data class TextMark(val packetId: Long = 0, val replyTo: Long = 0, val reaction: Boolean = false)

interface MeshRadio : AutoCloseable {
    interface Listener {
        fun onState(connected: Boolean, detail: String)

        /**
         * A connection the caller asked for ended before the radio became ready (no permission, Bluetooth off,
         * a bad address, a refused or timed-out connection, refused pairing). Progress reports stay on [onState]; a ready
         * connection that drops is [onState] with false.
         */
        fun onConnectFailed() {}

        /**
         * Android is pairing the phone with the radio asked for (true), which can take as long as the person needs
         * to type a code; false when that wait is over. The attempt then carries on by itself, or ends with
         * [onConnectFailed] when pairing was refused or took too long (90 s).
         */
        fun onPairing(waiting: Boolean) {}

        /** The scan is over: it was stopped, never started, or failed (no scanner, Android throttling). */
        fun onScanStopped() {}

        /** [direct] is true when the message was addressed to this radio, false for a broadcast. */
        fun onText(sender: String, text: String, channel: Int, direct: Boolean)

        /** The same text with its radio id and Meshtastic's reply and reaction marks; listeners that ignore them get [onText]. */
        fun onText(sender: String, text: String, channel: Int, direct: Boolean, mark: TextMark) = onText(sender, text, channel, direct)

        /** A radio seen while scanning; [rssi] is its signal strength in dBm. */
        fun onDevice(address: String, name: String, rssi: Int)

        /**
         * Per-packet delivery progress, correlated by the id returned from [send]. ACKNOWLEDGED means
         * the destination radio confirmed the packet, never that it was read or played.
         */
        fun onDelivery(packetId: Long, state: DeliveryState, detail: String) {}

        /** [peers] changed: a name arrived or the session ended. Coalesced, at most every 500 ms. */
        fun onPeersChanged() {}

        /**
         * A private-port packet addressed to this radio (see [MeshtasticProtocol.PRIVATE_DATA_PORTS]); never a broadcast.
         * [packetId] is its mesh packet id (0 when unknown).
         */
        fun onData(sender: String, port: Int, payload: ByteArray, channel: Int, packetId: Long) {}
    }

    fun scan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun peers(): List<RadioPeer>
    /** [replyTo] is the radio id of the message answered (0 for none); [reaction] marks [text] as a reaction to it. */
    fun send(text: String, destinationId: String, channel: Int, replyTo: Long = 0, reaction: Boolean = false): Long

    /**
     * One private-port packet to one other radio, with want_ack; returns its packet id for [Listener.onDelivery],
     * like [send]. Never resends.
     */
    fun sendData(port: Int, payload: ByteArray, destinationId: String, channel: Int): Long =
        throw UnsupportedOperationException("this radio cannot send private data")

    /** The radio's modem preset name from the handshake (\"LONG_FAST\", ...); null before it arrives or when custom. */
    fun modemPreset(): String? = null

    /** The name the connected radio announces for itself, the one other radios show; null while away or when it has none. */
    fun ownName(): String?

    /** The name the connected radio's owner gave its primary channel; null while away or when it is Meshtastic's default channel. */
    fun channelName(): String?

    /** The radio's region from the handshake: "UNSET", a region name, "UNRECOGNIZED", or null before it arrives. */
    fun region(): String?

    /**
     * Writes only the LoRa region: reads the radio's LoRa settings (10 s), else uses the handshake's, copies them,
     * changes the region, and sends set_config inside begin/commit_edit_settings. The radio usually restarts after,
     * so the link drops; the caller reconnects and checks [region]. [done] runs once, on the main thread.
     * Throws IllegalArgumentException, and writes nothing, for a [region] that is not in [RadioRegions.all].
     */
    fun setRegion(region: String, done: (RegionResult) -> Unit)

    companion object {
        /** The private Meshtastic port pictures travel on (core PictureWire.PORT must match). */
        const val PICTURE_PORT = 300
    }
}

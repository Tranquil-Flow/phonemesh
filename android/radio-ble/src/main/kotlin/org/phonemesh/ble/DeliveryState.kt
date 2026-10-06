package org.phonemesh.ble

/**
 * Delivery outcome of one outgoing text packet, correlated by its unsigned packet id.
 *
 * ACKNOWLEDGED means the destination radio itself confirmed receipt over the mesh. It never means the
 * message was read, displayed or played, and a local radio queue ACK is deliberately not ACKNOWLEDGED.
 */
enum class DeliveryState {
    /** The app handed the encoded packet to the transport; nothing is known about the radio yet. */
    SUBMITTING,

    /** The connected radio accepted the packet into its own transmit queue (not a recipient ACK). */
    QUEUED,

    /** The destination radio acknowledged this exact packet id. */
    ACKNOWLEDGED,

    /** An explicit failure was reported for this packet id, e.g. a routing error reason. */
    FAILED,

    /** No confirmation arrived before the bound expired, or the session ended first. Never a failure cause. */
    UNCONFIRMED,
}

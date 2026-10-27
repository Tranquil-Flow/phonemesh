package org.phonemesh.core.hub

import java.io.IOException

/**
 * One offer of a stored packet to a hub (accounts plan step 16). The hub gets a copy of the exact durable bytes and can
 * never change them; nothing here seals, edits or stores. The only good outcome is [Result.REACHED], and what that earns
 * is "reached the hub", never "delivered" (review F17). The port is the outbox row's own, so hellos and anything else
 * queued in the one outbox take the same path with no bypass.
 */
object HubDelivery {
    enum class Result {
        /** The hub holds the packet (it said stored, or already). */
        REACHED,
        /** The hub says it is not a Phonemesh packet. */
        REFUSED,
        /** The hub is full of this phone's packets for now. */
        BUSY,
        /** No answer, a bad answer or the wrong key: unknown, so the packet must go another way. */
        NOT_REACHED,
    }

    /** [held]: the hub must keep it off the radio ([HubWire.sendHeld]); a way that cannot is [Result.NOT_REACHED]. */
    fun offer(wire: HubWire, port: Int, packet: ByteArray, held: Boolean = false): Result = try {
        when (if (held) wire.sendHeld(port, packet.copyOf()) else wire.send(port, packet.copyOf())) {
            HubClient.Sent.STORED, HubClient.Sent.ALREADY -> Result.REACHED
            HubClient.Sent.REFUSED -> Result.REFUSED
            HubClient.Sent.BUSY -> Result.BUSY
        }
    } catch (_: IOException) {
        Result.NOT_REACHED
    }
}

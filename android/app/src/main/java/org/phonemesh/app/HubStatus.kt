package org.phonemesh.app

import org.phonemesh.core.hub.HubDelivery
import org.phonemesh.core.hub.MailPaths

/**
 * How far one way to the hub has got, in the order of what has been proven (comparable: later is more).
 * [LINKED] is only a connection: Wi-Fi joined or Bluetooth connected. It says nothing about the hub's key.
 * [ANSWERED] is earned only by a request that completed over TLS pinned to the QR's key (a stored send or a poll).
 */
enum class HubWay { NONE, SEARCHING, LINKED, ANSWERED }

/** The last way that failed an offer or a poll, with the hub's own word for it. */
class HubFailure(val path: MailPaths.Path, val result: HubDelivery.Result)

/** What the hub screen shows. [sending] is the hub way a message would take now; null means the radio or nothing. */
data class HubStatus(
    val saved: Boolean,
    val wifi: HubWay,
    val bluetooth: HubWay,
    val sending: MailPaths.Path?,
    val failure: HubFailure?,
)

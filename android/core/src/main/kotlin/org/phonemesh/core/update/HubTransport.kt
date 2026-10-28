package org.phonemesh.core.update

import java.io.Closeable
import java.io.InputStream

/**
 * The one way the update downloader reaches the network, and it can only be the enrolled local hub (decision 45, UP3: hub
 * Wi-Fi only; hub updates spec 5.5). There is no value here for the radio, for mobile data or for another Wi-Fi, and nothing in
 * this package can send a message or open a socket: the app supplies an implementation that talks to the pinned hub on the
 * local hub network it joined (TLS pin, socket bound to that network), and nothing else.
 */
enum class UpdateRoute { PINNED_HUB_WIFI }

interface HubTransport {
    val route: UpdateRoute

    /**
     * The most bytes one answer can carry (the app's client holds an answer in memory). A `200` that announces more than this
     * answered a range request with the whole file: the downloader names that as the hub's fault, not as a dropped connection.
     */
    val maxAnswerBytes: Long get() = Long.MAX_VALUE

    /**
     * `GET` [path] (always under `/updates/v1/`) with `Range: bytes=<first>-<last>` when [range] is given. The transport must
     * not follow redirects or decode content; it returns what the hub said, with header names in any case.
     */
    fun get(path: String, range: LongRange?): HubResponse
}

class HubResponse(val status: Int, val headers: Map<String, List<String>>, val body: InputStream) : Closeable {
    /** All values of header [name], any case. */
    fun all(name: String): List<String> = headers.filterKeys { it.equals(name, ignoreCase = true) }.values.flatten()

    /** The one value of header [name]; null when absent, and [AMBIGUOUS] when it is given more than once. */
    fun one(name: String): String? = all(name).let { if (it.size > 1) AMBIGUOUS else it.firstOrNull() }

    override fun close() = body.close()

    companion object { const val AMBIGUOUS = "\u0000ambiguous" }
}

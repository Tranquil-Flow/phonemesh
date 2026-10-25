package org.phonemesh.core.hub

/** Passive facts only: this policy never joins, scans, resolves a host or opens a socket. */
object ConnectedWifiIdentity {
    data class Link(
        val id: Long,
        val connectedWifi: Boolean,
        val vpn: Boolean,
        val scopedSsid: String?,
        /** IPv4 addresses in WifiInfo.getIpAddress's little-endian representation; null means unavailable. */
        val ipv4Addresses: Set<Int>?,
    )

    /** Binds a visible current Wi-Fi name to a particular independently observed Network. */
    fun ssidFor(id: Long, visibleSsid: String?, currentIpv4: Int, links: List<Link>): String? {
        val visible = ssid(visibleSsid) ?: return null
        val firstOctet = currentIpv4 and 0xff
        if (firstOctet == 0 || firstOctet == 127 || firstOctet >= 224) return null
        val eligible = links.filter { it.connectedWifi && !it.vpn }
        if (eligible.any { it.ipv4Addresses == null }) return null
        val link = eligible.singleOrNull { it.id == id } ?: return null
        // AOSP Android10 leaves extraInfo null. A name alone is not a Network binding:
        // require this snapshot's address on exactly one eligible interface, with no second IPv4 alias.
        if (link.ipv4Addresses != setOf(currentIpv4)) return null
        if (eligible.count { currentIpv4 in it.ipv4Addresses.orEmpty() } != 1) return null
        // Retain contradictory scoped identity as a refusal, not an invitation to guess.
        val scoped = ssid(link.scopedSsid)
        if (scoped != null && scoped != visible) return null
        return visible
    }

    private fun ssid(raw: String?): String? {
        if (raw == null || raw.isEmpty() || raw == "<unknown ssid>") return null
        val value = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') raw.substring(1, raw.length - 1) else raw
        return value.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }
}

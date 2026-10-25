package org.phonemesh.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AOSP Android10 supplies null extraInfo: test the production predicate, not an approved-name fake. */
class ConnectedWifiIdentityTest {
    private fun ip(a: Int, b: Int, c: Int, d: Int) = a or (b shl 8) or (c shl 16) or (d shl 24)
    private val address = ip(192, 168, 43, 27)
    private val link = ConnectedWifiIdentity.Link(101L, true, false, null, setOf(address))
    private fun name(links: List<ConnectedWifiIdentity.Link> = listOf(link),
        visible: String? = "\"Fixture\"", ipv4: Int = address, id: Long = 101L) =
        ConnectedWifiIdentity.ssidFor(id, visible, ipv4, links)

    @Test fun androidTenNullExtraInfoStillBindsTheUniquelyMatchingLocalAddress() {
        assertNull("counterexample really has AOSP's absent scoped name", link.scopedSsid)
        assertEquals("Fixture", name())
    }
    @Test fun aLegacyScopedNameCanAgreeWithTheAddressEvidence() {
        assertEquals("Fixture", name(listOf(link.copy(scopedSsid = "\"Fixture\""))))
    }
    @Test fun aLegacyScopedNameCannotReplaceUniqueAddressEvidence() {
        val known = link.copy(scopedSsid = "Fixture")
        assertNull(name(listOf(known), ipv4 = 0))
        assertNull(name(listOf(known.copy(ipv4Addresses = emptySet()))))
        assertNull(name(listOf(known, known.copy(id = 102L))))
    }
    @Test fun anIneligibleDuplicateAddressDoesNotHideTheActualWifiNetwork() {
        assertEquals("Fixture", name(listOf(link, link.copy(id = 102L, vpn = true))))
        assertEquals("Fixture", name(listOf(link, link.copy(id = 102L, connectedWifi = false))))
    }
    @Test fun conflictingScopedNameCannotBeOverriddenByAnIpMatch() {
        assertNull(name(listOf(link.copy(scopedSsid = "Other"))))
    }
    @Test fun duplicateLocalAddressOnTwoWifiNetworksIsAmbiguous() {
        assertNull(name(listOf(link, link.copy(id = 102L))))
    }
    @Test fun aVisibleGlobalNameDoesNotIdentifyAnUnrelatedNetwork() {
        assertNull(name(listOf(link.copy(ipv4Addresses = setOf(ip(192, 168, 44, 27))))))
        assertNull(name(id = 102L))
    }
    @Test fun aDifferentWifiNetworkWithADifferentAddressDoesNotCreateAmbiguity() {
        assertEquals("Fixture", name(listOf(link, link.copy(id = 102L,
            ipv4Addresses = setOf(ip(192, 168, 44, 27))))))
        assertNull(name(listOf(link, link.copy(id = 102L,
            ipv4Addresses = setOf(ip(192, 168, 44, 27)))), id = 102L))
    }
    @Test fun hiddenOrAbsentGlobalIdentityNeverFallsBackToAddressesAlone() {
        for (ssid in listOf(null, "", "\"\"", "<unknown ssid>", "\"<unknown ssid>\"")) {
            assertNull(name(visible = ssid))
        }
    }
    @Test fun vpnAndDisconnectedOrNonWifiNetworksCannotSupplyIdentity() {
        assertNull(name(listOf(link.copy(vpn = true))))
        assertNull(name(listOf(link.copy(connectedWifi = false))))
    }
    @Test fun missingOrMultipleInterfaceIpv4AddressesFailClosed() {
        assertNull(name(listOf(link.copy(ipv4Addresses = emptySet()))))
        assertNull(name(listOf(link.copy(ipv4Addresses = setOf(address, ip(192, 168, 43, 28))))))
    }
    @Test fun unavailableAddressOfAnotherWifiNetworkCannotProveUniqueness() {
        assertNull(name(listOf(link, link.copy(id = 102L, ipv4Addresses = null))))
    }
    @Test fun duplicateNetworkRecordsAreNotIndependentEvidence() {
        assertNull(name(listOf(link, link)))
    }
    @Test fun unspecifiedLoopbackAndMulticastAddressesCannotBind() {
        for (bad in listOf(0, ip(0, 1, 2, 3), ip(127, 0, 0, 1), ip(224, 0, 0, 1), ip(255, 255, 255, 255))) {
            assertNull(name(listOf(link.copy(ipv4Addresses = setOf(bad))), ipv4 = bad))
        }
    }
    @Test fun littleEndianHighBitAddressesAndLiteralNamesArePreserved() {
        val high = ip(10, 20, 30, 200)
        assertEquals(" Fixture ", name(listOf(link.copy(ipv4Addresses = setOf(high))),
            visible = "\" Fixture \"", ipv4 = high))
        assertEquals("Fixture", name(listOf(link.copy(scopedSsid = null))))
    }
}

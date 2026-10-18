package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CountryRegionsTest {
    private val bundled = CountryRegions.bundled()

    /** Region names in the bundled Meshtastic proto (radio-ble), so a typo in the file fails here. */
    private val protoRegions: Set<String> by lazy {
        val proto = File("../mesh-protocol/src/main/proto/meshtastic/config.proto").readText()
        val body = proto.substringAfter("enum RegionCode {").substringBefore("\n    }")
        Regex("""^\s+([A-Z][A-Z0-9_]*) = \d+""", RegexOption.MULTILINE).findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test fun protoListIsRead() = assertTrue(protoRegions.containsAll(listOf("UNSET", "US", "EU_868")))

    /** The reader must stop at the end of RegionCode: 38 values, and nothing from the enums after it. */
    @Test fun protoListIsOnlyTheRegionEnum() {
        assertEquals(38, protoRegions.size)
        assertTrue("ITU2_125CM" in protoRegions)
        assertFalse(protoRegions.toString(), "LONG_FAST" in protoRegions)
    }

    @Test fun everyCountryRegionExistsAndIsNotUnset() = bundled.countries.values.forEach {
        assertTrue("${it.code}: ${it.region}", it.region in protoRegions && it.region != "UNSET")
    }

    @Test fun everyRegionLineExists() = bundled.regions.keys.forEach { assertTrue(it, it in protoRegions) }

    @Test fun everySourceIsReal() = bundled.countries.values.forEach {
        assertTrue(it.code, it.source.isNotBlank() && !it.source.contains("SOURCE"))
    }

    /** A country is supported only with a checked source: exactly these two, until another source is verified. */
    @Test fun onlyCheckedCountriesAreSupported() = assertEquals(setOf("DE", "TG"), bundled.countries.keys)

    @Test fun togoAndGermanyUseEu868() {
        assertEquals("EU_868", bundled.country("TG")!!.region)
        assertEquals("EU_868", bundled.country("de")!!.region)
        assertTrue(bundled.country("TG")!!.source.contains("226/ARCEP/DG/22"))
        assertTrue(bundled.country("DE")!!.source.contains("Bundesnetzagentur"))
    }

    @Test fun germanySourceIsTheVerifiedRow() =
        assertTrue(bundled.country("DE")!!.source.startsWith("Bundesnetzagentur Vfg. 91/2025 (November 2025), Tabelle 2, Band Nr. 54"))

    @Test fun regionLinesPointAtAFlag() {
        assertEquals("US", bundled.regions["US"]!!.flagOf)
        assertEquals("Europe", bundled.regions["EU_868"]!!.name)
        assertNotNull(bundled.regions["EU_868"]!!.flag)
        bundled.regions.values.mapNotNull { it.flagOf }.forEach { assertNotNull(it, bundled.flagOf(it)) }
    }

    @Test fun pictureOnlyFlagsAreNotSupported() {
        assertNotNull(bundled.flagOf("US"))
        assertNull(bundled.country("US"))
    }

    @Test fun badLinesNameTheirLineNumber() {
        val cases = mapOf(
            "[countries]\nTG | EU_868 | h:000000\n" to 2,
            "[countries]\nTG | EU_868 | h:000000 | src\nTG | EU_868 | h:000000 | src\n" to 3,
            "[countries]\nTOGO | EU_868 | h:000000 | src\n" to 2,
            "[countries]\nTG | EU_868 | h:000000 |  \n" to 2,
            "TG | EU_868 | h:000000 | src\n" to 1,
            "[regions]\nUS | nonsense:US |\n" to 2,
            "[planets]\n" to 1,
        )
        cases.forEach { (text, line) ->
            val error = assertThrows(text, IllegalArgumentException::class.java) { CountryRegions.load(text) }
            assertTrue("${error.message} should name line $line", error.message!!.startsWith("countries.txt line $line:"))
        }
    }

    @Test fun commentsAndBlankLinesAreSkipped() {
        val loaded = CountryRegions.load("# hi\n\n[countries]\n# code | region | flag | source\nTG | EU_868 | h:000000 | src\n")
        assertEquals(setOf("TG"), loaded.countries.keys)
    }
}

package org.phonemesh.core.hub

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NEW-API. Writes core/build/gatt-vectors/service-uuids.txt (pin, service UUID per line) from the Kotlin implementation,
 * so the Swift bridge's test can check that it derives the same UUIDs (SHA-256 cannot be done by hand here, and a
 * hand-copied constant would be a made-up vector). Run this before `swift test` and set PM_GATT_VECTORS to that file.
 * NOT RUN when staged.
 */
class GattVectorsExportTest {
    @Test fun writeServiceUuidVectors() {
        val pins = listOf("A".repeat(43), "B".repeat(43), "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO_-")
        val dir = File("build/gatt-vectors").apply { mkdirs() }
        val file = File(dir, "service-uuids.txt")
        file.writeText(pins.joinToString("") { "$it ${HubGatt.serviceUuid(it)}\n" })
        assertTrue(file.length() > 0)
    }
}

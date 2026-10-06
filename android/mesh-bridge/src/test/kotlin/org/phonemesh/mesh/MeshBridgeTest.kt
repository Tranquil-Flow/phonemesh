package org.phonemesh.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.CharacterCodingException

class MeshBridgeTest {
    @Test
    fun strictUtf8RejectsMalformedBytes() {
        assertThrows(CharacterCodingException::class.java) {
            MeshBridge.strictUtf8(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun strictUtf8AcceptsPlainText() {
        assertEquals("[pm1:fr] Bonjour.", MeshBridge.strictUtf8("[pm1:fr] Bonjour.".encodeToByteArray()))
    }

    @Test
    fun productTextBudgetIsConservative200Bytes() {
        assertEquals(200, MeshBridge.MAX_TEXT_BYTES)
    }
}

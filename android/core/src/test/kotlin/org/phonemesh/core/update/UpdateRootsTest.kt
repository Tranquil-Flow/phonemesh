package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The owner's `public-keys.txt` (compressed points) becomes the 65-byte keys the verifier wants; hostile text is refused. */
class UpdateRootsTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val good = "catalog key_id=1 public_key=${hex(TestKeys.catalog.compressed)}\n" +
        "recovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\n"

    private fun refused(text: String, why: String) {
        try { UpdateRoots.parse(text); fail("accepted: $why") } catch (_: IllegalArgumentException) { }
    }

    @Test fun theOwnersFileFormatIsReadAndDecompressed() {
        val r = UpdateRoots.parse(good)
        assertEquals(1, r.catalogKeyId); assertEquals(100, r.recoveryKeyId)
        assertArrayEquals(TestKeys.catalog.public, r.catalogKey)
        assertArrayEquals(TestKeys.recovery.public, r.recoveryKey)
        assertFalse(r.isTest)
        assertTrue(UpdateRoots.parse("# status: TEST keys, not the owner's\n$good").isTest)
        assertNotNull(UpdateMetadata.publicKey(r.catalogKey))
    }

    @Test fun badFilesAreRefused() {
        refused("", "empty")
        refused(good.lines().first(), "no recovery key")
        refused(good.lines()[1], "no catalog key")
        refused(good + good.lines().first(), "two catalog keys")
        refused(good.replace("key_id=100", "key_id=99"), "recovery under another id")
        refused(good.replace("key_id=1 ", "key_id=100 "), "catalog key at the recovery id")
        refused(good.replace("key_id=1 ", "key_id=0 "), "catalog key id 0")
        refused(good.replace("key_id=1 ", "key_id=65536 "), "id over 16 bits")
        refused(good.replace(hex(TestKeys.catalog.compressed), hex(TestKeys.catalog.compressed).drop(2)), "short key")
        refused(good.replace(hex(TestKeys.catalog.compressed), hex(TestKeys.catalog.public)), "uncompressed key")
        refused(good.replace(hex(TestKeys.catalog.compressed), "05" + hex(TestKeys.catalog.compressed).drop(2)), "bad prefix")
        refused(good.replace(hex(TestKeys.catalog.compressed), "02" + "ff".repeat(32)), "x not on the curve or above p")
        refused(good.replace(hex(TestKeys.catalog.compressed), hex(TestKeys.recovery.compressed)), "one key twice")
        refused(good + "extra key_id=5 public_key=${hex(TestKeys.catalog2.compressed)}\n", "unknown line")
        refused(good.replace("public_key", "pubkey"), "missing field")
        refused(good.replace(hex(TestKeys.catalog.compressed), "zz".repeat(33)), "not hex")
        refused("x".repeat(100_000), "huge")
    }

    @Test fun theCompiledRootsAndBaselineBelongTogether() {
        val roots = UpdateRoots.compiled
        val env = UpdateMetadata.verify(UpdateRoots.baselineCatalog(), mapOf(roots.catalogKeyId to roots.catalogKey), roots.recoveryKey)
        assertNotNull("the packaged baseline catalog is signed by the packaged catalog key", env)
        assertEquals(UpdateMetadata.CATALOG, env!!.kind)
        val catalog = UpdateMetadata.catalog(env.payload)!!
        assertEquals(1L, catalog.epoch)
    }

    /** While the packaged keys are the TEST keys, the test helper's keys (derived in Kotlin) are the ones in the file (derived in Python). */
    @Test fun theTestKeysAreTheOnesDerivedFromTheirPublicLabel() {
        val roots = UpdateRoots.compiled
        if (!roots.isTest) return
        assertArrayEquals(TestKeys.catalog.public, roots.catalogKey)
        assertArrayEquals(TestKeys.recovery.public, roots.recoveryKey)
    }

    @Test fun theBaselineListsExactlyTheCompiledPacks() {
        val env = UpdateMetadata.verify(UpdateRoots.baselineCatalog(), mapOf(UpdateRoots.compiled.catalogKeyId to UpdateRoots.compiled.catalogKey), null)!!
        val catalog = UpdateMetadata.catalog(env.payload)!!
        val compiled = org.phonemesh.core.GeneratedPackCatalog.entries
        assertEquals(compiled.size, catalog.entries.size)
        for (c in compiled) {
            val e = catalog.entries.single { it.id == c.id }
            assertEquals(c.version.toLong(), e.version)
            assertEquals(if (c.kind == org.phonemesh.core.PackKind.ENGINE) UpdateMetadata.ENGINE else UpdateMetadata.VOICE, e.kind)
            assertEquals(c.zipBytes, e.bytes)
            assertEquals(c.manifestSha256, hex(e.sums))
        }
    }
}

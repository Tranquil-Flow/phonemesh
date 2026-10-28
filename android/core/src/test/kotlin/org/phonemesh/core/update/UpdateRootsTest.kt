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
        assertTrue("the known label-derived keys are TEST keys even without a status line", r.isTest)
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

    /** What the real swap will rely on: the roots in the build decide, and nothing signed by another key is believed. */
    @Test fun aCatalogOrPolicySignedByAKeyOutsideTheCompiledRootsIsRefused() {
        val trust = (UpdateTrust.open(MemoryTrustStore(), UpdateRoots.compiled, 3, allowTestRoots = true) as UpdateTrust.Opened.Ready).trust
        val before = trust.state.encode()
        val entries = listOf(TestCatalogs.pack("voice-fr-siwis", 5))
        for ((name, key, id) in listOf(Triple("another catalog key", TestKeys.catalog2, 1), Triple("a third key", TestKeys.catalog3, 1),
            Triple("another key under an unlisted id", TestKeys.catalog2, 2), Triple("the recovery key signing a catalog", TestKeys.recovery, 1))) {
            val r = trust.acceptCatalog(TestCatalogs.catalog(1, 99, entries, key = key, keyId = id))
            assertTrue("$name: $r", r is UpdateTrust.CatalogResult.Refused && r.reason == UpdateTrust.Reason.NOT_SIGNED)
        }
        // A recovery policy needs the recovery key; a catalog key cannot start a new epoch.
        val policy = TestCatalogs.policy(2, TestKeys.catalog3, 3, by = TestKeys.catalog2)
        val p = trust.acceptRecovery(policy)
        assertTrue("$p", p is UpdateTrust.RecoveryResult.Refused && p.reason == UpdateTrust.Reason.NOT_SIGNED)
        assertArrayEquals("nothing changed", before, trust.state.encode())
    }

    @Test fun theBuildKeysAreTheOneFileAndOpenOnlyWhenTheyAreRealOrTheBuildAllowsTestKeys() {
        // The swap is one owner step (sign-baseline writes update/public-keys.txt and baseline-catalog.pmup together).
        val roots = UpdateRoots.compiled
        assertTrue(UpdateTrust.open(MemoryTrustStore(), roots, 3, allowTestRoots = true) is UpdateTrust.Opened.Ready)
        assertEquals(roots.isTest, UpdateTrust.open(MemoryTrustStore(), roots, 3) is UpdateTrust.Opened.NotSetUp)
    }
}

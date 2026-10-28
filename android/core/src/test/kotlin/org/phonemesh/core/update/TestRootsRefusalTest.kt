package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.security.interfaces.ECPublicKey

/**
 * U7-1: the TEST keys' private parts follow from public labels, so a build that trusts them lets anyone sign. A build that
 * does not allow TEST roots (release) must run no updates under them: nothing is read, written or accepted.
 */
class TestRootsRefusalTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val compiled get() = UpdateRoots.compiled

    /** Holds before and after the owner's keys replace the file: the default open is refused for TEST keys and works for real ones. */
    @Test fun theDefaultOpenFollowsTheKindOfKeysPackagedInThisBuild() {
        val r = UpdateTrust.open(MemoryTrustStore(), compiled, 3)
        if (compiled.isTest) assertTrue("$r", r is UpdateTrust.Opened.NotSetUp) else assertTrue("$r", r is UpdateTrust.Opened.Ready)
    }

    @Test fun aForgedCatalogFromTheLabelKeyVerifiesButTheOpenRefusesToTrustAnything() {
        if (!compiled.isTest) return // the owner's keys are in: there is nothing to forge with
        val forged = TestCatalogs.catalog(1, 2, listOf(TestCatalogs.pack("voice-fr-siwis", 9, seed = "evil")))
        assertNotNull("the forgery is correctly signed by a key anyone can compute",
            UpdateMetadata.verify(forged, mapOf(compiled.catalogKeyId to compiled.catalogKey), null))
        val store = MemoryTrustStore()
        val r = UpdateTrust.open(store, compiled, 3)
        assertTrue("default: $r", r is UpdateTrust.Opened.NotSetUp)
        assertTrue("nothing written", store.saves.isEmpty())
        assertNull(store.slots[0]); assertNull(store.slots[1])
        // The same for a forged recovery policy and for a store that already holds state: still off.
        val held = MemoryTrustStore()
        UpdateTrust.open(held, compiled, 3, allowTestRoots = true)
        val before = held.slots.map { it?.copyOf() }
        assertTrue(UpdateTrust.open(held, compiled, 3) is UpdateTrust.Opened.NotSetUp)
        assertEquals(before.map { it?.toList() }, held.slots.map { it?.toList() })
    }

    @Test fun debugAndTestBuildsMayOptIn() {
        assertTrue(UpdateTrust.open(MemoryTrustStore(), compiled, 3, allowTestRoots = true) is UpdateTrust.Opened.Ready)
    }

    @Test fun takingTheStatusLineOutDoesNotMakeTheLabelKeysReal() {
        val text = "catalog key_id=1 public_key=${hex(TestKeys.catalog.compressed)}\nrecovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\n"
        assertTrue(UpdateRoots.parse(text).isTest)
        // A single known key is enough: the pair's other half may be fresh.
        val fresh = P256.generate()
        val half = "catalog key_id=1 public_key=${hex(P256.compress(fresh.public as ECPublicKey))}\nrecovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\n"
        assertTrue(UpdateRoots.parse(half).isTest)
        assertTrue(UpdateTrust.open(MemoryTrustStore(), UpdateRoots.parse(text), 3) is UpdateTrust.Opened.NotSetUp)
    }

    @Test fun freshKeysAreNotTestKeys() {
        val a = P256.generate(); val b = P256.generate()
        val roots = UpdateRoots.parse("catalog key_id=1 public_key=${hex(P256.compress(a.public as ECPublicKey))}\nrecovery key_id=100 public_key=${hex(P256.compress(b.public as ECPublicKey))}\n")
        assertFalse(roots.isTest)
    }

    @Test fun theListOfKnownTestKeysIsExactlyTheLabelDerivedOnes() {
        val derived = listOf(TestKeys.catalog, TestKeys.catalog2, TestKeys.catalog3, TestKeys.recovery).map { hex(it.compressed) }.toSet()
        assertEquals(derived, UpdateRoots.KNOWN_TEST_KEYS)
    }

    @Test fun burntKeysAreReadAndBadOnesRefused() {
        val base = "catalog key_id=1 public_key=${hex(TestKeys.catalog.compressed)}\nrecovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\n"
        val r = UpdateRoots.parse(base + "burnt public_key=${hex(TestKeys.catalog2.compressed)}\n")
        assertEquals(1, r.burnt.size)
        assertArrayEquals(TestKeys.catalog2.public, r.burnt[0])
        try { UpdateRoots.parse(base + "burnt public_key=02" + "ff".repeat(32)); throw AssertionError("accepted a burnt line that is not a point") } catch (_: IllegalArgumentException) { }
        try { UpdateRoots.parse(base + "burnt public_key=${hex(TestKeys.catalog2.compressed)}\n".repeat(17)); throw AssertionError("accepted 17 burnt keys") } catch (_: IllegalArgumentException) { }
    }

    @Test fun rootsHaveANameThatChangesWithAnyKeyOrId() {
        val a = TestCatalogs.roots
        val b = UpdateRoots.parse("catalog key_id=1 public_key=${hex(TestKeys.catalog2.compressed)}\nrecovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\n")
        val c = UpdateRoots.parse("catalog key_id=2 public_key=${hex(TestKeys.catalog.compressed)}\nrecovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\n")
        val burntOnly = UpdateRoots.parse("catalog key_id=1 public_key=${hex(TestKeys.catalog.compressed)}\nrecovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\nburnt public_key=${hex(TestKeys.catalog2.compressed)}\n")
        assertEquals(32, a.id.size)
        assertFalse(a.id.contentEquals(b.id)); assertFalse(a.id.contentEquals(c.id))
        assertArrayEquals("growing the burnt list is not a change of roots", a.id, burntOnly.id)
    }
}

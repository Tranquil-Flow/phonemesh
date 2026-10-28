package org.phonemesh.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyPair

/**
 * U7-2 (a hub whose records were made under other keys starts fresh), U7-11 (older releases free their room), U7-13 (burnt keys).
 * Keys are made for each test.
 */
class UpdateImportRootsTest {
    @get:Rule val folder = TemporaryFolder()
    private val c: KeyPair = P256.generate()
    private val c2: KeyPair = P256.generate()
    private val r: KeyPair = P256.generate()
    private val r2: KeyPair = P256.generate()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun entry(data: ByteArray, version: Long) = UpdateMetadata.Entry(UpdateMetadata.VOICE, "voice-fr-siwis", version.toString(), "", version, 1, 0, 26, 2,
        data.size.toLong(), Bytes.sha256(data), 2000, 3, Bytes.sha256("s$version".toByteArray()), ByteArray(32))

    private fun stick(seq: Long, key: KeyPair = c, epoch: Long = 1, recovery: ByteArray? = null, vararg objs: Pair<ByteArray, Long>): File {
        val s = folder.newFolder(); File(s, "objects").mkdirs()
        val entries = objs.map { entry(it.first, it.second) }
        val payload = CatalogSigner.encode(epoch, seq, 1793145600, 1808697600, 1, entries, emptyList())
        File(s, "catalog.pmup").writeBytes(UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, payload, key.private))
        recovery?.let { File(s, "recovery.pmup").writeBytes(it) }
        objs.forEach { File(s, "objects/${hex(Bytes.sha256(it.first))}").writeBytes(it.first) }
        return s
    }

    private fun policy(epoch: Long, newKey: KeyPair, keyId: Int = 1, by: KeyPair = r) = UpdateMetadata.sign(UpdateMetadata.RECOVERY, 100,
        ByteBuffer.allocate(87).putLong(epoch).putShort(keyId.toShort()).put(TestKeys.pub(newKey)).putLong(1).putInt(1).array(), by.private)

    private fun importer(dir: File, catalog: KeyPair = c, recovery: KeyPair = r, quota: Long = UpdateImport.OBJECT_QUOTA, burnt: List<ByteArray> = emptyList()) =
        UpdateImport(dir, TestKeys.pub(recovery), TestKeys.pub(catalog), objectQuota = quota, mailReserve = 0, burnt = burnt)

    private val a = ByteArray(600) { 1 }; private val b = ByteArray(600) { 2 }; private val d = ByteArray(600) { 3 }
    private fun objects(dir: File) = File(dir, "updates/objects").list()!!.sorted()

    // ---- U7-11

    @Test fun olderReleasesFreeTheirRoomSoALongSeriesOfImportsKeepsWorking() {
        val dir = folder.newFolder("hub")
        val imp = importer(dir, quota = 1_300)
        assertTrue(imp.import(stick(2, objs = arrayOf(a to 1L))) is UpdateImport.Result.Imported)
        assertTrue(imp.import(stick(3, objs = arrayOf(b to 2L))) is UpdateImport.Result.Imported)
        val third = imp.import(stick(4, objs = arrayOf(d to 3L)))
        assertTrue("$third", third is UpdateImport.Result.Imported)
        assertEquals("the current and the previous release remain", listOf(hex(Bytes.sha256(b)), hex(Bytes.sha256(d))).sorted(), objects(dir))
        assertEquals(1, (third as UpdateImport.Result.Imported).pruned)
        assertEquals("only the stored catalogs of those two", 2, File(dir, "updates/catalogs").list()!!.size)
    }

    @Test fun thePreviousReleaseStaysForPhonesStillCopyingItAndGoesWithTheNextOne() {
        val dir = folder.newFolder("hub")
        val imp = importer(dir)
        imp.import(stick(2, objs = arrayOf(a to 1L)))
        imp.import(stick(3, objs = arrayOf(b to 2L)))
        assertTrue("a is still here after the second release", hex(Bytes.sha256(a)) in objects(dir))
        imp.import(stick(4, objs = arrayOf(d to 3L)))
        assertFalse(hex(Bytes.sha256(a)) in objects(dir))
    }

    @Test fun roomIsStillRefusedWhenWhatMustStayDoesNotFit() {
        val dir = folder.newFolder("hub")
        val imp = importer(dir, quota = 1_100)
        assertTrue(imp.import(stick(2, objs = arrayOf(a to 1L))) is UpdateImport.Result.Imported)
        // The new release (600) and the one before it (600) are 1200 > 1100: refused, and nothing was removed.
        val second = imp.import(stick(3, objs = arrayOf(b to 2L)))
        assertTrue("$second", second is UpdateImport.Result.Refused)
        assertEquals(listOf(hex(Bytes.sha256(a))), objects(dir))
    }

    // ---- U7-2

    @Test fun recordsMadeUnderOtherKeysAreSetAsideByTheFirstImportUnderNewKeys() {
        val dir = folder.newFolder("hub")
        val old = importer(dir)
        old.import(stick(7, objs = arrayOf(a to 1L)))
        old.import(stick(8, objs = arrayOf(b to 2L), key = c, epoch = 1))
        File(dir, "updates/policies").mkdirs(); File(dir, "updates/policies/stale.pmup").writeBytes(ByteArray(30))
        val fresh = importer(dir, catalog = c2, recovery = r2)
        assertTrue(fresh.recordsAreFromOtherKeys)
        assertEquals(-1L, fresh.state.sequence)
        // A catalog from the old key is refused; one from the new key, with a lower number than the old records held, is taken.
        assertTrue(fresh.import(stick(8, objs = arrayOf(b to 2L), key = c)) is UpdateImport.Result.Refused)
        val r1 = fresh.import(stick(1, key = c2, objs = arrayOf(d to 3L)))
        assertTrue("$r1", r1 is UpdateImport.Result.Imported && r1.freshStart)
        assertFalse(fresh.recordsAreFromOtherKeys)
        assertFalse("a stale policy is not served under the new keys", File(dir, "updates/policies").exists())
        assertEquals(1, File(dir, "updates/catalogs").list()!!.size)
        assertArrayEquals(UpdateRoots.idOf(1, TestKeys.pub(c2), 100, TestKeys.pub(r2)), UpdateImport.State.decode(File(dir, "updates/trust-state.bin").readBytes())!!.rootsId)
        // And the next start under the same keys reads it as its own.
        assertFalse(importer(dir, catalog = c2, recovery = r2).recordsAreFromOtherKeys)
        assertEquals(1L, importer(dir, catalog = c2, recovery = r2).state.sequence)
    }

    @Test fun aRecordWithoutARootsNameBelongsByTheKeyItHolds() {
        val dir = folder.newFolder("hub")
        val imp = importer(dir)
        imp.import(stick(5, objs = arrayOf(a to 1L)))
        val file = File(dir, "updates/trust-state.bin")
        val legacy = UpdateImport.State.decode(file.readBytes())!!.let { UpdateImport.State(it.epoch, it.keyId, it.key, it.sequence, it.catalogDigest) }
        file.writeBytes(legacy.encode())
        assertEquals(115, file.length().toInt())
        val same = importer(dir)
        assertFalse(same.recordsAreFromOtherKeys); assertEquals(5L, same.state.sequence)
        assertTrue(importer(dir, catalog = c2).recordsAreFromOtherKeys)
    }

    @Test fun aLegacyRecordOfAnAdoptedEpochBelongsOnlyWhileOurRecoveryKeySignedItsPolicy() {
        val dir = folder.newFolder("hub")
        val imp = importer(dir)
        val adopt = policy(2, c2)
        assertTrue(imp.import(stick(1, key = c2, epoch = 2, recovery = adopt, objs = arrayOf(a to 1L))) is UpdateImport.Result.Imported)
        val file = File(dir, "updates/trust-state.bin")
        val legacy = UpdateImport.State.decode(file.readBytes())!!.let { UpdateImport.State(it.epoch, it.keyId, it.key, it.sequence, it.catalogDigest) }
        file.writeBytes(legacy.encode())
        assertFalse(importer(dir).recordsAreFromOtherKeys)
        assertTrue("another recovery key did not sign that policy", importer(dir, recovery = r2).recordsAreFromOtherKeys)
    }

    // ---- U7-13

    @Test fun aBurntCatalogKeyCannotComeBackThroughARecoveryPolicy() {
        val dir = folder.newFolder("hub")
        val imp = importer(dir, burnt = listOf(TestKeys.pub(c2)))
        imp.import(stick(1, objs = arrayOf(a to 1L)))
        val res = imp.import(stick(1, key = c2, epoch = 2, recovery = policy(2, c2), objs = arrayOf(a to 1L)))
        assertTrue("$res", res is UpdateImport.Result.Refused && "burnt" in res.why)
        assertEquals(1L, imp.state.epoch)
        // The same policy without a burnt list is accepted.
        assertTrue(importer(folder.newFolder("hub2")).let { it.import(stick(1, objs = arrayOf(a to 1L))); it.import(stick(1, key = c2, epoch = 2, recovery = policy(2, c2), objs = arrayOf(a to 1L))) } is UpdateImport.Result.Imported)
    }
}

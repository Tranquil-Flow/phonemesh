package org.phonemesh.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateMetadata
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyPair
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey

/** USB import of signed updates (hub updates spec 5.4, decision 45). Keys here are made for the test only. */
class UpdateImportTest {
    @get:Rule val folder = TemporaryFolder()
    private val c: KeyPair = P256.generate()
    private val c2: KeyPair = P256.generate()
    private val r: KeyPair = P256.generate()
    private val voice = ByteArray(5_000) { (it * 7).toByte() }
    private val other = ByteArray(1_234) { (it * 3 + 1).toByte() }

    private fun pub(k: KeyPair): ByteArray {
        val w = (k.public as ECPublicKey).w
        fun f(v: java.math.BigInteger) = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }.let { ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + f(w.affineX) + f(w.affineY)
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun packEntry(id: String, bytes: ByteArray, version: Long = 1): ByteArray {
        val i = id.toByteArray(); val label = "1".toByteArray()
        return byteArrayOf(UpdateMetadata.VOICE.toByte(), i.size.toByte(), label.size.toByte(), 0) +
            ByteBuffer.allocate(8 * 3 + 4 + 2 + 8).putLong(version).putLong(2).putLong(0).putInt(26).putShort(2).putLong(bytes.size.toLong()).array() +
            sha(bytes) + ByteBuffer.allocate(12).putLong(10_000).putInt(3).array() + sha("sums".toByteArray()) + ByteArray(32) + i + label
    }

    private fun catalog(sequence: Long, epoch: Long = 1, key: KeyPair = c, keyId: Int = 1, entries: List<ByteArray> = listOf(packEntry("phonemesh-voice-fr-siwis", voice)), revoked: List<ByteArray> = emptyList()): ByteArray {
        val p = byteArrayOf(1) + ByteBuffer.allocate(42).putLong(epoch).putLong(sequence).putLong(1).putLong(2).putLong(1).putShort(entries.size.toShort()).array() +
            entries.fold(ByteArray(0)) { a, b -> a + b } + ByteBuffer.allocate(2).putShort(revoked.size.toShort()).array() + revoked.fold(ByteArray(0)) { a, b -> a + b }
        return UpdateMetadata.sign(UpdateMetadata.CATALOG, keyId, p, key.private)
    }

    private fun stick(catalog: ByteArray, objects: Map<ByteArray, ByteArray> = mapOf(sha(voice) to voice), recovery: ByteArray? = null): File {
        val s = folder.newFolder()
        File(s, "catalog.pmup").writeBytes(catalog)
        recovery?.let { File(s, "recovery.pmup").writeBytes(it) }
        val o = File(s, "objects").apply { mkdirs() }
        for ((d, b) in objects) File(o, hex(d)).writeBytes(b)
        return s
    }

    private fun hub(dir: File = folder.newFolder()) = UpdateImport(dir, pub(r), pub(c)) to dir

    @Test fun aSignedCatalogAndItsObjectsAreImported() {
        val (h, dir) = hub()
        val result = h.import(stick(catalog(1)))
        assertTrue(result is UpdateImport.Result.Imported)
        assertEquals(1, (result as UpdateImport.Result.Imported).objects)
        assertArrayEquals(voice, File(dir, "updates/objects/${hex(sha(voice))}").readBytes())
        assertEquals(1L, h.state.sequence)
        assertTrue("nothing left half-copied", File(dir, "updates/partial").listFiles()!!.isEmpty())
        assertTrue(h.import(stick(catalog(1).let { File(folder.root, "same").apply { writeBytes(File(dir, "updates/catalogs").listFiles()!!.single().readBytes()) }.readBytes() })) is UpdateImport.Result.AlreadyHave)
    }

    @Test fun olderSameNumberedOrWronglySignedCatalogsAreRefused() {
        val (h, _) = hub()
        h.import(stick(catalog(5)))
        assertTrue("older", h.import(stick(catalog(4))) is UpdateImport.Result.Refused)
        assertTrue("same number, other bytes", h.import(stick(catalog(5, entries = listOf(packEntry("phonemesh-voice-fr-siwis", voice), packEntry("phonemesh-voice-zz", other))), mapOf(sha(voice) to voice, sha(other) to other))) is UpdateImport.Result.Refused)
        assertTrue("another key", h.import(stick(catalog(6, key = c2))) is UpdateImport.Result.Refused)
        assertTrue("another epoch", h.import(stick(catalog(6, epoch = 2))) is UpdateImport.Result.Refused)
        assertEquals(5L, h.state.sequence)
    }

    @Test fun anObjectThatDoesNotMatchItsDigestIsNotPublished() {
        val (h, dir) = hub()
        val fake = voice.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val result = h.import(stick(catalog(1), mapOf(sha(voice) to fake)))
        assertTrue(result is UpdateImport.Result.Refused)
        assertTrue(!File(dir, "updates/objects/${hex(sha(voice))}").exists())
        assertEquals("the catalog was not accepted either", -1L, h.state.sequence)
        assertTrue(File(dir, "updates/partial").listFiles()!!.isEmpty())
    }

    @Test fun linksExtraFilesAndMissingObjectsAreHandledSafely() {
        val (h, dir) = hub()
        val s = stick(catalog(1), objects = emptyMap())
        val secret = folder.newFile("secret").apply { writeBytes(voice) }
        Files.createSymbolicLink(File(s, "objects/${hex(sha(voice))}").toPath(), secret.toPath())
        File(s, "autorun.sh").writeText("rm -rf /")
        val result = h.import(s) as UpdateImport.Result.Imported
        assertEquals("a link is not an object", 1, result.missing)
        assertTrue(!File(dir, "updates/objects/${hex(sha(voice))}").exists())
        val linked = folder.newFolder()
        Files.createSymbolicLink(File(linked, "catalog.pmup").toPath(), File(s, "catalog.pmup").toPath())
        assertTrue("a linked catalog is not read", hub().first.import(linked) is UpdateImport.Result.Refused)
    }

    @Test fun revokedObjectsAreNotCopied() {
        val (h, dir) = hub()
        h.import(stick(catalog(1, revoked = listOf(sha(voice)))))
        assertTrue(!File(dir, "updates/objects/${hex(sha(voice))}").exists())
    }

    @Test fun onlyTheRecoveryKeyReplacesTheCatalogKey() {
        val (h, dir) = hub()
        h.import(stick(catalog(3)))
        fun policy(by: KeyPair, newKey: KeyPair, epoch: Long = 2, keyId: Int = 100) = UpdateMetadata.sign(UpdateMetadata.RECOVERY, keyId,
            ByteBuffer.allocate(8 + 2 + 65 + 8 + 4).putLong(epoch).putShort(2).put(pub(newKey)).putLong(1).putInt(1).array(), by.private)
        assertTrue("signed by the old catalog key", h.import(stick(catalog(1, epoch = 2, key = c2, keyId = 2), recovery = policy(c, c2))) is UpdateImport.Result.Refused)
        assertTrue("signed by the catalog key under its own id", h.import(stick(catalog(1, epoch = 2, key = c2, keyId = 2), recovery = policy(c, c2, keyId = 1))) is UpdateImport.Result.Refused)
        val ok = h.import(stick(catalog(1, epoch = 2, key = c2, keyId = 2), recovery = policy(r, c2)))
        assertTrue(ok is UpdateImport.Result.Imported)
        assertEquals(2L, h.state.epoch)
        assertTrue("the old key no longer signs", h.import(stick(catalog(9, epoch = 2))) is UpdateImport.Result.Refused)
        assertTrue("the state survives a restart", UpdateImport(dir, pub(r), pub(c)).state.epoch == 2L)
    }

    @Test fun aDamagedTrustFileStopsUpdatesInsteadOfResetting() {
        val (h, dir) = hub()
        h.import(stick(catalog(2)))
        File(dir, "updates/trust-state.bin").writeBytes(ByteArray(10))
        assertTrue(runCatching { UpdateImport(dir, pub(r), pub(c)) }.isFailure)
    }
}

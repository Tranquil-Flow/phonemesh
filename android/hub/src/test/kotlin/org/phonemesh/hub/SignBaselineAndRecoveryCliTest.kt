package org.phonemesh.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.PackKind
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.TrustStore
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import org.phonemesh.core.update.UpdateTrust
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.KeyPair
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** U7-2: the owner's real-key swap is one command (`sign-baseline`), and `sign-recovery` makes the policy that moves phones to a new key. */
class SignBaselineAndRecoveryCliTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalogKey = P256.generate()
    private val recoveryKey = P256.generate()
    private val secret = "tr0ub4dor&3-secret-phrase"
    private fun compressed(k: KeyPair) = TestKeys.hex(TestKeys.compressed(k))
    private fun rootsText(c: KeyPair = catalogKey, r: KeyPair = recoveryKey, test: Boolean = false) = (if (test) "# status: TEST\n" else "") +
        "catalog key_id=1 public_key=${compressed(c)}\nrecovery key_id=100 public_key=${compressed(r)}\n"

    private class Run(val code: Int, val out: String, val err: String)

    private var compiled: List<CatalogEntry> = emptyList()

    private fun run(vararg args: String, passphrase: String? = secret): Run {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = UpdateCli.run(args.toList(), UpdateCli.Io(PrintStream(out), PrintStream(err), { passphrase?.toCharArray() }, compiledPacks = compiled))
        return Run(code, out.toString(), err.toString())
    }

    /** A folder with `fr.zip` and `update-baseline.list`, and sets [compiled] to describe that pack as the build's own. */
    private fun packs(version: Int = 1): File {
        val dir = folder.newFolder()
        val files = linkedMapOf(
            "pack.properties" to "format=2\nkind=voice\nid=voice-fr-siwis\nversion=$version\nlabel=Francais\nlanguageTag=fr\nwhisperLanguage=fr\n".toByteArray(),
            "tts/model.onnx" to ByteArray(500) { it.toByte() }, "tts/tokens.txt" to "a 1\n".toByteArray(),
            "tts/espeak-ng-data/phontab" to "x".toByteArray(), "tts/espeak-ng-data/phondata" to "x".toByteArray(),
            "tts/espeak-ng-data/phonindex" to "x".toByteArray(), "tts/espeak-ng-data/intonations" to "x".toByteArray(),
            "tts/espeak-ng-data/lang/fr" to "name French\n".toByteArray())
        val sums = files.entries.joinToString("") { (n, b) -> "${java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }}  $n\n" }
        File(dir, "fr.zip").outputStream().use { o -> ZipOutputStream(o).use { z ->
            for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            z.putNextEntry(ZipEntry("SHA256SUMS")); z.write(sums.toByteArray()); z.closeEntry()
        } }
        File(dir, "update-baseline.list").writeText("# the baseline\nepoch = 1\nsequence = 1\nissued-at = 1793145600\nmin-reader = 1\npack file=fr.zip\n")
        compiled = listOf(CatalogEntry("voice-fr-siwis", version, PackKind.VOICE, "fr.zip", File(dir, "fr.zip").length(),
            org.phonemesh.core.crypto.Bytes.sha256(sums.toByteArray()).joinToString("") { "%02x".format(it) }))
        return dir
    }

    private fun keyFile(key: KeyPair = catalogKey, passphrase: String = secret) = folder.newFile().also { it.writeText(TestPem.encryptedPem(key, passphrase)) }
    private fun publicKeys(text: String = rootsText()) = folder.newFile().also { it.writeText(text) }

    private fun baseline(dir: File, keys: File, out: File, key: File = keyFile(), vararg extra: String) =
        run("sign-baseline", "--public-keys", keys.path, "--key", key.path, "--list", File(dir, "update-baseline.list").path, "--out", out.path, *extra)

    private class Mem : TrustStore {
        val slots = arrayOfNulls<ByteArray>(2)
        override fun load(slot: Int) = slots[slot]?.copyOf()
        override fun save(slot: Int, bytes: ByteArray) { slots[slot] = bytes.copyOf() }
    }

    // ---- sign-baseline

    @Test fun oneCommandWritesTheOwnersKeysAndTheReSignedBaselineTogether() {
        val dir = packs(); val keys = publicKeys(); val out = File(folder.newFolder(), "update")
        val r = baseline(dir, keys, out)
        assertEquals(r.err + r.out, 0, r.code)
        assertEquals(setOf("public-keys.txt", "baseline-catalog.pmup"), out.list()!!.toSet())
        assertEquals("the owner's file, byte for byte", keys.readText(), File(out, "public-keys.txt").readText())
        val roots = UpdateRoots.parse(File(out, "public-keys.txt").readText())
        assertFalse(roots.isTest)
        val env = UpdateMetadata.verify(File(out, "baseline-catalog.pmup").readBytes(), mapOf(1 to roots.catalogKey), null)
        assertNotNull(env)
        // The pair opens a fresh phone exactly as the app does it.
        val opened = UpdateTrust.open(Mem(), roots, 3, File(out, "baseline-catalog.pmup").readBytes())
        assertTrue("$opened", opened is UpdateTrust.Opened.Ready)
        assertFalse(secret in r.out + r.err)
        assertTrue(r.out, "Wrote" in r.out && "together" in r.out)
    }

    @Test fun anOldPairInTheFolderIsReplacedAsOne() {
        val dir = packs(); val out = folder.newFolder("update")
        File(out, "public-keys.txt").writeText("# status: TEST\nold"); File(out, "baseline-catalog.pmup").writeBytes(ByteArray(10))
        assertEquals(0, baseline(dir, publicKeys(), out).code)
        assertTrue(UpdateRoots.parse(File(out, "public-keys.txt").readText()).catalogKey.contentEquals(TestKeys.pub(catalogKey)))
        assertTrue(File(out, "baseline-catalog.pmup").length() > 100)
        assertEquals(setOf("public-keys.txt", "baseline-catalog.pmup"), out.list()!!.toSet())
    }

    @Test fun testKeysAreRefusedUnlessAskedForAndSayTheyAreTestKeys() {
        val dir = packs(); val keys = publicKeys(rootsText(test = true)); val out = File(folder.newFolder(), "update")
        val r = baseline(dir, keys, out)
        assertEquals(2, r.code); assertTrue(r.err, "TEST keys" in r.err); assertFalse(out.exists())
        val ok = baseline(dir, keys, out, keyFile(), "--allow-test-keys")
        assertEquals(ok.err, 0, ok.code); assertTrue(ok.out, "TEST keys" in ok.out)
    }

    @Test fun everyRefusalWritesNothingAndKeepsWhatWasThere() {
        val dir = packs(); val keys = publicKeys()
        fun check(why: String, out: File, run: () -> Run) {
            val before = out.list()?.sorted()
            val r = run()
            assertEquals("$why: ${r.out}${r.err}", 2, r.code)
            assertEquals(why, before, out.list()?.sorted())
        }
        val out = File(folder.newFolder(), "update")
        check("wrong passphrase", out) { run("sign-baseline", "--public-keys", keys.path, "--key", keyFile(passphrase = "other").path, "--list", File(dir, "update-baseline.list").path, "--out", out.path) }
        check("a key that is not the catalog key", out) { baseline(dir, keys, out, keyFile(recoveryKey)) }
        check("another catalog key", out) { baseline(dir, keys, out, keyFile(P256.generate())) }
        check("no passphrase", out) { run("sign-baseline", "--public-keys", keys.path, "--key", keyFile().path, "--list", File(dir, "update-baseline.list").path, "--out", out.path, passphrase = null) }
        check("garbage public keys", out) { baseline(dir, publicKeys("nonsense"), out) }
        check("a missing public key file", out) { baseline(dir, File(folder.root, "none"), out) }
        // A baseline that does not name the build's own packs is the build mistake this command exists to stop.
        val other = packs(version = 2)
        compiled = compiled.map { CatalogEntry(it.id, 1, it.kind, it.fileName, it.zipBytes, it.manifestSha256) }
        check("a pack list that is not the build's packs", out) { baseline(other, keys, out) }
        compiled = emptyList()
        check("no packs in the build", out) { baseline(dir, keys, out) }
        for (missing in listOf("--public-keys", "--key", "--list", "--out")) {
            val args = mutableListOf("sign-baseline", "--public-keys", keys.path, "--key", keyFile().path, "--list", File(dir, "update-baseline.list").path, "--out", out.path)
            val i = args.indexOf(missing); args.removeAt(i); args.removeAt(i)
            assertEquals("missing $missing", 2, run(*args.toTypedArray()).code)
        }
    }

    @Test fun lightlyProtectedKeysSignButTheOwnerIsWarned() {
        val dir = packs(); val out = File(folder.newFolder(), "update")
        val r = baseline(dir, publicKeys(), out)
        assertEquals(0, r.code)
        assertTrue(r.out, "lightly protected" in r.out)
    }

    @Test fun thePacksFolderCanBeNamedSeparatelyFromTheList() {
        val dir = packs(); val listDir = folder.newFolder(); File(dir, "update-baseline.list").copyTo(File(listDir, "update-baseline.list"))
        val out = File(folder.newFolder(), "update")
        val r = run("sign-baseline", "--public-keys", publicKeys().path, "--key", keyFile().path, "--list", File(listDir, "update-baseline.list").path, "--packs", dir.path, "--out", out.path)
        assertEquals(r.err, 0, r.code)
    }

    // ---- sign-recovery

    private val newKey = P256.generate()

    private fun baselineTrust(): UpdateTrust {
        val roots = UpdateRoots.parse(rootsText())
        val entry = UpdateMetadata.Entry(UpdateMetadata.VOICE, "voice-fr-siwis", "1", "", 1, 1, 0, 26, 2, 10_000, org.phonemesh.core.crypto.Bytes.sha256("z".toByteArray()), 20_000, 5, org.phonemesh.core.crypto.Bytes.sha256("s".toByteArray()), ByteArray(32))
        val base = UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, CatalogSigner.encode(1, 1, 1793145600, 1808697600, 1, listOf(entry), emptyList()), catalogKey.private)
        return (UpdateTrust.open(Mem(), roots, 3, base) as UpdateTrust.Opened.Ready).trust
    }

    private fun recovery(out: File, vararg extra: String, key: File = keyFile(recoveryKey), keyHex: String = compressed(newKey), epoch: String = "2", id: String = "2", passphrase: String? = secret) =
        run("sign-recovery", "--key", key.path, "--public-keys", publicKeys().path, "--new-catalog-key", keyHex, "--new-key-id", id, "--epoch", epoch, "--min-sequence", "1", "--out", out.path, *extra, passphrase = passphrase)

    @Test fun aSignedPolicyIsExactlyWhatAPhoneAccepts() {
        val out = File(folder.newFolder(), "recovery")
        val r = recovery(out)
        assertEquals(r.err + r.out, 0, r.code)
        assertEquals(listOf("recovery.pmup"), out.list()!!.toList())
        val trust = baselineTrust()
        val res = trust.acceptRecovery(File(out, "recovery.pmup").readBytes())
        assertTrue("$res", res is UpdateTrust.RecoveryResult.Accepted)
        assertEquals(2L, trust.state.epoch); assertEquals(2, trust.state.keyId)
        assertArrayEquals(TestKeys.pub(newKey), trust.state.key)
        assertFalse(secret in r.out + r.err)
        // The hub takes the same file.
        val hubDir = folder.newFolder("hub")
        val stick = folder.newFolder("stick"); File(out, "recovery.pmup").copyTo(File(stick, "recovery.pmup"))
        val payload = CatalogSigner.encode(2, 1, 1793145600, 1808697600, 1, emptyList(), emptyList())
        File(stick, "catalog.pmup").writeBytes(UpdateMetadata.sign(UpdateMetadata.CATALOG, 2, payload, newKey.private))
        val roots = UpdateRoots.parse(rootsText())
        val imp = UpdateImport(hubDir, roots.recoveryKey, roots.catalogKey)
        val imported = imp.import(stick)
        assertTrue("$imported", imported is UpdateImport.Result.Imported)
        assertEquals(2L, imp.state.epoch)
    }

    @Test fun everyBadPolicyRequestIsRefusedAndWritesNothing() {
        fun refused(why: String, run: (File) -> Run) {
            val out = File(folder.newFolder(), "recovery")
            val r = run(out)
            assertEquals("$why: ${r.out}${r.err}", 2, r.code)
            assertFalse(why, out.exists())
        }
        refused("the catalog key signing") { recovery(it, key = keyFile(catalogKey)) }
        refused("a stranger's key") { recovery(it, key = keyFile(P256.generate())) }
        refused("wrong passphrase") { recovery(it, key = keyFile(recoveryKey, "other")) }
        refused("no passphrase") { recovery(it, passphrase = null) }
        refused("the new key is the old catalog key") { recovery(it, keyHex = compressed(catalogKey)) }
        refused("the new key is the recovery key") { recovery(it, keyHex = compressed(recoveryKey)) }
        refused("the recovery key id") { recovery(it, id = "100") }
        refused("key id 0") { recovery(it, id = "0") }
        refused("key id too large") { recovery(it, id = "70000") }
        refused("epoch 1") { recovery(it, epoch = "1") }
        refused("not a hex key") { recovery(it, keyHex = "zz".repeat(33)) }
        refused("not a point") { recovery(it, keyHex = "02" + "ff".repeat(32)) }
        // The reasons are the plain ones, not a later check failing by accident.
        val byCatalog = recovery(File(folder.newFolder(), "r1"), key = keyFile(catalogKey))
        assertTrue(byCatalog.err, "this is the catalog key" in byCatalog.err)
        val byStranger = recovery(File(folder.newFolder(), "r2"), key = keyFile(P256.generate()))
        assertTrue(byStranger.err, "not the recovery key" in byStranger.err)
        val busy = folder.newFolder("busy"); File(busy, "x").writeText("x")
        assertEquals("a folder that is not empty", 2, recovery(busy).code)
    }

    @Test fun aBurntKeyCannotBeTheNewCatalogKey() {
        val out = File(folder.newFolder(), "recovery")
        val keys = folder.newFile().also { it.writeText(rootsText() + "burnt public_key=${compressed(newKey)}\n") }
        val r = run("sign-recovery", "--key", keyFile(recoveryKey).path, "--public-keys", keys.path, "--new-catalog-key", compressed(newKey), "--new-key-id", "2", "--epoch", "2", "--min-sequence", "1", "--out", out.path)
        assertEquals(2, r.code); assertTrue(r.err, "burnt" in r.err)
    }
}

package org.phonemesh.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.PackKind
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateRoots
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.KeyPair
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `check-baseline` (docs/update-keys.md, step 5): the public pair the owner returns from `sign-baseline` is checked with the
 * product's own parsers before anything is committed. It needs no private key or passphrase, writes nothing, refuses TEST
 * keys, and accepts only the catalog that `sign-baseline` makes from the same list and packs.
 */
class CheckBaselineCliTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalogKey = P256.generate()
    private val recoveryKey = P256.generate()
    private val secret = "correct-horse-battery-staple-offline"
    private fun compressed(k: KeyPair) = TestKeys.hex(TestKeys.compressed(k))
    private fun rootsText(c: KeyPair = catalogKey, r: KeyPair = recoveryKey, test: Boolean = false) = (if (test) "# status: TEST\n" else "") +
        "catalog key_id=1 public_key=${compressed(c)}\nrecovery key_id=100 public_key=${compressed(r)}\n"

    private class Run(val code: Int, val out: String, val err: String)

    private var compiled: List<CatalogEntry> = emptyList()
    private var passphraseAsked = 0

    private fun run(vararg args: String): Run {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val io = UpdateCli.Io(PrintStream(out), PrintStream(err), { passphraseAsked++; secret.toCharArray() }, compiledPacks = compiled)
        val code = UpdateCli.run(args.toList(), io)
        return Run(code, out.toString(), err.toString())
    }

    /** A folder with `fr.zip` and `update-baseline.list`; sets [compiled] to describe that pack as the build's own. */
    private fun packs(sequence: Int = 1): File {
        val dir = folder.newFolder()
        val files = linkedMapOf(
            "pack.properties" to "format=2\nkind=voice\nid=voice-fr-siwis\nversion=1\nlabel=Francais\nlanguageTag=fr\nwhisperLanguage=fr\n".toByteArray(),
            "tts/model.onnx" to ByteArray(500) { it.toByte() }, "tts/tokens.txt" to "a 1\n".toByteArray(),
            "tts/espeak-ng-data/phontab" to "x".toByteArray(), "tts/espeak-ng-data/phondata" to "x".toByteArray(),
            "tts/espeak-ng-data/phonindex" to "x".toByteArray(), "tts/espeak-ng-data/intonations" to "x".toByteArray(),
            "tts/espeak-ng-data/lang/fr" to "name French\n".toByteArray())
        val sums = files.entries.joinToString("") { (n, b) -> "${java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }}  $n\n" }
        File(dir, "fr.zip").outputStream().use { o -> ZipOutputStream(o).use { z ->
            for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            z.putNextEntry(ZipEntry("SHA256SUMS")); z.write(sums.toByteArray()); z.closeEntry()
        } }
        writeList(dir, sequence)
        compiled = listOf(CatalogEntry("voice-fr-siwis", 1, PackKind.VOICE, "fr.zip", File(dir, "fr.zip").length(),
            org.phonemesh.core.crypto.Bytes.sha256(sums.toByteArray()).joinToString("") { "%02x".format(it) }))
        return dir
    }

    private fun writeList(dir: File, sequence: Int) =
        File(dir, "update-baseline.list").writeText("# the baseline\nepoch = 1\nsequence = $sequence\nissued-at = 1793145600\nmin-reader = 1\npack file=fr.zip\n")

    private fun keyFile(key: KeyPair = catalogKey) = folder.newFile().also { it.writeText(TestPem.encryptedPem(key, secret)) }
    private fun publicKeys(text: String = rootsText()) = folder.newFile().also { it.writeText(text) }

    /** The pair `sign-baseline` writes for [dir]'s list under [keys], signed with [key]. */
    private fun signedPair(dir: File, keys: File = publicKeys(), key: File = keyFile(), vararg extra: String): File {
        val out = File(folder.newFolder(), "signed")
        val r = run("sign-baseline", "--public-keys", keys.path, "--key", key.path, "--list", File(dir, "update-baseline.list").path, "--out", out.path, *extra)
        assertEquals(r.err + r.out, 0, r.code)
        passphraseAsked = 0
        return out
    }

    private fun check(signed: File, dir: File, keys: File = File(signed, "public-keys.txt"), baseline: File = File(signed, "baseline-catalog.pmup")) =
        run("check-baseline", "--public-keys", keys.path, "--baseline", baseline.path, "--list", File(dir, "update-baseline.list").path)

    private fun snapshot(): Map<String, String> = folder.root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(folder.root).path to TestKeys.hex(org.phonemesh.core.crypto.Bytes.sha256(it.readBytes())) }

    private fun sha(f: File) = TestKeys.hex(org.phonemesh.core.crypto.Bytes.sha256(f.readBytes()))

    @Test fun theOwnersReturnedPairChecksOutWithoutAKeyAndWritesNothing() {
        val dir = packs(); val signed = signedPair(dir)
        val before = snapshot()
        val r = check(signed, dir)
        assertEquals(r.err + r.out, 0, r.code)
        assertEquals("no passphrase is ever asked for", 0, passphraseAsked)
        assertEquals("nothing is written, moved or removed", before, snapshot())
        assertTrue(r.out, "belong together" in r.out)
        assertTrue(r.out, "catalog key id 1" in r.out && "recovery key id 100" in r.out)
        assertTrue(r.out, "epoch 1, catalog number 1, 1 pack" in r.out)
        // The hashes printed are the ones the owner's `shasum -a 256` lines are compared with.
        assertTrue(r.out, sha(File(signed, "public-keys.txt")) in r.out)
        assertTrue(r.out, sha(File(signed, "baseline-catalog.pmup")) in r.out)
        assertTrue(r.out, "fresh" in r.out)
        assertFalse(r.out, "TEST" in r.out)
    }

    @Test fun testKeysAreRefusedAndThereIsNoOverride() {
        val dir = packs(); val signed = signedPair(dir, publicKeys(rootsText(test = true)), keyFile(), "--allow-test-keys")
        val r = check(signed, dir)
        assertEquals(2, r.code); assertTrue(r.err, "TEST keys" in r.err)
        val forced = run("check-baseline", "--public-keys", File(signed, "public-keys.txt").path, "--baseline", File(signed, "baseline-catalog.pmup").path,
            "--list", File(dir, "update-baseline.list").path, "--allow-test-keys")
        assertEquals(2, forced.code); assertTrue(forced.err, "unexpected '--allow-test-keys'" in forced.err)
        // The known label-derived keys are TEST keys even without the status comment.
        val known = UpdateRoots.KNOWN_TEST_KEYS.sorted()
        val unmarked = publicKeys("catalog key_id=1 public_key=${known[0]}\nrecovery key_id=100 public_key=${known[1]}\n")
        val noComment = check(signed, dir, keys = unmarked)
        assertEquals(2, noComment.code); assertTrue(noComment.err, "TEST keys" in noComment.err)
    }

    @Test fun aBaselineThatIsNotSignedByThisCatalogKeyIsRefused() {
        val dir = packs()
        // A real-looking pair made under another catalog key, checked against the owner's public keys.
        val stranger = P256.generate()
        val other = signedPair(dir, publicKeys(rootsText(c = stranger)), keyFile(stranger))
        val r = check(other, dir, keys = publicKeys())
        assertEquals(2, r.code); assertTrue(r.err, "not signed by the catalog key" in r.err)
    }

    @Test fun aChangedOrMalformedBaselineIsRefused() {
        val dir = packs(); val signed = signedPair(dir)
        val good = File(signed, "baseline-catalog.pmup").readBytes()
        fun refused(why: String, bytes: ByteArray) {
            val f = folder.newFile(); f.writeBytes(bytes)
            val r = check(signed, dir, baseline = f)
            assertEquals("$why: ${r.out}${r.err}", 2, r.code)
            assertTrue("$why: ${r.err}", "not signed by the catalog key" in r.err || "too large" in r.err)
        }
        refused("a payload byte changed", good.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })
        refused("a signature byte changed", good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        refused("cut short", good.copyOf(good.size - 1))
        refused("empty", ByteArray(0))
        refused("larger than any envelope", ByteArray(70_000))
        val missing = check(signed, dir, baseline = File(folder.root, "none.pmup"))
        assertEquals(2, missing.code); assertTrue(missing.err, "is not a file" in missing.err)
    }

    @Test fun aBaselineOfAnotherListIsRefusedEvenWhenItsSignatureIsGood() {
        val dir = packs(); val signed = signedPair(dir)
        // Same keys, same packs, but the list in the source now says catalog number 2: the returned baseline is not its catalog.
        writeList(dir, sequence = 2)
        val r = check(signed, dir)
        assertEquals(2, r.code); assertTrue(r.err, "not the catalog of" in r.err)
    }

    @Test fun aListThatIsNotTheBuildsPacksIsRefused() {
        val dir = packs(); val signed = signedPair(dir)
        compiled = compiled.map { CatalogEntry(it.id, 2, it.kind, it.fileName, it.zipBytes, it.manifestSha256) }
        val r = check(signed, dir)
        assertEquals(2, r.code); assertTrue(r.err, "not the pack this build carries" in r.err)
    }

    @Test fun aPrivateKeyFileGivenAsThePublicKeysIsRefusedWithoutBeingShown() {
        val dir = packs(); val signed = signedPair(dir)
        val pem = keyFile()
        val r = check(signed, dir, keys = pem)
        assertEquals(2, r.code)
        assertTrue(r.err, "unrecognised line" in r.err)
        val body = pem.readLines().filter { it.isNotEmpty() && !it.startsWith("-----") }
        assertTrue(body.isNotEmpty())
        for (line in body) assertFalse("a key line was printed", line in r.out + r.err)
        assertEquals(0, passphraseAsked)
    }

    @Test fun missingRepeatedOrStrayOptionsAreRefused() {
        val dir = packs(); val signed = signedPair(dir)
        val keys = File(signed, "public-keys.txt").path; val base = File(signed, "baseline-catalog.pmup").path; val list = File(dir, "update-baseline.list").path
        for ((missing, need) in listOf("--public-keys" to "check-baseline needs --public-keys", "--baseline" to "check-baseline needs --baseline", "--list" to "check-baseline needs --list")) {
            val args = mutableListOf("check-baseline", "--public-keys", keys, "--baseline", base, "--list", list)
            val i = args.indexOf(missing); args.removeAt(i); args.removeAt(i)
            val r = run(*args.toTypedArray())
            assertEquals("missing $missing", 2, r.code); assertTrue(r.err, need in r.err)
        }
        val twice = run("check-baseline", "--public-keys", keys, "--public-keys", keys, "--baseline", base, "--list", list)
        assertEquals(2, twice.code); assertTrue(twice.err, "given twice" in twice.err)
        val stray = run("check-baseline", "stray", "--public-keys", keys, "--baseline", base, "--list", list)
        assertEquals(2, stray.code); assertTrue(stray.err, "unexpected 'stray'" in stray.err)
        assertTrue("the command is one the hub program dispatches", "check-baseline" in UpdateCli.COMMANDS)
    }

    @Test fun thePacksFolderCanBeNamedSeparatelyFromTheList() {
        val dir = packs(); val signed = signedPair(dir)
        val listDir = folder.newFolder(); File(dir, "update-baseline.list").copyTo(File(listDir, "update-baseline.list"))
        val r = run("check-baseline", "--public-keys", File(signed, "public-keys.txt").path, "--baseline", File(signed, "baseline-catalog.pmup").path,
            "--list", File(listDir, "update-baseline.list").path, "--packs", dir.path)
        assertEquals(r.err + r.out, 0, r.code)
    }
}

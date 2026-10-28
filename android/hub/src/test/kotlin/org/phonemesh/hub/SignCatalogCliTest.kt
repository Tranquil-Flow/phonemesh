package org.phonemesh.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `phonemesh-hub sign-catalog ...` as the owner types it. */
class SignCatalogCliTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalogKey = P256.generate()
    private val recoveryKey = P256.generate()
    private val secret = "tr0ub4dor&3-secret-phrase"
    private fun rootsText(test: Boolean = false) = (if (test) "# status: TEST\n" else "") +
        "catalog key_id=1 public_key=${TestKeys.hex(TestKeys.compressed(catalogKey))}\nrecovery key_id=100 public_key=${TestKeys.hex(TestKeys.compressed(recoveryKey))}\n"

    private class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String, passphrase: String? = secret, roots: UpdateRoots = UpdateRoots.parse(rootsText())): Run {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = UpdateCli.run(args.toList(), UpdateCli.Io(PrintStream(out), PrintStream(err), { passphrase?.toCharArray() }, roots))
        return Run(code, out.toString(), err.toString())
    }

    private fun pack(): File {
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
        File(dir, "packs.list").writeText("epoch = 1\nsequence = 1\nissued-at = 1790000000\npack file=fr.zip\n")
        return dir
    }

    private fun keyFile(passphrase: String = secret, key: java.security.KeyPair = catalogKey) =
        folder.newFile().also { it.writeText(TestPem.encryptedPem(key, passphrase)) }

    @Test fun signsAndReportsWithoutEchoingSecrets() {
        val dir = pack(); val out = File(folder.newFolder(), "release-1")
        val r = run("sign-catalog", "--key", keyFile().path, "--list", File(dir, "packs.list").path, "--out", out.path)
        assertEquals(r.err, 0, r.code)
        assertTrue(File(out, "catalog.pmup").isFile)
        assertTrue(r.out, "sequence 1" in r.out && "1 file" in r.out)
        assertFalse(secret in r.out + r.err)
        val env = UpdateMetadata.verify(File(out, "catalog.pmup").readBytes(), mapOf(1 to UpdateRoots.parse(rootsText()).catalogKey), null)
        assertTrue(env != null)
    }

    @Test fun aPublicKeyFileCanNameTheKeys() {
        val dir = pack(); val pk = folder.newFile().also { it.writeText(rootsText()) }
        val out = File(folder.newFolder(), "r")
        val other = UpdateRoots.parse("catalog key_id=1 public_key=${TestKeys.hex(TestKeys.compressed(P256.generate()))}\nrecovery key_id=100 public_key=${TestKeys.hex(TestKeys.compressed(P256.generate()))}\n")
        assertEquals(0, run("sign-catalog", "--key", keyFile().path, "--list", File(dir, "packs.list").path, "--out", out.path, "--public-keys", pk.path, roots = other).code)
        val bad = folder.newFile().also { it.writeText("nonsense") }
        assertEquals(2, run("sign-catalog", "--key", keyFile().path, "--list", File(dir, "packs.list").path, "--out", File(folder.newFolder(), "x").path, "--public-keys", bad.path).code)
        assertEquals(2, run("sign-catalog", "--key", keyFile().path, "--list", File(dir, "packs.list").path, "--out", File(folder.newFolder(), "x").path, "--public-keys", File(folder.root, "none").path).code)
    }

    @Test fun testKeysAreNamedAsTestKeys() {
        val dir = pack()
        val r = run("sign-catalog", "--key", keyFile().path, "--list", File(dir, "packs.list").path, "--out", File(folder.newFolder(), "r").path, roots = UpdateRoots.parse(rootsText(test = true)))
        assertEquals(0, r.code)
        assertTrue(r.out, "TEST keys" in r.out)
    }

    @Test fun everyRefusalIsAnExitCodeAndLeavesNothing() {
        val dir = pack(); val list = File(dir, "packs.list").path
        fun out() = File(folder.newFolder(), "release").also { }
        fun clean(o: File) = assertFalse("nothing written", o.exists())
        var o = out(); assertEquals(2, run("sign-catalog", "--key", keyFile("other").path, "--list", list, "--out", o.path).code); clean(o)
        o = out(); assertEquals("no passphrase available", 2, run("sign-catalog", "--key", keyFile().path, "--list", list, "--out", o.path, passphrase = null).code); clean(o)
        o = out(); assertEquals(2, run("sign-catalog", "--key", keyFile(key = recoveryKey).path, "--list", list, "--out", o.path).code); clean(o)
        o = out(); assertEquals("missing key file", 2, run("sign-catalog", "--key", File(folder.root, "none.pem").path, "--list", list, "--out", o.path).code); clean(o)
        o = out(); assertEquals("missing list", 2, run("sign-catalog", "--key", keyFile().path, "--list", File(folder.root, "none").path, "--out", o.path).code); clean(o)
        o = out(); assertEquals("missing --out", 2, run("sign-catalog", "--key", keyFile().path, "--list", list).code)
        assertEquals("missing --key", 2, run("sign-catalog", "--list", list, "--out", o.path).code); clean(o)
        assertEquals("missing --list", 2, run("sign-catalog", "--key", keyFile().path, "--out", o.path).code); clean(o)
        assertEquals("unknown option", 2, run("sign-catalog", "--key", keyFile().path, "--list", list, "--out", o.path, "--force").code); clean(o)
        assertEquals("option without value", 2, run("sign-catalog", "--key").code)
        assertEquals("repeated option", 2, run("sign-catalog", "--key", keyFile().path, "--key", keyFile().path, "--list", list, "--out", o.path).code)
        assertEquals("a stray word", 2, run("sign-catalog", "stray", "--key", keyFile().path, "--list", list, "--out", o.path).code); clean(o)
        val plain = folder.newFile().also { it.writeText(TestPem.pem("PRIVATE KEY", TestPem.pkcs8(catalogKey))) }
        o = out(); assertEquals("an unencrypted key", 2, run("sign-catalog", "--key", plain.path, "--list", list, "--out", o.path).code); clean(o)
        val busy = folder.newFolder(); File(busy, "keep").writeText("x")
        assertEquals("an output folder with files", 2, run("sign-catalog", "--key", keyFile().path, "--list", list, "--out", busy.path).code)
        assertTrue(File(busy, "keep").exists())
        val r = run("nonsense")
        assertEquals(2, r.code); assertTrue(r.err.contains("sign-catalog"))
    }
}

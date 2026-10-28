package org.phonemesh.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateRoots
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** `import-updates <stick>` and `update-status` (U2): a real signed release from the signing tool, imported from a stick. */
class UpdateCommandsTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalogKey = P256.generate()
    private val recoveryKey = P256.generate()
    private val roots = UpdateRoots.parse(
        "catalog key_id=1 public_key=${TestKeys.hex(TestKeys.compressed(catalogKey))}\n" +
            "recovery key_id=100 public_key=${TestKeys.hex(TestKeys.compressed(recoveryKey))}\n")
    private val hubDir by lazy { folder.newFolder("hub-data") }
    private val conf by lazy { File(folder.root, "hub.conf").also { it.writeText("name = Test hub\ndir = ${hubDir.absolutePath}\n") } }
    private var clock = 1_793_177_460_000L // 2026-10-28 08:51 UTC

    private class Run(val code: Int, val out: String, val err: String)

    private fun run(vararg args: String): Run {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = UpdateCli.run(args.toList(), UpdateCli.Io(PrintStream(out), PrintStream(err), { null }, roots, now = { clock }, defaultConfig = conf))
        return Run(code, out.toString(), err.toString())
    }

    /** A release folder as the signing tool writes it: [packs] are (id, version, kind). */
    private fun release(sequence: Int, vararg packs: Triple<String, Int, String>, revoke: String? = null, withObjects: Boolean = true): File {
        val dir = folder.newFolder()
        packs.forEach { (id, v, kind) -> File(dir, "$id-$v.zip").writeBytes(TestPacks.zip(id, v, kind)) }
        File(dir, "packs.list").writeText("epoch = 1\nsequence = $sequence\nissued-at = 1793145600\n" + (revoke?.let { "revoke = $it\n" } ?: "") +
            packs.joinToString("") { (id, v, _) -> "pack file=$id-$v.zip\n" })
        val out = File(folder.newFolder(), "release")
        CatalogSigner.sign(CatalogSigner.readList(File(dir, "packs.list")), KeyFile.read(TestPem.encryptedPem(catalogKey, "pw"), "pw".toCharArray()), roots, out)
        if (!withObjects) File(out, "objects").deleteRecursively()
        return out
    }

    private val fr = Triple("voice-fr-siwis", 1, "voice")
    private val en = Triple("voice-en-US-ljspeech", 1, "voice")
    private val engine = Triple("engine-whisper-tiny", 1, "engine")

    @Test fun anImportFromAStickPutsTheReleaseOnTheHubAndTheStatusShowsIt() {
        val r = run("import-updates", release(3, fr, engine).path, conf.path)
        assertEquals(r.err, 0, r.code)
        assertTrue(r.out, "catalog number 3" in r.out && "2 files copied" in r.out)
        assertEquals(2, File(hubDir, "updates/objects").list()!!.size)
        val s = run("update-status", conf.path)
        assertEquals(s.err, 0, s.code)
        assertTrue(s.out, "catalog number 3" in s.out)
        assertTrue(s.out, "2 of 2 here" in s.out && "voice-fr-siwis" in s.out && "engine-whisper-tiny" in s.out)
        assertFalse(s.out, "MISSING" in s.out)
        assertTrue(s.out, "Last import: 2026-10-28 08:51 UTC" in s.out)
        assertTrue(s.out, "Revoked: 0" in s.out)
    }

    @Test fun theConfigFileIsOptionalAndDefaultsToHubConf() {
        assertEquals(0, run("import-updates", release(1, fr).path).code)
        assertEquals(0, run("update-status").code)
    }

    @Test fun importingTheSameReleaseAgainChangesNothing() {
        val stick = release(3, fr)
        assertEquals(0, run("import-updates", stick.path, conf.path).code)
        val again = run("import-updates", stick.path, conf.path)
        assertEquals(again.err, 0, again.code)
        assertTrue(again.out, "already" in again.out.lowercase())
    }

    @Test fun aCatalogWithoutFilesIsKeptAndALaterStickFillsTheGaps() {
        val full = release(5, fr, engine)
        val bare = File(folder.newFolder(), "bare").also { full.copyRecursively(it) }.also { File(it, "objects").deleteRecursively() }
        val first = run("import-updates", bare.path, conf.path)
        assertEquals(first.err, 0, first.code)
        assertTrue(first.out, "0 files copied" in first.out && "2 missing" in first.out)
        val s1 = run("update-status", conf.path).out
        assertTrue(s1, "0 of 2 here" in s1 && "MISSING" in s1)
        val second = run("import-updates", full.path, conf.path)
        assertEquals(second.err, 0, second.code)
        assertTrue(second.out, "2 files copied" in second.out && "0 missing" in second.out)
        val s2 = run("update-status", conf.path).out
        assertTrue(s2, "2 of 2 here" in s2 && "MISSING" !in s2)
        assertTrue("last import is the second one", "2 files copied" in s2)
    }

    @Test fun aLaterReleaseMovesOnAndARevocationRemovesTheFile() {
        run("import-updates", release(1, fr, en).path, conf.path)
        val frDigest = File(hubDir, "updates/objects").list()!!.size
        assertEquals(2, frDigest)
        val digestOfFr = java.security.MessageDigest.getInstance("SHA-256").digest(TestPacks.zip("voice-fr-siwis", 1, "voice")).let { TestKeys.hex(it) }
        val r = run("import-updates", release(2, en, revoke = digestOfFr).path, conf.path)
        assertEquals(r.err, 0, r.code)
        assertFalse(File(hubDir, "updates/objects/$digestOfFr").exists())
        val s = run("update-status", conf.path).out
        assertTrue(s, "Revoked: 1" in s && "catalog number 2" in s)
    }

    @Test fun refusalsExplainThemselvesAndChangeNothing() {
        val good = release(4, fr)
        assertEquals(0, run("import-updates", good.path, conf.path).code)
        val before = File(hubDir, "updates/trust-state.bin").readBytes()
        val older = run("import-updates", release(2, fr).path, conf.path)
        assertEquals(2, older.code); assertTrue(older.err, "older" in older.err)
        val otherKey = P256.generate()
        val forged = release(9, fr).also { rel ->
            val dir = folder.newFolder(); File(dir, "f.zip").writeBytes(TestPacks.zip("voice-fr-siwis", 1, "voice"))
            File(dir, "packs.list").writeText("epoch = 1\nsequence = 9\nissued-at = 1\npack file=f.zip\n")
            val other = UpdateRoots.parse("catalog key_id=1 public_key=${TestKeys.hex(TestKeys.compressed(otherKey))}\nrecovery key_id=100 public_key=${TestKeys.hex(TestKeys.compressed(recoveryKey))}\n")
            rel.deleteRecursively()
            CatalogSigner.sign(CatalogSigner.readList(File(dir, "packs.list")), KeyFile.read(TestPem.encryptedPem(otherKey, "pw"), "pw".toCharArray()), other, rel)
        }
        val bad = run("import-updates", forged.path, conf.path)
        assertEquals(2, bad.code); assertTrue(bad.err, "not signed" in bad.err)
        assertEquals(2, run("import-updates", File(folder.root, "nothing").path, conf.path).code)
        assertEquals(2, run("import-updates").code)
        assertEquals(2, run("import-updates", good.path, File(folder.root, "none.conf").path).code)
        assertEquals(2, run("import-updates", good.path, conf.path, "extra").code)
        assertEquals(2, run("update-status", conf.path, "extra").code)
        assertTrue(before.contentEquals(File(hubDir, "updates/trust-state.bin").readBytes()))
    }

    @Test fun aDamagedTrustFileStopsBothCommandsWithAMessage() {
        run("import-updates", release(1, fr).path, conf.path)
        File(hubDir, "updates/trust-state.bin").writeBytes(ByteArray(7))
        val i = run("import-updates", release(2, fr).path, conf.path)
        assertEquals(2, i.code); assertTrue(i.err, "damaged" in i.err)
        val s = run("update-status", conf.path)
        assertEquals(2, s.code); assertTrue(s.err + s.out, "damaged" in s.err + s.out)
    }

    @Test fun twoImportsAtOnceAreNotAllowed() {
        val stick = release(1, fr)
        File(hubDir, "updates").mkdirs()
        FileChannel.open(File(hubDir, "updates/.import.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.tryLock()!!.use {
                val r = run("import-updates", stick.path, conf.path)
                assertEquals(2, r.code); assertTrue(r.err, "another import" in r.err)
            }
        }
        assertEquals(0, run("import-updates", stick.path, conf.path).code)
    }

    @Test fun statusBeforeAnyImportSaysSo() {
        val s = run("update-status", conf.path)
        assertEquals(s.err, 0, s.code)
        assertTrue(s.out, "No updates have been imported" in s.out)
    }

    @Test fun statusCountsFilesThatAreNotInTheCatalogAndShowsRoom() {
        run("import-updates", release(1, fr).path, conf.path)
        File(hubDir, "updates/objects/${"ab".repeat(32)}").writeBytes(ByteArray(1234))
        val report = UpdateStatus.read(hubDir, roots, now = { clock })
        assertEquals(UpdateStatus.Kind.OK, report.kind)
        assertEquals(1L, report.sequence)
        assertEquals(1, report.entries.size); assertTrue(report.entries[0].present)
        assertEquals(1, report.strangers)
        assertTrue(report.objectBytes > 1234)
        assertEquals(UpdateImport.OBJECT_QUOTA, report.quota)
        val text = report.render().joinToString("\n")
        assertTrue(text, "1 file not in this release" in text)
        assertTrue(text, "of 2.0 GB used" in text)
    }

    @Test fun theImportRecordsWhenItWasDone() {
        val dir = folder.newFolder()
        val i = UpdateImport(dir, roots.recoveryKey, roots.catalogKey, now = { 1_700_000_000_000L })
        assertNull(i.lastImport())
        i.import(release(1, fr))
        val last = i.lastImport()!!
        assertEquals(1_700_000_000L, last.seconds); assertEquals(1L, last.sequence); assertEquals(1, last.copied); assertEquals(0, last.missing)
        assertNotNull(UpdateImport(dir, roots.recoveryKey, roots.catalogKey).lastImport())
    }
}

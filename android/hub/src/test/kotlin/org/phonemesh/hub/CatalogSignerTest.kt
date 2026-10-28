package org.phonemesh.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `hub sign-catalog` (U1): list in, `catalog.pmup` and `objects/` out, and every refusal. Keys here are made for the test only. */
class CatalogSignerTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalogKey: KeyPair = P256.generate()
    private val recoveryKey: KeyPair = P256.generate()
    private val roots = UpdateRoots.parse(
        "catalog key_id=1 public_key=${TestKeys.hex(TestKeys.compressed(catalogKey))}\n" +
            "recovery key_id=100 public_key=${TestKeys.hex(TestKeys.compressed(recoveryKey))}\n")
    private fun loaded(k: KeyPair) = KeyFile.read(TestPem.encryptedPem(k, "pw"), "pw".toCharArray())
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = TestKeys.hex(b)
    private val header = "epoch = 1\nsequence = 3\nissued-at = 1790000000\nreview-after = 1800000000\nmin-reader = 2\n"

    private fun zip(
        id: String = "voice-fr-siwis", version: Int = 1, kind: String = "voice", format: Int = 2, extra: Map<String, ByteArray> = emptyMap(),
        bend: (Map<String, ByteArray>, String) -> String = { _, s -> s }, props: Boolean = true, sums: Boolean = true,
    ): ByteArray = TestPacks.zip(id, version, kind, format, extra, bend, props, sums)

    private fun entries(zip: ByteArray): List<Pair<String, Int>> = java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
        generateSequence { z.nextEntry }.filter { !it.isDirectory }.map { it.name to z.readBytes().size }.toList()
    }

    private fun write(dir: File, name: String, bytes: ByteArray): File = File(dir, name).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }

    private fun list(vararg lines: String, head: String = header, dir: File = folder.newFolder()): File =
        File(dir, "packs.list").also { it.writeText(head + lines.joinToString("\n") + "\n") }

    private fun refusedList(file: File, why: String) {
        try { CatalogSigner.readList(file); fail("accepted: $why") } catch (_: CatalogSigner.Refused) { }
    }

    private fun sign(file: File, out: File = File(folder.newFolder(), "release"), key: KeyPair = catalogKey, roots: UpdateRoots = this.roots) =
        CatalogSigner.sign(CatalogSigner.readList(file), loaded(key), roots, out)

    private fun refusedSign(file: File, why: String, out: File = File(folder.newFolder(), "release"), key: KeyPair = catalogKey, roots: UpdateRoots = this.roots) {
        try { sign(file, out, key, roots); fail("accepted: $why") } catch (_: CatalogSigner.Refused) { }
        assertTrue("nothing left behind after: $why", !out.exists() || out.listFiles().orEmpty().isEmpty())
    }

    @Test fun aListOfPacksBecomesASignedCatalogAndItsObjects() {
        val dir = folder.newFolder()
        val fr = zip(); val engine = zip("engine-whisper-tiny", 1, "engine")
        write(dir, "packs/fr.zip", fr); write(dir, "packs/engine.zip", engine)
        val out = File(folder.newFolder(), "release")
        val list = list("pack file=packs/fr.zip", "pack file=packs/engine.zip min-app=2 max-app=9 min-api=28", dir = dir)
        sign(list, out)
        val bytes = File(out, "catalog.pmup").readBytes()
        val env = UpdateMetadata.verify(bytes, mapOf(1 to roots.catalogKey), roots.recoveryKey)!!
        val c = UpdateMetadata.catalog(env.payload)!!
        assertEquals(1L, c.epoch); assertEquals(3L, c.sequence); assertEquals(1_790_000_000L, c.issuedAt)
        assertEquals(1_800_000_000L, c.reviewAfter); assertEquals(2L, c.minReader)
        assertEquals(listOf("engine-whisper-tiny", "voice-fr-siwis"), c.entries.map { it.id })
        val e = c.entries[0]
        assertEquals(UpdateMetadata.ENGINE, e.kind); assertEquals(1L, e.version); assertEquals(2L, e.minApp); assertEquals(9L, e.maxApp); assertEquals(28, e.minApi)
        assertEquals(engine.size.toLong(), e.bytes); assertArrayEquals(sha(engine), e.digest)
        assertEquals(entries(engine).sumOf { it.second.toLong() }, e.expanded)
        assertEquals(entries(engine).size, e.files)
        val v = c.entries[1]
        assertEquals(entries(fr).size, v.files); assertEquals("1", v.label)
        assertEquals(UpdateMetadata.VOICE, v.kind); assertEquals(26, v.minApi); assertEquals(0L, v.maxApp); assertEquals(1L, v.minApp)
        assertArrayEquals(fr, File(out, "objects/${hex(v.digest)}").readBytes())
        assertArrayEquals(engine, File(out, "objects/${hex(e.digest)}").readBytes())
        assertArrayEquals(sha(zipEntry(fr, "SHA256SUMS")), v.sums)
        assertEquals(setOf("catalog.pmup", "objects"), out.list()!!.toSet())
    }

    private fun zipEntry(zip: ByteArray, name: String): ByteArray = java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
        generateSequence { z.nextEntry }.first { it.name == name }.let { z.readBytes() }
    }

    @Test fun theSamePackListGivesTheSamePayload() {
        val dir = folder.newFolder(); write(dir, "a.zip", zip())
        val l = list("pack file=a.zip", dir = dir)
        assertArrayEquals(CatalogSigner.payload(CatalogSigner.readList(l)), CatalogSigner.payload(CatalogSigner.readList(l)))
    }

    /** The payload matches the Python vector byte for byte (that vector was written from the spec tables alone). */
    @Test fun theEncodingMatchesThePythonVector() {
        val v = javaClass.getResourceAsStream("/update/update-vectors.txt")!!.bufferedReader().readLines().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        fun h(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val apk = UpdateMetadata.Entry(1, "org.phonemesh.app", "0.1.1", "arm64-v8a", 2, 1, 0, 26, 0, 16_600_000, h("apkDigest"), 0, 0, ByteArray(32), h("certDigest"))
        val voice = UpdateMetadata.Entry(3, "phonemesh-voice-fr-siwis", "1", "", 1, 2, 0, 26, 2, 6_334_594, h("voiceDigest"), 6_400_000, 4, h("sumsDigest"), ByteArray(32))
        val payload = CatalogSigner.encode(1, 7, 1_790_000_000, 1_800_000_000, 2, listOf(voice, apk), listOf(h("revoked")))
        assertArrayEquals(h("catalogPayload"), payload)
    }

    @Test fun theCatalogKeyMustBeTheOneInThePublicKeyFile() {
        val dir = folder.newFolder(); write(dir, "a.zip", zip())
        val l = list("pack file=a.zip", dir = dir)
        refusedSign(l, "the recovery key", key = recoveryKey)
        refusedSign(l, "a stranger's key", key = P256.generate())
        // The reason is the key check itself, not only the read-back that follows writing.
        fun why(k: KeyPair) = try { sign(l, File(folder.newFolder(), "r"), k); "" } catch (e: CatalogSigner.Refused) { e.message.orEmpty() }
        assertTrue(why(recoveryKey), "recovery key" in why(recoveryKey))
        assertTrue(why(P256.generate()), "not the catalog key" in why(P256.generate()))
        val sameKeyBoth = UpdateRoots.parse("catalog key_id=1 public_key=${TestKeys.hex(TestKeys.compressed(recoveryKey))}\nrecovery key_id=100 public_key=${TestKeys.hex(TestKeys.compressed(catalogKey))}\n")
        refusedSign(l, "roots swapped", roots = sameKeyBoth)
    }

    @Test fun badListsAreRefused() {
        val dir = folder.newFolder(); write(dir, "a.zip", zip()); write(dir, "e.zip", zip("engine-whisper-tiny", 1, "engine"))
        fun l(vararg lines: String, head: String = header) = list(*lines, head = head, dir = dir)
        refusedList(l("pack file=a.zip", head = header.replace("epoch = 1\n", "")), "no epoch")
        refusedList(l("pack file=a.zip", head = header.replace("sequence = 3\n", "")), "no sequence")
        refusedList(l("pack file=a.zip", head = header.replace("issued-at = 1790000000\n", "")), "no issued-at")
        refusedList(l("pack file=a.zip", head = header.replace("epoch = 1", "epoch = 0")), "epoch 0")
        refusedList(l("pack file=a.zip", head = header.replace("sequence = 3", "sequence = -1")), "negative sequence")
        refusedList(l("pack file=a.zip", head = header.replace("sequence = 3", "sequence = three")), "text for a number")
        refusedList(l("pack file=a.zip", head = header + "bogus = 1\n"), "unknown setting")
        refusedList(l("pack file=a.zip", head = header + "epoch = 2\n"), "repeated setting")
        refusedList(l("pack file=a.zip", "pack file=a.zip"), "the same pack twice")
        refusedList(l("pack file=missing.zip"), "missing file")
        refusedList(l("pack file=."), "a folder")
        refusedList(l("pack file=a.zip bogus=1"), "unknown field")
        refusedList(l("pack"), "no file")
        refusedList(l("pack file=a.zip min-app=-1"), "negative min-app")
        refusedList(l("pack file=a.zip min-app=5 max-app=3"), "max below min")
        refusedList(l("pack file=a.zip min-api=0"), "min-api 0")
        refusedList(l("pack file=a.zip id=voice-xx"), "asserted id differs from pack.properties")
        refusedList(l("pack file=a.zip version=2"), "asserted version differs")
        refusedList(l("pack file=a.zip label="), "empty label")
        write(dir.parentFile, "outside.zip", zip())
        refusedList(l("pack file=../outside.zip"), "path outside the list's folder")
        write(dir, "sub/inner.zip", zip()); assertEquals(1, CatalogSigner.readList(l("pack file=sub/inner.zip")).items.size)
        refusedList(l("pack file=/etc/hosts"), "absolute path")
        refusedList(l("sack file=a.zip"), "unknown entry kind")
        refusedList(l("revoke = zz"), "bad revoke")
        refusedList(l("pack file=a.zip", head = header + "revoke = ${"ab".repeat(31)}\n"), "short revoke")
        refusedList(l("pack file=a.zip", head = header + "revoke = ${"ab".repeat(32)}\nrevoke = ${"ab".repeat(32)}\n"), "duplicate revoke")
        refusedList(l("apk file=a.zip version=2 abi=arm64-v8a label=0.1.2 cert=${"ab".repeat(31)}"), "short certificate digest")
        refusedList(l("apk file=a.zip version=2 abi=mips label=0.1.2 cert=${"ab".repeat(32)}"), "unknown ABI")
        refusedList(l("apk file=a.zip version=0 abi=arm64-v8a label=0.1.2 cert=${"ab".repeat(32)}"), "version 0")
        refusedList(l("apk file=a.zip version=2 abi=arm64-v8a label=0.1.2 cert=${"00".repeat(32)}"), "zero certificate")
        refusedList(l("apk file=a.zip version=2 abi=arm64-v8a label=0.1.2 cert=${"ab".repeat(32)} id=org.other.app"), "another app id")
        refusedList(l("pack file=a.zip", "apk file=a.zip version=2 abi=arm64-v8a label=<b> cert=${"ab".repeat(32)}"), "markup in a label")
        (1..129).forEach { write(dir, "v$it.zip", zip(version = it)) }
        refusedList(l(*(1..129).map { "pack file=v$it.zip" }.toTypedArray()), "too many entries")
        assertEquals(128, CatalogSigner.readList(l(*(1..128).map { "pack file=v$it.zip" }.toTypedArray())).items.size)
        refusedList(l("pack file=a.zip", head = header + (1..257).joinToString("") { "revoke = ${"%064x".format(it)}\n" }), "too many revocations")
    }

    @Test fun twoVersionsOfOnePackAreFineButNotTwoOfTheSameVersion() {
        val dir = folder.newFolder(); write(dir, "a.zip", zip(version = 1)); write(dir, "b.zip", zip(version = 2)); write(dir, "c.zip", zip(version = 1, extra = mapOf("tts/more" to ByteArray(4))))
        assertEquals(2, CatalogSigner.readList(list("pack file=a.zip", "pack file=b.zip", dir = dir)).items.size)
        refusedList(list("pack file=a.zip", "pack file=c.zip", dir = dir), "the same pack and version with other bytes")
    }

    @Test fun badPackArchivesAreRefused() {
        val dir = folder.newFolder()
        fun refusedZip(bytes: ByteArray, why: String) { write(dir, "x.zip", bytes); refusedList(list("pack file=x.zip", dir = dir), why) }
        refusedZip(ByteArray(100), "not a zip")
        refusedZip(zip(props = false), "no pack.properties")
        refusedZip(zip(sums = false), "no SHA256SUMS")
        refusedZip(zip(format = 1), "format 1")
        refusedZip(zip(kind = "other"), "unknown kind")
        refusedZip(zip(id = "x"), "id too short")
        refusedZip(zip(id = "bad/id"), "id with a slash")
        refusedZip(zip(version = 0), "version 0")
        refusedZip(zip(bend = { _, s -> s.replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64)) }), "a wrong digest in SHA256SUMS")
        refusedZip(zip(bend = { _, s -> s.lines().drop(1).joinToString("\n") }), "a file missing from SHA256SUMS")
        refusedZip(zip(bend = { _, s -> s + "${"1".repeat(64)}  tts/ghost\n" }), "a ghost file in SHA256SUMS")
        refusedZip(zip(extra = mapOf("../evil" to ByteArray(3))), "a path going up")
        refusedZip(zip(extra = mapOf("/abs" to ByteArray(3))), "an absolute path")
        refusedZip(zip(extra = mapOf("tts/a\\b" to ByteArray(3))), "a backslash")
        refusedZip(zip(extra = mapOf("whisper/x" to ByteArray(3))), "a file under the other kind's folder")
        refusedZip(zip(extra = mapOf("run.sh" to ByteArray(3))), "an unexpected root file")
        refusedZip(zip().let { z -> z.copyOf(z.size / 2) }, "a cut-off archive")
        refusedZip(zip(kind = "engine").let { z -> zip(id = "engine-whisper-tiny", kind = "engine", props = true).copyOf(10) }, "a stub")
        refusedZip(zip(extra = mapOf("tts/model.onnx" to ByteArray(0))), "an empty model file")
    }

    @Test fun limitsAreEnforcedWhileReading() {
        val dir = folder.newFolder(); write(dir, "a.zip", zip())
        val l = list("pack file=a.zip", dir = dir)
        try { CatalogSigner.readList(l, CatalogSigner.Limits(maxPackBytes = 100)); fail() } catch (_: CatalogSigner.Refused) { }
        try { CatalogSigner.readList(l, CatalogSigner.Limits(maxExpanded = 100)); fail() } catch (_: CatalogSigner.Refused) { }
        try { CatalogSigner.readList(l, CatalogSigner.Limits(maxFiles = 2)); fail() } catch (_: CatalogSigner.Refused) { }
        assertNotNull(CatalogSigner.readList(l))
    }

    @Test fun apkEntriesCarryTheirDeclaredFields() {
        val dir = folder.newFolder(); val apk = ByteArray(5_000) { it.toByte() }; write(dir, "app.apk", apk)
        val out = File(folder.newFolder(), "release")
        sign(list("apk file=app.apk version=3 abi=arm64-v8a label=0.1.2 cert=${"ab".repeat(32)} min-api=26", dir = dir), out)
        val c = UpdateMetadata.catalog(UpdateMetadata.verify(File(out, "catalog.pmup").readBytes(), mapOf(1 to roots.catalogKey), null)!!.payload)!!
        val e = c.entries.single()
        assertEquals(UpdateMetadata.APK, e.kind); assertEquals("org.phonemesh.app", e.id); assertEquals(3L, e.version); assertEquals("arm64-v8a", e.abi)
        assertArrayEquals(sha(apk), e.digest); assertArrayEquals(ByteArray(32) { 0xab.toByte() }, e.cert)
        assertEquals(0, e.files)
    }

    @Test fun anExistingOrDirtyOutputFolderIsRefusedAndNothingIsLeftBehind() {
        val dir = folder.newFolder(); write(dir, "a.zip", zip())
        val l = list("pack file=a.zip", dir = dir)
        val busy = folder.newFolder(); File(busy, "catalog.pmup").writeText("old")
        try { sign(l, busy); fail("accepted a folder holding a catalog") } catch (_: CatalogSigner.Refused) { }
        assertEquals("old", File(busy, "catalog.pmup").readText())
        val file = folder.newFile("afile")
        try { sign(l, file); fail() } catch (_: CatalogSigner.Refused) { }
        val nonEmpty = folder.newFolder(); File(nonEmpty, "other").writeText("x")
        try { sign(l, nonEmpty); fail() } catch (_: CatalogSigner.Refused) { }
        assertEquals(listOf("other"), nonEmpty.list()!!.toList())
    }

    @Test fun aRevocationIsSortedAndSigned() {
        val dir = folder.newFolder(); write(dir, "a.zip", zip())
        val out = File(folder.newFolder(), "release")
        sign(list("pack file=a.zip", head = header + "revoke = ${"ff".repeat(32)}\nrevoke = ${"01".repeat(32)}\n", dir = dir), out)
        val c = UpdateMetadata.catalog(UpdateMetadata.verify(File(out, "catalog.pmup").readBytes(), mapOf(1 to roots.catalogKey), null)!!.payload)!!
        assertEquals(listOf("01".repeat(32), "ff".repeat(32)), c.revoked.map { hex(it) })
    }

    @Test fun aLinkedObjectFileIsNotFollowed() {
        val dir = folder.newFolder(); val real = write(folder.newFolder(), "real.zip", zip())
        Files.createSymbolicLink(File(dir, "a.zip").toPath(), real.toPath())
        refusedList(list("pack file=a.zip", dir = dir), "a symbolic link")
    }

    @Test fun anEntryCannotBeSignedWithTheRecoveryKeyId() {
        val dir = folder.newFolder(); write(dir, "a.zip", zip())
        try { CatalogSigner.sign(CatalogSigner.readList(list("pack file=a.zip", dir = dir)), loaded(catalogKey), roots, File(folder.newFolder(), "r"), keyId = 100); fail() } catch (_: CatalogSigner.Refused) { }
        try { CatalogSigner.sign(CatalogSigner.readList(list("pack file=a.zip", dir = dir)), loaded(catalogKey), roots, File(folder.newFolder(), "r"), keyId = 7); fail() } catch (_: CatalogSigner.Refused) { }
        assertFalse(roots.catalogKeyId == UpdateMetadata.RECOVERY_KEY_ID)
    }
}

package org.phonemesh.hub

import org.phonemesh.core.PackFormat
import org.phonemesh.core.PackRejected
import org.phonemesh.core.PackStore
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The owner's offline signing tool (`phonemesh-hub sign-catalog`; hub updates spec 5.2 and 5.3). It reads a pack list,
 * inspects every archive itself (sizes, file count, `SHA256SUMS` digest, and a trial install through the phone's own
 * [PackStore] rules), and writes `catalog.pmup` plus `objects/<sha256>` for a USB stick or a hub import. It refuses
 * anything the phone would refuse, a key that is not the catalog key of the public key file, and an output folder that
 * is not new. Nothing is written until the key and the list pass; if writing fails, the folder is removed again.
 *
 * Pack list (`#` starts a comment; paths are relative to the list's folder, never outside it, never a link):
 * ```
 * epoch = 1            sequence = 1       issued-at = 1790000000   (all three needed; seconds since 1970 UTC)
 * review-after = ...   min-reader = 1     (optional: default issued-at + 180 days, and 1)
 * revoke = <64 hex>    (optional, repeatable: an artifact digest phones must stop using)
 * pack file=packs/x.zip [min-app=1] [max-app=0] [min-api=26] [label=1] [id=..] [version=..]
 * apk  file=app.apk version=3 abi=arm64-v8a label=0.1.2 cert=<64 hex> [min-app=1] [max-app=0] [min-api=26]
 * ```
 * A pack's id, version and kind come from the archive's own `pack.properties`; `id=` and `version=` only assert them.
 */
object CatalogSigner {
    class Refused(message: String) : Exception(message)

    class Limits(
        val maxPackBytes: Long = UpdateMetadata.MAX_PACK_BYTES, val maxApkBytes: Long = UpdateMetadata.MAX_APK_BYTES,
        val maxExpanded: Long = UpdateMetadata.MAX_EXPANDED, val maxFiles: Int = UpdateMetadata.MAX_FILES,
        val maxFile: Long = 256L * 1024 * 1024,
    )

    class Item(val entry: UpdateMetadata.Entry, val source: File)

    class CatalogList(
        val epoch: Long, val sequence: Long, val issuedAt: Long, val reviewAfter: Long, val minReader: Long,
        val items: List<Item>, val revoked: List<ByteArray>,
    )

    class Signed(val catalog: File, val sequence: Long, val objects: Int)

    private const val DAY = 86_400L
    private val SETTINGS = setOf("epoch", "sequence", "issued-at", "review-after", "min-reader", "revoke")
    private val NUMBER = Regex("[0-9]{1,18}")
    private val HEX32 = Regex("[0-9a-fA-F]{64}")
    private val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    // ---- the list ----

    fun readList(list: File, limits: Limits = Limits()): CatalogList {
        if (!Files.isRegularFile(list.toPath(), LinkOption.NOFOLLOW_LINKS)) throw Refused("${list.name} is not a file")
        if (list.length() > 256 * 1024) throw Refused("${list.name} is too large for a pack list")
        val base = list.absoluteFile.parentFile.toPath().toRealPath()
        val settings = HashMap<String, String>()
        val revoked = ArrayList<ByteArray>()
        val lines = ArrayList<Pair<Int, List<String>>>()
        list.readLines().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val where = "line ${index + 1}"
            val word = line.substringBefore(' ').substringBefore('\t')
            if (word == "pack" || word == "apk") { lines += index + 1 to line.split(Regex("\\s+")); return@forEachIndexed }
            val m = Regex("([a-z-]+)\\s*=\\s*(\\S*)").matchEntire(line) ?: throw Refused("$where: not understood")
            val name = m.groupValues[1]; val value = m.groupValues[2]
            if (name !in SETTINGS) throw Refused("$where: unknown setting '$name'")
            if (name == "revoke") {
                if (!HEX32.matches(value)) throw Refused("$where: a revoked digest is 64 hex digits")
                val d = hexBytes(value)
                if (revoked.any { it.contentEquals(d) }) throw Refused("$where: the same digest is revoked twice")
                revoked += d
            } else if (settings.put(name, value) != null) throw Refused("$where: '$name' is set twice")
        }
        fun number(name: String, default: Long?): Long {
            val v = settings[name] ?: return default ?: throw Refused("'$name' is needed")
            if (!NUMBER.matches(v)) throw Refused("'$name' must be a whole number, not '$v'")
            return v.toLong()
        }
        val epoch = number("epoch", null); val sequence = number("sequence", null); val issued = number("issued-at", null)
        val review = number("review-after", issued + 180 * DAY); val minReader = number("min-reader", 1)
        if (epoch < 1) throw Refused("'epoch' starts at 1")
        if (revoked.size > UpdateMetadata.MAX_REVOKED) throw Refused("at most ${UpdateMetadata.MAX_REVOKED} revoked digests")
        if (lines.size > UpdateMetadata.MAX_ENTRIES) throw Refused("at most ${UpdateMetadata.MAX_ENTRIES} entries")
        val items = lines.map { (n, tokens) -> item(n, tokens, base, limits) }
        val sorted = items.sortedWith(compareBy({ it.entry.kind }, { it.entry.id }, { it.entry.version }, { it.entry.abi }))
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1].entry; val b = sorted[i].entry
            if (a.kind == b.kind && a.id == b.id && a.version == b.version && a.abi == b.abi) throw Refused("${a.id} version ${a.version} is listed twice")
        }
        val result = CatalogList(epoch, sequence, issued, review, minReader, sorted, revoked.sortedWith { x, y -> compare(x, y) })
        // The phone's own parser has the last word on the whole payload (labels, sizes, ordering).
        if (UpdateMetadata.catalog(payload(result)) == null) throw Refused("the catalog would not pass the phone's own checks (look at labels, sizes and versions)")
        return result
    }

    private fun item(line: Int, tokens: List<String>, base: java.nio.file.Path, limits: Limits): Item {
        val where = "line $line"
        val isApk = tokens[0] == "apk"
        val fields = HashMap<String, String>()
        for (t in tokens.drop(1)) {
            val k = t.substringBefore('=', ""); val v = t.substringAfter('=', "")
            if ('=' !in t || k.isEmpty()) throw Refused("$where: '$t' is not name=value")
            val allowed = setOf("file", "min-app", "max-app", "min-api", "label") + if (isApk) setOf("version", "abi", "cert", "id") else setOf("id", "version")
            if (k !in allowed) throw Refused("$where: unknown field '$k'")
            if (fields.put(k, v) != null) throw Refused("$where: '$k' is given twice")
        }
        val fileName = fields["file"]?.takeIf { it.isNotEmpty() } ?: throw Refused("$where: file= is needed")
        val resolved = base.resolve(fileName).normalize()
        if (fileName.startsWith("/") || !resolved.startsWith(base) || resolved == base) throw Refused("$where: $fileName is outside the list's folder")
        val source = resolved.toFile()
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) throw Refused("$where: $fileName is not a plain file")
        fun num(name: String, default: Long): Long {
            val v = fields[name] ?: return default
            if (!NUMBER.matches(v)) throw Refused("$where: $name must be a whole number")
            return v.toLong()
        }
        val minApp = num("min-app", 1); val maxApp = num("max-app", 0); val minApi = num("min-api", 26)
        if (maxApp != 0L && maxApp < minApp) throw Refused("$where: max-app is below min-app")
        if (minApi !in 1..1000) throw Refused("$where: min-api is 1 to 1000")
        val size = source.length()
        if (size <= 0) throw Refused("$where: $fileName is empty")
        if (isApk) {
            if (size > limits.maxApkBytes) throw Refused("$where: $fileName is larger than an app may be")
            val version = num("version", 0); if (version <= 0) throw Refused("$where: version= is needed (a positive number)")
            val abi = fields["abi"] ?: throw Refused("$where: abi= is needed")
            if (abi !in ABIS) throw Refused("$where: abi must be one of $ABIS")
            val cert = fields["cert"]?.takeIf { HEX32.matches(it) } ?: throw Refused("$where: cert= is the 64-digit certificate digest")
            val certBytes = hexBytes(cert)
            if (certBytes.all { it == 0.toByte() }) throw Refused("$where: the certificate digest is all zeros")
            val id = fields["id"] ?: UpdateMetadata.APP_ID
            if (id != UpdateMetadata.APP_ID) throw Refused("$where: an app entry is for ${UpdateMetadata.APP_ID}")
            val label = fields["label"] ?: version.toString()
            return Item(UpdateMetadata.Entry(UpdateMetadata.APK, id, label, abi, version, minApp, maxApp, minApi.toInt(), 0, size, sha256File(source, size), 0, 0, ByteArray(32), certBytes), source)
        }
        if (size > limits.maxPackBytes) throw Refused("$where: $fileName is larger than a pack may be")
        val shape = inspect(source, limits, where)
        val installed = trialInstall(source, limits, where)
        fields["id"]?.let { if (it != installed.id) throw Refused("$where: id=$it but the archive says ${installed.id}") }
        fields["version"]?.let { if (it != installed.version.toString()) throw Refused("$where: version=$it but the archive says ${installed.version}") }
        val label = fields["label"] ?: installed.version.toString()
        val kind = if (installed.kind == org.phonemesh.core.PackKind.ENGINE) UpdateMetadata.ENGINE else UpdateMetadata.VOICE
        return Item(UpdateMetadata.Entry(kind, installed.id, label, "", installed.version.toLong(), minApp, maxApp, minApi.toInt(), 2, size, sha256File(source, size), shape.expanded, shape.files, shape.sums, ByteArray(32)), source)
    }

    private class Shape(val expanded: Long, val files: Int, val sums: ByteArray)

    /** Reads an archive once, bounded: counts regular files and bytes and hashes `SHA256SUMS`. Names are the phone's rules. */
    private fun inspect(file: File, limits: Limits, where: String): Shape {
        var files = 0; var total = 0L; var sums: ByteArray? = null
        val seen = HashSet<String>()
        try {
            ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.isEmpty() || name.length > 512 || name.startsWith('/') || '\\' in name || '\u0000' in name ||
                        name.split('/').size > 16 || name.split('/').any { it == ".." || it == "." } ||
                        !PackFormat.plausible(name)) throw Refused("$where: ${file.name} holds the unexpected path '${name.take(60)}'")
                    if (!seen.add(name.trimEnd('/'))) throw Refused("$where: ${file.name} holds '$name' twice")
                    if (entry.isDirectory) continue
                    if (++files > limits.maxFiles) throw Refused("$where: ${file.name} holds too many files")
                    val md = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        size += n; total += n
                        if (size > limits.maxFile || total > limits.maxExpanded) throw Refused("$where: ${file.name} expands to more than a phone accepts")
                        md.update(buffer, 0, n)
                    }
                    if (name == "SHA256SUMS") {
                        // The pinned manifest identity is the digest of this file's bytes.
                        sums = md.digest()
                    }
                }
            }
        } catch (e: Refused) { throw e } catch (e: java.io.IOException) { throw Refused("$where: ${file.name} is not a readable archive") }
        if (sums == null) throw Refused("$where: ${file.name} has no SHA256SUMS")
        if (files == 0) throw Refused("$where: ${file.name} is empty")
        return Shape(total, files, sums)
    }

    /** The phone's own acceptance rules, on a scratch copy: what [PackStore] would refuse is refused here too. */
    private fun trialInstall(file: File, limits: Limits, where: String): org.phonemesh.core.PackInfo {
        val scratch = Files.createTempDirectory("phonemesh-sign-").toFile()
        try {
            return PackStore(scratch, emptyList(), allowUnknown = true, maxBytes = limits.maxExpanded).install(file.inputStream().buffered())
        } catch (e: PackRejected) {
            throw Refused("$where: ${file.name} is not a pack a phone accepts (${e.reason})")
        } catch (e: java.io.IOException) {
            throw Refused("$where: ${file.name} could not be read")
        } finally { scratch.deleteRecursively() }
    }

    // ---- the payload ----

    fun payload(list: CatalogList): ByteArray = encode(list.epoch, list.sequence, list.issuedAt, list.reviewAfter, list.minReader, list.items.map { it.entry }, list.revoked)

    /** The spec 5.2 catalog payload: 43-byte header, entries in `(kind, id, version, abi)` order, then revoked digests sorted. */
    fun encode(
        epoch: Long, sequence: Long, issuedAt: Long, reviewAfter: Long, minReader: Long,
        entries: List<UpdateMetadata.Entry>, revoked: List<ByteArray>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(1)
        out.write(ByteBuffer.allocate(42).putLong(epoch).putLong(sequence).putLong(issuedAt).putLong(reviewAfter).putLong(minReader).putShort(entries.size.toShort()).array())
        for (e in entries.sortedWith(compareBy({ it.kind }, { it.id }, { it.version }, { it.abi }))) {
            val id = e.id.toByteArray(Charsets.UTF_8); val label = e.label.toByteArray(Charsets.UTF_8); val abi = e.abi.toByteArray(Charsets.UTF_8)
            if (id.size > 255 || label.size > 255 || abi.size > 255) throw Refused("a name is too long")
            out.write(byteArrayOf(e.kind.toByte(), id.size.toByte(), label.size.toByte(), abi.size.toByte()))
            out.write(ByteBuffer.allocate(8 + 8 + 8 + 4 + 2 + 8).putLong(e.version).putLong(e.minApp).putLong(e.maxApp).putInt(e.minApi).putShort(e.packFormat.toShort()).putLong(e.bytes).array())
            out.write(e.digest); out.write(ByteBuffer.allocate(12).putLong(e.expanded).putInt(e.files).array())
            out.write(e.sums); out.write(e.cert); out.write(id); out.write(label); out.write(abi)
        }
        out.write(ByteBuffer.allocate(2).putShort(revoked.size.toShort()).array())
        for (d in revoked.sortedWith { x, y -> compare(x, y) }) out.write(d)
        return out.toByteArray()
    }

    // ---- signing ----

    fun sign(list: CatalogList, key: KeyFile.Loaded, roots: UpdateRoots, outDir: File, keyId: Int = roots.catalogKeyId): Signed {
        if (keyId == UpdateMetadata.RECOVERY_KEY_ID || keyId != roots.catalogKeyId) throw Refused("key id $keyId is not the catalog key id (${roots.catalogKeyId})")
        if (!key.publicKey.contentEquals(roots.catalogKey)) {
            throw Refused(if (key.publicKey.contentEquals(roots.recoveryKey)) "this is the recovery key; a catalog is signed by the catalog key"
            else "this key is not the catalog key named in the public key file")
        }
        val payload = payload(list)
        if (8 + 12 + payload.size + 64 > UpdateMetadata.MAX_ENVELOPE) throw Refused("the catalog is larger than ${UpdateMetadata.MAX_ENVELOPE} bytes")
        val existed = outDir.exists()
        if (existed && !outDir.isDirectory) throw Refused("${outDir.name} is not a folder")
        if (existed && outDir.list().orEmpty().isNotEmpty()) throw Refused("${outDir.name} is not empty; use a new folder for each release")
        val envelope = UpdateMetadata.sign(UpdateMetadata.CATALOG, keyId, payload, key.privateKey)
        try {
            val objects = File(outDir, "objects")
            if (!objects.mkdirs()) throw Refused("cannot create ${objects.path}")
            var count = 0
            for (item in list.items.distinctBy { hex(it.entry.digest) }) {
                copyChecked(item, File(objects, hex(item.entry.digest)))
                count++
            }
            // The catalog goes last: a folder with a catalog.pmup is a complete release.
            val tmp = File(outDir, ".catalog.pmup.tmp")
            tmp.outputStream().use { it.write(envelope); it.fd.sync() }
            Files.move(tmp.toPath(), File(outDir, "catalog.pmup").toPath(), StandardCopyOption.ATOMIC_MOVE)
            // Read back what was written with the same verifier phones use.
            val back = File(outDir, "catalog.pmup").readBytes()
            val env = UpdateMetadata.verify(back, mapOf(keyId to roots.catalogKey), null) ?: throw Refused("the written catalog does not verify")
            val parsed = UpdateMetadata.catalog(env.payload) ?: throw Refused("the written catalog does not parse")
            if (parsed.entries.size != list.items.size || !env.payload.contentEquals(payload)) throw Refused("the written catalog differs from the list")
            return Signed(File(outDir, "catalog.pmup"), list.sequence, count)
        } catch (e: Exception) {
            if (existed) outDir.listFiles().orEmpty().forEach { it.deleteRecursively() } else outDir.deleteRecursively()
            throw if (e is Refused) e else Refused("could not write the release: ${e.message}")
        }
    }

    private fun copyChecked(item: Item, target: File) {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        item.source.inputStream().use { input -> target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                total += n; md.update(buffer, 0, n); out.write(buffer, 0, n)
            }
            out.fd.sync()
        } }
        if (total != item.entry.bytes || !md.digest().contentEquals(item.entry.digest)) throw Refused("${item.source.name} changed while it was being copied")
    }

    private fun sha256File(file: File, expected: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input: InputStream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; md.update(buffer, 0, n) }
        }
        if (total != expected) throw Refused("${file.name} changed while it was being read")
        return md.digest()
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) { val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF); if (c != 0) return c }
        return 0
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hexBytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

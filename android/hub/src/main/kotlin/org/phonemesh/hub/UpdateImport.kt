package org.phonemesh.hub

import org.phonemesh.core.update.UpdateMetadata
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Updates from a USB stick (hub updates spec 5.4; decision 45: USB import first). The operator names a folder holding
 * `catalog.pmup`, optionally `recovery.pmup`, and `objects/<lowercase sha256>`. Everything there is treated as
 * hostile: only those names are read, links and other files are ignored, every object is hashed while it is copied
 * and must match its signed size and digest, and nothing is published before it checks out. The catalog is accepted
 * only when signed by the current catalog key and newer than the one held (same epoch and sequence: only the same
 * bytes). A recovery policy, signed only by the recovery key, can replace the catalog key first. The hub serves
 * nothing new yet; this fills `updates/` for the phone routes that come next.
 */
class UpdateImport(private val dir: File, private val recoveryKey: ByteArray, initialCatalogKey: ByteArray, initialKeyId: Int = 1) {
    private val updates = File(dir, "updates")
    private val objects = File(updates, "objects")
    private val partial = File(updates, "partial")
    private val stateFile = File(updates, "trust-state.bin")

    /** Trust state: epoch, catalog key id and key, highest catalog sequence, and the accepted catalog's SHA-256. */
    class State(val epoch: Long, val keyId: Int, key: ByteArray, val sequence: Long, catalogDigest: ByteArray) {
        val key: ByteArray = key.copyOf()
        val catalogDigest: ByteArray = catalogDigest.copyOf()
        fun encode(): ByteArray = java.nio.ByteBuffer.allocate(8 + 2 + 65 + 8 + 32).putLong(epoch).putShort(keyId.toShort())
            .put(key).putLong(sequence).put(catalogDigest).array()
        companion object {
            fun decode(b: ByteArray): State? = if (b.size != 115) null else java.nio.ByteBuffer.wrap(b).let {
                State(it.long, it.short.toInt() and 0xFFFF, ByteArray(65).also(it::get), it.long, ByteArray(32).also(it::get))
            }
        }
    }

    sealed class Result {
        class Imported(val sequence: Long, val objects: Int, val missing: Int) : Result()
        object AlreadyHave : Result()
        class Refused(val why: String) : Result()
    }

    var state: State = loadState() ?: State(0, initialKeyId, initialCatalogKey, -1, ByteArray(32))
        private set

    private fun loadState(): State? {
        if (!stateFile.isFile) return null
        // A damaged trust file stops updates; it is never quietly reset to the baseline (spec 5.3).
        return State.decode(stateFile.readBytes()) ?: throw IOException("updates/trust-state.bin is damaged")
    }

    fun import(stick: File): Result {
        val root = stick.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return Result.Refused("not a folder")
        val recoveryFile = regular(stick, "recovery.pmup")
        var next = state
        if (recoveryFile != null) {
            val env = UpdateMetadata.verify(bounded(recoveryFile) ?: return Result.Refused("recovery.pmup is too large"), mapOf(100 to recoveryKey))
                ?: return Result.Refused("recovery.pmup is not signed by the recovery key")
            if (env.kind != UpdateMetadata.RECOVERY) return Result.Refused("recovery.pmup is not a recovery policy")
            val r = UpdateMetadata.recovery(env.payload) ?: return Result.Refused("recovery.pmup is damaged")
            if (r.catalogKey.contentEquals(recoveryKey)) return Result.Refused("the new catalog key equals the recovery key")
            if (r.epoch > next.epoch) {
                if (r.catalogKey.contentEquals(next.key)) return Result.Refused("the new catalog key is the old one")
                // A new epoch starts its own numbering, at the policy's minimum.
                next = State(r.epoch, r.catalogKeyId, r.catalogKey, r.minSequence - 1, ByteArray(32))
            }
        }
        val catalogFile = regular(stick, "catalog.pmup") ?: return Result.Refused("no catalog.pmup")
        val bytes = bounded(catalogFile) ?: return Result.Refused("catalog.pmup is too large")
        val env = UpdateMetadata.verify(bytes, mapOf(next.keyId to next.key))
            ?: return Result.Refused("catalog.pmup is not signed by the current catalog key")
        if (env.kind != UpdateMetadata.CATALOG) return Result.Refused("catalog.pmup is not a catalog")
        val catalog = UpdateMetadata.catalog(env.payload) ?: return Result.Refused("catalog.pmup is damaged")
        // Before any catalog (epoch 0) the shipped key starts epoch 1; after that the catalog's epoch is the held one.
        if (catalog.epoch != (if (next.epoch == 0L) 1L else next.epoch)) return Result.Refused("the catalog is for another trust epoch")
        val digest = sha256(env.payload)
        if (catalog.sequence < next.sequence) return Result.Refused("an older catalog than the one this hub has")
        if (catalog.sequence == next.sequence) {
            return if (digest.contentEquals(next.catalogDigest)) Result.AlreadyHave
            else Result.Refused("a different catalog with the same number")
        }
        // Objects: only signed digests, hashed while copied into partial/, then moved into objects/.
        objects.mkdirs(); partial.mkdirs()
        var copied = 0
        var missing = 0
        for (entry in catalog.entries) {
            val name = hex(entry.digest)
            if (File(objects, name).isFile) continue
            if (catalog.revoked.any { it.contentEquals(entry.digest) }) continue
            val source = File(File(stick, "objects"), name)
            if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) { missing++; continue }
            if (source.length() != entry.bytes) return Result.Refused("objects/$name has the wrong size")
            val part = File(partial, "$name.part")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            source.inputStream().use { input -> part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > entry.bytes) break
                    md.update(buffer, 0, n); out.write(buffer, 0, n)
                }
                out.fd.sync()
            } }
            if (total != entry.bytes || !md.digest().contentEquals(entry.digest)) {
                part.delete(); return Result.Refused("objects/$name does not match its signed digest")
            }
            Files.move(part.toPath(), File(objects, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
            copied++
        }
        // Publish: the catalog by its digest, then the trust state (new sequence) last, each by atomic rename.
        val catalogs = File(updates, "catalogs").apply { mkdirs() }
        write(File(catalogs, "${hex(digest)}.pmup"), bytes)
        recoveryFile?.let { write(File(File(updates, "policies").apply { mkdirs() }, "${hex(sha256(it.readBytes()))}.pmup"), it.readBytes()) }
        val accepted = State(catalog.epoch, next.keyId, next.key, catalog.sequence, digest)
        write(stateFile, accepted.encode())
        state = accepted
        return Result.Imported(catalog.sequence, copied, missing)
    }

    /** A plain file directly in [dir] named [name]; never a link, a folder or anything outside. */
    private fun regular(dir: File, name: String): File? =
        File(dir, name).takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    private fun bounded(f: File): ByteArray? = if (f.length() > UpdateMetadata.MAX_ENVELOPE) null else f.readBytes()

    private fun write(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

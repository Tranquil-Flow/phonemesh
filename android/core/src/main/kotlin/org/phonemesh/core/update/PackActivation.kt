package org.phonemesh.core.update

import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackRejected
import org.phonemesh.core.PackStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Puts a downloaded, whole pack file into use through [PackStore] (hub updates spec 5.5): the offer must still be authorised
 * (not revoked or dropped while it downloaded), the file is hashed again against the signed digest, the pack is checked
 * against the signed manifest digest (a pin built from the signed entry, never from the file), its file count and expanded
 * size must equal the signed ones, and it must open. The old copy stays on disk, and is what the person keeps using, until
 * the new one has opened and its version floor is saved; then older copies are removed. Any earlier failure removes only the
 * new copy.
 */
class PackActivation(private val directory: File, private val compiled: List<CatalogEntry> = GeneratedPackCatalog.entries) {
    sealed class Result {
        class Activated(val info: PackInfo) : Result()
        object NotAuthorized : Result()
        object WrongFile : Result()
        class Rejected(val why: String) : Result()
        object OpenFailed : Result()
        object StateNotSaved : Result()
    }

    fun activate(trust: UpdateTrust, offer: UpdateTrust.Offer, file: File, open: (PackInfo) -> Boolean = { true }): Result {
        val e = offer.entry
        if (!trust.stillAuthorized(offer)) return Result.NotAuthorized
        if (!file.isFile || file.length() != e.bytes || !sha256(file).contentEquals(e.digest)) return Result.WrongFile
        val kind = if (e.kind == UpdateMetadata.ENGINE) PackKind.ENGINE else PackKind.VOICE
        val pin = CatalogEntry(e.id, e.version.toInt(), kind, "", e.bytes, hex(e.sums))
        val store = PackStore(directory, compiled.filterNot { it.id == pin.id && it.version == pin.version && it.kind == pin.kind } + pin, maxBytes = e.expanded)
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val info = try {
            file.inputStream().buffered().use { store.install(it, retainOlder = true) }
        } catch (r: PackRejected) { return Result.Rejected(r.reason.name) } catch (io: IOException) { return Result.Rejected("DAMAGED") }
        val fresh = info.root.name !in before
        fun back(result: Result): Result { if (fresh) store.discard(info.root); return result }
        if (info.id != e.id || info.version.toLong() != e.version || info.kind != kind) return back(Result.Rejected("WRONG_IDENTITY"))
        val files = info.root.walkTopDown().filter { it.isFile }.toList()
        if (files.size != e.files || files.sumOf { it.length() } != e.expanded) return back(Result.Rejected("WRONG_SIZE"))
        val opened = try { open(info) } catch (_: RuntimeException) { false }
        if (!opened) return back(Result.OpenFailed)
        if (!trust.recordInstalled(e)) return back(Result.StateNotSaved)
        store.retireOlder(info)
        return Result.Activated(info)
    }

    /**
     * After a restart: an installed pack that matches an entry of the accepted catalog (same manifest digest, not revoked)
     * raises its floor, so an install that finished just before the floor was saved is not lost, and packs that came with
     * the app (the baseline) get their floors. Returns how many floors were raised.
     */
    fun reconcile(trust: UpdateTrust): Int {
        val catalog = trust.catalog() ?: return 0
        var raised = 0
        for (info in PackStore(directory, compiled).list()) {
            val sums = runCatching { sha256(info.root.resolve("SHA256SUMS")) }.getOrNull() ?: continue
            val kind = if (info.kind == PackKind.ENGINE) UpdateMetadata.ENGINE else UpdateMetadata.VOICE
            val entry = catalog.entries.firstOrNull { it.kind == kind && it.id == info.id && it.version == info.version.toLong() && it.sums.contentEquals(sums) } ?: continue
            if (catalog.revoked.any { it.contentEquals(entry.digest) }) continue
            val before = trust.floor(kind, info.id)
            if (trust.recordInstalled(entry) && trust.floor(kind, info.id) > before) raised++
        }
        return raised
    }

    private fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

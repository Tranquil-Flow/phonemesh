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
 * size must equal the signed ones, and it must open. The new copy is published under a name the pack list does not return
 * (`.pending-`), so the old pack stays the one in use at every moment, also if the process dies in between (a store opened
 * later removes the leftover). Only after [open] says the new pack really opens does it become the installed pack; then its
 * version floor is saved and older copies are removed. Any earlier failure removes only the new copy (U7-5).
 */
open class PackActivation(private val directory: File, private val compiled: List<CatalogEntry> = GeneratedPackCatalog.entries) {
    sealed class Result {
        class Activated(val info: PackInfo) : Result()
        object NotAuthorized : Result()
        object WrongFile : Result()
        class Rejected(val why: String) : Result()
        object OpenFailed : Result()
        object StateNotSaved : Result()
    }

    open fun activate(trust: UpdateTrust, offer: UpdateTrust.Offer, file: File, open: (PackInfo) -> Boolean = { true }): Result {
        val e = offer.entry
        if (!trust.stillAuthorized(offer)) return Result.NotAuthorized
        if (!file.isFile || file.length() != e.bytes || !sha256(file).contentEquals(e.digest)) return Result.WrongFile
        val kind = if (e.kind == UpdateMetadata.ENGINE) PackKind.ENGINE else PackKind.VOICE
        val pin = CatalogEntry(e.id, e.version.toInt(), kind, "", e.bytes, hex(e.sums))
        val store = PackStore(directory, compiled.filterNot { it.id == pin.id && it.version == pin.version && it.kind == pin.kind } + pin, maxBytes = e.expanded)
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val info = try {
            file.inputStream().buffered().use { store.install(it, retainOlder = true, pending = true) }
        } catch (r: PackRejected) { return Result.Rejected(r.reason.name) } catch (io: IOException) { return Result.Rejected("DAMAGED") }
        // A pack that was already installed (same manifest) comes back as it is: not ours to throw away.
        val fresh = info.root.name !in before
        var live = info
        fun back(result: Result): Result { if (fresh) store.discard(live.root); return result }
        try {
            if (info.id != e.id || info.version.toLong() != e.version || info.kind != kind) return back(Result.Rejected("WRONG_IDENTITY"))
            val files = info.root.walkTopDown().filter { it.isFile }.toList()
            if (files.size != e.files || files.sumOf { it.length() } != e.expanded) return back(Result.Rejected("WRONG_SIZE"))
            val opened = try { open(info) } catch (_: RuntimeException) { false }
            if (!opened) return back(Result.OpenFailed)
            if (fresh) live = store.promote(info)
            if (!trust.recordInstalled(e)) return back(Result.StateNotSaved)
            store.retireOlder(live)
            return Result.Activated(live)
        } catch (e: Exception) {
            // Not an outcome of the checks (an I/O failure, say): the new copy must not linger as a half-accepted pack.
            // (An Error is a dead process in the tests: nothing runs then, and the next start repairs.)
            if (fresh) runCatching { store.discard(live.root) }
            throw e
        }
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

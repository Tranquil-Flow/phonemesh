package org.phonemesh.hub

import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What an operator sees about the updates a hub holds (hub updates spec 5.8, "operator status"): the release (trust epoch
 * and catalog number), which of its files are here and which are still missing, how much of the update room is used, and
 * when files were last brought. Read-only; it re-verifies the stored catalog under the held key, and a damaged trust file
 * is reported, never reset. It conveys no trust to anybody else: phones check every byte themselves.
 */
object UpdateStatus {
    enum class Kind { NONE, OK, DAMAGED }

    class Entry(val id: String, val version: Long, val bytes: Long, val kind: Int, val present: Boolean)

    class Report(
        val kind: Kind, val note: String = "", val epoch: Long = 0, val sequence: Long = -1, val issuedAt: Long = 0, val reviewAfter: Long = 0,
        val entries: List<Entry> = emptyList(), val revoked: Int = 0, val strangers: Int = 0, val objectBytes: Long = 0,
        val quota: Long = UpdateImport.OBJECT_QUOTA, val freeSpace: Long = 0, val mailReserve: Long = UpdateImport.MAIL_RESERVE,
        val last: UpdateImport.LastImport? = null,
    ) {
        fun render(): List<String> {
            if (kind == Kind.DAMAGED) return listOf("The update records on this hub are damaged: $note", "Nothing was changed. Restore updates/ from a backup or move it aside and import a release again.")
            if (kind == Kind.NONE) return listOf("No updates have been imported on this hub yet.")
            val lines = ArrayList<String>()
            lines += "Updates on this hub"
            if (sequence < 0) lines += "Trust epoch $epoch, no catalog yet"
            else lines += "Release: trust epoch $epoch, catalog number $sequence, issued ${day(issuedAt)}, review after ${day(reviewAfter)}"
            if (note.isNotEmpty()) lines += "WARNING: $note"
            val here = entries.count { it.present }
            lines += "Files ($here of ${entries.size} here):"
            for (e in entries) lines += "  ${if (e.present) "here    " else "MISSING "} ${e.id}, version ${e.version}, ${size(e.bytes)}"
            lines += "Revoked: $revoked"
            if (strangers > 0) lines += "$strangers file${if (strangers == 1) "" else "s"} not in this release (kept, and counted in the room used)"
            lines += "Room: ${size(objectBytes)} of ${size(quota)} used for updates; ${size(freeSpace)} free on this disk, ${size(mailReserve)} of it kept for mail"
            lines += last?.let { "Last import: ${stamp(it.seconds)}, catalog number ${it.sequence}, ${it.copied} file${if (it.copied == 1) "" else "s"} copied, ${it.missing} missing" } ?: "Last import: none recorded"
            return lines
        }
    }

    fun read(dir: File, roots: UpdateRoots, quota: Long = UpdateImport.OBJECT_QUOTA, now: () -> Long = System::currentTimeMillis): Report {
        val updates = File(dir, "updates")
        val stateFile = File(updates, "trust-state.bin")
        if (!updates.isDirectory || !Files.isRegularFile(stateFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return Report(Kind.NONE)
        val state = (if (stateFile.length() <= 1024) UpdateImport.State.decode(stateFile.readBytes()) else null)
            ?: return Report(Kind.DAMAGED, "updates/trust-state.bin cannot be read")
        val objects = File(updates, "objects")
        val held = objects.listFiles().orEmpty().filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        val heldBytes = held.sumOf { it.length() }
        val last = UpdateImport.lastImport(dir)
        val free = dir.usableSpace
        if (state.sequence < 0) return Report(Kind.OK, epoch = state.epoch, objectBytes = heldBytes, quota = quota, freeSpace = free, last = last)
        val file = File(File(updates, "catalogs"), "${hex(state.catalogDigest)}.pmup")
        val bytes = if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() <= UpdateMetadata.MAX_ENVELOPE) file.readBytes() else null
        val env = bytes?.let { UpdateMetadata.verify(it, mapOf(state.keyId to state.key), null) }
        val catalog = env?.takeIf { it.kind == UpdateMetadata.CATALOG }?.let { UpdateMetadata.catalog(it.payload) }
            ?: return Report(Kind.DAMAGED, "the stored catalog for number ${state.sequence} is missing or does not verify")
        val entries = catalog.entries.map { Entry(it.id, it.version, it.bytes, it.kind, Files.isRegularFile(File(objects, hex(it.digest)).toPath(), LinkOption.NOFOLLOW_LINKS)) }
        val listed = catalog.entries.map { hex(it.digest) }.toSet()
        return Report(Kind.OK, epoch = state.epoch, sequence = state.sequence, issuedAt = catalog.issuedAt, reviewAfter = catalog.reviewAfter,
            entries = entries, revoked = catalog.revoked.size, strangers = held.count { it.name !in listed }, objectBytes = heldBytes,
            quota = quota, freeSpace = free, last = last)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fmt(pattern: String, seconds: Long) = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(seconds * 1000))
    private fun day(seconds: Long) = fmt("yyyy-MM-dd", seconds)
    private fun stamp(seconds: Long) = fmt("yyyy-MM-dd HH:mm", seconds) + " UTC"
    private fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(Locale.ROOT, bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1e6)
        bytes >= 1_000 -> "%.1f kB".format(Locale.ROOT, bytes / 1e3)
        else -> "$bytes B"
    }
}

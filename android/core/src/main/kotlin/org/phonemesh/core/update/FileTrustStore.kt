package org.phonemesh.core.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The phone's [TrustStore] on plain files (hub updates spec 5.3): `trust-0.bin` and `trust-1.bin` in a directory only the update
 * code owns. A save writes `trust-N.tmp`, syncs it, then renames it over the slot, so a reader never sees a half-written slot; a
 * leftover temp file from a crash is ignored (and replaced by the next save). Nothing here touches History or its version.
 */
class FileTrustStore(private val dir: File) : TrustStore {
    override val atomicSlotWrites: Boolean get() = true

    private fun slotFile(slot: Int): File { require(slot == 0 || slot == 1) { "two slots only" }; return File(dir, "trust-$slot.bin") }

    override fun load(slot: Int): ByteArray? {
        val file = slotFile(slot)
        if (!file.isFile) return null
        return try {
            file.inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        } catch (_: IOException) { null }
    }

    override fun save(slot: Int, bytes: ByteArray) {
        val target = slotFile(slot)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot make the update directory")
        val temp = File(dir, "trust-$slot.tmp")
        FileOutputStream(temp).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // The rename itself should survive a power cut; not every file system lets a directory be opened, so this is best effort.
        runCatching { java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
    }

    companion object {
        /** More than any whole record (see [TrustState]); a longer file is damage and decodes to nothing. */
        const val MAX_BYTES = 262_144
    }
}

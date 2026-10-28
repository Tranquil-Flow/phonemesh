package org.phonemesh.core.update

import org.phonemesh.core.crypto.Bytes
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Copies signed catalogs and pack files from the enrolled local hub (hub updates spec 5.5; decision 45, UP3). Everything the
 * hub says is checked here, nothing is believed because it came over TLS: metadata goes through [UpdateTrust]; a file is
 * fetched in ranges of at most 256 KiB, written through a 64 KiB buffer, kept across pauses and restarts with a small
 * sidecar (expected digest and length, trust epoch and number, durable offset), and used only after its whole SHA-256 equals the
 * signed digest. A server answer that is not exactly the range asked for is refused. There is no radio, mobile data, redirect,
 * download manager or message path in this class: the only network is the [HubTransport] it is given.
 */
class UpdateDownloader(
    private val trust: UpdateTrust, private val transport: HubTransport, private val dir: File,
    private val freeSpace: () -> Long = { dir.usableSpace },
    private val chunkBytes: Int = CHUNK, private val bufferBytes: Int = BUFFER, private val maxFailures: Int = 3,
    private val spaceReserve: Long = RESERVE,
    /** An answer to a range request shorter than this (unless it is all that was asked for) counts as a failure (U7-10). */
    private val minRangeBytes: Int = MIN_RANGE,
    /** How many times a busy hub (429) is waited out before the copy stops with [Download.Busy] (U7-8). */
    private val busyRetries: Int = BUSY_RETRIES,
    /** Waits [millis]; may return early. A seam for tests. */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    init { require(transport.route == UpdateRoute.PINNED_HUB_WIFI) { "updates only come from the pinned hub" } }

    sealed class Refresh {
        /** The hub has no catalog: an older hub, or nothing imported. Updates are simply unavailable. */
        object NoUpdates : Refresh()
        class Updated(val recovery: UpdateTrust.RecoveryResult?, val catalog: UpdateTrust.CatalogResult) : Refresh()
        class Failed(val why: String) : Refresh()
    }

    sealed class Download {
        class Ready(val file: File, val restarts: Int) : Download()
        /** Stopped on request; the partial file is kept. */
        class Paused(val offset: Long) : Download()
        /** Three failures in a row; the person chooses Resume. The partial file is kept. */
        class NeedsResume(val why: String, val offset: Long) : Download()
        class NoSpace(val needed: Long, val free: Long) : Download()
        object NotAuthorized : Download()
        object NotOnThisHub : Download()
        /** The bytes are whole but are not what was signed. The partial file is deleted. */
        object WrongHash : Download()
        /** The hub answered in a way the rules do not allow. */
        class Refused(val why: String) : Download()
        class Busy(val retryAfterSeconds: Int) : Download()
    }

    private val partial = File(dir, "partial")
    private val ready = File(dir, "ready")

    // ---- metadata ----

    fun refresh(): Refresh {
        // A hub that answers oddly on the recovery route must not block the catalog: no policy then, the catalog still counts (U7-15).
        val recovery = when (val r = small("/updates/v1/recovery")) {
            is Small.Bytes -> trust.acceptRecovery(r.bytes)
            is Small.Absent, is Small.Bad -> null
        }
        return when (val c = small("/updates/v1/catalog")) {
            is Small.Absent -> Refresh.NoUpdates
            is Small.Bad -> Refresh.Failed(c.why)
            is Small.Bytes -> Refresh.Updated(recovery, trust.acceptCatalog(c.bytes))
        }
    }

    private sealed class Small {
        class Bytes(val bytes: ByteArray) : Small()
        object Absent : Small()
        class Bad(val why: String) : Small()
    }

    private fun small(path: String): Small = try {
        transport.get(path, null).use { r ->
            when {
                r.status == 404 -> Small.Absent
                r.status != 200 -> Small.Bad(plainStatus(r.status))
                r.all("content-encoding").isNotEmpty() -> Small.Bad("the hub encoded the answer")
                else -> {
                    val body = r.body.readNBytes(UpdateMetadata.MAX_ENVELOPE + 1)
                    // A hub without updates may answer an unknown path with any page: only PMUP bytes count.
                    if (body.size > UpdateMetadata.MAX_ENVELOPE || body.size < 4 || String(body, 0, 4, Charsets.ISO_8859_1) != "PMUP") Small.Bad("not update metadata") else Small.Bytes(body)
                }
            }
        }
    } catch (e: IOException) { Small.Bad("the hub could not be reached") }

    private fun plainStatus(status: Int) = if (status in 300..399) "the hub tried to redirect" else "the hub answered $status"

    // ---- files ----

    /** [installedBytes]: what the pack being replaced takes on disk; it stays there until the new one has opened, so room is needed for both. */
    fun download(
        offer: UpdateTrust.Offer, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
        installedBytes: Long = 0,
    ): Download {
        val entry = offer.entry
        require(entry.kind == UpdateMetadata.ENGINE || entry.kind == UpdateMetadata.VOICE) { "packs only" }
        val name = hex(entry.digest)
        partial.mkdirs(); ready.mkdirs()
        val part = File(partial, "$name.part"); val sidecar = File(partial, "$name.state")
        if (!trust.stillAuthorized(offer)) { part.delete(); sidecar.delete(); return Download.NotAuthorized }
        val finished = File(ready, name)
        if (finished.isFile && finished.length() == entry.bytes && sha256(finished).contentEquals(entry.digest)) return Download.Ready(finished, 0)
        var offset = resume(part, sidecar, entry, offer)
        val needed = (entry.bytes - offset) + entry.expanded + installedBytes + spaceReserve
        val free = freeSpace()
        if (free < needed) return Download.NoSpace(needed, free)
        var failures = 0; var restarts = 0; var busy = 0
        onProgress(offset, entry.bytes)
        while (offset < entry.bytes) {
            if (cancelled()) return Download.Paused(offset)
            if (!trust.stillAuthorized(offer)) { part.delete(); sidecar.delete(); return Download.NotAuthorized }
            val last = minOf(offset + chunkBytes, entry.bytes) - 1
            val step = try {
                transport.get("/updates/v1/objects/$name", offset..last).use { r -> fetch(r, entry, name, part, sidecar, offset, last, offer, cancelled) }
            } catch (e: LocalWriteFailure) {
                // Our own disk, not the hub: the caller says "could not save" or "no space", and the hub is not blamed (U7-7).
                throw IOException(e.message, e.cause)
            } catch (e: IOException) { Step.Failed("the hub connection dropped", offset) }
            when (step) {
                is Step.Progress -> { busy = 0; if (step.restarted) restarts++; if (step.offset > offset || step.restarted) failures = 0; offset = step.offset; onProgress(offset, entry.bytes) }
                is Step.Busy -> {
                    // A busy hub is not a failure: wait as it asks (with a growing floor) and ask again, a few times (U7-8).
                    if (++busy > busyRetries) return Download.Busy(step.seconds)
                    if (!wait(maxOf(step.seconds.toLong(), 1L shl minOf(busy, 5)).coerceAtMost(MAX_BUSY_WAIT), cancelled)) return Download.Paused(offset)
                }
                is Step.Failed -> {
                    if (step.offset > offset) failures = 0
                    offset = step.offset; failures++
                    if (failures >= maxFailures) return Download.NeedsResume(step.why, offset)
                }
                is Step.Stop -> return step.result
            }
        }
        // Whole: the digest of every byte decides, before anything uses the file.
        if (part.length() != entry.bytes || !sha256(part).contentEquals(entry.digest)) {
            part.delete(); sidecar.delete(); return Download.WrongHash
        }
        Files.move(part.toPath(), finished.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        sidecar.delete()
        return Download.Ready(finished, restarts)
    }

    /** A failure of the phone's own storage while writing the copy (not of the hub link). */
    private class LocalWriteFailure(cause: IOException) : IOException(cause.message, cause)

    private sealed class Step {
        class Progress(val offset: Long, val restarted: Boolean = false) : Step()
        class Failed(val why: String, val offset: Long) : Step()
        class Busy(val seconds: Int) : Step()
        class Stop(val result: Download) : Step()
    }

    private fun fetch(
        r: HubResponse, entry: UpdateMetadata.Entry, name: String, part: File, sidecar: File, offset: Long, last: Long,
        offer: UpdateTrust.Offer, cancelled: () -> Boolean,
    ): Step {
        fun refuse(why: String) = Step.Stop(Download.Refused(why))
        when {
            r.status == 404 -> return Step.Stop(Download.NotOnThisHub)
            r.status == 429 -> return Step.Busy(r.one("retry-after")?.toIntOrNull()?.coerceIn(1, 3600) ?: 30)
            r.status == 416 -> { part.delete(); sidecar.delete(); return Step.Failed("the hub did not accept the range", 0) }
            r.status in 300..399 -> return refuse("the hub tried to redirect")
            r.status != 200 && r.status != 206 -> return if (r.status >= 500) Step.Failed("the hub answered ${r.status}", offset) else refuse("the hub answered ${r.status}")
        }
        if (r.all("content-encoding").isNotEmpty()) return refuse("the hub encoded the file")
        if (r.one("content-type")?.startsWith("multipart", ignoreCase = true) == true) return refuse("the hub sent several ranges")
        val etag = r.one("etag")
        if (etag == HubResponse.AMBIGUOUS || etag != "\"$name\"") return refuse("the hub's file is not the one asked for")
        val length = r.one("content-length")
        if (length == HubResponse.AMBIGUOUS) return refuse("ambiguous length")
        var start = offset; var end = last; var restarted = false
        if (r.status == 206) {
            val cr = r.one("content-range")
            if (cr == null || cr == HubResponse.AMBIGUOUS) return refuse("the hub's range is missing or ambiguous")
            val m = Regex("bytes (\\d{1,18})-(\\d{1,18})/(\\d{1,18})").matchEntire(cr) ?: return refuse("the hub's range is malformed")
            val from = m.groupValues[1].toLong(); val to = m.groupValues[2].toLong(); val total = m.groupValues[3].toLong()
            if (total != entry.bytes) return refuse("the hub's file has another size than the signed one")
            if (from != offset) return refuse("the hub answered another range")
            if (to < from || to > last) return refuse("the hub sent more than asked")
            // A hub that hands out a few bytes per request would keep the phone busy for hours: that is a failure, not progress.
            val asked = last - offset + 1
            if (to - from + 1 < minOf(asked, minRangeBytes.toLong())) return Step.Failed("the hub sent too little at a time", offset)
            end = to
            if (length != null && length.toLongOrNull() != end - start + 1) return refuse("the hub's length does not match its range")
        } else {
            // 200 to a range request: the whole file from the start. Never appended to what is already here.
            if (length == null || length.toLongOrNull() != entry.bytes) return refuse("the hub's file has another size than the signed one")
            // The client holds an answer in memory: a whole-file answer cannot arrive. Say what the hub did wrong.
            if (entry.bytes > transport.maxAnswerBytes) return refuse("the hub ignored the byte range and sent the whole file")
            start = 0; end = entry.bytes - 1; restarted = offset > 0
            if (restarted) { part.delete(); sidecar.delete() }
        }
        // Write what arrives: flush and sync before the durable offset moves (spec 5.5).
        var written = 0L
        val expected = end - start + 1
        var dropped = false
        try {
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(start); raf.seek(start)
                val buf = ByteArray(bufferBytes)
                while (written < expected) {
                    if (cancelled()) break
                    // Never read, and so never write, past the range that was asked for and announced.
                    val n = try { r.body.read(buf, 0, minOf(buf.size.toLong(), expected - written).toInt()) } catch (e: IOException) { dropped = true; break }
                    if (n < 0) { dropped = true; break }
                    raf.write(buf, 0, n); written += n
                }
                raf.fd.sync()
            }
            if (written > 0 || restarted) saveSidecar(sidecar, entry, offer, start + written)
        } catch (e: IOException) { throw LocalWriteFailure(e) }
        val now = start + written
        return when {
            written == expected -> Step.Progress(now, restarted)
            dropped -> Step.Failed("the hub connection dropped", now)
            else -> Step.Progress(now, restarted) // cancelled: the loop's check pauses
        }
    }

    /**
     * Where an earlier try stopped: only when the sidecar is whole, names this very file, epoch and length, and the partial file
     * has at least those bytes (a longer tail was never synced and is cut). Anything else starts again from nothing.
     */
    private fun resume(part: File, sidecar: File, entry: UpdateMetadata.Entry, offer: UpdateTrust.Offer): Long {
        val offset = readSidecar(sidecar, entry, offer)
        if (offset != null && offset in 0..entry.bytes && part.isFile && part.length() >= offset) {
            if (part.length() > offset) RandomAccessFile(part, "rw").use { it.setLength(offset) }
            return offset
        }
        part.delete(); sidecar.delete()
        return 0
    }

    private fun saveSidecar(file: File, entry: UpdateMetadata.Entry, offer: UpdateTrust.Offer, offset: Long) {
        val body = ByteBuffer.allocate(4 + 1 + 32 + 8 * 4 + 1).put(MAGIC).put(1).put(entry.digest).putLong(entry.bytes).putLong(offer.epoch).putLong(offer.sequence)
            .putLong(offset).put(1).array()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.outputStream().use { it.write(body + Bytes.sha256(body)); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readSidecar(file: File, entry: UpdateMetadata.Entry, offer: UpdateTrust.Offer): Long? {
        if (!file.isFile || file.length() != (4 + 1 + 32 + 8 * 4 + 1 + 32).toLong()) return null
        val all = file.readBytes()
        val body = all.copyOfRange(0, all.size - 32)
        if (!Bytes.equal(Bytes.sha256(body), all.copyOfRange(all.size - 32, all.size))) return null
        val b = ByteBuffer.wrap(body)
        if (!ByteArray(4).also(b::get).contentEquals(MAGIC) || b.get().toInt() != 1) return null
        if (!ByteArray(32).also(b::get).contentEquals(entry.digest) || b.long != entry.bytes || b.long != offer.epoch) return null
        b.long // the catalog number may move on; the digest names the file
        return b.long
    }

    /** Waits [seconds] in short slices; false when [cancelled] says stop. */
    private fun wait(seconds: Long, cancelled: () -> Boolean): Boolean {
        var left = seconds * 1000
        while (left > 0) {
            if (cancelled()) return false
            val slice = minOf(left, 250L)
            sleep(slice); left -= slice
        }
        return !cancelled()
    }

    private fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input: InputStream ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        const val CHUNK = 256 * 1024
        const val BUFFER = 64 * 1024
        const val RESERVE = 20L * 1024 * 1024
        const val MIN_RANGE = 64 * 1024
        const val BUSY_RETRIES = 8
        const val MAX_BUSY_WAIT = 30L
        private val MAGIC = "PMDL".toByteArray(Charsets.US_ASCII)
    }
}

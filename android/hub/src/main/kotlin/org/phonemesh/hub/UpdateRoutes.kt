package org.phonemesh.hub

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * The phones' read-only update routes (hub updates spec 5.4), serving what [UpdateImport] put in `updates/`:
 *
 * - `GET /updates/v1/catalog`: the accepted signed catalog, exact bytes, or 404.
 * - `GET /updates/v1/recovery`: the recovery policy of the held trust epoch, or 404 before any.
 * - `GET` or `HEAD /updates/v1/objects/<sha256>`: an object, with `ETag` (its digest) and one byte range at most.
 *
 * The hub vouches for nothing: phones check every byte against the signed catalog. Bulk transfers are capped (two at a
 * time, one per phone) so mail keeps the link. A request waits up to [queueMillis] for its slot before it gets 429 with
 * `Retry-After` (phones wait that out and ask again), and a slot is given back the moment the last byte is written, so a
 * phone's next range request never meets its own previous one. A transfer is closed when, over [stallMillis], it moved less
 * than [minBytesPerSecond] (a stalled or trickling reader cannot hold a slot, review D5, U7-8) or when it has lasted
 * [requestMillis]. Nothing is logged per phone.
 */
class UpdateRoutes(
    private val dir: File, private val stallMillis: Long = STALL_MILLIS,
    private val queueMillis: Long = QUEUE_MILLIS, private val minBytesPerSecond: Long = MIN_BYTES_PER_SECOND,
    private val requestMillis: Long = REQUEST_MILLIS,
    /** Who is asking, for the one-at-a-time-per-phone rule. The address, unless a test says otherwise. */
    private val clientOf: (HttpExchange) -> String = { it.remoteAddress.address.hostAddress },
) {
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "update-stall").apply { isDaemon = true } }
    private val updates get() = File(dir, "updates")
    private val transfers = Semaphore(MAX_TRANSFERS)
    private val perClient = ConcurrentHashMap<String, Semaphore>()

    fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        val head = ex.requestMethod == "HEAD"
        if (ex.requestMethod != "GET" && !head) return status(ex, 405, "Allow" to "GET, HEAD")
        when {
            path == "/updates/v1/catalog" -> small(ex, head, catalog())
            path == "/updates/v1/recovery" -> small(ex, head, recovery())
            path.startsWith("/updates/v1/objects/") -> obj(ex, head, path.removePrefix("/updates/v1/objects/"))
            else -> status(ex, 404)
        }
    }

    private fun state(): UpdateImport.State? =
        File(updates, "trust-state.bin").takeIf { it.isFile }?.readBytes()?.let(UpdateImport.State::decode)

    private fun catalog(): ByteArray? {
        val s = state() ?: return null
        if (s.sequence < 0) return null
        return regular(File(File(updates, "catalogs"), "${hex(s.catalogDigest)}.pmup"))?.readBytes()
    }

    private fun recovery(): ByteArray? {
        val s = state() ?: return null
        val files = File(updates, "policies").listFiles().orEmpty().filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        // Policy bytes: 12-byte header, then the new epoch (u64). The one that started the held epoch.
        return files.map { it.readBytes() }.firstOrNull { it.size >= 20 && ByteBuffer.wrap(it, 12, 8).long == s.epoch }
    }

    private fun small(ex: HttpExchange, head: Boolean, bytes: ByteArray?) {
        if (bytes == null) return status(ex, 404)
        ex.responseHeaders.set("Content-Type", "application/octet-stream")
        ex.responseHeaders.set("Cache-Control", "no-store")
        if (head) { ex.responseHeaders.set("Content-Length", bytes.size.toString()); ex.sendResponseHeaders(200, -1); return }
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun obj(ex: HttpExchange, head: Boolean, name: String) {
        if (!DIGEST.matches(name)) return status(ex, 404)
        val file = regular(File(File(updates, "objects"), name)) ?: return status(ex, 404)
        val size = file.length()
        ex.responseHeaders.set("ETag", "\"$name\"")
        ex.responseHeaders.set("Accept-Ranges", "bytes")
        ex.responseHeaders.set("Content-Type", "application/octet-stream")
        val ranges = ex.requestHeaders["Range"].orEmpty()
        var from = 0L
        var to = size - 1
        var partial = false
        if (ranges.isNotEmpty()) {
            val r = RANGE.matchEntire(ranges.singleOrNull()?.trim().orEmpty())
            val a = r?.groupValues?.get(1)?.toLongOrNull()
            val b = r?.groupValues?.get(2)?.let { if (it.isEmpty()) size - 1 else it.toLongOrNull() }
            if (ranges.size != 1 || a == null || b == null || a > b || a >= size) {
                ex.responseHeaders.set("Content-Range", "bytes */$size")
                return status(ex, 416)
            }
            from = a; to = minOf(b, size - 1); partial = true
            ex.responseHeaders.set("Content-Range", "bytes $from-$to/$size")
        }
        val length = to - from + 1
        val code = if (partial) 206 else 200
        if (head) { ex.responseHeaders.set("Content-Length", length.toString()); ex.sendResponseHeaders(code, -1); return }
        val client = clientOf(ex)
        val mine = perClient.computeIfAbsent(client) { Semaphore(1) }
        // Wait a moment for this phone's previous range (it may still be closing) and for a free slot; then tell the phone to come back.
        if (!mine.tryAcquire(queueMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) return busy(ex)
        if (!transfers.tryAcquire(queueMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) { mine.release(); return busy(ex) }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) { transfers.release(); mine.release() } }
        // A blocked socket write only gives way to an interrupt (the server's channels are interruptible), and only
        // while this transfer is still running on this thread.
        val started = System.currentTimeMillis()
        val written = java.util.concurrent.atomic.AtomicLong(0)
        val windowAt = java.util.concurrent.atomic.AtomicLong(started)
        val windowBytes = java.util.concurrent.atomic.AtomicLong(0)
        val writer = Thread.currentThread()
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val check = maxOf(minOf(stallMillis, requestMillis) / 4, 1L)
        val stall = watchdog.scheduleWithFixedDelay({
            synchronized(active) {
                if (!active.get()) return@synchronized
                val now = System.currentTimeMillis()
                var cut = now - started > requestMillis
                if (now - windowAt.get() >= stallMillis) {
                    // Over the last window it moved less than the floor: a stalled or trickling reader.
                    if (written.get() - windowBytes.get() < minBytesPerSecond * stallMillis / 1000) cut = true
                    windowAt.set(now); windowBytes.set(written.get())
                }
                if (cut) writer.interrupt()
            }
        }, check, check, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            ex.sendResponseHeaders(code, length)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val buffer = ByteArray(64 * 1024)
                var left = length
                ex.responseBody.use { out ->
                    while (left > 0) {
                        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buffer, 0, n); left -= n
                        written.addAndGet(n.toLong())
                    }
                    // The last byte is written: the phone's next request may come now, before this one has finished closing.
                    release()
                }
            }
        } catch (_: java.io.IOException) {
            // The phone went away or stalled: the slot is free again below.
        } finally {
            synchronized(active) { active.set(false) }
            stall.cancel(false)
            Thread.interrupted()
            release()
        }
    }

    private fun busy(ex: HttpExchange) = status(ex, 429, "Retry-After" to "2")

    private fun status(ex: HttpExchange, code: Int, vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> ex.responseHeaders.set(k, v) }
        ex.sendResponseHeaders(code, -1)
    }

    private fun regular(f: File): File? = f.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_TRANSFERS = 2
        const val STALL_MILLIS = 60_000L
        /** How long a request waits for a slot before it is told to come back. */
        const val QUEUE_MILLIS = 2_000L
        /** Least rate a transfer must keep over a stall window: a quarter of a megabit is slow, but not a slot-holder. */
        const val MIN_BYTES_PER_SECOND = 4_096L
        /** No single request may last longer than this (a range is at most 256 KiB; a whole object only for old tools). */
        const val REQUEST_MILLIS = 600_000L
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val RANGE = Regex("bytes=(\\d{1,18})-(\\d{0,18})")
    }
}

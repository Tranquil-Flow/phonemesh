package org.phonemesh.core.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** A hub's `/updates/v1/` routes in memory, answering as the real ones do, with hooks to bend an answer. */
class FakeHub : HubTransport {
    override val route = UpdateRoute.PINNED_HUB_WIFI
    /** What one answer may carry; the real client holds an answer in memory. */
    var maxAnswer: Long = Long.MAX_VALUE
    override val maxAnswerBytes: Long get() = maxAnswer
    var catalog: ByteArray? = null
    var recovery: ByteArray? = null
    val objects = HashMap<String, ByteArray>()
    val requests = ArrayList<Pair<String, LongRange?>>()
    /** Changes the answer to the next request(s) that pass [when]. */
    var bend: ((path: String, range: LongRange?, ok: HubResponse) -> HubResponse)? = null
    /** Throws this from the next object body read after [crashAfter] bytes were delivered. */
    var crashAfter: Long = -1
    var crash: Throwable? = null

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun add(data: ByteArray): String = hex(java.security.MessageDigest.getInstance("SHA-256").digest(data)).also { objects[it] = data }

    override fun get(path: String, range: LongRange?): HubResponse {
        requests += path to range
        check(path.startsWith("/updates/v1/")) { "outside the update routes: $path" }
        val ok = answer(path, range)
        return bend?.invoke(path, range, ok) ?: ok
    }

    private fun answer(path: String, range: LongRange?): HubResponse {
        fun small(b: ByteArray?) = if (b == null) HubResponse(404, emptyMap(), ByteArrayInputStream(ByteArray(0)))
        else HubResponse(200, mapOf("Content-Length" to listOf(b.size.toString())), ByteArrayInputStream(b))
        return when {
            path == "/updates/v1/catalog" -> small(catalog)
            path == "/updates/v1/recovery" -> small(recovery)
            path.startsWith("/updates/v1/objects/") -> {
                val name = path.removePrefix("/updates/v1/objects/")
                val data = objects[name] ?: return small(null)
                val etag = mapOf("ETag" to listOf("\"$name\""), "Accept-Ranges" to listOf("bytes"))
                if (range == null) return HubResponse(200, etag + ("Content-Length" to listOf(data.size.toString())), body(data, 0, data.size.toLong()))
                if (range.first >= data.size) return HubResponse(416, mapOf("Content-Range" to listOf("bytes */${data.size}")), ByteArrayInputStream(ByteArray(0)))
                val to = minOf(range.last, data.size - 1L)
                HubResponse(206, etag + mapOf("Content-Range" to listOf("bytes ${range.first}-$to/${data.size}"), "Content-Length" to listOf((to - range.first + 1).toString())),
                    body(data, range.first, to - range.first + 1))
            }
            else -> small(null)
        }
    }

    fun body(data: ByteArray, from: Long, length: Long): InputStream {
        val slice = ByteArrayInputStream(data, from.toInt(), length.toInt())
        if (crashAfter < 0) return slice
        val limit = crashAfter; val toThrow = crash!!
        crashAfter = -1
        return object : InputStream() {
            var delivered = 0L
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (delivered >= limit) throw toThrow
                val n = slice.read(b, off, minOf(len.toLong(), limit - delivered).toInt())
                if (n > 0) delivered += n
                return n
            }
        }
    }

    /** The drop of a connection after [bytes] bytes of the next object answer. */
    fun dropNextAfter(bytes: Long) { crashAfter = bytes; crash = IOException("connection reset") }
    fun dieNextAfter(bytes: Long) { crashAfter = bytes; crash = MemoryTrustStore.Crash() }
}

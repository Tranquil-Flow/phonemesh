package org.phonemesh.app.demo

import android.content.Context
import org.phonemesh.app.AppCore
import org.phonemesh.app.HubUpdates
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackStore
import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.HubResponse
import org.phonemesh.core.update.HubTransport
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import org.phonemesh.core.update.UpdateRoute
import org.phonemesh.hub.CatalogSigner
import org.phonemesh.hub.UpdateImport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.security.PrivateKey
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Demo-only update keys. Their private scalars come from public labels on purpose, so anyone can read them: nothing signed with
 * them is trusted by the real app, whose roots (`update/public-keys.txt`) are other keys. The demo app alone is built to trust
 * these, through the same verifier, the same trust state and the same downloader as the real app.
 */
object DemoUpdateKeys {
    private const val CATALOG_LABEL = "phonemesh DEMO catalog key"
    const val CATALOG_PUBLIC = "03303734e66bc4afcedd79b1d2ff8eaf05bacfa8cd240e588b48c672be6a7bfa7e"
    const val RECOVERY_PUBLIC = "03798dfd7f91a723695b8930bc40b4eccd8339efc6069759b191b3fe0b4ae9ec70"

    val roots: UpdateRoots by lazy {
        UpdateRoots.parse("# status: TEST (demo-only keys)\ncatalog key_id=1 public_key=$CATALOG_PUBLIC\nrecovery key_id=100 public_key=$RECOVERY_PUBLIC\n")
    }

    val catalogPrivate: PrivateKey by lazy {
        val d = BigInteger(1, Bytes.sha256(CATALOG_LABEL.toByteArray())).mod(P256.n - BigInteger.ONE) + BigInteger.ONE
        P256.privateKey(Bytes.fixed(d, 32))
    }

    fun sign(sequence: Long, entries: List<UpdateMetadata.Entry>): ByteArray =
        UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, CatalogSigner.encode(1, sequence, ISSUED, ISSUED + 365 * 86_400L, 1, entries, emptyList()), catalogPrivate)

    private const val ISSUED = 1_793_145_600L
}

/** The one pack the demo hub updates: a tiny made-up German voice, version 1 on the phone and version 2 on the hub. */
object DemoUpdatePack {
    const val ID = "voice-demo-update"
    const val LABEL = "Demo voice"
    private const val FIXED_TIME = 1_793_145_600_000L

    class Built(val zip: ByteArray, val entry: UpdateMetadata.Entry)

    fun build(version: Int): Built {
        val files = linkedMapOf<String, ByteArray>()
        files["pack.properties"] = "format=2\nkind=voice\nid=$ID\nversion=$version\nlabel=$LABEL\nlanguageTag=de\nwhisperLanguage=de\n".toByteArray()
        // Version 2 is a little over two megabytes: nine ranges at a slow pace, so the progress bar and Pause can be tried.
        files["tts/model.onnx"] = ByteArray(if (version == 1) 40_000 else 2_300_000).also { java.util.Random(version.toLong()).nextBytes(it) }
        files["tts/tokens.txt"] = "a 1\nb 2\n".toByteArray()
        for (n in listOf("phontab", "phondata", "phonindex", "intonations")) files["tts/espeak-ng-data/$n"] = "$n$version".toByteArray()
        files["tts/espeak-ng-data/lang/de"] = "name German\n".toByteArray()
        val sums = files.entries.joinToString("") { "${hex(Bytes.sha256(it.value))}  ${it.key}\n" }.toByteArray()
        val all = LinkedHashMap(files).also { it["SHA256SUMS"] = sums }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, bytes) in all) { z.putNextEntry(ZipEntry(name).also { it.time = FIXED_TIME }); z.write(bytes); z.closeEntry() } }
        val zip = out.toByteArray()
        return Built(zip, UpdateMetadata.Entry(UpdateMetadata.VOICE, ID, LABEL, "", version.toLong(), 1, 0, 26, 2, zip.size.toLong(), Bytes.sha256(zip),
            all.values.sumOf { it.size.toLong() }, all.size, Bytes.sha256(sums), ByteArray(32)))
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/**
 * The hub side of the demo's update: a release signed with the demo keys is imported into the demo hub's data folder by the real
 * hub import ([UpdateImport]), and [transport] answers the phone's requests from those files the way the hub's own update
 * routes do (which need a desktop Java web server, not available on Android). A little wait per range stands in for Wi-Fi speed.
 */
class DemoUpdateHub(private val hubDir: File, private val rangeMillis: Long = 1_000) {
    /** Imports the signed catalog 2 (and the version 2 pack) once; later starts find it already there. */
    fun prepare(): Boolean {
        if (File(hubDir, "updates/trust-state.bin").isFile) return false
        val v2 = DemoUpdatePack.build(2)
        val stick = File(hubDir, "update-stick").apply { deleteRecursively(); File(this, "objects").mkdirs() }
        try {
            File(stick, "catalog.pmup").writeBytes(DemoUpdateKeys.sign(2, listOf(v2.entry)))
            File(stick, "objects/${DemoUpdatePack.hex(v2.entry.digest)}").writeBytes(v2.zip)
            val result = UpdateImport(hubDir, DemoUpdateKeys.roots.recoveryKey, DemoUpdateKeys.roots.catalogKey, mailReserve = 1_000_000L).import(stick)
            check(result is UpdateImport.Result.Imported) { "the demo hub could not import its update: $result" }
        } finally { stick.deleteRecursively() }
        return true
    }

    val transport: HubTransport = object : HubTransport {
        override val route = UpdateRoute.PINNED_HUB_WIFI
        override fun get(path: String, range: LongRange?): HubResponse = answer(path, range)
    }

    private val updates get() = File(hubDir, "updates")

    private fun none(status: Int, vararg headers: Pair<String, String>) =
        HubResponse(status, headers.associate { it.first to listOf(it.second) }, ByteArrayInputStream(ByteArray(0)))

    private fun answer(path: String, range: LongRange?): HubResponse {
        if (!path.startsWith("/updates/v1/")) throw IOException("outside the update routes")
        return when {
            path == "/updates/v1/catalog" -> {
                val state = File(updates, "trust-state.bin").takeIf { it.isFile }?.readBytes()?.let(UpdateImport.State::decode) ?: return none(404)
                val bytes = File(updates, "catalogs/${DemoUpdatePack.hex(state.catalogDigest)}.pmup").takeIf { it.isFile }?.readBytes() ?: return none(404)
                HubResponse(200, mapOf("Content-Length" to listOf(bytes.size.toString())), ByteArrayInputStream(bytes))
            }
            path == "/updates/v1/recovery" -> none(404)
            path.startsWith("/updates/v1/objects/") -> obj(path.removePrefix("/updates/v1/objects/"), range)
            else -> none(404)
        }
    }

    private fun obj(name: String, range: LongRange?): HubResponse {
        if (!Regex("[0-9a-f]{64}").matches(name)) return none(404)
        val file = File(updates, "objects/$name").takeIf { it.isFile } ?: return none(404)
        val size = file.length()
        val etag = "ETag" to "\"$name\""
        val ranges = "Accept-Ranges" to "bytes"
        var from = 0L; var to = size - 1; var status = 200
        val headers = ArrayList<Pair<String, String>>().apply { add(etag); add(ranges) }
        if (range != null) {
            if (range.first > range.last || range.first >= size) return none(416, "Content-Range" to "bytes */$size")
            from = range.first; to = minOf(range.last, size - 1); status = 206
            headers += "Content-Range" to "bytes $from-$to/$size"
            Thread.sleep(rangeMillis)
        }
        headers += "Content-Length" to (to - from + 1).toString()
        return HubResponse(status, headers.groupBy({ it.first }, { it.second }), slice(file, from, to - from + 1))
    }

    private fun slice(file: File, from: Long, length: Long): InputStream {
        val raf = RandomAccessFile(file, "r").also { it.seek(from) }
        return object : InputStream() {
            var left = length
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() = raf.close()
        }
    }
}

/** Puts the demo's update world in place: the old pack on the phone, the signed release on the demo hub, the real screens' state. */
object DemoUpdates {
    /** Null when the demo's update world could not be made (logged); the app then shows the real, empty Updates screen. */
    fun attach(app: Context, core: AppCore, hubDir: File): HubUpdates? = try {
        build(app.filesDir, core.store, hubDir) { core.hub?.wire() != null }
    } catch (e: Exception) {
        android.util.Log.e(DemoWorld.TAG, "the demo update world could not be made", e)
        null
    }

    /** [reachable]: the phone has joined the demo hub's Wi-Fi and it answers (the same fact the real transport needs). */
    fun build(
        files: File, store: PackStore, hubDir: File, rangeMillis: Long = 1_000, executor: java.util.concurrent.Executor? = null,
        sdk: Int = android.os.Build.VERSION.SDK_INT, reachable: () -> Boolean,
    ): HubUpdates {
        val packs = File(files, "packs")
        val state = File(files, "updates-demo")
        val v1 = DemoUpdatePack.build(1)
        // Only on the very first start: a pack the person removes later stays removed.
        if (!state.exists()) {
            val pin = CatalogEntry(DemoUpdatePack.ID, 1, PackKind.VOICE, "", v1.entry.bytes, DemoUpdatePack.hex(v1.entry.sums))
            // A newer copy already on the phone (state erased by hand) is fine: there is still something to update from.
            try { PackStore(packs, GeneratedPackCatalog.entries + pin).install(v1.zip.inputStream()) } catch (_: org.phonemesh.core.PackRejected) { }
        }
        val hub = DemoUpdateHub(hubDir, rangeMillis)
        hub.prepare()
        // The phone's first start trusts a baseline signed with the demo keys that lists the version it already has.
        val baseline = DemoUpdateKeys.sign(1, listOf(v1.entry))
        val transport = { if (reachable()) hub.transport else null }
        return if (executor == null) HubUpdates.open(state, packs, store, transport, DemoUpdateKeys.roots, baseline, sdk = sdk)
        else HubUpdates.open(state, packs, store, transport, DemoUpdateKeys.roots, baseline, executor = executor, sdk = sdk)
    }
}

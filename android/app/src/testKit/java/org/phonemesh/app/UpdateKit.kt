package org.phonemesh.app

import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.HubResponse
import org.phonemesh.core.update.HubTransport
import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import org.phonemesh.core.update.UpdateRoute
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Test-only (JVM unit tests, instrumentation and demo tests share it): fresh update keys, signed catalogs, small packs, an in-memory hub. */
object UpdateKit {
    class Keys(val roots: UpdateRoots, val catalog: KeyPair, val recovery: KeyPair)

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** A fresh pair of keys and the roots that trust exactly them; [test] marks the file `# status: TEST`, as a build holding TEST keys would. */
    fun keys(test: Boolean = false): Keys {
        val c = P256.generate(); val r = P256.generate()
        val roots = UpdateRoots.parse((if (test) "# status: TEST\n" else "") + "catalog key_id=1 public_key=${hex(P256.compress(c.public as ECPublicKey))}\n" +
            "recovery key_id=100 public_key=${hex(P256.compress(r.public as ECPublicKey))}\n")
        return Keys(roots, c, r)
    }

    class Pack(val zip: ByteArray, val entry: UpdateMetadata.Entry)

    /** A whole format-2 voice pack as a ZIP and the catalog entry describing it. */
    fun voice(id: String, version: Int, label: String = "Fran\u00e7ais", modelBytes: Int = 4_000): Pack {
        val files = linkedMapOf<String, ByteArray>()
        files["pack.properties"] = "format=2\nkind=voice\nid=$id\nversion=$version\nlabel=$label\nlanguageTag=fr\nwhisperLanguage=fr\n".toByteArray()
        files["tts/model.onnx"] = ByteArray(modelBytes).also { java.util.Random(version * 31L + modelBytes).nextBytes(it) }
        files["tts/tokens.txt"] = "a 1\nb 2\n".toByteArray()
        for (n in listOf("phontab", "phondata", "phonindex", "intonations")) files["tts/espeak-ng-data/$n"] = "$n$version".toByteArray()
        files["tts/espeak-ng-data/lang/fr"] = "name French\n".toByteArray()
        val sums = files.entries.joinToString("") { "${hex(Bytes.sha256(it.value))}  ${it.key}\n" }.toByteArray()
        val all = LinkedHashMap(files).also { it["SHA256SUMS"] = sums }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in all) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        val zip = out.toByteArray()
        return Pack(zip, UpdateMetadata.Entry(UpdateMetadata.VOICE, id, label, "", version.toLong(), 1, 0, 26, 2, zip.size.toLong(), Bytes.sha256(zip),
            all.values.sumOf { it.size.toLong() }, all.size, Bytes.sha256(sums), ByteArray(32)))
    }

    /** A whole format-2 engine pack (the fixture Tiny engine at [version]) and the catalog entry describing it. */
    fun engine(version: Int): Pack {
        val files = linkedMapOf<String, ByteArray>()
        files["pack.properties"] = "format=2\nkind=engine\nid=engine-whisper-tiny\nversion=$version\nlabel=Fixture tiny\nrecognizerModel=whisper-tiny\n".toByteArray()
        files["whisper/encoder.int8.onnx"] = ByteArray(3_000).also { java.util.Random(version.toLong()).nextBytes(it) }
        files["whisper/decoder.int8.onnx"] = ByteArray(2_000).also { java.util.Random(version + 7L).nextBytes(it) }
        files["whisper/tokens.txt"] = "<|en|> 1\n<|fr|> 2\n".toByteArray()
        val sums = files.entries.joinToString("") { "${hex(Bytes.sha256(it.value))}  ${it.key}\n" }.toByteArray()
        val all = LinkedHashMap(files).also { it["SHA256SUMS"] = sums }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, d) in all) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        val zip = out.toByteArray()
        return Pack(zip, UpdateMetadata.Entry(UpdateMetadata.ENGINE, "engine-whisper-tiny", "Fixture tiny", "", version.toLong(), 1, 0, 26, 2, zip.size.toLong(), Bytes.sha256(zip),
            all.values.sumOf { it.size.toLong() }, all.size, Bytes.sha256(sums), ByteArray(32)))
    }

    fun catalog(keys: Keys, epoch: Long, sequence: Long, entries: List<UpdateMetadata.Entry>, revoked: List<ByteArray> = emptyList(), signer: KeyPair = keys.catalog): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(1)
        out.write(ByteBuffer.allocate(42).putLong(epoch).putLong(sequence).putLong(1_793_145_600L).putLong(1_808_697_600L).putLong(1).putShort(entries.size.toShort()).array())
        // No compareBy here: the minified test build keeps only the Kotlin classes its test sources name.
        val order = Comparator<UpdateMetadata.Entry> { a, b ->
            var c = a.kind.compareTo(b.kind)
            if (c == 0) c = a.id.compareTo(b.id)
            if (c == 0) c = a.version.compareTo(b.version)
            if (c == 0) c = a.abi.compareTo(b.abi)
            c
        }
        for (e in entries.sortedWith(order)) {
            val id = e.id.toByteArray(); val label = e.label.toByteArray(); val abi = e.abi.toByteArray()
            out.write(byteArrayOf(e.kind.toByte(), id.size.toByte(), label.size.toByte(), abi.size.toByte()))
            out.write(ByteBuffer.allocate(38).putLong(e.version).putLong(e.minApp).putLong(e.maxApp).putInt(e.minApi).putShort(e.packFormat.toShort()).putLong(e.bytes).array())
            out.write(e.digest); out.write(ByteBuffer.allocate(12).putLong(e.expanded).putInt(e.files).array()); out.write(e.sums); out.write(e.cert)
            out.write(id); out.write(label); out.write(abi)
        }
        out.write(ByteBuffer.allocate(2).putShort(revoked.size.toShort()).array())
        for (d in revoked.sortedWith { a, b -> hex(a).compareTo(hex(b)) }) out.write(d)
        return UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, out.toByteArray(), signer.private)
    }

    /** A hub's update routes in memory; [gate] runs before each object answer so a test can hold or fail a copy. */
    class Hub : HubTransport {
        override val route = UpdateRoute.PINNED_HUB_WIFI
        @Volatile var catalog: ByteArray? = null
        val objects = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, LongRange?>>()
        @Volatile var gate: (LongRange) -> Unit = {}

        fun add(data: ByteArray) { objects[hex(Bytes.sha256(data))] = data }

        override fun get(path: String, range: LongRange?): HubResponse {
            requests += path to range
            fun none() = HubResponse(404, emptyMap(), ByteArrayInputStream(ByteArray(0)))
            if (path == "/updates/v1/catalog") return catalog?.let { HubResponse(200, mapOf("Content-Length" to listOf(it.size.toString())), ByteArrayInputStream(it)) } ?: none()
            if (path == "/updates/v1/recovery") return none()
            val name = path.removePrefix("/updates/v1/objects/")
            val data = objects[name] ?: return none()
            val r = range ?: throw IOException("this fake serves ranges only")
            gate(r)
            val to = minOf(r.last, data.size - 1L)
            return HubResponse(206, mapOf("ETag" to listOf("\"$name\""), "Content-Range" to listOf("bytes ${r.first}-$to/${data.size}"),
                "Content-Length" to listOf((to - r.first + 1).toString())), ByteArrayInputStream(data, r.first.toInt(), (to - r.first + 1).toInt()))
        }
    }
}

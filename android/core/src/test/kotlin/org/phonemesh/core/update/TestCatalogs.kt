package org.phonemesh.core.update

import org.phonemesh.core.crypto.Bytes
import java.nio.ByteBuffer

/** Builds signed catalogs and recovery policies for the TEST keys (an independent encoder; the real tool is in the hub). */
object TestCatalogs {
    fun sha(s: String): ByteArray = Bytes.sha256(s.toByteArray())

    /** A pack entry. [seed] makes the digests; the same seed always gives the same artifact. */
    fun pack(
        id: String, version: Long, kind: Int = UpdateMetadata.VOICE, seed: String = "$id-$version", bytes: Long = 10_000, minApp: Long = 1, maxApp: Long = 0,
        minApi: Int = 26, format: Int = 2, expanded: Long = 20_000, files: Int = 5, label: String = version.toString(),
    ) = UpdateMetadata.Entry(kind, id, label, "", version, minApp, maxApp, minApi, format, bytes, sha("zip:$seed"), expanded, files, sha("sums:$seed"), ByteArray(32))

    fun apk(version: Long, abi: String = "arm64-v8a", seed: String = "apk-$version") =
        UpdateMetadata.Entry(UpdateMetadata.APK, UpdateMetadata.APP_ID, "0.1.$version", abi, version, 1, 0, 26, 0, 5_000_000, sha("apk:$seed"), 0, 0, ByteArray(32), sha("cert"))

    fun payload(
        epoch: Long, sequence: Long, entries: List<UpdateMetadata.Entry>, revoked: List<ByteArray> = emptyList(), minReader: Long = 1,
        issued: Long = 1_793_145_600, review: Long = 1_808_697_600,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(1)
        out.write(ByteBuffer.allocate(42).putLong(epoch).putLong(sequence).putLong(issued).putLong(review).putLong(minReader).putShort(entries.size.toShort()).array())
        for (e in entries.sortedWith(compareBy({ it.kind }, { it.id }, { it.version }, { it.abi }))) {
            val id = e.id.toByteArray(); val label = e.label.toByteArray(); val abi = e.abi.toByteArray()
            out.write(byteArrayOf(e.kind.toByte(), id.size.toByte(), label.size.toByte(), abi.size.toByte()))
            out.write(ByteBuffer.allocate(38).putLong(e.version).putLong(e.minApp).putLong(e.maxApp).putInt(e.minApi).putShort(e.packFormat.toShort()).putLong(e.bytes).array())
            out.write(e.digest); out.write(ByteBuffer.allocate(12).putLong(e.expanded).putInt(e.files).array()); out.write(e.sums); out.write(e.cert)
            out.write(id); out.write(label); out.write(abi)
        }
        out.write(ByteBuffer.allocate(2).putShort(revoked.size.toShort()).array())
        for (d in revoked.sortedWith { a, b -> a.zip(b).map { (x, y) -> (x.toInt() and 0xFF) - (y.toInt() and 0xFF) }.firstOrNull { it != 0 } ?: 0 }) out.write(d)
        return out.toByteArray()
    }

    fun catalog(
        epoch: Long, sequence: Long, entries: List<UpdateMetadata.Entry>, revoked: List<ByteArray> = emptyList(), minReader: Long = 1,
        key: TestKeys.Pair = TestKeys.catalog, keyId: Int = 1,
    ): ByteArray = UpdateMetadata.sign(UpdateMetadata.CATALOG, keyId, payload(epoch, sequence, entries, revoked, minReader), key.private)

    fun policy(
        epoch: Long, newKey: TestKeys.Pair, newKeyId: Int = 2, minSequence: Long = 1, minReader: Long = 1,
        by: TestKeys.Pair = TestKeys.recovery, signerId: Int = UpdateMetadata.RECOVERY_KEY_ID,
    ): ByteArray = UpdateMetadata.sign(UpdateMetadata.RECOVERY, signerId,
        ByteBuffer.allocate(87).putLong(epoch).putShort(newKeyId.toShort()).put(newKey.public).putLong(minSequence).putInt(minReader.toInt()).array(), by.private)

    val roots: UpdateRoots by lazy {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        UpdateRoots.parse("# status: TEST\ncatalog key_id=1 public_key=${hex(TestKeys.catalog.compressed)}\nrecovery key_id=100 public_key=${hex(TestKeys.recovery.compressed)}\n")
    }

    /** The test baseline: epoch 1, sequence 1, two packs at version 1. */
    val baselineEntries = listOf(pack("voice-fr-siwis", 1), pack("engine-whisper-tiny", 1, UpdateMetadata.ENGINE))
    val baseline: ByteArray by lazy { catalog(1, 1, baselineEntries) }
}

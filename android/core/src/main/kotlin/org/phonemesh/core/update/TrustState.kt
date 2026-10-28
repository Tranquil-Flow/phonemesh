package org.phonemesh.core.update

import org.phonemesh.core.crypto.Bytes
import java.nio.ByteBuffer
import java.util.TreeMap

/**
 * Everything the phone has decided to trust for updates, in one record (spec 5.3): the trust epoch and the catalog key of
 * that epoch, the highest accepted catalog number and the accepted catalog itself, the recovery policy that started this
 * epoch, and per pack the version floor (and what was installed at it). A record is `PMTS | schema | generation | ...`, closed
 * by the SHA-256 of everything before it, so a torn or damaged record is recognised and never half-used.
 */
class TrustState(
    val generation: Long, val epoch: Long, val keyId: Int, key: ByteArray, val sequence: Long,
    catalog: ByteArray?, policy: ByteArray?, floors: Map<String, Floor>,
) {
    /** The version installed for one pack id, the digest of its `SHA256SUMS` and of its published archive. */
    class Floor(val version: Long, sums: ByteArray, artifact: ByteArray) {
        val sums: ByteArray = sums.copyOf()
        val artifact: ByteArray = artifact.copyOf()
    }

    val key: ByteArray = key.copyOf()
    val catalog: ByteArray? = catalog?.copyOf()
    val policy: ByteArray? = policy?.copyOf()
    val floors: Map<String, Floor> = TreeMap(floors)

    fun copy(
        generation: Long = this.generation, epoch: Long = this.epoch, keyId: Int = this.keyId, key: ByteArray = this.key, sequence: Long = this.sequence,
        catalog: ByteArray? = this.catalog, policy: ByteArray? = this.policy, floors: Map<String, Floor> = this.floors,
    ) = TrustState(generation, epoch, keyId, key, sequence, catalog, policy, floors)

    fun encode(): ByteArray {
        val c = catalog ?: ByteArray(0); val p = policy ?: ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC); out.write(SCHEMA)
        out.write(ByteBuffer.allocate(8 + 8 + 2).putLong(generation).putLong(epoch).putShort(keyId.toShort()).array())
        out.write(key)
        out.write(ByteBuffer.allocate(8 + 4).putLong(sequence).putInt(c.size).array()); out.write(c)
        out.write(ByteBuffer.allocate(4).putInt(p.size).array()); out.write(p)
        out.write(ByteBuffer.allocate(2).putShort(floors.size.toShort()).array())
        for ((name, f) in floors) {
            val kind = name.substringBefore('/').toInt(); val id = name.substringAfter('/').toByteArray()
            out.write(byteArrayOf(kind.toByte(), id.size.toByte())); out.write(id)
            out.write(ByteBuffer.allocate(8).putLong(f.version).array()); out.write(f.sums); out.write(f.artifact)
        }
        val body = out.toByteArray()
        return body + Bytes.sha256(body)
    }

    companion object {
        private val MAGIC = "PMTS".toByteArray(Charsets.US_ASCII)
        private const val SCHEMA = 1
        const val MAX_FLOORS = 512
        private const val MAX_RECORD = 4 + 1 + 18 + 65 + 12 + 65_536 + 4 + 65_536 + 2 + MAX_FLOORS * (2 + 64 + 72) + 32

        fun floorKey(kind: Int, id: String) = "$kind/$id"

        /** The record in [bytes], or null for anything that is not exactly one whole, well-formed record. */
        fun decode(bytes: ByteArray): TrustState? {
            if (bytes.size < 4 + 1 + 18 + 65 + 12 + 4 + 2 + 32 || bytes.size > MAX_RECORD) return null
            val body = bytes.copyOfRange(0, bytes.size - 32)
            if (!Bytes.equal(Bytes.sha256(body), bytes.copyOfRange(bytes.size - 32, bytes.size))) return null
            return try {
                val b = ByteBuffer.wrap(body)
                if (!ByteArray(4).also(b::get).contentEquals(MAGIC) || b.get().toInt() != SCHEMA) return null
                val generation = b.long; val epoch = b.long; val keyId = b.short.toInt() and 0xFFFF
                val key = ByteArray(65).also(b::get)
                val sequence = b.long
                fun blob(): ByteArray? {
                    val n = b.int
                    if (n < 0 || n > UpdateMetadata.MAX_ENVELOPE || n > b.remaining()) throw IllegalArgumentException()
                    return if (n == 0) null else ByteArray(n).also(b::get)
                }
                val catalog = blob(); val policy = blob()
                val count = b.short.toInt() and 0xFFFF
                if (count > MAX_FLOORS) return null
                val floors = TreeMap<String, Floor>()
                repeat(count) {
                    val kind = b.get().toInt() and 0xFF; val idLen = b.get().toInt() and 0xFF
                    if (kind != UpdateMetadata.ENGINE && kind != UpdateMetadata.VOICE || idLen !in 1..64 || idLen > b.remaining()) throw IllegalArgumentException()
                    val id = String(ByteArray(idLen).also(b::get), Charsets.UTF_8)
                    val version = b.long
                    if (version <= 0) throw IllegalArgumentException()
                    val f = Floor(version, ByteArray(32).also(b::get), ByteArray(32).also(b::get))
                    if (floors.put(floorKey(kind, id), f) != null) throw IllegalArgumentException()
                }
                if (b.hasRemaining() || generation <= 0 || epoch <= 0 || keyId == 0 || sequence < -1 || UpdateMetadata.publicKey(key) == null) return null
                TrustState(generation, epoch, keyId, key, sequence, catalog, policy, floors)
            } catch (_: RuntimeException) { null }
        }
    }
}

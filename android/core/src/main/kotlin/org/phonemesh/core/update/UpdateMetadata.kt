package org.phonemesh.core.update

import org.phonemesh.core.crypto.P256
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint

/**
 * Signed update metadata, `PMUP` (hub updates spec 5.2; decision 45). An envelope is
 * `PMUP | schema 1 | kind | key id u16 | payload length u32 | payload | raw P-256 signature (64, low S)`, signed over
 * `"phonemesh-update-v1\0" | header | payload`. Kind 1 is a release catalog signed by the catalog key; kind 2 a
 * recovery policy signed only by the recovery key. The verifier the hub's signing and import tools and the phone's [UpdateTrust] share.
 * Nothing here trusts a URL, a file name or the hub: only these bytes and the keys the app ships.
 */
object UpdateMetadata {
    const val SCHEMA = 1
    const val CATALOG = 1
    const val RECOVERY = 2
    const val MAX_ENVELOPE = 65_536
    const val MAX_ENTRIES = 128
    const val MAX_REVOKED = 256
    const val APK = 1
    const val ENGINE = 2
    const val VOICE = 3
    const val MAX_APK_BYTES = 64L * 1024 * 1024
    const val MAX_PACK_BYTES = 128L * 1024 * 1024
    const val MAX_EXPANDED = 512L * 1024 * 1024
    const val MAX_FILES = 5_000
    const val APP_ID = "org.phonemesh.app"
    /** The recovery key's id: only kind 2 uses it, and a catalog key may never take it (review D1, D7). */
    const val RECOVERY_KEY_ID = 100
    private val MAGIC = "PMUP".toByteArray(Charsets.US_ASCII)
    private val DOMAIN = "phonemesh-update-v1\u0000".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 12
    private val ABIS = setOf("", "arm64-v8a", "armeabi-v7a", "x86_64")
    private val PACK_ID = Regex("^[A-Za-z0-9-]{3,64}$")

    class Entry(
        val kind: Int, val id: String, val label: String, val abi: String, val version: Long,
        val minApp: Long, val maxApp: Long, val minApi: Int, val packFormat: Int, val bytes: Long, digest: ByteArray,
        val expanded: Long, val files: Int, sums: ByteArray, cert: ByteArray,
    ) {
        val digest: ByteArray = digest.copyOf()
        val sums: ByteArray = sums.copyOf()
        val cert: ByteArray = cert.copyOf()
    }

    class Catalog(
        val channel: Int, val epoch: Long, val sequence: Long, val issuedAt: Long, val reviewAfter: Long, val minReader: Long,
        val entries: List<Entry>, revoked: List<ByteArray>,
    ) { val revoked: List<ByteArray> = revoked.map { it.copyOf() } }

    class Recovery(val epoch: Long, val catalogKeyId: Int, catalogKey: ByteArray, val minSequence: Long, val minReader: Long) {
        val catalogKey: ByteArray = catalogKey.copyOf()
    }

    /** A checked envelope: who signed it (key id) and its payload. */
    class Envelope(val kind: Int, val keyId: Int, val payload: ByteArray)

    /**
     * The envelope in [bytes] when it is well formed and signed by the key its kind allows; null otherwise. A catalog
     * verifies only under [catalogKeys] (allowlisted ids, never [RECOVERY_KEY_ID]); a recovery policy only under
     * [recoveryKey] at [RECOVERY_KEY_ID], so a catalog key can never sign its own successor (review D1). Keys are
     * 65-byte uncompressed P-256 public keys.
     */
    fun verify(bytes: ByteArray, catalogKeys: Map<Int, ByteArray>, recoveryKey: ByteArray?): Envelope? {
        if (bytes.size < HEADER + 64 || bytes.size > MAX_ENVELOPE) return null
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4].toInt() != SCHEMA) return null
        val b = ByteBuffer.wrap(bytes)
        val kind = bytes[5].toInt() and 0xFF
        if (kind != CATALOG && kind != RECOVERY) return null
        val keyId = b.getShort(6).toInt() and 0xFFFF
        val length = b.getInt(8)
        if (length < 0 || HEADER.toLong() + length + 64 != bytes.size.toLong()) return null
        val encoded = when (kind) {
            CATALOG -> if (keyId == RECOVERY_KEY_ID) null else catalogKeys[keyId]
            else -> if (keyId == RECOVERY_KEY_ID) recoveryKey else null
        }
        val key = encoded?.let(::publicKey) ?: return null
        val signature = bytes.copyOfRange(HEADER + length, bytes.size)
        if (BigInteger(1, signature.copyOfRange(32, 64)) > P256.n.shiftRight(1)) return null
        if (!P256.verify(key, DOMAIN + bytes.copyOfRange(0, HEADER + length), signature)) return null
        return Envelope(kind, keyId, bytes.copyOfRange(HEADER, HEADER + length))
    }

    /** Signs [payload] as an envelope: for release tooling and tests; no private update key ever lives on a phone or hub. */
    fun sign(kind: Int, keyId: Int, payload: ByteArray, key: PrivateKey): ByteArray {
        require(HEADER + payload.size + 64 <= MAX_ENVELOPE)
        val header = MAGIC + byteArrayOf(SCHEMA.toByte(), kind.toByte()) + ByteBuffer.allocate(6).putShort(keyId.toShort()).putInt(payload.size).array()
        val raw = P256.sign(key, DOMAIN + header + payload)
        val s = BigInteger(1, raw.copyOfRange(32, 64))
        val low = if (s > P256.n.shiftRight(1)) P256.n - s else s
        return header + payload + raw.copyOfRange(0, 32) + fixed(low)
    }

    fun catalog(payload: ByteArray): Catalog? = try { readCatalog(Fields(payload)) } catch (_: Bad) { null } catch (_: RuntimeException) { null }

    fun recovery(payload: ByteArray): Recovery? = try {
        val f = Fields(payload)
        val r = Recovery(f.u64(), f.u16(), f.raw(65), f.u64(), f.u32())
        need(publicKey(r.catalogKey) != null && r.epoch > 0 && r.catalogKeyId != RECOVERY_KEY_ID && r.catalogKeyId != 0)
        f.end(); r
    } catch (_: Bad) { null } catch (_: RuntimeException) { null }

    private fun readCatalog(f: Fields): Catalog {
        val channel = f.u8(); need(channel == 1)
        val epoch = f.u64(); val sequence = f.u64(); val issued = f.u64(); val review = f.u64(); val minReader = f.u64()
        val count = f.u16(); need(count <= MAX_ENTRIES)
        val entries = List(count) { readEntry(f) }
        for (i in 1 until entries.size) need(order(entries[i - 1], entries[i]) < 0)
        val revokedCount = f.u16(); need(revokedCount <= MAX_REVOKED)
        val revoked = List(revokedCount) { f.raw(32) }
        for (i in 1 until revoked.size) need(compare(revoked[i - 1], revoked[i]) < 0)
        f.end()
        return Catalog(channel, epoch, sequence, issued, review, minReader, entries, revoked)
    }

    private fun readEntry(f: Fields): Entry {
        val kind = f.u8(); need(kind in APK..VOICE)
        val idLen = f.u8(); val labelLen = f.u8(); val abiLen = f.u8()
        need(idLen in 1..64 && labelLen in 1..64 && abiLen <= 32)
        val version = f.u64(); val minApp = f.u64(); val maxApp = f.u64(); val minApi = f.u32().toInt(); val format = f.u16()
        val bytes = f.u64(); val digest = f.raw(32); val expanded = f.u64(); val files = f.u32().toInt()
        val sums = f.raw(32); val cert = f.raw(32)
        val id = f.text(idLen); val label = f.text(labelLen); val abi = f.text(abiLen)
        need(version > 0 && (maxApp == 0L || maxApp >= minApp) && minApi in 1..1000 && abi in ABIS && bytes > 0)
        val zero = ByteArray(32)
        need(!digest.contentEquals(zero))
        if (kind == APK) {
            need(id == APP_ID && format == 0 && bytes <= MAX_APK_BYTES && expanded == 0L && files == 0)
            need(sums.contentEquals(zero) && !cert.contentEquals(zero) && abi.isNotEmpty())
        } else {
            need(PACK_ID.matches(id) && format == 2 && bytes <= MAX_PACK_BYTES && version <= Int.MAX_VALUE)
            need(expanded in 1..MAX_EXPANDED && files in 1..MAX_FILES && !sums.contentEquals(zero) && cert.contentEquals(zero))
        }
        return Entry(kind, id, label, abi, version, minApp, maxApp, minApi, format, bytes, digest, expanded, files, sums, cert)
    }

    /** Spec order (kind, id, version, abi); equal tuples are refused as duplicates. */
    private fun order(a: Entry, b: Entry): Int = compareValuesBy(a, b, { it.kind }, { it.id }, { it.version }, { it.abi })

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) { val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF); if (c != 0) return c }
        return 0
    }

    /** An uncompressed (65-byte) P-256 public key on the curve, or null. */
    fun publicKey(encoded: ByteArray): ECPublicKey? {
        if (encoded.size != 65 || encoded[0].toInt() != 4) return null
        val x = BigInteger(1, encoded.copyOfRange(1, 33)); val y = BigInteger(1, encoded.copyOfRange(33, 65))
        if (x >= P256.p || y >= P256.p || !P256.onCurve(x, y)) return null
        return P256.publicKey(ECPoint(x, y))
    }

    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - raw.size) + raw
    }

    private class Bad : Exception()
    private fun need(ok: Boolean) { if (!ok) throw Bad() }

    private class Fields(bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes)
        fun raw(n: Int): ByteArray { need(n <= b.remaining()); return ByteArray(n).also { b.get(it) } }
        fun u8(): Int { need(b.remaining() >= 1); return b.get().toInt() and 0xFF }
        fun u16(): Int { need(b.remaining() >= 2); return b.short.toInt() and 0xFFFF }
        fun u32(): Long { need(b.remaining() >= 4); return b.int.toLong() and 0xFFFFFFFFL }
        fun u64(): Long { need(b.remaining() >= 8); val n = b.long; need(n >= 0); return n }
        /** Bounded UTF-8 with no control characters, bidi controls or markup. */
        fun text(n: Int): String {
            val raw = raw(n)
            val s = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { throw Bad() }
            need(s.none { it.code < 0x20 || it.code == 0x7F || it in "<>&\"'" || it.code in 0x200B..0x200F || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 })
            return s
        }
        fun end() = need(!b.hasRemaining())
    }
}

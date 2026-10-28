package org.phonemesh.core.update

import org.phonemesh.core.crypto.P256

/**
 * The two public keys a phone and a hub trust for updates (decisions 45, 61, 70), and the signed baseline catalog that
 * ships with the app. This is the one place real keys enter the code: `update/public-keys.txt` in the resources holds
 * the owner's file exactly as the key-making script wrote it (`catalog key_id=1 public_key=<33 bytes hex>` and
 * `recovery key_id=100 public_key=<33 bytes hex>`). Until the owner hands that file over it holds TEST keys, whose private
 * parts anyone can compute from a public label. [isTest] is true when the file says `# status: TEST` OR when either key is
 * one of the known label-derived TEST keys ([KNOWN_TEST_KEYS]), so deleting the comment line does not make them real. A
 * release build must not run updates under TEST roots ([UpdateTrust.open] refuses them unless told otherwise).
 * The swap is one owner step: `phonemesh-hub sign-baseline` writes the owner's `public-keys.txt` and the re-signed
 * `baseline-catalog.pmup` together (docs/update-keys.md). Burnt catalog keys (optional `burnt public_key=<33 bytes hex>`
 * lines, added when a catalog key is rotated out) can never be adopted again through a replayed recovery policy.
 * Points are compressed in the file and decompressed here to the 65-byte form [UpdateMetadata] verifies with.
 */
class UpdateRoots private constructor(
    val catalogKeyId: Int, catalogKey: ByteArray, val recoveryKeyId: Int, recoveryKey: ByteArray, val isTest: Boolean,
    burnt: List<ByteArray> = emptyList(),
) {
    val catalogKey: ByteArray = catalogKey.copyOf()
    val recoveryKey: ByteArray = recoveryKey.copyOf()
    val burnt: List<ByteArray> = burnt.map { it.copyOf() }

    /** Names these keys and ids: trust state records it, so state made under other roots is never carried over (U7-2). */
    val id: ByteArray get() = idOf(catalogKeyId, this.catalogKey, recoveryKeyId, this.recoveryKey)

    companion object {
        private const val MAX_TEXT = 4096
        private val LINE = Regex("(catalog|recovery) key_id=(\\d{1,5}) public_key=([0-9a-fA-F]{66})")
        private val BURNT = Regex("burnt public_key=([0-9a-fA-F]{66})")
        private const val MAX_BURNT = 16

        /**
         * The compressed public keys whose private scalars are SHA-256 of a public label (the TEST keys of the tests and of
         * the first packaged baseline). Anyone can sign with them, so roots holding one are never real, whatever the file says.
         */
        val KNOWN_TEST_KEYS: Set<String> = setOf(
            "03fdb408c61ac3b82df72a7ebde885a12eaf3e5d66c7eaf23c2b84c42820498c88",
            "0334584c514e4c29766b0bd6599acd24ae9a52b24bdeb3d988cf34bbd2a59e7169",
            "037d3fda3b527f00cd99428c022a48a084b9743390ecf097d5f4aa5e20b0d7bd0c",
            "03d866f3bd7de3b7caea20559a84dfda764ae8283b389ced76e866eab709dbead9",
        )

        /** A 32-byte name for a pair of roots (ids and keys; the burnt list is not part of it). */
        fun idOf(catalogKeyId: Int, catalogKey: ByteArray, recoveryKeyId: Int, recoveryKey: ByteArray): ByteArray =
            org.phonemesh.core.crypto.Bytes.sha256(
                "phonemesh update roots v1\u0000".toByteArray(Charsets.US_ASCII), byteArrayOf((catalogKeyId shr 8).toByte(), catalogKeyId.toByte()), catalogKey,
                byteArrayOf((recoveryKeyId shr 8).toByte(), recoveryKeyId.toByte()), recoveryKey,
            )

        /** Reads the owner's key file text; throws [IllegalArgumentException] for anything else. */
        fun parse(text: String): UpdateRoots {
            require(text.length <= MAX_TEXT) { "the key file is too large" }
            var test = false
            var catalog: Pair<Int, ByteArray>? = null
            var recovery: Pair<Int, ByteArray>? = null
            val burnt = ArrayList<ByteArray>()
            var knownTest = false
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) { if (line.replace(" ", "").startsWith("#status:TEST")) test = true; continue }
                BURNT.matchEntire(line)?.let { b ->
                    require(burnt.size < MAX_BURNT) { "too many burnt keys" }
                    val compressed = b.groupValues[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    require(P256.decompress(compressed) != null) { "not a point of P-256" }
                    burnt += compressed
                    continue
                }
                val m = requireNotNull(LINE.matchEntire(line)) { "unrecognised line in the key file" }
                if (m.groupValues[3].lowercase() in KNOWN_TEST_KEYS) knownTest = true
                val id = m.groupValues[2].toInt()
                require(id in 1..0xFFFF) { "key id out of range" }
                val compressed = m.groupValues[3].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val point = requireNotNull(P256.decompress(compressed)) { "not a point of P-256" }
                val key = byteArrayOf(4) + org.phonemesh.core.crypto.Bytes.fixed(point.affineX, 32) + org.phonemesh.core.crypto.Bytes.fixed(point.affineY, 32)
                require(UpdateMetadata.publicKey(key) != null) { "not a point of P-256" }
                if (m.groupValues[1] == "catalog") {
                    require(catalog == null) { "two catalog keys" }
                    require(id != UpdateMetadata.RECOVERY_KEY_ID) { "the catalog key cannot use the recovery key id" }
                    catalog = id to key
                } else {
                    require(recovery == null) { "two recovery keys" }
                    require(id == UpdateMetadata.RECOVERY_KEY_ID) { "the recovery key id is ${UpdateMetadata.RECOVERY_KEY_ID}" }
                    recovery = id to key
                }
            }
            requireNotNull(catalog) { "no catalog key" }
            requireNotNull(recovery) { "no recovery key" }
            require(!catalog.second.contentEquals(recovery.second)) { "the catalog and recovery keys must differ" }
            val uncompressedBurnt = burnt.map { c -> P256.decompress(c)!!.let { byteArrayOf(4) + org.phonemesh.core.crypto.Bytes.fixed(it.affineX, 32) + org.phonemesh.core.crypto.Bytes.fixed(it.affineY, 32) } }
            return UpdateRoots(catalog.first, catalog.second, recovery.first, recovery.second, test || knownTest, uncompressedBurnt)
        }

        private fun resource(name: String): ByteArray =
            checkNotNull(UpdateRoots::class.java.getResourceAsStream("/update/$name")) { "missing resource update/$name" }.use { it.readBytes() }

        /** The keys packaged in this build. */
        val compiled: UpdateRoots by lazy { parse(String(resource("public-keys.txt"), Charsets.UTF_8)) }

        /** The signed catalog of the packs this build knows, as the first thing a fresh install trusts. */
        fun baselineCatalog(): ByteArray = resource("baseline-catalog.pmup")
    }
}

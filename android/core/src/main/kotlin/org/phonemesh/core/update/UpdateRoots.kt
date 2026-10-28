package org.phonemesh.core.update

import org.phonemesh.core.crypto.P256

/**
 * The two public keys a phone and a hub trust for updates (decisions 45, 61, 70), and the signed baseline catalog that
 * ships with the app. This is the one place real keys enter the code: `update/public-keys.txt` in the resources holds
 * the owner's file exactly as the key-making script wrote it (`catalog key_id=1 public_key=<33 bytes hex>` and
 * `recovery key_id=100 public_key=<33 bytes hex>`). Until the owner hands that file over it holds TEST keys, marked by
 * the line `# status: TEST`, and [isTest] says so. Swapping is: replace that file, re-sign `baseline-catalog.pmup`.
 * Points are compressed in the file and decompressed here to the 65-byte form [UpdateMetadata] verifies with.
 */
class UpdateRoots private constructor(
    val catalogKeyId: Int, catalogKey: ByteArray, val recoveryKeyId: Int, recoveryKey: ByteArray, val isTest: Boolean,
) {
    val catalogKey: ByteArray = catalogKey.copyOf()
    val recoveryKey: ByteArray = recoveryKey.copyOf()

    companion object {
        private const val MAX_TEXT = 4096
        private val LINE = Regex("(catalog|recovery) key_id=(\\d{1,5}) public_key=([0-9a-fA-F]{66})")

        /** Reads the owner's key file text; throws [IllegalArgumentException] for anything else. */
        fun parse(text: String): UpdateRoots {
            require(text.length <= MAX_TEXT) { "the key file is too large" }
            var test = false
            var catalog: Pair<Int, ByteArray>? = null
            var recovery: Pair<Int, ByteArray>? = null
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) { if (line.replace(" ", "").startsWith("#status:TEST")) test = true; continue }
                val m = requireNotNull(LINE.matchEntire(line)) { "unrecognised line in the key file" }
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
            return UpdateRoots(catalog.first, catalog.second, recovery.first, recovery.second, test)
        }

        private fun resource(name: String): ByteArray =
            checkNotNull(UpdateRoots::class.java.getResourceAsStream("/update/$name")) { "missing resource update/$name" }.use { it.readBytes() }

        /** The keys packaged in this build. */
        val compiled: UpdateRoots by lazy { parse(String(resource("public-keys.txt"), Charsets.UTF_8)) }

        /** The signed catalog of the packs this build knows, as the first thing a fresh install trusts. */
        fun baselineCatalog(): ByteArray = resource("baseline-catalog.pmup")
    }
}

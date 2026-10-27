package org.phonemesh.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.io.File
import java.nio.ByteBuffer
import java.security.interfaces.ECPublicKey

/** PMUP (hub updates spec 5.2). Vectors from `.local/crypto-vectors/update_vectors.py`, written from the spec tables. */
class UpdateMetadataTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/update/update-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val keys get() = mapOf(1 to hex("catalogKey"), 100 to hex("recoveryKey"))

    /** Both keys at hand, as a phone has them: catalogs only under catalog ids, policies only under the recovery key. */
    private fun verify(b: ByteArray, keys: Map<Int, ByteArray>) =
        UpdateMetadata.verify(b, keys.filterKeys { it != UpdateMetadata.RECOVERY_KEY_ID }, keys[UpdateMetadata.RECOVERY_KEY_ID])

    private fun pub(k: java.security.KeyPair) =
        byteArrayOf(4) + fixed((k.public as ECPublicKey).w.affineX) + fixed((k.public as ECPublicKey).w.affineY)

    /** Review D1: the kind decides the key; a catalog key never signs a policy, the recovery key never a catalog. */
    @Test fun eachKindVerifiesOnlyUnderItsOwnKey() {
        val c = P256.generate(); val r = P256.generate()
        val both = mapOf(1 to pub(c), 100 to pub(r))
        val policy = ByteBuffer.allocate(8 + 2 + 65 + 8 + 4).putLong(2).putShort(2).put(pub(P256.generate())).putLong(1).putInt(1).array()
        assertNotNull(verify(UpdateMetadata.sign(UpdateMetadata.RECOVERY, 100, policy, r.private), both))
        assertNull("a policy signed by the catalog key, under its own id", verify(UpdateMetadata.sign(UpdateMetadata.RECOVERY, 1, policy, c.private), both))
        assertNull("a policy signed by the catalog key, under the recovery id", verify(UpdateMetadata.sign(UpdateMetadata.RECOVERY, 100, policy, c.private), both))
        val p = hex("catalogPayload")
        assertNotNull(verify(UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, p, c.private), both))
        assertNull("a catalog under the recovery id", verify(UpdateMetadata.sign(UpdateMetadata.CATALOG, 100, p, r.private), both))
        assertNull("a catalog key at the recovery id", verify(UpdateMetadata.sign(UpdateMetadata.CATALOG, 100, p, c.private), mapOf(100 to pub(c))))
    }

    /** Review D7: no zero digest, no catalog key at the recovery id, no zero-width or direction marks in labels. */
    @Test fun parserHardening() {
        val p = hex("catalogPayload")
        val zero = p.copyOf().also { ByteArray(32).copyInto(it, 43 + 42) }
        assertNull(UpdateMetadata.catalog(zero))
        val lrm = p.copyOf().also { "\u200E1.".toByteArray().copyInto(it, 43 + 150 + 17) }
        assertNull(UpdateMetadata.catalog(lrm))
        val policy = { id: Int -> ByteBuffer.allocate(8 + 2 + 65 + 8 + 4).putLong(2).putShort(id.toShort()).put(hex("catalogKey2")).putLong(1).putInt(1).array() }
        assertNotNull(UpdateMetadata.recovery(policy(2)))
        assertNull(UpdateMetadata.recovery(policy(100)))
    }

    @Test fun thePythonCatalogVerifiesAndReadsAsTheSpecSays() {
        val env = verify(hex("catalog"), keys)!!
        assertEquals(UpdateMetadata.CATALOG, env.kind); assertEquals(1, env.keyId)
        assertArrayEquals(hex("catalogPayload"), env.payload)
        val c = UpdateMetadata.catalog(env.payload)!!
        assertEquals(1L, c.epoch); assertEquals(7L, c.sequence); assertEquals(2L, c.minReader)
        assertEquals(2, c.entries.size)
        val apk = c.entries[0]
        assertEquals("org.phonemesh.app", apk.id); assertEquals("arm64-v8a", apk.abi); assertEquals(2L, apk.version)
        assertArrayEquals(hex("apkDigest"), apk.digest); assertArrayEquals(hex("certDigest"), apk.cert)
        val voice = c.entries[1]
        assertEquals("phonemesh-voice-fr-siwis", voice.id); assertEquals(4, voice.files); assertArrayEquals(hex("sumsDigest"), voice.sums)
        assertArrayEquals(hex("revoked"), c.revoked.single())
    }

    @Test fun theRecoveryPolicyVerifiesOnlyUnderTheRecoveryKey() {
        val env = verify(hex("policy"), keys)!!
        assertEquals(UpdateMetadata.RECOVERY, env.kind); assertEquals(100, env.keyId)
        val r = UpdateMetadata.recovery(env.payload)!!
        assertEquals(2L, r.epoch); assertEquals(2, r.catalogKeyId); assertArrayEquals(hex("catalogKey2"), r.catalogKey)
        assertNull("signed by R, so not valid under the catalog key's id", verify(hex("policy"), mapOf(100 to hex("catalogKey"))))
    }

    @Test fun kotlinSignsAnEnvelopePythonVerifies() {
        val k = P256.generate()
        val env = UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, hex("catalogPayload"), k.private)
        val pub = byteArrayOf(4) + fixed((k.public as ECPublicKey).w.affineX) + fixed((k.public as ECPublicKey).w.affineY)
        assertNotNull(verify(env, mapOf(1 to pub)))
        File(System.getProperty("java.io.tmpdir"), "pmup-kotlin.txt").writeText(env.hex() + " " + pub.hex())
    }

    @Test fun everyFlippedBitCutAndExtraByteIsRefused() {
        val e = hex("catalog")
        for (i in e.indices) for (bit in 0..7) {
            val bad = e.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertNull("byte $i bit $bit", verify(bad, keys))
        }
        for (n in 0 until e.size) assertNull("cut $n", verify(e.copyOf(n), keys))
        assertNull(verify(e + 0, keys))
        assertNull("an unknown key id", verify(e, mapOf(2 to hex("catalogKey"))))
    }

    @Test fun aHighSSignatureIsRefused() {
        val e = hex("catalog")
        val n = P256.n
        val s = java.math.BigInteger(1, e.copyOfRange(e.size - 32, e.size))
        val high = fixed(n - s)
        val flipped = e.copyOfRange(0, e.size - 32) + high
        assertTrue("the high-S twin is a valid ECDSA signature", P256.verify(UpdateMetadata.publicKey(hex("catalogKey"))!!,
            "phonemesh-update-v1\u0000".toByteArray() + e.copyOfRange(0, e.size - 64), flipped.copyOfRange(flipped.size - 64, flipped.size)))
        assertNull("but malleable signatures are refused", verify(flipped, keys))
    }

    private fun resign(payload: ByteArray): ByteArray? {
        val k = P256.generate()
        val pub = byteArrayOf(4) + fixed((k.public as ECPublicKey).w.affineX) + fixed((k.public as ECPublicKey).w.affineY)
        val env = verify(UpdateMetadata.sign(1, 1, payload, k.private), mapOf(1 to pub))!!
        return env.payload
    }

    @Test fun catalogRulesRefuseWhatTheSpecForbids() {
        val p = hex("catalogPayload")
        assertNotNull(UpdateMetadata.catalog(resign(p)!!))
        fun bad(change: (ByteArray) -> Unit) { val q = p.copyOf().also(change); assertNull(UpdateMetadata.catalog(q)) }
        bad { it[0] = 2 }                               // channel 1 only
        bad { ByteBuffer.wrap(it).putShort(41, 3) }     // entry count beyond the entries
        bad { it[43] = 4 }                              // unknown kind
        assertNull("trailing bytes", UpdateMetadata.catalog(p + 0))
        // Entries out of order: swap the voice and APK entries.
        val apkLen = 150 + "org.phonemesh.app".length + "0.1.1".length + "arm64-v8a".length
        val voiceLen = 150 + "phonemesh-voice-fr-siwis".length + 1
        val swapped = p.copyOfRange(0, 43) + p.copyOfRange(43 + apkLen, 43 + apkLen + voiceLen) + p.copyOfRange(43, 43 + apkLen) +
            p.copyOfRange(43 + apkLen + voiceLen, p.size)
        assertNull(UpdateMetadata.catalog(swapped))
        // An APK entry naming another app.
        val other = p.copyOf().also { "org.phonemesh.xyz".toByteArray().copyInto(it, 43 + 150) }
        assertNull(UpdateMetadata.catalog(other))
        // Markup in a label.
        val markup = p.copyOf().also { it[43 + 150 + 17] = '<'.code.toByte() }
        assertNull(UpdateMetadata.catalog(markup))
    }

    @Test fun keysMustBeOnTheCurve() {
        assertNotNull(UpdateMetadata.publicKey(hex("catalogKey")))
        val off = hex("catalogKey").also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertNull(UpdateMetadata.publicKey(off))
        assertNull(UpdateMetadata.publicKey(hex("catalogKey").copyOf(64)))
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun fixed(v: java.math.BigInteger): ByteArray {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - raw.size) + raw
    }
}

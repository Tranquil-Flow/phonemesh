package org.phonemesh.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.update.UpdateMetadata
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** The owner's encrypted catalog key file (PKCS#8, PBES2, AES-256-CBC, as `make-update-keys.sh` writes it). */
class KeyFileTest {
    private val key = P256.generate()
    private val pass = "correct horse".toCharArray()

    private fun refused(pem: String, passphrase: CharArray = pass, why: String) {
        try { KeyFile.read(pem, passphrase); fail("accepted: $why") } catch (_: KeyFile.Rejected) { }
    }

    @Test fun anEncryptedKeyIsReadWithEitherPbkdf2Hash() {
        for (prf in listOf(TestPem.hmacSha1, TestPem.hmacSha256)) {
            val loaded = KeyFile.read(TestPem.encryptedPem(key, "correct horse", prf), pass)
            assertArrayEquals(TestKeys.pub(key), loaded.publicKey)
            val signed = UpdateMetadata.sign(UpdateMetadata.CATALOG, 1, ByteArray(10), loaded.privateKey)
            assertTrue("the loaded key signs for the public key", UpdateMetadata.verify(signed, mapOf(1 to loaded.publicKey), null) != null)
        }
    }

    @Test fun aKeyWithoutAnEmbeddedPublicPartIsStillUsable() {
        val pem = TestPem.pem("ENCRYPTED PRIVATE KEY", TestPem.encrypt(TestPem.pkcs8(key, withPublic = false), "correct horse"))
        assertArrayEquals(TestKeys.pub(key), KeyFile.read(pem, pass).publicKey)
    }

    @Test fun wrongOrEmptyPassphrasesAreRefused() {
        val pem = TestPem.encryptedPem(key, "correct horse")
        refused(pem, "wrong horse".toCharArray(), "wrong passphrase")
        refused(pem, CharArray(0), "empty passphrase")
        refused(pem, "correct horse ".toCharArray(), "trailing space")
    }

    @Test fun plainKeysAndOtherKindsOfFileAreRefused() {
        refused(TestPem.pem("PRIVATE KEY", TestPem.pkcs8(key)), why = "an unencrypted key")
        refused(TestPem.pem("EC PRIVATE KEY", TestPem.pkcs8(key)), why = "a legacy key block")
        refused(TestPem.pem("PUBLIC KEY", ByteArray(91)), why = "a public key")
        refused("", why = "empty")
        refused("not a key at all", why = "text")
        refused("-----BEGIN ENCRYPTED PRIVATE KEY-----\n!!!!\n-----END ENCRYPTED PRIVATE KEY-----\n", why = "bad base64")
        refused(TestPem.pem("ENCRYPTED PRIVATE KEY", ByteArray(40)), why = "random bytes")
        refused(TestPem.pem("ENCRYPTED PRIVATE KEY", TestPem.seq(TestPem.int(1))), why = "wrong structure")
        val pem = TestPem.encryptedPem(key, "correct horse")
        refused(pem + pem, why = "two blocks")
        refused(pem.replace("END ENCRYPTED", "END PRIVATE"), why = "mismatched markers")
        refused(pem.take(pem.length / 2), why = "cut short")
        refused("x".repeat(70_000), why = "too large")
    }

    @Test fun otherCurvesAndMismatchedPublicPartsAreRefused() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val d384 = (p384.private as java.security.interfaces.ECPrivateKey).s.toByteArray().let { it.copyOfRange(it.size - 32, it.size) }
        val other = seq384(d384)
        refused(TestPem.pem("ENCRYPTED PRIVATE KEY", TestPem.encrypt(other, "correct horse")), why = "P-384")
        val wrongPublic = TestKeys.pub(P256.generate())
        refused(TestPem.pem("ENCRYPTED PRIVATE KEY", TestPem.encrypt(TestPem.pkcs8(key, publicOverride = wrongPublic), "correct horse")), why = "public part of another key")
        val rsa = TestPem.seq(TestPem.int(0), TestPem.seq(byteArrayOf(6, 9, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 1, 1, 1), byteArrayOf(5, 0)), TestPem.oct(ByteArray(20)))
        refused(TestPem.pem("ENCRYPTED PRIVATE KEY", TestPem.encrypt(rsa, "correct horse")), why = "an RSA key")
    }

    private fun seq384(d: ByteArray): ByteArray = TestPem.seq(TestPem.int(0), TestPem.seq(TestPem.ecPublicKey, TestPem.p384), TestPem.oct(TestPem.seq(TestPem.int(1), TestPem.oct(d))))

    @Test fun weakOrAbsurdEncryptionSettingsAreRefused() {
        val plain = TestPem.pkcs8(key)
        refused(TestPem.pem("ENCRYPTED PRIVATE KEY", TestPem.encrypt(plain, "correct horse", iterations = 1)), why = "one iteration")
        refused(TestPem.pem("ENCRYPTED PRIVATE KEY", TestPem.encrypt(plain, "correct horse", iterations = 20_000_000)), why = "twenty million iterations")
        refused(TestPem.pem("ENCRYPTED PRIVATE KEY", TestPem.encrypt(plain, "correct horse", cipherOid = TestPem.aes128, keyBytes = 16)), why = "AES-128")
        assertEquals(KeyFile.MIN_ITERATIONS, 1000)
    }
}

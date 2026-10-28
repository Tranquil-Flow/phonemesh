package org.phonemesh.hub

import org.phonemesh.core.crypto.Bytes
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Writes private keys the way `openssl pkcs8 -topk8 -v2 aes-256-cbc` does (PBES2, PBKDF2, AES-256-CBC), for tests only. */
object TestPem {
    fun tlv(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + body
    }
    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun int(v: Int) = tlv(0x02, BigInteger.valueOf(v.toLong()).toByteArray())
    fun oct(b: ByteArray) = tlv(0x04, b)
    private fun oid(vararg b: Int) = tlv(0x06, ByteArray(b.size) { b[it].toByte() })

    val ecPublicKey = oid(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01)
    val p256 = oid(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07)
    val p384 = oid(0x2B, 0x81, 0x04, 0x00, 0x22)
    val pbes2 = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x05, 0x0D)
    val pbkdf2 = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x05, 0x0C)
    val hmacSha1 = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x02, 0x07)
    val hmacSha256 = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x02, 0x09)
    val aes256 = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x2A)
    val aes128 = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x02)

    fun pkcs8(key: KeyPair, withPublic: Boolean = true, publicOverride: ByteArray? = null, curve: ByteArray = p256): ByteArray {
        val d = Bytes.fixed((key.private as ECPrivateKey).s, 32)
        val w = (key.public as ECPublicKey).w
        val pub = publicOverride ?: (byteArrayOf(4) + Bytes.fixed(w.affineX, 32) + Bytes.fixed(w.affineY, 32))
        val inner = seq(int(1), oct(d), *(if (withPublic) arrayOf(tlv(0xA1, tlv(0x03, byteArrayOf(0) + pub))) else emptyArray()))
        return seq(int(0), seq(ecPublicKey, curve), oct(inner))
    }

    fun encrypt(
        plain: ByteArray, passphrase: String, prf: ByteArray = hmacSha256, iterations: Int = 2048, cipherOid: ByteArray = aes256,
        keyBytes: Int = 32, random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(16).also(random::nextBytes)
        val algorithm = if (prf.contentEquals(hmacSha1)) "PBKDF2WithHmacSHA1" else "PBKDF2WithHmacSHA256"
        val k = SecretKeyFactory.getInstance(algorithm).generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, iterations, keyBytes * 8)).encoded
        val ct = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv)) }.doFinal(plain)
        val kdf = seq(pbkdf2, seq(oct(salt), int(iterations), seq(prf, tlv(0x05, ByteArray(0)))))
        return seq(seq(pbes2, seq(kdf, seq(cipherOid, oct(iv)))), oct(ct))
    }

    fun pem(label: String, der: ByteArray): String =
        "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"

    fun encryptedPem(key: KeyPair, passphrase: String, prf: ByteArray = hmacSha256, iterations: Int = 2048): String =
        pem("ENCRYPTED PRIVATE KEY", encrypt(pkcs8(key), passphrase, prf, iterations))
}

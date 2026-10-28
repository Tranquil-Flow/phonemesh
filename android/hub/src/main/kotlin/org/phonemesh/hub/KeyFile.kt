package org.phonemesh.hub

import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import java.math.BigInteger
import java.security.PrivateKey
import java.security.spec.ECPoint
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads the owner's encrypted update key (`openssl pkcs8 -topk8 -v2 aes-256-cbc`: an `ENCRYPTED PRIVATE KEY` PEM block,
 * PBES2 with PBKDF2 and AES-256-CBC), as `make-update-keys.sh` writes it. Only that shape is accepted: an unencrypted
 * key is refused (a signing key must not lie on disk in the clear), as is any other curve than P-256, a public part
 * that does not belong to the private scalar, or a file that is not exactly one block. The key stays in memory only.
 * Parsing is a small strict DER reader; there is no extra library.
 */
object KeyFile {
    /** What `make-update-keys.sh` produces: macOS's openssl and OpenSSL 3 both write 2048 PBKDF2 rounds. Fewer is refused (U7-14). */
    const val MIN_ITERATIONS = 2048
    const val MAX_ITERATIONS = 5_000_000
    /** Below this many rounds, or with SHA-1, an offline guess of a typed passphrase is cheap: the key loads, with a warning. */
    const val STRONG_ITERATIONS = 100_000
    private const val MAX_FILE = 16 * 1024

    class Rejected(message: String) : Exception(message)
    class Loaded(val privateKey: PrivateKey, publicKey: ByteArray, val iterations: Int = 0, val sha1: Boolean = false) {
        val publicKey: ByteArray = publicKey.copyOf()
        /** Only lightly protected by its passphrase (few rounds, or SHA-1): say so, never refuse. */
        val lightlyProtected: Boolean get() = iterations < STRONG_ITERATIONS || sha1
    }

    private class Der(val bytes: ByteArray, var pos: Int = 0, val end: Int = bytes.size) {
        class Item(val tag: Int, val start: Int, val end: Int)
        fun next(expect: Int): Item {
            if (pos >= end) throw Rejected("the key file is damaged")
            val tag = bytes[pos++].toInt() and 0xFF
            if (tag != expect) throw Rejected("the key file is not a P-256 key (unexpected structure)")
            if (pos >= end) throw Rejected("the key file is damaged")
            var len = bytes[pos++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val count = len and 0x7F
                if (count == 0 || count > 3 || pos + count > end) throw Rejected("the key file is damaged")
                len = 0
                repeat(count) { len = (len shl 8) or (bytes[pos++].toInt() and 0xFF) }
            }
            if (len < 0 || pos + len > end) throw Rejected("the key file is damaged")
            return Item(tag, pos, pos + len).also { pos += len }
        }
        fun sub(item: Item) = Der(bytes, item.start, item.end)
        fun content(item: Item) = bytes.copyOfRange(item.start, item.end)
        fun done() { if (pos != end) throw Rejected("the key file has trailing data") }
        fun int(): Int {
            val v = BigInteger(content(next(0x02)))
            if (v.bitLength() > 31) throw Rejected("the key file has an out-of-range number")
            return v.toInt()
        }
    }

    private val OID_PBES2 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x05, 0x0D)
    private val OID_PBKDF2 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x05, 0x0C)
    private val OID_SHA1 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x02, 0x07)
    private val OID_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x02, 0x09)
    private val OID_AES256 = byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x2A)
    private val OID_EC = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)
    private val OID_P256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

    fun read(pem: String, passphrase: CharArray): Loaded {
        if (pem.length > MAX_FILE) throw Rejected("the key file is too large")
        val der = unwrap(pem)
        if (passphrase.isEmpty()) throw Rejected("a passphrase is needed")
        val params = IntArray(2)
        val plain = decrypt(der, passphrase, params)
        try { return parsePrivateKey(plain).let { Loaded(it.privateKey, it.publicKey, params[0], params[1] == 1) } } finally { plain.fill(0) }
    }

    private fun unwrap(pem: String): ByteArray {
        val lines = pem.trim().lines().map { it.trim() }
        if (lines.firstOrNull() == "-----BEGIN PRIVATE KEY-----" || lines.firstOrNull() == "-----BEGIN EC PRIVATE KEY-----") {
            throw Rejected("the key file is not encrypted; use the encrypted key from make-update-keys.sh")
        }
        if (lines.size < 3 || lines.first() != "-----BEGIN ENCRYPTED PRIVATE KEY-----" || lines.last() != "-----END ENCRYPTED PRIVATE KEY-----") {
            throw Rejected("this is not an encrypted private key file (ENCRYPTED PRIVATE KEY)")
        }
        val body = lines.subList(1, lines.size - 1)
        if (body.any { it.startsWith("-----") || it.isEmpty() }) throw Rejected("the key file must hold exactly one key")
        return try { Base64.getDecoder().decode(body.joinToString("")) } catch (_: IllegalArgumentException) { throw Rejected("the key file is damaged") }
    }

    private fun decrypt(der: ByteArray, passphrase: CharArray, report: IntArray): ByteArray {
        val top = Der(der)
        val info = top.sub(top.next(0x30)); top.done()
        val alg = info.sub(info.next(0x30))
        val data = info.content(info.next(0x04)); info.done()
        if (!alg.content(alg.next(0x06)).contentEquals(OID_PBES2)) throw Rejected("the key is not encrypted with PBES2")
        val params = alg.sub(alg.next(0x30)); alg.done()
        val kdf = params.sub(params.next(0x30))
        val scheme = params.sub(params.next(0x30)); params.done()
        if (!kdf.content(kdf.next(0x06)).contentEquals(OID_PBKDF2)) throw Rejected("the key does not use PBKDF2")
        val kdfParams = kdf.sub(kdf.next(0x30)); kdf.done()
        val salt = kdfParams.content(kdfParams.next(0x04))
        val iterations = kdfParams.int()
        if (iterations < MIN_ITERATIONS) throw Rejected("the key file uses only $iterations PBKDF2 rounds (at least $MIN_ITERATIONS are needed); make a new key")
        if (iterations > MAX_ITERATIONS) throw Rejected("the key file has an unsafe iteration count")
        if (salt.size < 8 || salt.size > 64) throw Rejected("the key file has an unusable salt")
        var hash = "PBKDF2WithHmacSHA1"
        if (kdfParams.pos < kdfParams.end) {
            val prf = kdfParams.sub(kdfParams.next(0x30)); kdfParams.done()
            val oid = prf.content(prf.next(0x06))
            hash = when {
                oid.contentEquals(OID_SHA1) -> "PBKDF2WithHmacSHA1"
                oid.contentEquals(OID_SHA256) -> "PBKDF2WithHmacSHA256"
                else -> throw Rejected("the key file uses an unsupported hash")
            }
        }
        report[0] = iterations; report[1] = if (hash == "PBKDF2WithHmacSHA1") 1 else 0
        if (!scheme.content(scheme.next(0x06)).contentEquals(OID_AES256)) throw Rejected("the key must be encrypted with AES-256-CBC")
        val iv = scheme.content(scheme.next(0x04)); scheme.done()
        if (iv.size != 16 || data.isEmpty() || data.size % 16 != 0) throw Rejected("the key file is damaged")
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val k = SecretKeyFactory.getInstance(hash).generateSecret(spec).encoded
            return try {
                Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv)) }.doFinal(data)
            } catch (_: java.security.GeneralSecurityException) {
                throw Rejected("wrong passphrase, or the key file is damaged")
            } finally { k.fill(0) }
        } finally { spec.clearPassword() }
    }

    private fun parsePrivateKey(plain: ByteArray): Loaded {
        val wrong = Rejected("wrong passphrase, or the key file is damaged")
        val top = Der(plain)
        val info = try { top.sub(top.next(0x30)).also { top.done() } } catch (_: Rejected) { throw wrong }
        val scalar: ByteArray; val embedded: ByteArray?
        try {
            if (info.int() != 0) throw Rejected("unsupported private key version")
            val alg = info.sub(info.next(0x30))
            if (!alg.content(alg.next(0x06)).contentEquals(OID_EC)) throw Rejected("the key is not an elliptic-curve key")
            if (!alg.content(alg.next(0x06)).contentEquals(OID_P256)) throw Rejected("the key is not on the P-256 curve")
            alg.done()
            val ec = info.sub(info.next(0x04))
            info.done()
            val inner = ec.sub(ec.next(0x30)); ec.done()
            if (inner.int() != 1) throw Rejected("unsupported EC key version")
            scalar = inner.content(inner.next(0x04))
            var pub: ByteArray? = null
            while (inner.pos < inner.end) {
                val item = inner.next(if (inner.bytes[inner.pos].toInt() and 0xFF == 0xA0) 0xA0 else 0xA1)
                if (item.tag == 0xA1) {
                    val bits = inner.sub(item); val b = bits.content(bits.next(0x03)); bits.done()
                    if (b.isEmpty() || b[0].toInt() != 0) throw Rejected("the key file is damaged")
                    pub = b.copyOfRange(1, b.size)
                }
            }
            embedded = pub
        } catch (r: Rejected) {
            // Garbage after a wrong passphrase looks like a structure error; only a clean P-256 structure error is specific.
            throw if (r.message?.startsWith("the key is not") == true || r.message?.startsWith("unsupported") == true) r else wrong
        }
        if (scalar.size != 32) throw Rejected("the key is not a 32-byte P-256 key")
        val d = BigInteger(1, scalar)
        if (d.signum() <= 0 || d >= P256.n) throw Rejected("the private key is out of range")
        val point = multiplyBase(d)
        val derived = byteArrayOf(4) + Bytes.fixed(point.affineX, 32) + Bytes.fixed(point.affineY, 32)
        if (embedded != null && !embedded.contentEquals(derived)) throw Rejected("the key file's public part does not match its private key")
        return Loaded(P256.privateKey(scalar), derived)
    }

    /** d times the base point, by double-and-add on the affine curve (a one-off at start-up, so speed is irrelevant). */
    private fun multiplyBase(d: BigInteger): ECPoint {
        val p = P256.p
        fun add(a: ECPoint, b: ECPoint): ECPoint {
            if (a == ECPoint.POINT_INFINITY) return b
            if (b == ECPoint.POINT_INFINITY) return a
            val lambda = if (a.affineX == b.affineX) {
                if (a.affineY != b.affineY || a.affineY.signum() == 0) return ECPoint.POINT_INFINITY
                (BigInteger.valueOf(3) * a.affineX * a.affineX + P256.params.curve.a) * (BigInteger.TWO * a.affineY).modInverse(p)
            } else (b.affineY - a.affineY) * (b.affineX - a.affineX).modInverse(p)
            val l = lambda.mod(p)
            val x = (l * l - a.affineX - b.affineX).mod(p)
            return ECPoint(x, (l * (a.affineX - x) - a.affineY).mod(p))
        }
        var result = ECPoint.POINT_INFINITY
        var addend = P256.params.generator
        for (i in 0 until d.bitLength()) {
            if (d.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }
}

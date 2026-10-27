package org.phonemesh.core.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * P-256 for the accounts spec, on the platform's own crypto (JCA on the JVM, the same names on Android, where private
 * keys may live in the Keystore). Public keys travel compressed (33 bytes, SEC1), signatures raw (r || s, 64 bytes).
 * Every point from outside is checked: on the curve, coordinates below p; the identity cannot be encoded.
 */
object P256 {
    const val POINT = 33
    const val SIGNATURE = 64

    val params: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    val p: BigInteger = (params.curve.field as ECFieldFp).p
    val n: BigInteger = params.order
    private val a: BigInteger = params.curve.a
    private val b: BigInteger = params.curve.b
    private val sqrtExponent = p.add(BigInteger.ONE).shiftRight(2)
    /** The base point G, compressed: ECDH of a scalar with it gives that scalar's own public x coordinate. */
    val generator: ByteArray by lazy { compress(params.generator) }

    fun generate(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** A private key from its 32-byte scalar, which must be in 1..n-1. */
    fun privateKey(scalar: ByteArray): PrivateKey {
        require(scalar.size == 32) { "a scalar is 32 bytes" }
        val d = BigInteger(1, scalar)
        require(d.signum() > 0 && d < n) { "scalar out of range" }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params))
    }

    /** Whether (x, y) is a point of the curve with both coordinates in 0..p-1. */
    fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        return y.multiply(y).mod(p) == rhs(x)
    }

    fun compress(point: ECPoint): ByteArray {
        require(onCurve(point.affineX, point.affineY)) { "not a point of P-256" }
        return byteArrayOf(if (point.affineY.testBit(0)) 3 else 2) + Bytes.fixed(point.affineX, 32)
    }

    fun compress(key: ECPublicKey): ByteArray = compress(key.w)

    /** The point a compressed key names; null for anything that is not one: a bad prefix or length, x >= p, no y. */
    fun decompress(encoded: ByteArray): ECPoint? {
        if (encoded.size != POINT || (encoded[0] != 2.toByte() && encoded[0] != 3.toByte())) return null
        val x = BigInteger(1, encoded.copyOfRange(1, POINT))
        if (x >= p) return null
        val right = rhs(x)
        var y = right.modPow(sqrtExponent, p)
        if (y.multiply(y).mod(p) != right) return null
        if (y.testBit(0) != (encoded[0] == 3.toByte())) y = p.subtract(y)
        return ECPoint(x, y)
    }

    fun publicKey(encoded: ByteArray): ECPublicKey? = decompress(encoded)?.let { publicKey(it) }

    fun publicKey(point: ECPoint): ECPublicKey {
        require(onCurve(point.affineX, point.affineY)) { "not a point of P-256" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    /** ECDH: the 32-byte x coordinate of the shared point; null when [theirs] is not a valid compressed key. */
    fun agree(mine: PrivateKey, theirs: ByteArray): ByteArray? {
        val peer = publicKey(theirs) ?: return null
        val secret = KeyAgreement.getInstance("ECDH").apply { init(mine); doPhase(peer, true) }.generateSecret()
        return Bytes.fixed(BigInteger(1, secret), 32)
    }

    /** ECDSA with SHA-256; the raw 64-byte signature. */
    fun sign(key: PrivateKey, message: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(message) }.sign()
        return checkNotNull(derToRaw(der)) { "the platform gave a malformed signature" }
    }

    /** False for a bad key, a malformed or out-of-range signature, or one that does not match. Never throws. */
    /** Whether a raw r||s signature has s at most n/2 (the one form of each signature). */
    fun isLowS(signature: ByteArray): Boolean =
        signature.size == 64 && BigInteger(1, signature.copyOfRange(32, 64)) <= n.shiftRight(1)

    /** The same signature with s at most n/2. */
    fun lowS(signature: ByteArray): ByteArray {
        if (signature.size != 64 || isLowS(signature)) return signature
        val s = (n - BigInteger(1, signature.copyOfRange(32, 64))).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return signature.copyOfRange(0, 32) + ByteArray(32 - s.size) + s
    }

    fun verify(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val publicKey = publicKey(key) ?: return false
        return verify(publicKey, message, signature)
    }

    fun verify(key: ECPublicKey, message: ByteArray, signature: ByteArray): Boolean {
        val der = rawToDer(signature) ?: return false
        return try {
            Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(message) }.verify(der)
        } catch (_: java.security.SignatureException) {
            false
        }
    }

    /** DER for the platform; null unless [raw] is 64 bytes with r and s both in 1..n-1. */
    fun rawToDer(raw: ByteArray): ByteArray? {
        if (raw.size != SIGNATURE) return null
        val r = BigInteger(1, raw.copyOfRange(0, 32))
        val s = BigInteger(1, raw.copyOfRange(32, 64))
        if (r.signum() == 0 || s.signum() == 0 || r >= n || s >= n) return null
        val ri = r.toByteArray()
        val si = s.toByteArray()
        val body = byteArrayOf(2, ri.size.toByte()) + ri + byteArrayOf(2, si.size.toByte()) + si
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** Raw r || s from strict DER; null for anything else (extra bytes, long forms, negative or oversized integers). */
    fun derToRaw(der: ByteArray): ByteArray? {
        if (der.size < 8 || der[0] != 0x30.toByte() || der[1].toInt() != der.size - 2) return null
        var at = 2
        val out = ByteArray(SIGNATURE)
        for (part in 0..1) {
            if (at + 2 > der.size || der[at] != 2.toByte()) return null
            val length = der[at + 1].toInt()
            at += 2
            if (length < 1 || length > 33 || at + length > der.size) return null
            val bytes = der.copyOfRange(at, at + length)
            if (bytes[0] < 0) return null
            if (length > 1 && bytes[0] == 0.toByte() && bytes[1] >= 0) return null
            val value = BigInteger(1, bytes)
            if (value.signum() == 0 || value >= n) return null
            Bytes.fixed(value, 32).copyInto(out, part * 32)
            at += length
        }
        return if (at == der.size) out else null
    }

    private fun rhs(x: BigInteger): BigInteger = x.pow(3).add(a.multiply(x)).add(b).mod(p)
}

package org.phonemesh.core.update

import org.phonemesh.core.crypto.Bytes
import org.phonemesh.core.crypto.P256
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey

/**
 * The TEST update keys. Their private scalars come from a public label on purpose: nothing signed with them is
 * trusted by a real release. The real public keys replace `update/public-keys.txt` later (see [UpdateRoots]).
 */
object TestKeys {
    class Pair(val private: PrivateKey, val public: ByteArray, val compressed: ByteArray)

    private fun derive(label: String): Pair {
        val d = BigInteger(1, Bytes.sha256(label.toByteArray())).mod(P256.n - BigInteger.ONE) + BigInteger.ONE
        val key = P256.privateKey(Bytes.fixed(d, 32))
        // The public point is d*G; ECDH against G gives only x, so sign-and-recover is not needed: use the JCA pair instead.
        val point = multiply(d)
        val uncompressed = byteArrayOf(4) + Bytes.fixed(point.affineX, 32) + Bytes.fixed(point.affineY, 32)
        return Pair(key, uncompressed, P256.compress(point))
    }

    private fun multiply(d: BigInteger): java.security.spec.ECPoint {
        val p = P256.p
        var result: java.security.spec.ECPoint = java.security.spec.ECPoint.POINT_INFINITY
        var addend = P256.params.generator
        fun add(a: java.security.spec.ECPoint, b: java.security.spec.ECPoint): java.security.spec.ECPoint {
            if (a == java.security.spec.ECPoint.POINT_INFINITY) return b
            if (b == java.security.spec.ECPoint.POINT_INFINITY) return a
            val lambda = if (a.affineX == b.affineX) {
                if (a.affineY != b.affineY || a.affineY.signum() == 0) return java.security.spec.ECPoint.POINT_INFINITY
                (BigInteger.valueOf(3) * a.affineX * a.affineX + P256.params.curve.a) * (BigInteger.TWO * a.affineY).modInverse(p)
            } else (b.affineY - a.affineY) * (b.affineX - a.affineX).modInverse(p)
            val l = lambda.mod(p)
            val x = (l * l - a.affineX - b.affineX).mod(p)
            return java.security.spec.ECPoint(x, (l * (a.affineX - x) - a.affineY).mod(p))
        }
        for (i in 0 until d.bitLength()) {
            if (d.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    val catalog: Pair by lazy { derive("phonemesh TEST catalog key") }
    val catalog2: Pair by lazy { derive("phonemesh TEST catalog key 2") }
    val catalog3: Pair by lazy { derive("phonemesh TEST catalog key 3") }
    val recovery: Pair by lazy { derive("phonemesh TEST recovery key") }

    fun pub(k: KeyPair): ByteArray {
        val w = (k.public as ECPublicKey).w
        return byteArrayOf(4) + Bytes.fixed(w.affineX, 32) + Bytes.fixed(w.affineY, 32)
    }
}

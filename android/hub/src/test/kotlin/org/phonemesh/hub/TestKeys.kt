package org.phonemesh.hub

import org.phonemesh.core.crypto.Bytes
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

object TestKeys {
    fun pub(k: KeyPair): ByteArray {
        val w = (k.public as ECPublicKey).w
        return byteArrayOf(4) + Bytes.fixed(w.affineX, 32) + Bytes.fixed(w.affineY, 32)
    }
    fun compressed(k: KeyPair): ByteArray = org.phonemesh.core.crypto.P256.compress((k.public as ECPublicKey).w)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

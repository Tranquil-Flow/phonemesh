package org.phonemesh.core.group

import org.phonemesh.core.crypto.Bytes
import java.nio.ByteBuffer

/**
 * Relayed Ask (op 10) and Leave (op 11): op | version 1 | admin index u8 | epoch u32, big endian.
 * The group and sender come from the authenticated carrying chain, never from this body. Decoding checks only
 * framing and representable fields: the caller must bind the index to that chain's roster, check membership,
 * named-admin authority and the operation's epoch/lifetime rules before acting. Not a private [GroupControl].
 */
sealed class GroupRelayedControl(val op: Int, val adminIndex: Int, val epoch: Long) {
    init {
        require(adminIndex in 0 until GroupRoster.MAX_MEMBERS)
        // Match the existing private Ask/Leave epoch range: neither empty nor exhausted.
        require(epoch in 1 until 0xFFFF_FFFFL)
    }

    class Ask(adminIndex: Int, epoch: Long) : GroupRelayedControl(ASK, adminIndex, epoch)
    class Leave(adminIndex: Int, epoch: Long) : GroupRelayedControl(LEAVE, adminIndex, epoch)

    fun encode(): ByteArray = byteArrayOf(op.toByte(), 1, adminIndex.toByte()) + Bytes.u32(epoch)

    companion object {
        const val ASK = 10
        const val LEAVE = 11
        const val SIZE = 7

        fun decode(body: ByteArray): GroupRelayedControl? {
            if (body.size != SIZE) return null
            if (body[1].toInt() != 1) return null
            return try {
                val index = body[2].toInt() and 0xFF
                val epoch = ByteBuffer.wrap(body, 3, 4).int.toLong() and 0xFFFF_FFFFL
                when (body[0].toInt()) {
                    ASK -> Ask(index, epoch)
                    LEAVE -> Leave(index, epoch)
                    else -> null
                }
            } catch (_: IllegalArgumentException) { null }
        }
    }
}

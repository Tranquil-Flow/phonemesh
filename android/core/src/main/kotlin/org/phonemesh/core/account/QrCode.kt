package org.phonemesh.core.account

import org.phonemesh.core.crypto.P256

/**
 * What a phone's QR code holds: its contact card, then a one-time key made for this showing (33 bytes). After a mutual
 * scan both one-time keys enter the session start, so a second scan gives a new session, never the old keys again
 * (review F1), and the first messages get forward secrecy (F22). Only the QR code carries it; the link does not.
 */
class QrCode(val card: ContactCard, oneTime: ByteArray) {
    val oneTime: ByteArray = oneTime.copyOf()

    init {
        require(P256.decompress(oneTime) != null) { "the one-time key must be a valid point" }
    }

    fun encode(): ByteArray = card.encode() + oneTime

    companion object {
        fun decode(bytes: ByteArray): QrCode? {
            val cardLength = ContactCard.lengthAt(bytes)
            if (cardLength == 0 || bytes.size != cardLength + P256.POINT) return null
            val card = ContactCard.decode(bytes.copyOf(cardLength)) ?: return null
            val oneTime = bytes.copyOfRange(cardLength, bytes.size)
            if (P256.decompress(oneTime) == null) return null
            return QrCode(card, oneTime)
        }
    }
}

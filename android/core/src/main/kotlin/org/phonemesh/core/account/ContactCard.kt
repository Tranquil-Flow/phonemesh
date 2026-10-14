package org.phonemesh.core.account

import org.phonemesh.core.crypto.P256
import org.phonemesh.core.session.OwnKeys
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Contact card v1, shown as a QR code and carried in the add-me link:
 * `01 | key type (01 = P-256) | IK (33) | SPK (33) | sig_IK(SPK) (64) | name length (1) | name (UTF-8, 1..40) | colour`.
 * The prekey is signed by the identity key; the name and colour are what the person chose, not proven.
 */
class ContactCard(identity: ByteArray, prekey: ByteArray, signature: ByteArray, val name: String, val colour: Int) {
    val identity: ByteArray = identity.copyOf()
    val prekey: ByteArray = prekey.copyOf()
    val signature: ByteArray = signature.copyOf()
    val accountId: AccountId get() = AccountId.of(identity)

    init {
        require(identity.size == P256.POINT && prekey.size == P256.POINT && signature.size == P256.SIGNATURE) { "bad key sizes" }
        require(nameBytes(name).size in 1..MAX_NAME_BYTES && Names.valid(name)) { "a name is 1..$MAX_NAME_BYTES UTF-8 bytes, NFC, no controls" }
        require(colour in 0..255) { "colour is one byte" }
    }

    fun encode(): ByteArray {
        val name = nameBytes(name)
        return ByteBuffer.allocate(FIXED + name.size)
            .put(VERSION.toByte()).put(KEY_P256.toByte()).put(identity).put(prekey).put(signature)
            .put(name.size.toByte()).put(name).put(colour.toByte()).array()
    }

    companion object {
        const val VERSION = 1
        const val KEY_P256 = 1
        const val MAX_NAME_BYTES = 40
        private const val FIXED = 1 + 1 + 33 + 33 + 64 + 1 + 1
        const val MAX_BYTES = FIXED + MAX_NAME_BYTES

        /** This phone's card: its keys, the current prekey signed with the identity key. */
        fun create(own: OwnKeys, name: String, colour: Int): ContactCard =
            ContactCard(own.identity, own.prekey, own.signIdentity(signed(own.prekey)), name, colour)

        /** What the identity key signs: a label, the version and key type, then the prekey (review F23). */
        fun signed(prekey: ByteArray): ByteArray =
            "Phonemesh card v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(VERSION.toByte(), KEY_P256.toByte()) + prekey

        /** The length of the card at the start of [bytes] (its name length decides it); 0 when too short to tell. */
        internal fun lengthAt(bytes: ByteArray): Int =
            if (bytes.size <= 132) 0 else FIXED + (bytes[132].toInt() and 0xff)

        /**
         * A card from outside; null unless every field is right: version, key type, exact length, valid points,
         * the prekey's signature, and a name of strict UTF-8.
         */
        fun decode(bytes: ByteArray): ContactCard? {
            if (bytes.size < FIXED + 1 || bytes.size > MAX_BYTES) return null
            if (bytes[0].toInt() != VERSION || bytes[1].toInt() != KEY_P256) return null
            val identity = bytes.copyOfRange(2, 35)
            val prekey = bytes.copyOfRange(35, 68)
            val signature = bytes.copyOfRange(68, 132)
            val nameLength = bytes[132].toInt() and 0xff
            if (nameLength !in 1..MAX_NAME_BYTES || bytes.size != FIXED + nameLength) return null
            val name = strictUtf8(bytes.copyOfRange(133, 133 + nameLength))?.takeIf { Names.valid(it) } ?: return null
            val colour = bytes[133 + nameLength].toInt() and 0xff
            if (P256.decompress(identity) == null || P256.decompress(prekey) == null) return null
            if (!P256.verify(identity, signed(prekey), signature)) return null
            return ContactCard(identity, prekey, signature, name, colour)
        }

        private fun nameBytes(name: String) = name.toByteArray(Charsets.UTF_8)

        internal fun strictUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

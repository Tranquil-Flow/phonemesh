package org.phonemesh.core.account

import org.phonemesh.core.crypto.P256
import org.phonemesh.core.session.OwnKeys
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Contact card v2, shown as a QR code and carried in the add-me link (spec decision 36: the only card format):
 * `02 | key type (01 = P-256) | IK (33) | SPK (33) | signature (64) | profile` ([Profile], 10 + name + avatar bytes).
 * The identity key signs the version, key type, both keys and the whole profile, so nobody can change the name,
 * colour or picture on someone's card. They are what the person chose, not proven: the safety pictures are the check.
 */
class ContactCard(identity: ByteArray, prekey: ByteArray, signature: ByteArray, val profile: Profile) {
    val identity: ByteArray = identity.copyOf()
    val prekey: ByteArray = prekey.copyOf()
    val signature: ByteArray = signature.copyOf()
    val accountId: AccountId get() = AccountId.of(identity)
    val name: String get() = profile.name
    /** RGB. */
    val colour: Int get() = profile.colour

    init {
        require(identity.size == P256.POINT && prekey.size == P256.POINT && signature.size == P256.SIGNATURE) { "bad key sizes" }
    }

    fun encode(): ByteArray =
        byteArrayOf(VERSION.toByte(), KEY_P256.toByte()) + identity + prekey + signature + profile.encode()

    companion object {
        const val VERSION = 2
        const val KEY_P256 = 1
        const val MAX_NAME_BYTES = Profile.MAX_NAME_BYTES
        private const val KEYS = 1 + 1 + 33 + 33
        private const val FIXED = KEYS + 64
        const val MAX_BYTES = FIXED + Profile.MAX_BYTES

        /** This phone's card: its keys and [profile], signed with the identity key. */
        fun create(own: OwnKeys, profile: Profile): ContactCard =
            ContactCard(own.identity, own.prekey, own.signIdentity(signed(own.identity, own.prekey, profile.encode())), profile)

        /** What the identity key signs: a label, the version and key type, both keys, then the profile. */
        private fun signed(identity: ByteArray, prekey: ByteArray, profile: ByteArray): ByteArray =
            "Phonemesh card v2".toByteArray(Charsets.US_ASCII) + byteArrayOf(VERSION.toByte(), KEY_P256.toByte()) +
                identity + prekey + profile

        /** The length of the card at the start of [bytes] (its profile's lengths decide it); 0 when too short to tell. */
        internal fun lengthAt(bytes: ByteArray): Int =
            Profile.lengthAt(bytes, FIXED).let { if (it == 0) 0 else FIXED + it }

        /**
         * A card from outside; null unless every field is right: version, key type, exact length, valid points, the
         * profile, and the signature over all of it.
         */
        fun decode(bytes: ByteArray): ContactCard? {
            if (bytes.size <= FIXED || bytes.size > MAX_BYTES) return null
            if (bytes[0].toInt() != VERSION || bytes[1].toInt() != KEY_P256) return null
            if (lengthAt(bytes) != bytes.size) return null
            val identity = bytes.copyOfRange(2, 35)
            val prekey = bytes.copyOfRange(35, 68)
            val signature = bytes.copyOfRange(68, FIXED)
            val profileBytes = bytes.copyOfRange(FIXED, bytes.size)
            val profile = Profile.decode(profileBytes) ?: return null
            if (P256.decompress(identity) == null || P256.decompress(prekey) == null) return null
            if (!P256.verify(identity, signed(identity, prekey, profileBytes), signature)) return null
            return ContactCard(identity, prekey, signature, profile)
        }

        internal fun strictUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

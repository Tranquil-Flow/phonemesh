package org.phonemesh.core.wire

import org.phonemesh.core.MessageCodec
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.crypto.Bytes

/**
 * Port 303, the shared chat with names, protected by the channel key only:
 * `version | sender's account id (8) | name length (1) | name (1..24) | language length (1) | language | text`.
 * The name is claimed, not proven. A whole packet is at most [WireV2.MAX_PACKET] bytes.
 */
object SharedChatWire {
    const val PORT = 303
    const val VERSION = 1
    const val MAX_NAME_BYTES = 24

    class Message(val sender: AccountId, val name: String, val language: String, val text: String) {
        /** How replies and reactions name it: SHA-256(sender id, time, text)[0..4]; [sentAtSeconds] is the arrival time. */
        fun ref(sentAtSeconds: Long): ByteArray =
            Bytes.sha256(sender.toBytes(), Bytes.u32(sentAtSeconds and 0xffffffffL), text.toByteArray(Charsets.UTF_8)).copyOf(4)
    }

    /** The most text bytes a message with this name and language can carry. */
    fun maxText(name: String, language: String): Int =
        WireV2.MAX_PACKET - 1 - AccountId.SIZE - 1 - name.toByteArray(Charsets.UTF_8).size - 1 - language.length

    fun encode(message: Message): ByteArray {
        val name = message.name.toByteArray(Charsets.UTF_8)
        require(name.size in 1..MAX_NAME_BYTES) { "a name is 1..$MAX_NAME_BYTES bytes here" }
        require(MessageCodec.canonical(message.language) == message.language) { "language must be canonical" }
        val text = message.text.toByteArray(Charsets.UTF_8)
        require(text.isNotEmpty() && text.size <= maxText(message.name, message.language)) { "text does not fit" }
        val language = message.language.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(VERSION.toByte()) + message.sender.toBytes() + byteArrayOf(name.size.toByte()) + name +
            byteArrayOf(language.size.toByte()) + language + text
    }

    fun decode(bytes: ByteArray): Message? {
        if (bytes.size > WireV2.MAX_PACKET || bytes.size < 1 + AccountId.SIZE + 1 + 1 + 1 + 2 + 1) return null
        if (bytes[0].toInt() != VERSION) return null
        var at = 1
        val sender = AccountId.fromBytes(bytes.copyOfRange(at, at + AccountId.SIZE)); at += AccountId.SIZE
        val nameLength = bytes[at++].toInt() and 0xff
        if (nameLength !in 1..MAX_NAME_BYTES || at + nameLength + 1 > bytes.size) return null
        val name = ContactCard.strictUtf8(bytes.copyOfRange(at, at + nameLength)) ?: return null; at += nameLength
        val languageLength = bytes[at++].toInt() and 0xff
        if (languageLength !in 2..6 || at + languageLength >= bytes.size) return null
        val language = String(bytes.copyOfRange(at, at + languageLength), Charsets.US_ASCII); at += languageLength
        val canonical = try { MessageCodec.canonical(language) } catch (_: IllegalArgumentException) { return null }
        if (canonical != language) return null
        val text = ContactCard.strictUtf8(bytes.copyOfRange(at, bytes.size)) ?: return null
        return Message(sender, name, language, text)
    }
}

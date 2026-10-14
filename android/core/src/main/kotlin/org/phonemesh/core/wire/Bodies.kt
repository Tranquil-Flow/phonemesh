package org.phonemesh.core.wire

import org.phonemesh.core.MessageCodec
import org.phonemesh.core.PictureWire
import org.phonemesh.core.account.ContactCard
import java.nio.ByteBuffer

/**
 * What a wire v2 packet carries once opened: one kind byte, then the kind's fields (plan, "Body kinds"). Texts carry
 * their language as a length-prefixed canonical tag ("fr", "fr-FR"). Sizes are checked against the packet by
 * [WireV2.maxBody]; [decode] refuses anything malformed with null, never an exception.
 */
sealed class Body {
    data class Text(val language: String, val text: String) : Body()
    class Reply(ref: ByteArray, val language: String, val text: String) : Body() { val ref: ByteArray = ref.copyOf() }
    class Reaction(ref: ByteArray, val emoji: String) : Body() { val ref: ByteArray = ref.copyOf() }
    class PictureHead(bytes: ByteArray) : Body() { val bytes: ByteArray = bytes.copyOf() }
    class PicturePart(bytes: ByteArray) : Body() { val bytes: ByteArray = bytes.copyOf() }
    data class Receipt(val highest: Long) : Body()
    data class Hello(val name: String, val colour: Int) : Body()

    companion object {
        const val TEXT = 1
        const val REPLY = 2
        const val REACTION = 3
        const val PICTURE_HEAD = 4
        const val PICTURE_PART = 5
        const val RECEIPT = 6
        const val HELLO = 7
        const val REF = 4
        const val MAX_EMOJI = 8

        fun encode(body: Body): ByteArray = when (body) {
            is Text -> byteArrayOf(TEXT.toByte()) + language(body.language) + text(body.text)
            is Reply -> byteArrayOf(REPLY.toByte()) + ref(body.ref) + language(body.language) + text(body.text)
            is Reaction -> {
                val emoji = body.emoji.toByteArray(Charsets.UTF_8)
                require(emoji.size in 1..MAX_EMOJI) { "a reaction is 1..$MAX_EMOJI UTF-8 bytes" }
                byteArrayOf(REACTION.toByte()) + ref(body.ref) + emoji
            }
            is PictureHead -> {
                require(PictureWire.decode(body.bytes) is PictureWire.Head) { "not a picture head" }
                byteArrayOf(PICTURE_HEAD.toByte()) + body.bytes
            }
            is PicturePart -> {
                require(PictureWire.decode(body.bytes) is PictureWire.Part) { "not a picture part" }
                byteArrayOf(PICTURE_PART.toByte()) + body.bytes
            }
            is Receipt -> {
                require(body.highest in 0..0xffffffffL) { "a counter is 4 bytes" }
                ByteBuffer.allocate(5).put(RECEIPT.toByte()).putInt(body.highest.toInt()).array()
            }
            is Hello -> {
                val name = body.name.toByteArray(Charsets.UTF_8)
                require(name.size in 1..ContactCard.MAX_NAME_BYTES) { "a name is 1..${ContactCard.MAX_NAME_BYTES} bytes" }
                require(body.colour in 0..255) { "colour is one byte" }
                byteArrayOf(HELLO.toByte(), name.size.toByte()) + name + byteArrayOf(body.colour.toByte())
            }
        }

        fun decode(bytes: ByteArray): Body? = try {
            if (bytes.isEmpty()) null else when (bytes[0].toInt()) {
                TEXT -> Reader(bytes).run { Text(language(), rest()).takeIf { done() } }
                REPLY -> Reader(bytes).run { val r = take(REF); Reply(r, language(), rest()).takeIf { done() } }
                REACTION -> Reader(bytes).run {
                    val r = take(REF)
                    val emoji = take(bytes.size - 1 - REF)
                    if (emoji.size !in 1..MAX_EMOJI) null else Reaction(r, ContactCard.strictUtf8(emoji) ?: return@run null)
                }
                PICTURE_HEAD -> bytes.copyOfRange(1, bytes.size).takeIf { PictureWire.decode(it) is PictureWire.Head }?.let { PictureHead(it) }
                PICTURE_PART -> bytes.copyOfRange(1, bytes.size).takeIf { PictureWire.decode(it) is PictureWire.Part }?.let { PicturePart(it) }
                RECEIPT -> if (bytes.size != 5) null else Receipt(ByteBuffer.wrap(bytes, 1, 4).int.toLong() and 0xffffffffL)
                HELLO -> Reader(bytes).run {
                    val length = byte()
                    if (length !in 1..ContactCard.MAX_NAME_BYTES) return@run null
                    val name = ContactCard.strictUtf8(take(length)) ?: return@run null
                    Hello(name, byte()).takeIf { done() }
                }
                else -> null
            }
        } catch (_: Refused) {
            null
        }

        private fun ref(ref: ByteArray): ByteArray {
            require(ref.size == REF) { "a reference is $REF bytes" }
            return ref
        }

        private fun language(tag: String): ByteArray {
            val canonical = MessageCodec.canonical(tag)
            require(canonical == tag) { "language must be canonical: $tag" }
            val bytes = tag.toByteArray(Charsets.US_ASCII)
            return byteArrayOf(bytes.size.toByte()) + bytes
        }

        private fun text(text: String): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty()) { "a text is not empty" }
            return bytes
        }
    }

    private class Refused : Exception()

    /** Reads fields after the kind byte; any shortfall or bad field throws [Refused], which decode turns into null. */
    private class Reader(private val bytes: ByteArray) {
        private var at = 1
        fun byte(): Int { if (at >= bytes.size) throw Refused(); return bytes[at++].toInt() and 0xff }
        fun take(n: Int): ByteArray {
            if (n < 0 || at + n > bytes.size) throw Refused()
            return bytes.copyOfRange(at, at + n).also { at += n }
        }
        fun language(): String {
            val length = byte()
            if (length !in 2..6) throw Refused()
            val tag = String(take(length), Charsets.US_ASCII)
            val canonical = try { MessageCodec.canonical(tag) } catch (_: IllegalArgumentException) { throw Refused() }
            if (canonical != tag) throw Refused()
            return tag
        }
        fun rest(): String {
            val text = take(bytes.size - at)
            if (text.isEmpty()) throw Refused()
            return ContactCard.strictUtf8(text) ?: throw Refused()
        }
        fun done() = at == bytes.size
    }
}

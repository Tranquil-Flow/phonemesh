package org.phonemesh.core.wire

import org.phonemesh.core.MessageCodec
import org.phonemesh.core.PictureWire
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.Names
import java.nio.ByteBuffer

/**
 * What a wire v2 packet carries once opened: one kind byte, then the kind's fields (plan, "Body kinds"). Texts carry
 * their language as a length-prefixed canonical tag ("fr", "pt-BR"). Any kind but a receipt or hello may carry an
 * [Ack] right after the kind byte (kind | 0x80), so a reply is also a receipt at no extra packet (review F12). Sizes
 * are checked against the packet by [WireV2.maxBody]; decoding refuses anything malformed with null, never throws.
 */
sealed class Body {
    data class Text(val language: String, val text: String) : Body()
    class Reply(ref: ByteArray, val language: String, val text: String) : Body() { val ref: ByteArray = ref.copyOf() }
    class Reaction(ref: ByteArray, val emoji: String) : Body() { val ref: ByteArray = ref.copyOf() }
    class PictureHead(bytes: ByteArray) : Body() { val bytes: ByteArray = bytes.copyOf() }
    class PicturePart(bytes: ByteArray) : Body() { val bytes: ByteArray = bytes.copyOf() }
    data class Receipt(val ack: Ack) : Body()
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
        private const val WITH_ACK = 0x80

        /** [body], with [ack] riding along when given. */
        fun encode(body: Body, ack: Ack?): ByteArray {
            val plain = encode(body)
            if (ack == null) return plain
            require(body !is Receipt && body !is Hello) { "a receipt or hello carries no extra ack" }
            return byteArrayOf((plain[0].toInt() or WITH_ACK).toByte()) + ack.encode() + plain.copyOfRange(1, plain.size)
        }

        class Decoded(val body: Body, val ack: Ack?)

        /** A body and the ack it carries, if any; null for anything malformed. */
        fun decodeWithAck(bytes: ByteArray): Decoded? {
            if (bytes.isEmpty()) return null
            val first = bytes[0].toInt() and 0xff
            if (first and WITH_ACK == 0) return decode(bytes)?.let { Decoded(it, (it as? Receipt)?.ack) }
            val kind = first and WITH_ACK.inv()
            if (kind == RECEIPT || kind == HELLO || bytes.size < 1 + Ack.SIZE + 1) return null
            val ack = Ack.decode(bytes.copyOfRange(1, 1 + Ack.SIZE)) ?: return null
            val body = decode(byteArrayOf(kind.toByte()) + bytes.copyOfRange(1 + Ack.SIZE, bytes.size)) ?: return null
            return Decoded(body, ack)
        }

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
            is Receipt -> byteArrayOf(RECEIPT.toByte()) + body.ack.encode()
            is Hello -> {
                val name = body.name.toByteArray(Charsets.UTF_8)
                require(name.size in 1..ContactCard.MAX_NAME_BYTES && Names.valid(body.name)) { "a name is 1..${ContactCard.MAX_NAME_BYTES} bytes" }
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
                RECEIPT -> if (bytes.size != 1 + Ack.SIZE) null else Ack.decode(bytes.copyOfRange(1, bytes.size))?.let { Receipt(it) }
                HELLO -> Reader(bytes).run {
                    val length = byte()
                    if (length !in 1..ContactCard.MAX_NAME_BYTES) return@run null
                    val name = ContactCard.strictUtf8(take(length))?.takeIf { Names.valid(it) } ?: return@run null
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

/**
 * "Delivered to their phone" for one chain (review F12): which chain ([tag], 2 bytes the two phones derive from its
 * header key), the highest counter received, and which of the 32 counters below it are still missing (bit i set:
 * counter highest - 1 - i has not arrived). 10 bytes.
 */
class Ack(tag: ByteArray, val highest: Long, val missing: Int) {
    val tag: ByteArray = tag.copyOf()

    init {
        require(tag.size == TAG) { "a chain tag is $TAG bytes" }
        require(highest in 0..0xffffffffL) { "a counter is 4 bytes" }
    }

    /** Whether counter [n] of this chain is known to have arrived. */
    fun covers(n: Long): Boolean = when {
        n > highest || n < 0 -> false
        n == highest -> true
        highest - 1 - n < 32 -> (missing ushr (highest - 1 - n).toInt()) and 1 == 0
        else -> false
    }

    fun encode(): ByteArray = ByteBuffer.allocate(SIZE).put(tag).putInt(highest.toInt()).putInt(missing).array()

    override fun equals(other: Any?) = other is Ack && tag.contentEquals(other.tag) && highest == other.highest && missing == other.missing
    override fun hashCode() = (tag.contentHashCode() * 31 + highest.hashCode()) * 31 + missing

    companion object {
        const val TAG = 2
        const val SIZE = TAG + 4 + 4

        fun decode(bytes: ByteArray): Ack? {
            if (bytes.size != SIZE) return null
            val buffer = ByteBuffer.wrap(bytes, TAG, 8)
            return Ack(bytes.copyOf(TAG), buffer.int.toLong() and 0xffffffffL, buffer.int)
        }
    }
}

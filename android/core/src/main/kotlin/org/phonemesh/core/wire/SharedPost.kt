package org.phonemesh.core.wire

import org.phonemesh.core.MessageCodec
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.Names
import org.phonemesh.core.crypto.Bytes

/**
 * Port 303, version 2: the shared chat's plain posts, replies and reactions, all in one envelope. Version 1
 * ([SharedChatWire]) is still read, never sent. Names are claimed, not proven; nothing here is signed.
 *
 * `version (2) | kind (1) | sender's account id (8) | sender's time, whole seconds (4, big-endian) | name length (1) |
 * name (1..24) | language length (1) | language | reference (4) | text`
 *
 * - kind 1 plain text: language 2..6 bytes, no reference. Kind 2 reply: language, then the 4-byte reference of the post
 *   answered. Kind 3 reaction: language length 0, then the reference, then 1..8 bytes of emoji.
 * - A post's reference is [reference]: SHA-256(sender id, sender's time, text)[0..4]. Every phone computes it from the
 *   same three things the post carries, so a reply or reaction names the same post on every phone, whichever radio
 *   carried it and however many times it was sent again.
 * - A whole frame is at most [WireV2.MAX_PACKET] bytes, so it is never split.
 */
object SharedPost {
    const val VERSION = 2
    const val MAX_EMOJI = 8
    const val REF_BYTES = 4

    /** Version, kind, sender id, sender's time. */
    private const val FIXED = 1 + 1 + AccountId.SIZE + 4

    enum class Kind(val code: Int) { TEXT(1), REPLY(2), REACTION(3) }

    /** [ref]: the reference of the post answered; null for plain text. [text] is the words, or the emoji of a reaction. */
    class Post(
        val kind: Kind, val sender: AccountId, val sentAtSeconds: Long, val name: String, val language: String,
        ref: ByteArray?, val text: String,
    ) {
        val ref: ByteArray? = ref?.copyOf()

        /** What replies and reactions to this post name. */
        fun reference(): ByteArray = SharedPost.reference(sender, sentAtSeconds, text)
    }

    /** A received port-303 frame: a version-2 post, or an old version-1 text (no time, no kind, no reference). */
    sealed class Read {
        class V2(val post: Post) : Read()
        class V1(val message: SharedChatWire.Message) : Read()
    }

    fun reference(sender: AccountId, sentAtSeconds: Long, text: String): ByteArray =
        Bytes.sha256(sender.toBytes(), Bytes.u32(sentAtSeconds and 0xffffffffL), text.toByteArray(Charsets.UTF_8)).copyOf(REF_BYTES)

    /** The most text (or emoji) bytes a post of this kind can carry beside a name of [nameBytes] bytes and a canonical [language]. */
    fun room(kind: Kind, nameBytes: Int, language: String): Int =
        WireV2.MAX_PACKET - FIXED - 1 - nameBytes - 1 - (if (kind == Kind.REACTION) 0 else language.length) -
            (if (kind == Kind.TEXT) 0 else REF_BYTES)

    /** Throws IllegalArgumentException for an invalid name, text, language or reference, or a frame over 228 bytes. */
    fun encode(post: Post): ByteArray {
        val name = post.name.toByteArray(Charsets.UTF_8)
        require(name.size in 1..SharedChatWire.MAX_NAME_BYTES && Names.valid(post.name)) { "a name is 1..${SharedChatWire.MAX_NAME_BYTES} bytes here" }
        require(post.sentAtSeconds in 0..0xffffffffL) { "time does not fit 4 bytes" }
        MessageCodec.validateText(post.text)
        val text = post.text.toByteArray(Charsets.UTF_8)
        val language = if (post.kind == Kind.REACTION) {
            require(post.language.isEmpty()) { "a reaction names no language" }
            require(text.size in 1..MAX_EMOJI) { "a reaction is 1..$MAX_EMOJI UTF-8 bytes" }
            ByteArray(0)
        } else {
            require(MessageCodec.canonical(post.language) == post.language) { "language must be canonical" }
            post.language.toByteArray(Charsets.US_ASCII)
        }
        val ref = post.ref
        if (post.kind == Kind.TEXT) require(ref == null) { "a plain post answers nothing" }
        else require(ref != null && ref.size == REF_BYTES) { "a reply or reaction names a $REF_BYTES-byte reference" }
        val out = byteArrayOf(VERSION.toByte(), post.kind.code.toByte()) + post.sender.toBytes() + Bytes.u32(post.sentAtSeconds) +
            byteArrayOf(name.size.toByte()) + name + byteArrayOf(language.size.toByte()) + language + (ref ?: ByteArray(0)) + text
        require(out.size <= WireV2.MAX_PACKET) { "text does not fit" }
        return out
    }

    /** A version-2 frame, or null for anything else or anything malformed. Never throws. */
    fun decode(bytes: ByteArray): Post? {
        if (bytes.size > WireV2.MAX_PACKET || bytes.size < FIXED + 4) return null
        if (bytes[0].toInt() != VERSION) return null
        val kind = Kind.values().firstOrNull { it.code == (bytes[1].toInt() and 0xff) } ?: return null
        var at = 2
        val sender = AccountId.fromBytes(bytes.copyOfRange(at, at + AccountId.SIZE)); at += AccountId.SIZE
        var sent = 0L
        for (i in 0 until 4) sent = (sent shl 8) or (bytes[at + i].toLong() and 0xff)
        at += 4
        val nameLength = bytes[at++].toInt() and 0xff
        if (nameLength !in 1..SharedChatWire.MAX_NAME_BYTES || at + nameLength + 1 > bytes.size) return null
        val name = ContactCard.strictUtf8(bytes.copyOfRange(at, at + nameLength))?.takeIf { Names.valid(it) } ?: return null
        at += nameLength
        val languageLength = bytes[at++].toInt() and 0xff
        val language: String
        if (kind == Kind.REACTION) {
            if (languageLength != 0) return null
            language = ""
        } else {
            if (languageLength !in 2..6 || at + languageLength > bytes.size) return null
            language = String(bytes, at, languageLength, Charsets.US_ASCII)
            at += languageLength
            val canonical = try { MessageCodec.canonical(language) } catch (_: IllegalArgumentException) { return null }
            if (canonical != language) return null
        }
        var ref: ByteArray? = null
        if (kind != Kind.TEXT) {
            if (at + REF_BYTES >= bytes.size) return null
            ref = bytes.copyOfRange(at, at + REF_BYTES)
            at += REF_BYTES
        }
        if (at >= bytes.size) return null
        val body = bytes.copyOfRange(at, bytes.size)
        if (kind == Kind.REACTION && body.size > MAX_EMOJI) return null
        val text = ContactCard.strictUtf8(body) ?: return null
        try { MessageCodec.validateText(text) } catch (_: IllegalArgumentException) { return null }
        return Post(kind, sender, sent, name, language, ref, text)
    }

    /** Version 2 or the old version 1, by the first byte; null for anything else. */
    fun read(bytes: ByteArray): Read? = when (bytes.firstOrNull()?.toInt()) {
        VERSION -> decode(bytes)?.let { Read.V2(it) }
        SharedChatWire.VERSION -> SharedChatWire.decode(bytes)?.let { Read.V1(it) }
        else -> null
    }
}

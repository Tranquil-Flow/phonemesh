package org.phonemesh.core.backup

import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.crypto.P256
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * What a contacts-only saved copy holds (backup spec 5.5, purpose 0): one MANIFEST, the CONTACT records, one END with
 * the count, length and SHA-256 of everything before it. Frames are `type u8 | flags u8 = 0 | length u32 | payload`.
 * Never keys: no private key, prekey, session or packet has a record type, so none can be read back.
 *
 * Deviation from the drafted format, for the crypto review: colours are 3-byte RGB (profile decision 48 replaced the
 * colour index), and a CONTACT's card is the v0.1.1 card (version 2, signed profile) or the bare 33-byte identity.
 */
object ContactsBackup {
    const val SCHEMA = 1
    const val MAX_CONTACTS = 10_000
    const val MAX_NAME_BYTES = 40
    const val MAX_VOICE_BYTES = 128
    private const val MANIFEST = 1
    private const val CONTACT = 2
    private const val END = 255
    private const val ABSENT = 0xFFFF
    private const val POINT = 33

    class Manifest(sourceIdentity: ByteArray, val createdAt: Long, val generation: Long, val ownName: String, val ownColour: Int) {
        val sourceIdentity: ByteArray = sourceIdentity.copyOf()
    }

    class Contact(
        val id: Long, identity: ByteArray, card: ByteArray, val savedName: String, val colour: Int,
        /** Met in person on the old phone. History only: never current trust on a new phone (RC5 A). */
        val historicalMet: Boolean, val voice: String?, val addedAt: Long,
    ) {
        val identity: ByteArray = identity.copyOf()
        val card: ByteArray = card.copyOf()
    }

    class Copy(val manifest: Manifest, val contacts: List<Contact>)

    fun encode(copy: Copy): ByteArray {
        require(copy.contacts.size <= MAX_CONTACTS) { "too many contacts" }
        val out = ByteArrayOutputStream()
        var records = 0L
        fun frame(type: Int, payload: ByteArray) {
            out.write(type); out.write(0); out.write(ByteBuffer.allocate(4).putInt(payload.size).array()); out.write(payload)
            records++
        }
        val m = copy.manifest
        frame(MANIFEST, Writer().apply {
            u16(SCHEMA); point(m.sourceIdentity); u64(m.createdAt); u64(m.generation); str(m.ownName, 1, MAX_NAME_BYTES)
            rgb(m.ownColour); u64(0); u64(0); u16(0); u32(copy.contacts.size); repeat(5) { u32(0) }
        }.bytes())
        var last = 0L
        for (c in copy.contacts) {
            require(c.id > last) { "contact ids ascend and are not zero" }
            last = c.id
            require(validCard(c.identity, c.card)) { "contact ${c.id} has a card that is not theirs" }
            frame(CONTACT, Writer().apply {
                u64(c.id); point(c.identity); blob(c.card); str(c.savedName, 1, MAX_NAME_BYTES); rgb(c.colour)
                u8(if (c.historicalMet) 1 else 0); optstr(c.voice, MAX_VOICE_BYTES); u64(c.addedAt)
            }.bytes())
        }
        require(copy.contacts.map { it.identity.toList() }.toSet().size == copy.contacts.size) { "one record per identity" }
        val before = out.toByteArray()
        frame(END, Writer().apply { u64(records); u64(before.size.toLong()); raw(sha256(before)) }.bytes())
        return out.toByteArray()
    }

    /** The copy in [plain], or null when anything breaks the format: no partial result. */
    fun decode(plain: ByteArray): Copy? = try { read(plain) } catch (_: Bad) { null } catch (_: RuntimeException) { null }

    private class Bad : Exception()

    private fun read(plain: ByteArray): Copy {
        val b = ByteBuffer.wrap(plain)
        var records = 0L
        fun next(): Pair<Int, Reader> {
            check(b.remaining() >= 6)
            val type = b.get().toInt() and 0xFF
            check(b.get().toInt() == 0)
            val length = b.int
            check(length in 0..65_536 && length <= b.remaining())
            val payload = ByteArray(length).also { b.get(it) }
            records++
            return type to Reader(payload)
        }
        val (firstType, m) = next()
        check(firstType == MANIFEST)
        check(m.u16() == SCHEMA)
        val source = m.point()
        val created = m.u64()
        val generation = m.u64()
        val ownName = m.str(1, MAX_NAME_BYTES)
        val ownColour = m.rgb()
        check(m.u64() == 0L && m.u64() == 0L && m.u16() == 0)
        val count = m.u32()
        check(count in 0..MAX_CONTACTS)
        repeat(5) { check(m.u32() == 0) }
        m.end()
        val contacts = ArrayList<Contact>(count)
        var last = 0L
        val seen = HashSet<List<Byte>>()
        repeat(count) {
            val (type, c) = next()
            check(type == CONTACT)
            val id = c.u64()
            check(id > last)
            last = id
            val identity = c.point()
            val card = c.blob(256)
            check(validCard(identity, card))
            check(seen.add(identity.toList()))
            val name = c.str(1, MAX_NAME_BYTES)
            val colour = c.rgb()
            val met = when (c.u8()) { 0 -> false; 1 -> true; else -> throw Bad() }
            val voice = c.optstr(MAX_VOICE_BYTES)
            val added = c.u64()
            c.end()
            contacts += Contact(id, identity, card, name, colour, met, voice, added)
        }
        val before = plain.copyOfRange(0, b.position())
        val (endType, e) = next()
        check(endType == END)
        check(e.u64() == records - 1)
        check(e.u64() == before.size.toLong())
        check(MessageDigest.isEqual(e.raw(32), sha256(before)))
        e.end()
        check(!b.hasRemaining())
        return Copy(Manifest(source, created, generation, ownName, ownColour), contacts)
    }

    /** A bare identity equal to [identity], or a card that verifies and names exactly that identity. */
    private fun validCard(identity: ByteArray, card: ByteArray): Boolean {
        if (P256.decompress(identity) == null) return false
        if (card.size == POINT) return card.contentEquals(identity)
        val decoded = ContactCard.decode(card) ?: return false
        return decoded.identity.contentEquals(identity)
    }

    private fun check(ok: Boolean) { if (!ok) throw Bad() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Display text: strict UTF-8, no NUL or other control characters. */
    private fun displayable(s: String) = s.none { it.code < 0x20 || it.code == 0x7F }

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun bytes(): ByteArray = out.toByteArray()
        fun raw(b: ByteArray) = out.write(b)
        fun u8(n: Int) = out.write(n)
        fun u16(n: Int) = raw(ByteBuffer.allocate(2).putShort(n.toShort()).array())
        fun u32(n: Int) = raw(ByteBuffer.allocate(4).putInt(n).array())
        fun u64(n: Long) { require(n >= 0) { "negative" }; raw(ByteBuffer.allocate(8).putLong(n).array()) }
        fun rgb(c: Int) { require(c in 0..0xFFFFFF) { "colour" }; raw(byteArrayOf((c shr 16).toByte(), (c shr 8).toByte(), c.toByte())) }
        fun point(p: ByteArray) { require(p.size == POINT); raw(p) }
        fun blob(b: ByteArray) { u32(b.size); raw(b) }
        fun str(s: String, min: Int, max: Int) {
            val b = s.toByteArray(Charsets.UTF_8)
            require(b.size in min..max && displayable(s)) { "text of ${b.size} bytes" }
            u16(b.size); raw(b)
        }
        fun optstr(s: String?, max: Int) = if (s == null) u16(ABSENT) else str(s, 0, max)
    }

    private class Reader(private val bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes)
        fun raw(n: Int): ByteArray { check(n in 0..b.remaining()); return ByteArray(n).also { b.get(it) } }
        fun u8(): Int { check(b.remaining() >= 1); return b.get().toInt() and 0xFF }
        fun u16(): Int { check(b.remaining() >= 2); return b.short.toInt() and 0xFFFF }
        fun u32(): Int { check(b.remaining() >= 4); val n = b.int; check(n >= 0); return n }
        fun u64(): Long { check(b.remaining() >= 8); val n = b.long; check(n >= 0); return n }
        fun rgb(): Int { val c = raw(3); return ((c[0].toInt() and 0xFF) shl 16) or ((c[1].toInt() and 0xFF) shl 8) or (c[2].toInt() and 0xFF) }
        fun point(): ByteArray = raw(POINT)
        fun blob(max: Int): ByteArray { val n = u32(); check(n <= max); return raw(n) }
        fun str(min: Int, max: Int): String {
            val n = u16()
            check(n in min..max)
            val s = utf8(raw(n))
            check(displayable(s))
            return s
        }
        fun optstr(max: Int): String? {
            val n = u16()
            if (n == ABSENT) return null
            check(n <= max)
            val s = utf8(raw(n))
            check(displayable(s))
            return s
        }
        fun end() = check(!b.hasRemaining())

        private fun utf8(raw: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: CharacterCodingException) { throw Bad() }

        @Suppress("unused") val size get() = bytes.size
    }
}

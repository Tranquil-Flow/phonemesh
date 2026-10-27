package org.phonemesh.core.backup

import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.crypto.P256
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
        frame(MANIFEST, RecordWriter().apply {
            u16(SCHEMA); identity(m.sourceIdentity); u64(m.createdAt); u64(m.generation); name(m.ownName, MAX_NAME_BYTES)
            rgb(m.ownColour); u64(0); u64(0); u16(0); u32(copy.contacts.size); repeat(5) { u32(0) }
        }.bytes())
        var last = 0L
        for (c in copy.contacts) {
            require(c.id > last) { "contact ids ascend and are not zero" }
            last = c.id
            require(validCard(c.identity, c.card)) { "contact ${c.id} has a card that is not theirs" }
            frame(CONTACT, RecordWriter().apply {
                u64(c.id); point(c.identity); blob(c.card); name(c.savedName, MAX_NAME_BYTES); rgb(c.colour)
                u8(if (c.historicalMet) 1 else 0); optstr(c.voice, MAX_VOICE_BYTES); u64(c.addedAt)
            }.bytes())
        }
        require(copy.contacts.map { it.identity.toList() }.toSet().size == copy.contacts.size) { "one record per identity" }
        val before = out.toByteArray()
        frame(END, RecordWriter().apply { u64(records); u64(before.size.toLong()); raw(sha256(before)) }.bytes())
        return out.toByteArray()
    }

    /** The copy in [plain], or null when anything breaks the format: no partial result. */
    fun decode(plain: ByteArray): Copy? = try { read(plain) } catch (_: RecordBad) { null } catch (_: RuntimeException) { null }

    private fun read(plain: ByteArray): Copy {
        val b = ByteBuffer.wrap(plain)
        var records = 0L
        fun next(): Pair<Int, RecordReader> {
            need(b.remaining() >= 6)
            val type = b.get().toInt() and 0xFF
            need(b.get().toInt() == 0)
            val length = b.int
            need(length in 0..65_536 && length <= b.remaining())
            val payload = ByteArray(length).also { b.get(it) }
            records++
            return type to RecordReader(payload)
        }
        val (firstType, m) = next()
        need(firstType == MANIFEST)
        need(m.u16() == SCHEMA)
        val source = m.identity()
        val created = m.u64()
        val generation = m.u64()
        val ownName = m.name(MAX_NAME_BYTES)
        val ownColour = m.rgb()
        need(m.u64() == 0L && m.u64() == 0L && m.u16() == 0)
        val count = m.u32()
        need(count in 0..MAX_CONTACTS)
        repeat(5) { need(m.u32() == 0) }
        m.end()
        val contacts = ArrayList<Contact>(count)
        var last = 0L
        val seen = HashSet<List<Byte>>()
        repeat(count) {
            val (type, c) = next()
            need(type == CONTACT)
            val id = c.u64()
            need(id > last)
            last = id
            val identity = c.point()
            val card = c.blob(256)
            need(validCard(identity, card))
            need(seen.add(identity.toList()))
            val name = c.name(MAX_NAME_BYTES)
            val colour = c.rgb()
            val met = when (c.u8()) { 0 -> false; 1 -> true; else -> throw RecordBad() }
            val voice = c.optstr(MAX_VOICE_BYTES)
            val added = c.u64()
            c.end()
            contacts += Contact(id, identity, card, name, colour, met, voice, added)
        }
        val before = plain.copyOfRange(0, b.position())
        val (endType, e) = next()
        need(endType == END)
        need(e.u64() == records - 1)
        need(e.u64() == before.size.toLong())
        need(MessageDigest.isEqual(e.raw(32), sha256(before)))
        e.end()
        need(!b.hasRemaining())
        return Copy(Manifest(source, created, generation, ownName, ownColour), contacts)
    }

    /** A bare identity equal to [identity], or a card that verifies and names exactly that identity. */
    internal fun validCard(identity: ByteArray, card: ByteArray): Boolean {
        if (P256.decompress(identity) == null) return false
        if (card.size == POINT) return card.contentEquals(identity)
        val decoded = ContactCard.decode(card) ?: return false
        return decoded.identity.contentEquals(identity)
    }
}

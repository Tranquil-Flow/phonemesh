package org.phonemesh.core.backup

import org.phonemesh.core.PictureCodec
import org.phonemesh.core.wire.Reactions
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * What a full chat saved copy holds (encrypted chat backup spec 5.5, purpose 1): MANIFEST, then CONTACT, CHAT,
 * MESSAGE, REACTION and PICTURE records, each family by ascending archive id, then END with the count, length and
 * SHA-256 of everything before it. One set of rules ([Rules]) checks both what is written and what is read, record by
 * record, so a copy streams through one bounded record at a time; cross-references go through an [Index] (memory in
 * tests, a staging database on the phone). Never keys, sessions or packets: there is no record type for them.
 *
 * Deviations from the drafted format, for the crypto review: colours are RGB (as in contacts-only copies), and
 * message text may hold line breaks.
 */
object HistoryBackup {
    const val SCHEMA = 1
    const val MAX_CHATS = 10_000
    const val MAX_MESSAGES = 500_000
    const val MAX_REACTIONS = 500_000
    const val MAX_PICTURES = 100_000
    const val MAX_TEXT = 4_096
    const val MAX_PICTURE_BYTES = 3_200
    const val MAX_FRAME = 65_536

    /** Scope bits (spec 5.5): shared-channel chats, old radio chats, unsent and planned text, picture bytes. */
    const val SHARED = 1
    const val LEGACY = 2
    const val UNSENT = 4
    const val PICTURE_DATA = 8

    const val PERSON = 0
    const val SHARED_CHAT = 1
    const val LEGACY_CHAT = 2

    private const val MANIFEST = 1
    private const val CONTACT = 2
    private const val CHAT = 3
    private const val MESSAGE = 4
    private const val REACTION = 5
    private const val PICTURE = 6
    private const val END = 255

    class Manifest(
        sourceIdentity: ByteArray, val createdAt: Long, val generation: Long, val ownName: String, val ownColour: Int,
        val scopeStart: Long, val scopeEnd: Long, val scopeFlags: Int,
        val contacts: Int, val chats: Int, val messages: Int, val reactions: Int, val pictures: Int, val missingPictures: Int,
    ) { val sourceIdentity: ByteArray = sourceIdentity.copyOf() }

    sealed class Record
    class Contact(val contact: ContactsBackup.Contact) : Record()
    class Chat(val id: Long, val kind: Int, val contactId: Long, val title: String, val pinned: Boolean) : Record()
    class Message(
        val id: Long, val chatId: Long, val outgoing: Boolean, val authorContactId: Long, val authorLabel: String?,
        val text: String, val language: String?, val time: Long,
        /** 0 received, 1 sent, 2 reached the hub, 3 delivered, 4 failed, 5 unsent, 6 unconfirmed. */
        val status: Int, val replyId: Long, val replyMissing: Boolean, val plannedAt: Long, val pictureId: Long,
    ) : Record()
    class Reaction(
        val id: Long, val targetId: Long, val authorContactId: Long, val authorLabel: String?, val emoji: String,
        val time: Long, val wasUnsent: Boolean,
    ) : Record()
    class Picture(
        val id: Long, val messageId: Long, val complete: Boolean, val codec: Int, val width: Int, val height: Int,
        sketch: ByteArray, data: ByteArray,
    ) : Record() {
        val sketch: ByteArray = sketch.copyOf()
        val data: ByteArray = data.copyOf()
    }

    /** Cross-references for [Rules]: what earlier records named. A phone keeps these on disk, not in memory. */
    interface Index {
        fun addContact(id: Long, identity: ByteArray): Boolean
        fun hasContact(id: Long): Boolean
        fun addChat(id: Long, contactId: Long): Boolean
        fun hasChat(id: Long): Boolean
        fun addMessage(id: Long, chatId: Long, pictureId: Long): Boolean
        fun messageChat(id: Long): Long?
        fun messagePicture(id: Long): Long?
    }

    /** [Index] in memory, for tests and small copies. */
    class MemoryIndex : Index {
        private val identities = HashSet<List<Byte>>()
        private val contacts = HashSet<Long>()
        private val chats = HashSet<Long>()
        private val personChats = HashSet<Long>()
        private val messages = HashMap<Long, Pair<Long, Long>>()
        private val pictureIds = HashSet<Long>()
        override fun addContact(id: Long, identity: ByteArray) = identities.add(identity.toList()) && contacts.add(id)
        override fun hasContact(id: Long) = id in contacts
        override fun addChat(id: Long, contactId: Long) = (contactId == 0L || personChats.add(contactId)) && chats.add(id)
        override fun hasChat(id: Long) = id in chats
        override fun addMessage(id: Long, chatId: Long, pictureId: Long): Boolean {
            if (pictureId != 0L && !pictureIds.add(pictureId)) return false
            return messages.put(id, chatId to pictureId) == null
        }
        override fun messageChat(id: Long) = messages[id]?.first
        override fun messagePicture(id: Long) = messages[id]?.second
    }

    /** The format's rules, record by record; [RecordBad] for anything outside them. */
    private class Rules(val m: Manifest, val index: Index) {
        private var rank = MANIFEST
        private var lastId = 0L
        private val seen = IntArray(7)
        private var pictureMessages = 0
        private var missing = 0

        init {
            need(m.scopeFlags and 0xF.inv() == 0)
            need((m.scopeStart == 0L && m.scopeEnd == 0L) || m.scopeStart <= m.scopeEnd)
            need(m.contacts in 0..ContactsBackup.MAX_CONTACTS && m.chats in 0..MAX_CHATS && m.messages in 0..MAX_MESSAGES)
            need(m.reactions in 0..MAX_REACTIONS && m.pictures in 0..MAX_PICTURES && m.missingPictures in 0..m.pictures)
            need(m.ownName.toByteArray(Charsets.UTF_8).size in 1..ContactsBackup.MAX_NAME_BYTES && m.ownColour in 0..0xFFFFFF)
        }

        private fun declared(type: Int): Int = when (type) {
            CONTACT -> m.contacts; CHAT -> m.chats; MESSAGE -> m.messages; REACTION -> m.reactions; PICTURE -> m.pictures
            else -> 0
        }

        /** Each family stays within its declared count, and is complete before the next family starts (review A-03). */
        private fun next(type: Int, id: Long) {
            need(type >= rank)
            if (type > rank) {
                for (t in maxOf(rank, CONTACT) until type) need(seen[t - 1] == declared(t))
                rank = type; lastId = 0
            }
            need(id > lastId)
            lastId = id
            seen[type - 1]++
            need(seen[type - 1] <= declared(type))
        }

        fun accept(r: Record) {
            when (r) {
                is Contact -> {
                    val c = r.contact
                    next(CONTACT, c.id)
                    need(ContactsBackup.validCard(c.identity, c.card) && index.addContact(c.id, c.identity))
                    need(c.savedName.toByteArray(Charsets.UTF_8).size in 1..ContactsBackup.MAX_NAME_BYTES && c.colour in 0..0xFFFFFF)
                }
                is Chat -> {
                    next(CHAT, r.id)
                    when (r.kind) {
                        PERSON -> need(r.contactId != 0L && index.hasContact(r.contactId))
                        SHARED_CHAT -> need(r.contactId == 0L && m.scopeFlags and SHARED != 0)
                        LEGACY_CHAT -> need(r.contactId == 0L && m.scopeFlags and LEGACY != 0)
                        else -> throw RecordBad()
                    }
                    need(index.addChat(r.id, r.contactId))
                }
                is Message -> {
                    next(MESSAGE, r.id)
                    need(index.hasChat(r.chatId))
                    need(r.authorContactId == 0L || index.hasContact(r.authorContactId))
                    need(r.status in 0..6 && (if (r.outgoing) r.status != 0 else r.status == 0))
                    need(r.status < 5 || m.scopeFlags and UNSENT != 0)
                    need(!r.replyMissing || r.replyId == 0L)
                    need(r.replyId == 0L || (r.replyId < r.id && index.messageChat(r.replyId) == r.chatId))
                    if (r.pictureId == 0L) need(r.text.isNotEmpty()) else pictureMessages++
                    need(index.addMessage(r.id, r.chatId, r.pictureId))
                }
                is Reaction -> {
                    next(REACTION, r.id)
                    need(index.messageChat(r.targetId) != null)
                    need(r.authorContactId == 0L || index.hasContact(r.authorContactId))
                    need(Reactions.single(r.emoji) == r.emoji)
                }
                is Picture -> {
                    next(PICTURE, r.id)
                    need(index.messagePicture(r.messageId) == r.id)
                    val codec = PictureCodec.of(r.codec)
                    need(codec != null && r.width in 1..512 && r.height in 1..512 && r.sketch.size in 1..32)
                    need(r.data.size <= MAX_PICTURE_BYTES)
                    if (!r.complete || codec == PictureCodec.SKETCH) need(r.data.isEmpty())
                    else need(m.scopeFlags and PICTURE_DATA != 0 && isWebp(r.data))
                    if (!r.complete) missing++
                }
            }
        }

        fun finish() {
            need(seen[CONTACT - 1] == m.contacts && seen[CHAT - 1] == m.chats && seen[MESSAGE - 1] == m.messages)
            need(seen[REACTION - 1] == m.reactions && seen[PICTURE - 1] == m.pictures && missing == m.missingPictures)
            need(pictureMessages == m.pictures)
        }

        private fun isWebp(d: ByteArray) = d.size >= 12 && String(d, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(d, 8, 4, Charsets.US_ASCII) == "WEBP" &&
            ByteBuffer.wrap(d, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() == d.size - 8L
    }

    /** Writes a copy record by record to [out]; [finish] adds END. Breaking a rule throws before anything is written. */
    class Writer(private val out: OutputStream, private val manifest: Manifest, index: Index = MemoryIndex()) {
        private val rules = Rules(manifest, index)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var records = 0L
        private var bytes = 0L

        init {
            frame(MANIFEST, RecordWriter().apply {
                val m = manifest
                u16(SCHEMA); identity(m.sourceIdentity); u64(m.createdAt); u64(m.generation); name(m.ownName, ContactsBackup.MAX_NAME_BYTES)
                rgb(m.ownColour); u64(m.scopeStart); u64(m.scopeEnd); u16(m.scopeFlags)
                u32(m.contacts); u32(m.chats); u32(m.messages); u32(m.reactions); u32(m.pictures); u32(m.missingPictures)
            }.bytes())
        }

        fun write(r: Record) {
            try { rules.accept(r) } catch (_: RecordBad) { throw IllegalArgumentException("a record outside the format: $r") }
            val (type, payload) = encode(r)
            frame(type, payload)
        }

        fun finish() {
            try { rules.finish() } catch (_: RecordBad) { throw IllegalArgumentException("counts do not match the manifest") }
            val end = RecordWriter().apply { u64(records); u64(bytes); raw(digest.digest()) }.bytes()
            out.write(byteArrayOf(END.toByte(), 0) + ByteBuffer.allocate(4).putInt(end.size).array() + end)
        }

        private fun frame(type: Int, payload: ByteArray) {
            require(payload.size <= MAX_FRAME)
            val f = byteArrayOf(type.toByte(), 0) + ByteBuffer.allocate(4).putInt(payload.size).array() + payload
            out.write(f); digest.update(f); records++; bytes += f.size
        }
    }

    private fun encode(r: Record): Pair<Int, ByteArray> = when (r) {
        is Contact -> CONTACT to RecordWriter().apply {
            val c = r.contact
            u64(c.id); point(c.identity); blob(c.card); name(c.savedName, ContactsBackup.MAX_NAME_BYTES); rgb(c.colour)
            u8(if (c.historicalMet) 1 else 0); optstr(c.voice, ContactsBackup.MAX_VOICE_BYTES); u64(c.addedAt)
        }.bytes()
        is Chat -> CHAT to RecordWriter().apply { u64(r.id); u8(r.kind); u64(r.contactId); str(r.title, 0, 256); u8(if (r.pinned) 1 else 0) }.bytes()
        is Message -> MESSAGE to RecordWriter().apply {
            u64(r.id); u64(r.chatId); u8(if (r.outgoing) 1 else 0); u64(r.authorContactId); optstr(r.authorLabel, 160)
            str(r.text, 0, MAX_TEXT, lines = true); optstr(r.language, 64); u64(r.time); u8(r.status); u64(r.replyId)
            u8(if (r.replyMissing) 1 else 0); u64(r.plannedAt); u8(if (r.pictureId != 0L) 1 else 0); u64(r.pictureId)
        }.bytes()
        is Reaction -> REACTION to RecordWriter().apply {
            u64(r.id); u64(r.targetId); u64(r.authorContactId); optstr(r.authorLabel, 160); str(r.emoji, 1, 32); u64(r.time)
            u8(if (r.wasUnsent) 1 else 0)
        }.bytes()
        is Picture -> PICTURE to RecordWriter().apply {
            u64(r.id); u64(r.messageId); u8(if (r.complete) 0 else 1); u8(r.codec); u16(r.width); u16(r.height)
            blob(r.sketch); blob(r.data); raw(sha256(r.data))
        }.bytes()
    }

    /**
     * Reads a copy from [input], giving each checked record to [sink]. The manifest only when the whole stream, END and
     * counts included, is right; null otherwise, and the caller drops what [sink] got (spec 5.7: one commit at the end).
     */
    fun read(input: InputStream, index: Index = MemoryIndex(), sink: (Record) -> Unit = {}): Manifest? = try {
        readOrThrow(input, index, sink)
    } catch (_: RecordBad) { null } catch (_: EOFException) { null } catch (_: RuntimeException) { null }

    private fun readOrThrow(input: InputStream, index: Index, sink: (Record) -> Unit): Manifest {
        val digest = MessageDigest.getInstance("SHA-256")
        var records = 0L
        var bytes = 0L
        val head = ByteArray(6)
        fun frame(): Pair<Int, ByteArray> {
            readFully(input, head)
            val type = head[0].toInt() and 0xFF
            need(head[1].toInt() == 0)
            val length = ByteBuffer.wrap(head, 2, 4).int
            need(length in 0..MAX_FRAME)
            val payload = ByteArray(length).also { readFully(input, it) }
            if (type != END) { digest.update(head); digest.update(payload); records++; bytes += 6 + length }
            return type to payload
        }
        val (firstType, mp) = frame()
        need(firstType == MANIFEST)
        val m = RecordReader(mp).run {
            need(u16() == SCHEMA)
            val manifest = Manifest(identity(), u64(), u64(), name(ContactsBackup.MAX_NAME_BYTES), rgb(), u64(), u64(), u16(),
                u32(), u32(), u32(), u32(), u32(), u32())
            end(); manifest
        }
        val rules = Rules(m, index)
        while (true) {
            val (type, payload) = frame()
            if (type == END) {
                rules.finish()
                RecordReader(payload).run { need(u64() == records && u64() == bytes && MessageDigest.isEqual(raw(32), digest.digest())); end() }
                need(input.read() == -1)
                return m
            }
            val r = decode(type, RecordReader(payload))
            rules.accept(r)
            sink(r)
        }
    }

    private fun decode(type: Int, p: RecordReader): Record {
        val r = when (type) {
            CONTACT -> Contact(ContactsBackup.Contact(p.u64(), p.point(), p.blob(256), p.name(ContactsBackup.MAX_NAME_BYTES), p.rgb(),
                p.flag(), p.optstr(ContactsBackup.MAX_VOICE_BYTES), p.u64()))
            CHAT -> Chat(p.u64(), p.u8(), p.u64(), p.str(0, 256), p.flag())
            MESSAGE -> {
                val id = p.u64(); val chat = p.u64(); val out = p.flag(); val author = p.u64(); val label = p.optstr(160)
                val text = p.str(0, MAX_TEXT, lines = true); val lang = p.optstr(64); val time = p.u64(); val status = p.u8()
                val reply = p.u64(); val replyMissing = p.flag(); val planned = p.u64(); val picture = p.flag(); val pictureId = p.u64()
                need(picture == (pictureId != 0L))
                Message(id, chat, out, author, label, text, lang, time, status, reply, replyMissing, planned, pictureId)
            }
            REACTION -> Reaction(p.u64(), p.u64(), p.u64(), p.optstr(160), p.str(1, 32), p.u64(), p.flag())
            PICTURE -> {
                val id = p.u64(); val message = p.u64(); val state = p.u8(); need(state <= 1)
                val codec = p.u8(); val w = p.u16(); val h = p.u16(); val sketch = p.blob(32); val data = p.blob(MAX_PICTURE_BYTES)
                need(MessageDigest.isEqual(p.raw(32), sha256(data)))
                Picture(id, message, state == 0, codec, w, h, sketch, data)
            }
            else -> throw RecordBad()
        }
        p.end()
        return r
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var at = 0
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) throw EOFException()
            at += n
        }
    }
}

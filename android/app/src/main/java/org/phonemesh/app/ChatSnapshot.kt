package org.phonemesh.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.backup.ChatExport
import org.phonemesh.core.backup.ContactsBackup
import org.phonemesh.core.backup.HistoryBackup
import org.phonemesh.core.wire.Reactions
import java.io.File
import java.io.IOException
import java.util.UUID

/** What a person chose to save: [chats] by id, [since] (ms; null = all history), [unsent] text, [pictures] bytes. */
class ChatScope(val chats: Set<Long>, val since: Long?, val unsent: Boolean = false, val pictures: Boolean = true)

/** What the snapshot left out and why, for the preview ("Never silently claim all chats", spec 5.1). */
class Left(
    /** Messages that did not leave the phone, and planned ones, when unsent text was not chosen. */
    val unsent: Int,
    /** Lines the app wrote into a group chat ("Kofi joined."): shown in the current language, not saved text. */
    val notes: Int,
    /** Chats whose person is not a saved contact any more: the format needs a contact for a person chat. */
    val chatsWithoutContact: Int,
    /** Reactions to messages outside the chosen range, and queued ones. */
    val reactions: Int,
    /** Data the format cannot hold (text that is empty, a picture with impossible sizes). */
    val unsupported: Int,
)

/**
 * A frozen copy of what a person chose to save (backup spec 5.6, "a brief bounded exclusive snapshot copy into
 * app-private staging"). One short transaction on the history copies the chosen rows, and nothing else, into a private
 * scratch file; the history is free again at once, and everything after (checks, picture files, the preview, the
 * sealing) reads only the scratch file, so counts, sizes and records cannot move under the export. A message or
 * picture deleted afterwards is still in this snapshot: it is a picture of the phone at the moment of choosing.
 * [close] deletes the file; [sweep] removes any left by a crash.
 *
 * The scratch file is plaintext in app-private storage, like the history itself, and lives only while a person is
 * saving; it holds no key, session, outbox row or group state.
 */
class ChatSnapshot private constructor(
    private val file: File, private val db: SQLiteDatabase, override val sourceIdentity: ByteArray, override val ownName: String,
    override val ownColour: Int, override val scopeStart: Long, override val scopeEnd: Long, override val scopeFlags: Int,
    private val counted: ChatExport.Counts, val left: Left,
) : ChatExport.Source, AutoCloseable {
    override fun counts() = counted

    override fun contacts(): Iterator<ContactsBackup.Contact> = pages("SELECT id, identity, card, name, colour, met, voice, added FROM s_contacts WHERE id > ? ORDER BY id LIMIT $PAGE") { c ->
        ContactsBackup.Contact(c.getLong(0), c.getBlob(1), c.getBlob(2), c.getString(3), c.getInt(4), c.getInt(5) != 0, c.optString(6), c.getLong(7))
    }

    override fun chats(): Iterator<HistoryBackup.Chat> = pages("SELECT id, kind, contact, title, pinned FROM s_chats WHERE id > ? ORDER BY id LIMIT $PAGE") { c ->
        HistoryBackup.Chat(c.getLong(0), c.getInt(1), c.getLong(2), c.getString(3), c.getInt(4) != 0)
    }

    override fun messages(): Iterator<HistoryBackup.Message> = pages(
        "SELECT id, chat, outgoing, author, label, text, language, time, status, reply, reply_missing, planned, picture " +
            "FROM s_messages WHERE id > ? ORDER BY id LIMIT $PAGE",
    ) { c ->
        HistoryBackup.Message(
            c.getLong(0), c.getLong(1), c.getInt(2) != 0, c.getLong(3), c.optString(4), c.getString(5), c.optString(6), c.getLong(7),
            c.getInt(8), c.getLong(9), c.getInt(10) != 0, c.getLong(11), c.getLong(12),
        )
    }

    override fun reactions(): Iterator<HistoryBackup.Reaction> = pages("SELECT id, target, author, label, emoji, time, unsent FROM s_reactions WHERE id > ? ORDER BY id LIMIT $PAGE") { c ->
        HistoryBackup.Reaction(c.getLong(0), c.getLong(1), c.getLong(2), c.optString(3), c.getString(4), c.getLong(5), c.getInt(6) != 0)
    }

    override fun pictures(): Iterator<HistoryBackup.Picture> = pages("SELECT id, message, complete, codec, width, height, sketch, data FROM s_pictures WHERE id > ? ORDER BY id LIMIT $PAGE") { c ->
        HistoryBackup.Picture(c.getLong(0), c.getLong(1), c.getInt(2) != 0, c.getInt(3), c.getInt(4), c.getInt(5), c.getBlob(6), c.getBlob(7))
    }

    /** Keyset pages: each page asks for ids above the last one seen, so nothing is skipped or repeated. */
    private fun <T> pages(sql: String, row: (Cursor) -> T): Iterator<T> = iterator {
        var last = 0L
        while (true) {
            val batch = db.rawQuery(sql, arrayOf(last.toString())).use { c -> buildList { while (c.moveToNext()) { last = c.getLong(0); add(row(c)) } } }
            if (batch.isEmpty()) return@iterator
            yieldAll(batch)
        }
    }

    private fun Cursor.optString(i: Int): String? = if (isNull(i)) null else getString(i)

    override fun close() {
        runCatching { db.close() }
        file.delete()
        File(file.path + "-journal").delete()
    }

    /** Finds a message of the snapshot by the id the history gave it: for tests and for the preview's per-chat counts. */
    fun messageCount(chatArchiveId: Long): Int = db.rawQuery("SELECT COUNT(*) FROM s_messages WHERE chat = ?", arrayOf(chatArchiveId.toString())).use { it.moveToFirst(); it.getInt(0) }

    companion object {
        private const val PAGE = 500
        const val PREFIX = "snapshot-"

        /** Removes scratch files an earlier run left behind (a crash or a closed screen); call at start. */
        fun sweep(dir: File) {
            dir.listFiles()?.filter { it.name.startsWith(PREFIX) }?.forEach { it.delete() }
        }

        /** How many messages each chat holds since [since] (null: all) that a copy keeps unless unsent text is chosen, for the picker's counts. */
        fun sizes(history: History, since: Long?): Map<Long, Int> = history.readableDatabase.rawQuery(
            "SELECT chat_id, COUNT(*) FROM messages WHERE reaction = 0 AND kind IN ('TEXT','PICTURE') AND time >= ? AND " +
                "(direction = 'IN' OR delivery IS NULL OR delivery NOT IN ('WAITING','QUEUED','UNCONFIRMED')) GROUP BY chat_id",
            arrayOf((since ?: 0L).toString()),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1)) } }

        /**
         * Takes the snapshot on the calling (worker) thread; null before this phone has a name. [label] names a sender
         * of a chat's messages (a group member by what the group knows of them); [cancelled] is asked between steps.
         */
        fun build(
            history: History, picturesDir: File, workDir: File, scope: ChatScope, now: Long,
            label: (Chat, String) -> String?, cancelled: () -> Boolean = { false },
        ): ChatSnapshot? {
            val me = history.accountRow()?.takeIf { it.name.isNotEmpty() } ?: return null
            workDir.mkdirs()
            val file = File(workDir, PREFIX + UUID.randomUUID() + ".db")
            val snap = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                create(snap)
                val entries = Recovery.entries(history)
                val byAccount = entries.associate { it.accountId to it.contact.id }
                val chosen = history.chats().filter { it.id in scope.chats }
                // Who sent what, named before the short exclusive step: the lookups are not part of the frozen view.
                val labels = HashMap<Pair<Long, String>, String?>()
                for (chat in chosen) {
                    history.readableDatabase.rawQuery("SELECT DISTINCT sender_id FROM messages WHERE chat_id = ? AND sender_id IS NOT NULL", arrayOf(chat.id.toString())).use { c ->
                        while (c.moveToNext()) { val id = c.getString(0); labels[chat.id to id] = label(chat, id)?.let { ArchiveText.clean(it, 160) } }
                    }
                }
                if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
                val since = scope.since ?: 0L
                var chatsWithoutContact = 0
                var unsent = 0
                var notes = 0
                snap.close()
                val main = history.writableDatabase
                main.execSQL("ATTACH DATABASE ? AS snap", arrayOf(file.path))
                try {
                    main.beginTransaction()
                    try {
                        for (e in entries) main.execSQL(
                            "INSERT INTO snap.s_contacts (id, account, identity, card, name, colour, met, voice, added) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf<Any?>(e.contact.id, e.accountId, e.contact.identity, e.contact.card, e.contact.savedName, e.contact.colour,
                                if (e.contact.historicalMet) 1 else 0, e.contact.voice, e.contact.addedAt),
                        )
                        for (chat in chosen) {
                            val kind = when (chat.kind) {
                                ChatKind.PERSON -> HistoryBackup.PERSON
                                ChatKind.GROUP -> HistoryBackup.SHARED_CHAT
                                ChatKind.DIRECT -> HistoryBackup.LEGACY_CHAT
                                ChatKind.PRIVATE_GROUP -> HistoryBackup.GROUP_CHAT
                            }
                            val contact = if (chat.kind == ChatKind.PERSON) byAccount[chat.address] ?: 0L else 0L
                            if (chat.kind == ChatKind.PERSON && contact == 0L) { chatsWithoutContact++; continue }
                            val title = ArchiveText.clean(chat.name.ifEmpty { if (contact != 0L) entries.first { it.contact.id == contact }.contact.savedName else "" }, 256)
                                .ifEmpty { if (kind == HistoryBackup.GROUP_CHAT) "?" else "" }
                            main.execSQL(
                                "INSERT INTO snap.s_chats (src, kind, contact, title, pinned) VALUES (?, ?, ?, ?, ?)",
                                arrayOf<Any?>(chat.id, kind, contact, title, if (chat.pinned) 1 else 0),
                            )
                            // Names for the senders heard in this chat; a person chat's incoming messages are by that contact.
                            main.rawQuery("SELECT DISTINCT sender_id FROM messages WHERE chat_id = ? AND sender_id IS NOT NULL", arrayOf(chat.id.toString())).use { c ->
                                while (c.moveToNext()) {
                                    val sender = c.getString(0)
                                    val author = if (chat.kind == ChatKind.PERSON || chat.kind == ChatKind.PRIVATE_GROUP) byAccount[sender] ?: 0L else 0L
                                    main.execSQL(
                                        "INSERT INTO snap.s_labels (src_chat, sender, label, contact) VALUES (?, ?, ?, ?)",
                                        arrayOf<Any?>(chat.id, sender, if (chat.kind == ChatKind.PERSON) null else labels[chat.id to sender], author),
                                    )
                                }
                            }
                        }
                        val unsentSql = "(? = 1 OR m.direction = 'IN' OR m.delivery IS NULL OR m.delivery NOT IN ('WAITING','QUEUED','UNCONFIRMED'))"
                        val hold = "'WAITING','QUEUED','UNCONFIRMED'"
                        main.execSQL(
                            "INSERT INTO snap.s_messages (src, chat, packet, outgoing, author, label, text, language, time, status, reply_src, is_picture) " +
                                "SELECT m.id, c.id, m.packet_id, m.direction = 'OUT', COALESCE(l.contact, 0), l.label, m.text, m.language, m.time, " +
                                "CASE WHEN m.direction = 'IN' THEN 0 ELSE CASE m.delivery WHEN 'DELIVERED' THEN 3 WHEN 'HUB' THEN 2 WHEN 'FAILED' THEN 4 " +
                                "WHEN 'WAITING' THEN 5 WHEN 'QUEUED' THEN 5 WHEN 'UNCONFIRMED' THEN 6 ELSE 1 END END, m.reply_to, m.kind = 'PICTURE' " +
                                "FROM main.messages m JOIN snap.s_chats c ON c.src = m.chat_id " +
                                "LEFT JOIN snap.s_labels l ON l.src_chat = m.chat_id AND l.sender = m.sender_id " +
                                "WHERE m.reaction = 0 AND m.kind IN ('TEXT', 'PICTURE') AND m.time >= ? AND $unsentSql " +
                                "AND (m.kind = 'TEXT' OR EXISTS (SELECT 1 FROM main.pictures p WHERE p.message_id = m.id)) ORDER BY m.id",
                            arrayOf<Any>(since, if (scope.unsent) 1 else 0),
                        )
                        if (scope.unsent) {
                            main.execSQL(
                                "INSERT INTO snap.s_messages (src, chat, packet, outgoing, author, label, text, language, time, status, reply_src, is_picture, planned, reply_missing) " +
                                    "SELECT NULL, c.id, NULL, 1, 0, NULL, p.text, p.language, p.due, 5, NULL, 0, p.due, CASE WHEN p.reply_to IS NULL THEN 0 ELSE 1 END " +
                                    "FROM main.planned p JOIN snap.s_chats c ON c.src = p.chat_id ORDER BY p.id",
                            )
                        } else {
                            unsent = scalar(main, "SELECT COUNT(*) FROM main.messages m JOIN snap.s_chats c ON c.src = m.chat_id WHERE m.reaction = 0 AND " +
                                "m.kind IN ('TEXT', 'PICTURE') AND m.time >= ? AND m.direction = 'OUT' AND m.delivery IN ($hold)", since.toString()) +
                                scalar(main, "SELECT COUNT(*) FROM main.planned p JOIN snap.s_chats c ON c.src = p.chat_id")
                        }
                        notes = scalar(main, "SELECT COUNT(*) FROM main.messages m JOIN snap.s_chats c ON c.src = m.chat_id WHERE m.kind = 'NOTE' AND m.time >= ?", since.toString())
                        main.execSQL(
                            "INSERT INTO snap.s_pictures (id, message, complete, codec, width, height, sketch, src_file, lost) " +
                                "SELECT sm.id, sm.id, 0, p.codec, p.width, p.height, p.sketch, p.file, p.lost FROM snap.s_messages sm " +
                                "JOIN main.pictures p ON p.message_id = sm.src WHERE sm.is_picture = 1 ORDER BY sm.id",
                        )
                        main.execSQL(
                            "INSERT INTO snap.s_reactions (src_chat, chat, target_packet, author, label, emoji, time, unsent) " +
                                "SELECT r.chat_id, c.id, r.reply_to, COALESCE(l.contact, 0), l.label, r.text, r.time, CASE WHEN r.delivery = 'QUEUED' THEN 1 ELSE 0 END " +
                                "FROM main.messages r JOIN snap.s_chats c ON c.src = r.chat_id " +
                                "LEFT JOIN snap.s_labels l ON l.src_chat = r.chat_id AND l.sender = r.sender_id " +
                                "WHERE r.reaction = 1 AND r.reply_to IS NOT NULL AND (? = 1 OR r.delivery IS NOT 'QUEUED') ORDER BY r.id",
                            arrayOf<Any>(if (scope.unsent) 1 else 0),
                        )
                        main.setTransactionSuccessful()
                    } finally {
                        main.endTransaction()
                    }
                } finally {
                    runCatching { main.execSQL("DETACH DATABASE snap") }
                }
                // From here on the history is not touched: the checks and picture files work on the private file.
                val db = SQLiteDatabase.openOrCreateDatabase(file, null)
                db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA synchronous=OFF", null).use { it.moveToFirst() }
                val s = finish(db, picturesDir, scope, now, since, cancelled)
                val kinds = chosen.map { it.kind }.toSet()
                val flags = (if (ChatKind.GROUP in kinds) HistoryBackup.SHARED else 0) or (if (ChatKind.DIRECT in kinds) HistoryBackup.LEGACY else 0) or
                    (if (scope.unsent) HistoryBackup.UNSENT else 0) or (if (scope.pictures) HistoryBackup.PICTURE_DATA else 0) or
                    (if (ChatKind.PRIVATE_GROUP in kinds) HistoryBackup.GROUPS else 0)
                val counts = ChatExport.Counts(
                    scalar(db, "SELECT COUNT(*) FROM s_contacts"), scalar(db, "SELECT COUNT(*) FROM s_chats"), scalar(db, "SELECT COUNT(*) FROM s_messages"),
                    scalar(db, "SELECT COUNT(*) FROM s_reactions"), scalar(db, "SELECT COUNT(*) FROM s_pictures"),
                    scalar(db, "SELECT COUNT(*) FROM s_pictures WHERE complete = 0"),
                )
                return ChatSnapshot(
                    file, db, me.identity, Recovery.displayName(me.name), me.colour and 0xFFFFFF, scope.since?.let { maxOf(0L, it) } ?: 0L,
                    if (scope.since != null) maxOf(now, scope.since) else 0L, flags, counts,
                    Left(unsent, notes, chatsWithoutContact, s.reactions, s.unsupported),
                )
            } catch (e: Throwable) {
                runCatching { snap.close() }
                file.delete()
                File(file.path + "-journal").delete()
                throw e
            }
        }

        private class Finished(val reactions: Int, val unsupported: Int)

        private fun create(db: SQLiteDatabase) {
            for (sql in listOf(
                "CREATE TABLE s_contacts (id INTEGER PRIMARY KEY, account TEXT, identity BLOB NOT NULL, card BLOB NOT NULL, name TEXT NOT NULL, colour INTEGER NOT NULL, met INTEGER NOT NULL, voice TEXT, added INTEGER NOT NULL)",
                "CREATE TABLE s_chats (id INTEGER PRIMARY KEY, src INTEGER NOT NULL, kind INTEGER NOT NULL, contact INTEGER NOT NULL, title TEXT NOT NULL, pinned INTEGER NOT NULL)",
                "CREATE TABLE s_labels (src_chat INTEGER NOT NULL, sender TEXT NOT NULL, label TEXT, contact INTEGER NOT NULL, PRIMARY KEY (src_chat, sender))",
                "CREATE TABLE s_messages (id INTEGER PRIMARY KEY, src INTEGER, chat INTEGER NOT NULL, packet INTEGER, outgoing INTEGER NOT NULL, author INTEGER NOT NULL, " +
                    "label TEXT, text TEXT NOT NULL, language TEXT, time INTEGER NOT NULL, status INTEGER NOT NULL, reply_src INTEGER, reply INTEGER NOT NULL DEFAULT 0, " +
                    "reply_missing INTEGER NOT NULL DEFAULT 0, planned INTEGER NOT NULL DEFAULT 0, picture INTEGER NOT NULL DEFAULT 0, is_picture INTEGER NOT NULL)",
                "CREATE INDEX s_messages_by_packet ON s_messages (chat, packet)",
                "CREATE TABLE s_reactions (id INTEGER PRIMARY KEY, src_chat INTEGER, chat INTEGER NOT NULL, target_packet INTEGER NOT NULL, target INTEGER NOT NULL DEFAULT 0, " +
                    "author INTEGER NOT NULL, label TEXT, emoji TEXT NOT NULL, time INTEGER NOT NULL, unsent INTEGER NOT NULL)",
                "CREATE TABLE s_pictures (id INTEGER PRIMARY KEY, message INTEGER NOT NULL, complete INTEGER NOT NULL, codec INTEGER NOT NULL, width INTEGER NOT NULL, " +
                    "height INTEGER NOT NULL, sketch BLOB NOT NULL, src_file TEXT, lost INTEGER NOT NULL DEFAULT 0, data BLOB NOT NULL DEFAULT x'')",
            )) db.execSQL(sql)
        }

        private fun scalar(db: SQLiteDatabase, sql: String, vararg args: String): Int = db.rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }

        /** Checks every copied row against the format, reads the picture files and links replies and reactions. */
        private fun finish(db: SQLiteDatabase, picturesDir: File, scope: ChatScope, now: Long, since: Long, cancelled: () -> Boolean): Finished {
            var unsupported = 0
            val pending = ArrayList<Long>()
            db.beginTransaction()
            try {
                // Text the format's field rules would refuse is made to fit; a message with no text and no picture cannot be kept.
                db.rawQuery("SELECT id, text, label, language, is_picture FROM s_messages", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val text = c.getString(1)
                        val clean = ArchiveText.clean(text, HistoryBackup.MAX_TEXT, lines = true)
                        val label = c.getString(2)?.let { ArchiveText.clean(it, 160) }
                        val language = c.getString(3)?.let { ArchiveText.clean(it, 64) }
                        if (clean.isEmpty() && c.getInt(4) == 0) { pending.add(id); continue }
                        if (clean != text || label != c.getString(2) || language != c.getString(3)) {
                            db.execSQL("UPDATE s_messages SET text = ?, label = ?, language = ? WHERE id = ?", arrayOf<Any?>(clean, label, language, id))
                        }
                    }
                }
                for (id in pending) { db.execSQL("DELETE FROM s_messages WHERE id = ?", arrayOf<Any>(id)); unsupported++ }
                pending.clear()
                // Pictures: the bytes come from the picture file; one that is gone, broken or not chosen is a placeholder.
                db.rawQuery("SELECT id, codec, width, height, sketch, src_file, lost FROM s_pictures", null).use { c ->
                    while (c.moveToNext()) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
                        val id = c.getLong(0)
                        val codec = PictureCodec.of(c.getInt(1))
                        val sketch = c.getBlob(4)
                        if (codec == null || c.getInt(2) !in 1..512 || c.getInt(3) !in 1..512 || sketch.size !in 1..32) { pending.add(id); continue }
                        var complete = false
                        var data = ByteArray(0)
                        if (codec == PictureCodec.SKETCH) {
                            complete = c.getInt(6) == 0
                        } else if (scope.pictures && c.getInt(6) == 0 && !c.isNull(5)) {
                            val bytes = runCatching { File(picturesDir, c.getString(5)).takeIf { it.isFile && it.length() <= HistoryBackup.MAX_PICTURE_BYTES }?.readBytes() }.getOrNull()
                            if (bytes != null && isWebp(bytes)) { complete = true; data = bytes }
                        }
                        db.execSQL("UPDATE s_pictures SET complete = ?, data = ? WHERE id = ?", arrayOf<Any>(if (complete) 1 else 0, data, id))
                    }
                }
                for (id in pending) {
                    db.execSQL("DELETE FROM s_pictures WHERE id = ?", arrayOf<Any>(id))
                    db.execSQL("DELETE FROM s_messages WHERE id = ?", arrayOf<Any>(id))
                    unsupported++
                }
                pending.clear()
                db.execSQL("UPDATE s_messages SET picture = id WHERE is_picture = 1")
                // Replies name an earlier message of the same chat by its radio id; no earlier one means "missing", never a guess.
                db.execSQL(
                    "UPDATE s_messages SET reply = COALESCE((SELECT t.id FROM s_messages t WHERE t.chat = s_messages.chat AND t.packet = s_messages.reply_src " +
                        "AND t.id < s_messages.id ORDER BY t.id DESC LIMIT 1), 0) WHERE reply_src IS NOT NULL",
                )
                db.execSQL("UPDATE s_messages SET reply_missing = 1 WHERE reply_src IS NOT NULL AND reply = 0")
                // Reactions keep only targets that are in the snapshot, with an emoji the reaction rules accept.
                db.execSQL(
                    "UPDATE s_reactions SET target = COALESCE((SELECT MAX(t.id) FROM s_messages t WHERE t.chat = s_reactions.chat AND t.packet = s_reactions.target_packet), 0)",
                )
                var dropped = scalar(db, "SELECT COUNT(*) FROM s_reactions WHERE target = 0")
                db.execSQL("DELETE FROM s_reactions WHERE target = 0")
                val bad = ArrayList<Long>()
                db.rawQuery("SELECT id, emoji FROM s_reactions", null).use { c -> while (c.moveToNext()) if (Reactions.single(c.getString(1)) != c.getString(1)) bad.add(c.getLong(0)) }
                for (id in bad) { db.execSQL("DELETE FROM s_reactions WHERE id = ?", arrayOf<Any>(id)); dropped++ }
                db.setTransactionSuccessful()
                return Finished(dropped, unsupported)
            } finally {
                db.endTransaction()
            }
        }

        private fun isWebp(d: ByteArray) = d.size >= 12 && String(d, 0, 4, Charsets.US_ASCII) == "RIFF" && String(d, 8, 4, Charsets.US_ASCII) == "WEBP" &&
            java.nio.ByteBuffer.wrap(d, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() == d.size - 8L
    }
}

package org.phonemesh.core.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * Making a full chat saved copy (encrypted chat backup spec 5.6, purpose 1) from a [Source]: a frozen view of what the
 * person chose. The work is two walks over that view. [preview] walks it once into nothing, so the person sees exact
 * counts and the exact size before anything is saved, and every record has met the format's rules. [seal] walks it
 * again, one bounded record at a time, straight into the sealed container: no plaintext copy of the history is ever
 * written, and the size [preview] found must be the size written (the view changed otherwise: [Changed]).
 *
 * The [Source] has to be stable: the same records, in the same order, every time it is walked. It is built from a
 * snapshot on the phone ([org.phonemesh.app.ChatSnapshot]); a limit is refused here, before anything is saved, with
 * a reason that lets the screen say "choose fewer chats or fewer days", never silently cut.
 */
object ChatExport {
    class Counts(val contacts: Int, val chats: Int, val messages: Int, val reactions: Int, val pictures: Int, val missingPictures: Int)

    /** The chosen content, in the order the format needs: contacts, chats, messages, reactions, pictures, each by ascending id. */
    interface Source {
        val sourceIdentity: ByteArray
        val ownName: String
        val ownColour: Int
        /** Inclusive time range of the chosen history; both zero when all of it was chosen. */
        val scopeStart: Long
        val scopeEnd: Long
        val scopeFlags: Int
        fun counts(): Counts
        fun contacts(): Iterator<ContactsBackup.Contact>
        fun chats(): Iterator<HistoryBackup.Chat>
        fun messages(): Iterator<HistoryBackup.Message>
        fun reactions(): Iterator<HistoryBackup.Reaction>
        fun pictures(): Iterator<HistoryBackup.Picture>
    }

    enum class Why { TOO_MANY_CHATS, TOO_MANY_MESSAGES, TOO_MANY_REACTIONS, TOO_MANY_PICTURES, TOO_BIG }

    sealed class Preview {
        /** [plainBytes] is the stream before sealing (the size the header will hold); [sealedBytes] the whole file. */
        class Ok(val counts: Counts, val records: Long, val plainBytes: Long, val sealedBytes: Long) : Preview()
        class Refused(val why: Why) : Preview()
    }

    /** The view did not hold what it counted, or a different size than the preview found: nothing is published. */
    class Changed(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private fun manifest(source: Source, counts: Counts, createdAt: Long, generation: Long) = HistoryBackup.Manifest(
        source.sourceIdentity, createdAt, generation, source.ownName, source.ownColour, source.scopeStart, source.scopeEnd,
        source.scopeFlags, counts.contacts, counts.chats, counts.messages, counts.reactions, counts.pictures, counts.missingPictures,
    )

    private fun refusal(c: Counts): Why? = when {
        c.contacts > ContactsBackup.MAX_CONTACTS -> Why.TOO_BIG
        c.chats > HistoryBackup.MAX_CHATS -> Why.TOO_MANY_CHATS
        c.messages > HistoryBackup.MAX_MESSAGES -> Why.TOO_MANY_MESSAGES
        c.reactions > HistoryBackup.MAX_REACTIONS -> Why.TOO_MANY_REACTIONS
        c.pictures > HistoryBackup.MAX_PICTURES -> Why.TOO_MANY_PICTURES
        else -> null
    }

    /** The size of a whole file for [plainBytes] of stream: `120 + P + 20 * N` (spec 5.4). */
    fun sealedSize(plainBytes: Long): Long = BackupFile.HEADER + plainBytes + 20 * ((plainBytes + BackupFile.CHUNK - 1) / BackupFile.CHUNK)

    private class Counting : OutputStream() {
        var bytes = 0L
        override fun write(b: Int) { bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
    }

    /** The records of [source] in format order, one at a time. */
    private fun records(source: Source): Iterator<HistoryBackup.Record> = sequence {
        for (c in source.contacts()) yield(HistoryBackup.Contact(c))
        yieldAll(source.chats().asSequence())
        yieldAll(source.messages().asSequence())
        yieldAll(source.reactions().asSequence())
        yieldAll(source.pictures().asSequence())
    }.iterator()

    /**
     * Walks [source] into nothing: exact counts and sizes, every record checked against the format's rules. [progress]
     * gets records done and records expected; [cancelled] is asked between records ([CancellationException] when true).
     */
    fun preview(
        source: Source, createdAt: Long, generation: Long, index: HistoryBackup.Index = HistoryBackup.MemoryIndex(),
        cancelled: () -> Boolean = { false }, progress: (Long, Long) -> Unit = { _, _ -> },
    ): Preview {
        val counts = source.counts()
        refusal(counts)?.let { return Preview.Refused(it) }
        val total = counts.contacts + counts.chats + counts.messages + counts.reactions + counts.pictures.toLong()
        val sink = Counting()
        val rows = walk(source, manifest(source, counts, createdAt, generation), sink, index, total, cancelled, progress)
        if (sink.bytes > BackupFile.MAX_PLAIN) return Preview.Refused(Why.TOO_BIG)
        return Preview.Ok(counts, rows, sink.bytes, sealedSize(sink.bytes))
    }

    private fun walk(
        source: Source, m: HistoryBackup.Manifest, out: OutputStream, index: HistoryBackup.Index, total: Long,
        cancelled: () -> Boolean, progress: (Long, Long) -> Unit,
    ): Long {
        var done = 0L
        try {
            val writer = HistoryBackup.Writer(out, m, index)
            val all = records(source)
            while (all.hasNext()) {
                if (cancelled()) throw CancellationException("cancelled")
                writer.write(all.next())
                progress(++done, total)
            }
            writer.finish()
        } catch (e: IllegalArgumentException) {
            throw Changed("the chosen content does not match its counts", e)
        }
        return done + 2
    }

    /**
     * The same walk, now sealed under [key] into [out] (a private temporary file; the caller checks it, then copies it
     * out). [plainBytes] is what [preview] found; a different size means the view changed ([Changed]).
     */
    fun seal(
        source: Source, key: RecoveryKey, createdAt: Long, generation: Long, plainBytes: Long, out: OutputStream,
        index: HistoryBackup.Index = HistoryBackup.MemoryIndex(), cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val counts = source.counts()
        val total = counts.contacts + counts.chats + counts.messages + counts.reactions + counts.pictures.toLong()
        val m = manifest(source, counts, createdAt, generation)
        val feed = Feed(source, m, index, total, cancelled, progress)
        try {
            BackupStream.seal(key, BackupFile.Purpose.HISTORY, feed, plainBytes, out)
        } catch (e: IllegalArgumentException) {
            if (e.message?.startsWith("plaintext of") == true) throw e
            throw Changed("the chosen content is not the size it was counted at", e)
        } catch (e: java.io.EOFException) {
            throw Changed("the chosen content is shorter than it was counted", e)
        }
    }

    /** The plaintext stream, made on demand: a record at a time goes through the rules into a small buffer. */
    private class Feed(
        source: Source, m: HistoryBackup.Manifest, index: HistoryBackup.Index, private val total: Long,
        private val cancelled: () -> Boolean, private val progress: (Long, Long) -> Unit,
    ) : InputStream() {
        private val buffer = object : ByteArrayOutputStream() {
            fun take(into: ByteArray, off: Int, len: Int, from: Int): Int { System.arraycopy(buf, from, into, off, len); return len }
        }
        private val writer = HistoryBackup.Writer(buffer, m, index)
        private val all = records(source)
        private var at = 0
        private var done = 0L
        private var finished = false

        private fun fill(): Boolean {
            while (at >= buffer.size()) {
                if (finished) return false
                buffer.reset(); at = 0
                if (cancelled()) throw CancellationException("cancelled")
                if (all.hasNext()) { writer.write(all.next()); progress(++done, total) } else { writer.finish(); finished = true }
            }
            return true
        }

        override fun read(): Int {
            if (!fill()) return -1
            val one = ByteArray(1)
            buffer.take(one, 0, 1, at++)
            return one[0].toInt() and 0xFF
        }

        override fun read(into: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, buffer.size() - at)
            buffer.take(into, off, n, at)
            at += n
            return n
        }
    }
}

package org.phonemesh.app

import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.Names
import org.phonemesh.core.backup.BackupFile
import org.phonemesh.core.backup.ContactsBackup
import org.phonemesh.core.backup.RecoveryKey
import org.phonemesh.core.crypto.P256
import java.text.Normalizer

/**
 * Contacts-only saved copies (account recovery RC1-RC5 A, backup purpose 0). Making one reads this phone's name and
 * contacts, never a key or session. Restoring one fills a separate list of saved people, never the live contacts:
 * a new phone has a new identity, so each person is added again in person or by link (RC5 A).
 */
object Recovery {
    /** Why a copy did not open: wrong key and changed bytes are one answer (they cannot be told apart). */
    enum class Problem { NOT_A_COPY, KEY_OR_FILE_DOES_NOT_MATCH, NOT_CONTACTS, TOO_NEW, TOO_BIG }

    sealed class Opened {
        class Ok(val copy: ContactsBackup.Copy, val archiveId: ByteArray) : Opened()
        class Failed(val problem: Problem) : Opened()
    }

    /** A saved contact with the account id the app files them under (the full-copy exporter maps chats through it). */
    class Entry(val accountId: String, val contact: ContactsBackup.Contact)

    /** This phone's contacts as copy records, numbered from 1 in the order they were added. Old cards that no longer read keep their identity. */
    fun entries(history: History): List<Entry> {
        var id = 0L
        val seen = HashSet<List<Byte>>()
        return history.contactRows().mapNotNull { c ->
            // A contact added from a hello is kept as their bare identity, not a card (review A-02): both shapes count.
            val identity = identityOf(c.card) ?: return@mapNotNull null
            if (!seen.add(identity.toList())) return@mapNotNull null
            val card = if (c.card.size > IDENTITY && ContactCard.decode(c.card)?.identity?.contentEquals(identity) == true) c.card else identity
            Entry(c.accountId, ContactsBackup.Contact(
                ++id, identity, card, displayName(c.name), c.colour and 0xFFFFFF, c.met,
                c.voice?.takeIf { it.toByteArray(Charsets.UTF_8).size <= ContactsBackup.MAX_VOICE_BYTES }, maxOf(0L, c.added),
            ))
        }
    }

    /** What this phone would save now; null before it has a name. */
    fun copy(history: History, now: Long, generation: Long): ContactsBackup.Copy? {
        val me = history.accountRow()?.takeIf { it.name.isNotEmpty() } ?: return null
        val contacts = entries(history).map { it.contact }
        return ContactsBackup.Copy(ContactsBackup.Manifest(me.identity, maxOf(0L, now), generation, displayName(me.name), me.colour and 0xFFFFFF), contacts)
    }

    /** The sealed file for this phone's contacts under [key]; null before it has a name. */
    fun export(history: History, key: RecoveryKey, now: Long, generation: Long): ByteArray? =
        copy(history, now, generation)?.let { BackupFile.seal(key, BackupFile.Purpose.CONTACTS, ContactsBackup.encode(it)) }

    /** Opens [file] with [key] and reads every record, changing nothing (the check, and the first step of a restore). */
    fun open(file: ByteArray, key: RecoveryKey): Opened = when (val o = BackupFile.open(key, file)) {
        is BackupFile.Opened.Failed -> Opened.Failed(when (o.problem) {
            BackupFile.Problem.NOT_A_BACKUP -> Problem.NOT_A_COPY
            BackupFile.Problem.UNSUPPORTED -> Problem.TOO_NEW
            BackupFile.Problem.TOO_BIG -> Problem.TOO_BIG
            BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH -> Problem.KEY_OR_FILE_DOES_NOT_MATCH
        })
        is BackupFile.Opened.Ok ->
            if (o.purpose != BackupFile.Purpose.CONTACTS) Opened.Failed(Problem.NOT_CONTACTS)
            else ContactsBackup.decode(o.plain)?.let { Opened.Ok(it, o.archiveId) } ?: Opened.Failed(Problem.KEY_OR_FILE_DOES_NOT_MATCH)
    }

    /**
     * Keeps [opened]'s people as saved contacts to add again: not this phone itself, not anyone already a contact, and
     * nobody twice. Sends nothing and starts no session. Returns how many were new.
     */
    fun restore(history: History, opened: Opened.Ok, now: Long): Int = restore(history, opened.copy.contacts, opened.archiveId, now)

    /** [restore] for the people of any copy, a full chat copy's too. */
    fun restore(history: History, people: List<ContactsBackup.Contact>, archiveId: ByteArray, now: Long): Int {
        val own = history.accountRow()?.identity
        val live = history.contactRows().mapNotNull { r -> identityOf(r.card)?.toList() }.toSet()
        // Saved people stay bounded however many copies are restored: at most one copy's worth (review A-01).
        val room = maxOf(0, ContactsBackup.MAX_CONTACTS - history.recoveredRows().size)
        val rows = people
            .filter { c -> own == null || !c.identity.contentEquals(own) }
            .filter { c -> c.identity.toList() !in live }
            .take(room)
            .map { c -> History.RecoveredRow(c.identity, c.card, c.savedName, c.colour, c.historicalMet, c.voice, c.addedAt) }
        return history.addRecovered(rows, archiveId, now)
    }

    /** The identity of a saved contact row: a whole card or the bare key ([Accounts.identityOf]); null if neither. */
    private fun identityOf(card: ByteArray): ByteArray? =
        if (card.size == IDENTITY || card.size >= 2 + IDENTITY) Accounts.identityOf(card).takeIf { P256.decompress(it) != null } else null

    /** A name the copy's name rules take (review A-04): fitted, and "?" when nothing valid is left. */
    internal fun displayName(name: String): String =
        Names.fit(Normalizer.normalize(name, Normalizer.Form.NFC), ContactsBackup.MAX_NAME_BYTES).takeIf { Names.valid(it) } ?: "?"

    private const val IDENTITY = 33
}

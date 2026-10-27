package org.phonemesh.app

import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.Names
import org.phonemesh.core.backup.BackupFile
import org.phonemesh.core.backup.ContactsBackup
import org.phonemesh.core.backup.RecoveryKey
import org.phonemesh.core.crypto.P256

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

    /** What this phone would save now; null before it has a name. Old cards that no longer read keep their identity. */
    fun copy(history: History, now: Long, generation: Long): ContactsBackup.Copy? {
        val me = history.accountRow()?.takeIf { it.name.isNotEmpty() } ?: return null
        var id = 0L
        val seen = HashSet<List<Byte>>()
        val contacts = history.contactRows().mapNotNull { c ->
            if (c.card.size < 35) return@mapNotNull null
            val identity = c.card.copyOfRange(2, 35)
            if (P256.decompress(identity) == null || !seen.add(identity.toList())) return@mapNotNull null
            val card = if (ContactCard.decode(c.card)?.identity?.contentEquals(identity) == true) c.card else identity
            ContactsBackup.Contact(
                ++id, identity, card, displayName(c.name), c.colour and 0xFFFFFF, c.met,
                c.voice?.takeIf { it.toByteArray(Charsets.UTF_8).size <= ContactsBackup.MAX_VOICE_BYTES }, maxOf(0L, c.added),
            )
        }
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
    fun restore(history: History, opened: Opened.Ok, now: Long): Int {
        val own = history.accountRow()?.identity
        val live = history.contactRows().mapNotNull { r -> r.card.takeIf { it.size >= 35 }?.copyOfRange(2, 35)?.toList() }.toSet()
        val rows = opened.copy.contacts
            .filter { c -> own == null || !c.identity.contentEquals(own) }
            .filter { c -> c.identity.toList() !in live }
            .map { c -> History.RecoveredRow(c.identity, c.card, c.savedName, c.colour, c.historicalMet, c.voice, c.addedAt) }
        return history.addRecovered(rows, opened.archiveId, now)
    }

    private fun displayName(name: String): String =
        Names.fit(name.filter { it.code >= 0x20 && it.code != 0x7F }, ContactsBackup.MAX_NAME_BYTES).ifEmpty { "?" }
}

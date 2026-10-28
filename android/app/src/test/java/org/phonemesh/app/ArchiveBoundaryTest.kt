package org.phonemesh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Saved chat copies are read only history (backup spec 5.7 step 8, decision 75): nothing that sends, receives, notifies,
 * speaks, receipts or schedules may read the archive tables. Only the files that make, read and show saved copies may
 * name them; any other source file naming one fails here.
 */
class ArchiveBoundaryTest {
    private val sources = File("src/main/java/org/phonemesh/app").walkTopDown().filter { it.extension == "kt" }.toList()

    private val names = ArchiveRows.TABLES + listOf("ArchiveRows", "ArchiveImport", "ArchiveReader")

    /** The files that may name the archive tables or classes. */
    private val allowed = setOf(
        "History.kt", "ArchiveRows.kt", "ArchiveImport.kt", "ChatBackup.kt", "ArchivesActivity.kt", "ArchiveChatActivity.kt",
        "RecoveryActivity.kt", "ChatBackupActivity.kt", "AppCore.kt",
    )

    @Test fun onlyTheCopyFilesNameTheArchiveTables() {
        assertTrue("sources found", sources.size > 50)
        val offenders = sources.filter { f -> f.name !in allowed && names.any { n -> Regex("\\b$n\\b").containsMatchIn(f.readText()) } }.map { it.name }
        assertEquals("these files name archive tables: $offenders", emptyList<String>(), offenders)
    }

    @Test fun theCoreAppFileOnlyStartsTheCleanup() {
        val uses = File("src/main/java/org/phonemesh/app/AppCore.kt").readLines().filter { l -> names.any { n -> Regex("\\b$n\\b").containsMatchIn(l) } }
        assertEquals("AppCore may only call ArchiveImport.recover: $uses", 1, uses.size)
        assertTrue(uses.single().contains("ArchiveImport.recover("))
    }

    @Test fun noLiveTableIsWrittenByTheImportExceptSavedPeople() {
        val text = File("src/main/java/org/phonemesh/app/ArchiveImport.kt").readText()
        val live = listOf("messages", "chats", "contacts", "sessions", "outbox", "held", "private_groups", "group_members", "group_outbox", "planned", "pictures", "received_mail")
        val writes = Regex("(INSERT INTO|UPDATE|DELETE FROM|insertOrThrow\\(|insert\\(|replace\\()\\s*\"?(\\w+)").findAll(text).map { it.groupValues[2] }.toSet()
        assertEquals("tables the import writes: $writes", emptySet<String>(), writes.intersect(live.toSet()))
    }
}

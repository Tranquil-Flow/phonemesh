package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.Avatar

/**
 * Private group storage (v0.1.2 G2): History 20 -> 21 from the real old shape, group state that survives a reopen, and a
 * change that fails late in its transaction leaving nothing behind (review C8: state is saved before anything is sent).
 */
@RunWith(AndroidJUnit4::class)
class GroupStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val core get() = TestCore.core

    @After fun tearDown() {
        context.deleteDatabase(OLD)
        TestCore.uninstall()
    }

    private val groupTables = listOf(
        "private_groups", "group_members", "group_profiles", "group_invitations", "group_private_out", "group_outbox",
        "group_delivered", "group_photo_pieces", "group_photos", "group_picture_pieces",
    )

    private fun columns(db: android.database.sqlite.SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }

    private fun tables(db: android.database.sqlite.SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    @Test fun aVersion20HistoryGainsGroupTablesAndKeepsItsMessages() {
        context.deleteDatabase(OLD)
        // A real version 21 file, then taken back to version 20's shape: no group tables, messages without shared_by.
        History(context, OLD).apply {
            val chat = personChat("0011223344556677", "Kofi")
            addIncoming(chat.id, "0011223344556677", "Salut", "fr", 5, countUnread = true)
            close()
        }
        context.openOrCreateDatabase(OLD, 0, null).use { db ->
            for (t in groupTables) db.execSQL("DROP TABLE IF EXISTS $t")
            val keep = columns(db, "messages").filter { it != "shared_by" }.joinToString(", ")
            db.execSQL("CREATE TABLE messages_old AS SELECT $keep FROM messages")
            db.execSQL("DROP TABLE messages")
            db.execSQL("ALTER TABLE messages_old RENAME TO messages")
            assertFalse(columns(db, "messages").contains("shared_by"))
            db.version = 20
        }
        repeat(2) {
            // Twice: the second open must find everything in place and change nothing.
            val upgraded = History(context, OLD)
            try {
                val db = upgraded.readableDatabase
                assertEquals(21, db.version)
                assertTrue(tables(db).containsAll(groupTables))
                assertTrue(columns(db, "messages").contains("shared_by"))
                val chat = upgraded.chats().single { it.name == "Kofi" }
                val salut = upgraded.lastMessage(chat.id)!!
                assertEquals("Salut", salut.text)
                assertNull(salut.sharedBy)
            } finally {
                upgraded.close()
            }
        }
    }

    @Test fun aGroupKeepsItsStateAcrossAReopen() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val (bea, _) = PersonFixture.meet("Bea")
        val chatId = checkNotNull(TestCore.onMain { core.createGroup("Market", 0x2E7D32, Avatar.Letter, null, listOf(bea.account)) })
        val hex = TestCore.onMain { core.history.chat(chatId)!!.address }
        val before = TestCore.onMain { core.groupStore.saved(AppGroupStore.hexBytes(hex))!! }
        // A fresh store over the same database and Vault opens the same state.
        val again = TestCore.onMain { AppGroupStore(core.history, core.accounts, Vault()).saved(AppGroupStore.hexBytes(hex))!! }
        assertArrayEquals(before.state, again.state)
        assertArrayEquals(before.signer, again.signer)
        assertEquals(listOf(bea.account), again.invited.map { it.first.toString() })
        assertEquals("Market", TestCore.onMain { core.groupView(chatId)!!.name })
        // The invitation went from the group store to the Postman (sealed in Bea's session, waiting for a radio).
        assertTrue(TestCore.onMain { core.groupStore.rows.privates().isEmpty() })
        assertEquals(1, TestCore.onMain {
            core.history.readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getInt(0) }
        })
    }

    @Test fun aChangeThatFailsLateLeavesNothingBehind() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val (bea, _) = PersonFixture.meet("Bea")
        val chatsBefore = TestCore.onMain { core.history.chats().size }
        // The last write of a group's first change fails: the group row, its chat and the invitation must all roll back.
        TestCore.onMain {
            core.history.writableDatabase.execSQL(
                "CREATE TRIGGER fail_private BEFORE INSERT ON group_private_out BEGIN SELECT RAISE(ABORT, 'late failure'); END")
        }
        try {
            assertNull(TestCore.onMain { core.createGroup("Market", 0x2E7D32, Avatar.Letter, null, listOf(bea.account)) })
            TestCore.onMain {
                val db = core.history.readableDatabase
                for (t in listOf("private_groups", "group_private_out", "group_outbox")) {
                    assertEquals("$t is empty", 0, db.rawQuery("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst(); it.getInt(0) })
                }
                assertEquals(chatsBefore, core.history.chats().size)
            }
        } finally {
            TestCore.onMain { core.history.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_private") }
        }
        // With the failure gone, the same change goes through.
        assertNotNull(TestCore.onMain { core.createGroup("Market", 0x2E7D32, Avatar.Letter, null, listOf(bea.account)) })
    }

    private companion object {
        const val OLD = "group-storage-old.db"
    }
}

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
import org.phonemesh.core.group.GroupControl
import org.phonemesh.core.group.GroupPost

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
                assertEquals(22, db.version)
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

    // ---- review G9 fixes (storage and transport)

    private fun count(sql: String): Int = TestCore.onMain {
        core.history.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
    }

    /** G9-3: a change that fails inside someone else's transaction (a hub letter and its cursor) undoes itself alone. */
    @Test fun aGroupChangeThatFailsInsideAnotherTransactionDoesNotRollItBack() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val (bea, _) = PersonFixture.meet("Bea")
        val chatId = checkNotNull(TestCore.onMain { core.createGroup("Market", 0x2E7D32, Avatar.Letter, null, listOf(bea.account)) })
        val group = AppGroupStore.hexBytes(TestCore.onMain { core.history.chat(chatId)!!.address })
        // A photo piece of 138 bytes breaks the table's CHECK (a piece is at most 137): the change fails late.
        // It writes a private body first, so a change that is not undone leaves a trace.
        val bad = GroupPost.Change(group, private = listOf(AppMailStore.idOf(bea.account) to org.phonemesh.core.wire.Body.Group(GroupControl.ASK, ByteArray(21))),
            events = listOf(GroupPost.Event.Photo(group, AppMailStore.idOf(bea.account), false, ByteArray(138))))
        var kept = true
        TestCore.onMain {
            core.history.transaction {
                core.history.addNote(chatId, "before", 1)
                kept = core.groupStore.commit(bad)
                core.history.addNote(chatId, "after", 2)
            }
        }
        assertFalse("the engine is told it was refused", kept)
        val notes = TestCore.onMain { core.history.messages(chatId).filter { it.kind == MessageKind.NOTE }.map { it.text } }
        assertTrue("the outer transaction kept what it did before: $notes", "before" in notes)
        assertTrue("and after: $notes", "after" in notes)
        assertEquals("nothing of the failed change", 0, count("SELECT COUNT(*) FROM group_photo_pieces"))
        assertEquals("not even what it wrote first", 0, count("SELECT COUNT(*) FROM group_private_out"))
        // Outside a transaction it is refused the same way and leaves nothing.
        assertFalse(TestCore.onMain { core.groupStore.commit(bad) })
    }

    private val ghost = "00112233445566ff"
    private val vault = Vault()

    private fun queue(kind: Int, at: Long) = TestCore.onMain {
        core.groupStore.rows.addPrivate(ghost, kind, vault.seal(byteArrayOf(1, 2, 3), "group-private:$ghost:$kind"), at)
    }

    /** G9-7: a key for someone this phone has no way to reach is kept (and the person told); other bodies are dropped. */
    @Test fun aKeyForSomeoneWithNoSessionIsKeptAndReported() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = true)
        val now = System.currentTimeMillis()
        queue(GroupControl.KEY, now); queue(GroupControl.ASK, now)
        TestCore.onMain { core.runPostman() }
        val left = TestCore.onMain { core.groupStore.rows.privates().map { it.kind } }
        assertEquals("the key waits, the request is dropped", listOf(GroupControl.KEY), left)
        assertEquals("Cannot reach Someone. Add them as a contact.", TestCore.onMain { core.text(core.lastNote!!) })
    }

    private fun outRow(hubOnly: Boolean, created: Long, state: String = "WAITING") = TestCore.onMain {
        val id = core.groupStore.rows.addOut("aa".repeat(16), ByteArray(40) { 1 }, hubOnly, null, 1, 0, 0, created)
        if (state != "WAITING") core.groupStore.rows.setOutState(id, state)
        id
    }

    private fun picturePiece(account: String, id: Int, idx: Int, at: Long) = TestCore.onMain {
        core.history.writableDatabase.execSQL("INSERT INTO group_picture_pieces (group_id, account_id, picture_id, idx, data, at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>("aa".repeat(16), account, id, idx, ByteArray(10) { 2 }, at))
    }

    private val hour = 3_600_000L

    /** G9-10: a queued key that waited past the 72-hour horizon is let go and the person is told whose it was. */
    @Test fun aKeyQueuedPastTheHorizonIsDroppedAndNamed() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val now = System.currentTimeMillis()
        queue(GroupControl.KEY, now - 73 * hour); queue(GroupControl.ASK, now - 73 * hour); queue(GroupControl.KEY, now - hour)
        val pruned = TestCore.onMain { core.groupStore.prune(now) }
        assertEquals("the person is told whose key never went (once)", listOf(AppMailStore.idOf(ghost)), pruned.keysNotSent)
        assertEquals("only the key inside the horizon is left", 1, count("SELECT COUNT(*) FROM group_private_out"))
    }

    /** G9-10: a hub-only packet nobody can read any more stops waiting; a radio packet and a recent one do not. */
    @Test fun aHubOnlyPacketPastTheHorizonStopsWaiting() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val now = System.currentTimeMillis()
        val oldHub = outRow(true, now - 73 * hour); val freshHub = outRow(true, now - hour); val oldRadio = outRow(false, now - 73 * hour)
        TestCore.onMain { core.groupStore.prune(now) }
        TestCore.onMain {
            val rows = core.groupStore.rows
            assertEquals(AppGroupStore.STATE_EXPIRED, rows.out(oldHub)!!.state)
            assertEquals("a hub-only packet inside the horizon still waits", "WAITING", rows.out(freshHub)!!.state)
            assertEquals("a radio packet is not a hub packet", "WAITING", rows.out(oldRadio)!!.state)
        }
    }

    /** G9-10: finished outbox rows of no shown message go after a week. */
    @Test fun finishedOutboxRowsGoAfterAWeek() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val now = System.currentTimeMillis()
        val doneOld = outRow(false, now - 8 * 24 * hour, "SENT"); val doneFresh = outRow(false, now - 24 * hour, "SENT")
        TestCore.onMain { core.groupStore.prune(now) }
        TestCore.onMain {
            assertNull("finished long ago", core.groupStore.rows.out(doneOld))
            assertNotNull(core.groupStore.rows.out(doneFresh))
        }
    }

    /** G9-10: photo pieces that never became whole go after the horizon. */
    @Test fun unfinishedPhotoPiecesGoAfterTheHorizon() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val now = System.currentTimeMillis()
        TestCore.onMain {
            core.groupStore.rows.keepPhotoPiece("aa".repeat(16), ghost, false, byteArrayOf(1), now - 73 * hour)
            core.groupStore.rows.keepPhotoPiece("aa".repeat(16), ghost, false, byteArrayOf(2), now - hour)
        }
        TestCore.onMain { core.groupStore.prune(now) }
        assertEquals(1, count("SELECT COUNT(*) FROM group_photo_pieces"))
    }

    /** G9-10: picture parts that waited for a head that never came go after the horizon; newer ones stay. */
    @Test fun unfinishedPicturePartsGoAfterTheHorizon() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val now = System.currentTimeMillis()
        picturePiece(ghost, 5, 1, now - 73 * hour); picturePiece(ghost, 6, 1, now - hour)
        TestCore.onMain { core.groupStore.prune(now) }
        assertEquals(listOf(6), TestCore.onMain {
            core.history.readableDatabase.rawQuery("SELECT picture_id FROM group_picture_pieces", null).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
        })
    }

    /** G9-10: the postman does it by itself. */
    @Test fun thePostmanPrunesOldGroupState() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val now = System.currentTimeMillis()
        outRow(true, now - 73 * hour)
        TestCore.onMain { core.groupsPrunedAt = 0L; core.runPostman() } // starting up may already have pruned once
        assertEquals(0, count("SELECT COUNT(*) FROM group_outbox WHERE state = 'WAITING'"))
        assertEquals("expired, not deleted, until pruned as finished", 1, count("SELECT COUNT(*) FROM group_outbox WHERE state = 'EXPIRED'"))
    }

    /** G9-10: at most once an hour. */
    @Test fun thePostmanPrunesAtMostOnceAnHour() {
        TestCore.install(language = "en-US", voices = listOf("en-US"), connected = false)
        val now = System.currentTimeMillis()
        TestCore.onMain { core.groupsPrunedAt = 0L; core.runPostman() }
        outRow(true, now - 74 * hour)
        TestCore.onMain { core.runPostman() }
        assertEquals(1, count("SELECT COUNT(*) FROM group_outbox WHERE state = 'WAITING'"))
    }

    private companion object {
        const val OLD = "group-storage-old.db"
    }
}

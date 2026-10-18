package org.phonemesh.app

import android.os.Build
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
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.OwnKeys
import org.phonemesh.core.session.Ratchet
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.session.Start
import org.phonemesh.core.wire.Body
import java.security.KeyStore

/** Plan step 9: account keys in the Keystore or sealed, prekey rotation, sealed sessions, the v6 history. */
@RunWith(AndroidJUnit4::class)
class AccountStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val opened = ArrayList<History>()
    private val vault = Vault("test-vault")
    private var now = 1_791_000_000_000L
    private val day = AccountStore.DAY

    private fun history(file: String) = History(context, file).also { opened += it }
    private fun store(file: String, software: Boolean) =
        AccountStore(history(file), vault, software, { now }, keyPrefix = "test-$file")

    @After fun clean() {
        opened.forEach { it.close() }
        for (f in listOf(A, B, OLD)) context.deleteDatabase(f)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("test-") }.forEach { keys.deleteEntry(it) }
    }

    /** A QR start between [mine] and a software peer; one message each way must open. */
    private fun talk(mine: OwnKeys) {
        val peer = SoftKeys.generate()
        val km = KeyPairBytes.generate(); val kp = KeyPairBytes.generate()
        val qm = QrCode(ContactCard.create(mine, "Awa", 1), km.public)
        val qp = QrCode(ContactCard.create(peer, "Ben", 2), kp.public)
        assertNotNull("our card's signature checks", ContactCard.decode(qm.card.encode()))
        val sm = checkNotNull(Start.qr(mine, qm, km, QrCode.decode(qp.encode())!!))
        val sp = checkNotNull(Start.qr(peer, qp, kp, QrCode.decode(qm.encode())!!))
        val out = Ratchet.seal(sm, Body.encode(Body.Text("fr", "bonjour")))
        val got = Ratchet.open(sp, out.packet, peer) as Ratchet.Opened.Delivered
        assertEquals("bonjour", (Body.decode(got.body) as Body.Text).text)
        val back = Ratchet.seal(got.state, Body.encode(Body.Text("fr", "salut")))
        val opened = Ratchet.open(out.state, back.packet, mine) as Ratchet.Opened.Delivered
        assertEquals("salut", (Body.decode(opened.body) as Body.Text).text)
    }

    @Test fun theKeystoreIdentityWorksWithTheCore() {
        val store = store(A, software = false)
        assertEquals(if (Build.VERSION.SDK_INT >= 31) AccountStore.STORE_KEYSTORE else AccountStore.STORE_SEALED, store.store)
        talk(store)
    }

    /** The Android 10 and 11 path, forced. */
    @Test fun theSealedIdentityWorksWithTheCore() {
        val store = store(A, software = true)
        assertEquals(AccountStore.STORE_SEALED, store.store)
        talk(store)
    }

    @Test fun keysSurviveARestart() {
        for (software in listOf(false, true)) {
            val first = store(A, software)
            val peer = SoftKeys.generate().identity
            val identity = first.identity
            val prekeys = first.prekeys
            val shared = first.agreeIdentity(peer)
            val sharedPrekey = first.agreePrekey(prekeys[0], peer)
            opened.forEach { it.close() }
            val again = store(A, software)
            assertArrayEquals(identity, again.identity)
            assertEquals(prekeys.map { it.toList() }, again.prekeys.map { it.toList() })
            assertArrayEquals(shared, again.agreeIdentity(peer))
            assertArrayEquals(sharedPrekey, again.agreePrekey(prekeys[0], peer))
            assertNull("an invalid peer key", again.agreeIdentity(ByteArray(33)))
            opened.forEach { it.close() }; opened.clear()
            context.deleteDatabase(A)
        }
    }

    @Test fun prekeysRotateAndTheOldOneWorksAMonthMore() {
        val owner = store(A, software = false)
        val opener = SoftKeys.generate()
        val oldCard = ContactCard.create(owner, "Owner", 0)
        now += 31 * day
        owner.rotateIfDue()
        assertEquals(2, owner.prekeys.size)
        assertFalse("a new current prekey", owner.prekeys[0].contentEquals(oldCard.prekey))
        val hello = Start.link(opener, "Opener", 2, oldCard, KeyPairBytes.generate())!!.hello
        assertEquals("Opener", Start.hello(owner, hello)!!.name)
        now += 31 * day
        owner.rotateIfDue()
        assertTrue(owner.prekeys.none { it.contentEquals(oldCard.prekey) })
        assertNull("its time is over", Start.hello(owner, hello))
        assertNull(owner.agreePrekey(oldCard.prekey, opener.identity))
    }

    /** Reviews F3 and F6: the packet is stored with its state; an older state is refused. */
    @Test fun sessionsAreSealedAndNeverGoBack() {
        val store = store(A, software = false)
        val peer = SoftKeys.generate()
        val km = KeyPairBytes.generate(); val kp = KeyPairBytes.generate()
        val qm = QrCode(ContactCard.create(store, "Awa", 1), km.public); val qp = QrCode(ContactCard.create(peer, "Ben", 2), kp.public)
        val contact = AccountId.of(peer.identity)
        val start = Start.qr(store, qm, km, qp)!!
        assertTrue(store.storeSession(contact, start))
        val sealed = Ratchet.seal(start, Body.encode(Body.Text("fr", "un")))
        val row = History.OutboxRow(0, contact.toString(), null, 301, sealed.packet, sealed.chainTag, sealed.counter, now, 0, now, "WAITING")
        assertTrue(store.storeSession(contact, sealed.state, row))
        assertFalse("an older state", store.storeSession(contact, start))
        assertFalse("the same state again", store.storeSession(contact, sealed.state))
        assertEquals(sealed.state.serial, store.session(contact)!!.serial)
        val outbox = opened[0].outbox(contact.toString())
        assertEquals(1, outbox.size)
        assertArrayEquals(sealed.packet, outbox[0].packet)

        // A sealed state copied onto another contact's row does not open.
        val other = AccountId.of(SoftKeys.generate().identity)
        opened[0].writableDatabase.execSQL(
            "INSERT INTO sessions (account_id, serial, state, updated) SELECT ?, serial, state, updated FROM sessions WHERE account_id = ?",
            arrayOf(other.toString(), contact.toString()),
        )
        assertNull(store.session(other))
    }

    @Test fun aHelloIsTakenOnce() {
        val h = history(A)
        assertTrue(h.firstHello(byteArrayOf(1, 2, 3), now))
        assertFalse(h.firstHello(byteArrayOf(1, 2, 3), now + 1))
    }

    @Test fun noSecretReachesTheLog() {
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        val store = store(A, software = true)
        talk(store)
        store.rotateIfDue()
        val scalar = vault.open(opened[0].accountRow()!!.sealed!!, "identity")!!
        val prekey = opened[0].prekeyRows().first().let { vault.open(it.sealed, "prekey:" + hex(it.public))!! }
        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.bufferedReader().readText().lowercase()
        for (secret in listOf(scalar, prekey)) {
            assertFalse(log.contains(hex(secret)))
            assertFalse(log.contains(android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP).lowercase()))
        }
    }

    /** A phone on history version 5 keeps its chats, messages and pictures, and gains the account tables. */
    @Test fun aVersionFiveHistoryKeepsItsChats() {
        context.deleteDatabase(OLD)
        context.openOrCreateDatabase(OLD, 0, null).use { db ->
            db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, address TEXT NOT NULL, " +
                "name TEXT NOT NULL DEFAULT '', last_activity INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0, " +
                "pinned INTEGER NOT NULL DEFAULT 0, UNIQUE (kind, address))")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL REFERENCES chats(id), " +
                "direction TEXT NOT NULL, sender_id TEXT, text TEXT NOT NULL, language TEXT, time INTEGER NOT NULL, packet_id INTEGER, " +
                "delivery TEXT, reply_to INTEGER, reaction INTEGER NOT NULL DEFAULT 0, planned_at INTEGER, kind TEXT NOT NULL DEFAULT 'TEXT')")
            db.execSQL("CREATE INDEX messages_by_chat ON messages (chat_id, id)")
            db.execSQL("CREATE INDEX messages_by_packet ON messages (packet_id)")
            db.execSQL("CREATE TABLE planned (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL REFERENCES chats(id), " +
                "text TEXT NOT NULL, language TEXT NOT NULL, due INTEGER NOT NULL, reply_to INTEGER)")
            db.execSQL("CREATE TABLE pictures (message_id INTEGER PRIMARY KEY REFERENCES messages(id), picture_id INTEGER NOT NULL, " +
                "codec INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, sketch BLOB NOT NULL, file TEXT, " +
                "have INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL, lost INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO chats (kind, address, name, pinned) VALUES ('DIRECT', '!0000aaaa', 'Kofi', 1)")
            db.execSQL("INSERT INTO messages (chat_id, direction, sender_id, text, time) VALUES (1, 'IN', '!0000aaaa', 'Salut', 5)")
            db.execSQL("INSERT INTO messages (chat_id, direction, text, time, kind) VALUES (1, 'OUT', '', 6, 'PICTURE')")
            db.execSQL("INSERT INTO pictures (message_id, picture_id, codec, width, height, sketch, total) VALUES (2, 7, 1, 10, 20, x'00', 3)")
            db.version = 5
        }
        val upgraded = history(OLD)
        val kofi = upgraded.chats().single()
        assertEquals("Kofi", kofi.name)
        assertTrue(kofi.pinned)
        assertEquals(listOf("Salut", ""), upgraded.messages(kofi.id).map { it.text })
        assertEquals(7, upgraded.picture(2)!!.pictureId)
        val store = AccountStore(upgraded, vault, true, { now }, keyPrefix = "test-old")
        val person = upgraded.personChat(store.accountId.toString(), "Awa")
        assertEquals(ChatKind.PERSON, person.kind)
        assertEquals(2, upgraded.chats().size)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private companion object {
        const val A = "account-test-a.db"
        const val B = "account-test-b.db"
        const val OLD = "history-version-five-test.db"
    }
}

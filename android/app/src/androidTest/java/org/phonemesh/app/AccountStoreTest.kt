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
    private val fixture = AccountTestResources(context)
    private val opened = fixture.opened
    private val vault get() = fixture.vault
    private var now = 1_791_000_000_000L
    private val day = AccountStore.DAY

    private fun history(file: String) = fixture.history(file)
    private fun store(file: String, software: Boolean) =
        AccountStore(history(file), vault, software, { now }, keyPrefix = fixture.keyPrefix(file))

    @After fun clean() = fixture.clean()

    /** A QR start between [mine] and a software peer; one message each way must open. */
    private fun talk(mine: OwnKeys) {
        val peer = SoftKeys.generate()
        val km = KeyPairBytes.generate(); val kp = KeyPairBytes.generate()
        val qm = QrCode(ContactCard.create(mine, org.phonemesh.core.account.Profile("Awa", 0xFB8C00)), km.public)
        val qp = QrCode(ContactCard.create(peer, org.phonemesh.core.account.Profile("Ben", 0xFDD835)), kp.public)
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
        if (Build.VERSION.SDK_INT >= 31) {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keys.getKey("${fixture.keyPrefix(A)}-identity", null) as java.security.PrivateKey
            val info = java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, android.security.keystore.KeyInfo::class.java)
            assertEquals(256, info.keySize)
            assertEquals(android.security.keystore.KeyProperties.ORIGIN_GENERATED, info.origin)
            val level = info.securityLevel
            // Emulator software Keystore is valid API-path evidence, not hardware evidence.
            // The authorized physical Pixel must report TEE or StrongBox; never assume/skip it.
            if (Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.startsWith("Pixel")) {
                assertTrue("Pixel identity must be hardware-backed (securityLevel=$level)",
                    level == android.security.keystore.KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    level == android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX)
            }
            android.util.Log.i("AccountKeyMetadata", "identity securityLevel=$level")
        }
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
            fixture.deleteDatabase(A)
        }
    }

    /**
     * Profiles (decisions 36, 48): a version 15 history (no profile column, colours as indices into eight, a card of
     * version 1) opens with RGB colours, the account's name as profile revision 1, and contacts that still work.
     */
    @Test fun aVersion15HistoryUpgradesToProfiles() {
        val first = store(A, software = true)
        first.setName("Awa", 0x43A047)
        val contactIdentity = SoftKeys.generate().identity
        val oldCard = byteArrayOf(1, 1) + contactIdentity + SoftKeys.generate().prekey + ByteArray(64) +
            byteArrayOf(3) + "Ben".toByteArray() + byteArrayOf(5)
        val db = opened.last().writableDatabase
        db.insertOrThrow("contacts", null, android.content.ContentValues().apply {
            put("account_id", AccountId.of(contactIdentity).toString()); put("card", oldCard); put("name", "Ben")
            put("colour", 0); put("added", now)
        })
        for (table in listOf("account", "contacts")) {
            val columns = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.filter { it != "profile" }.toList()
            }.joinToString(",")
            db.execSQL("CREATE TABLE old_$table AS SELECT $columns FROM $table")
            db.execSQL("DROP TABLE $table")
            db.execSQL("ALTER TABLE old_$table RENAME TO $table")
        }
        db.execSQL("UPDATE account SET colour = 3")
        db.execSQL("UPDATE contacts SET colour = 5")
        db.version = 15
        opened.forEach { it.close() }; opened.clear()

        val again = store(A, software = true)
        assertEquals(17, opened.last().readableDatabase.version)
        assertEquals("green, index 3, as RGB", 0x43A047, opened.last().accountRow()!!.colour)
        assertEquals(org.phonemesh.core.account.Profile("Awa", 0x43A047), again.profile())
        assertEquals("Awa", checkNotNull(ContactCard.decode(again.card().encode())).name)
        val ben = opened.last().contactRows().single()
        assertEquals("purple, index 5, as RGB", 0x8E24AA, ben.colour)
        assertNull("no profile until Ben sends one", ben.profile)
        assertArrayEquals("a version 1 card still gives the identity", contactIdentity, Accounts.identityOf(ben.card))
        val changed = again.setName("Awa", 0x1E88E5)
        assertEquals("a change after the upgrade is revision 2", 2L, changed.revision)
        opened.forEach { it.close() }; opened.clear()
        fixture.deleteDatabase(A)
    }

    @Test fun prekeysRotateAndTheOldOneWorksAMonthMore() {
        val owner = store(A, software = false)
        val opener = SoftKeys.generate()
        val oldCard = ContactCard.create(owner, org.phonemesh.core.account.Profile("Owner", 0xE53935))
        now += 31 * day
        owner.rotateIfDue()
        assertEquals(2, owner.prekeys.size)
        assertFalse("a new current prekey", owner.prekeys[0].contentEquals(oldCard.prekey))
        val hello = Start.link(opener, org.phonemesh.core.account.Profile("Opener", 0xFDD835), oldCard, KeyPairBytes.generate())!!.hello
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
        val qm = QrCode(ContactCard.create(store, org.phonemesh.core.account.Profile("Awa", 0xFB8C00)), km.public); val qp = QrCode(ContactCard.create(peer, org.phonemesh.core.account.Profile("Ben", 0xFDD835)), kp.public)
        val contact = AccountId.of(peer.identity)
        val start = Start.qr(store, qm, km, qp)!!
        assertTrue(store.storeSession(contact, start))
        val sealed = Ratchet.seal(start, Body.encode(Body.Text("fr", "un")))
        val row = History.OutboxRow(0, contact.toString(), null, 301, sealed.packet, sealed.chainTag, sealed.counter, now, 0, now, "WAITING")
        assertTrue(store.storeSession(contact, sealed.state, row, expected = start))
        assertFalse("an older state", store.storeSession(contact, start, expected = sealed.state))
        assertFalse("the same state again", store.storeSession(contact, sealed.state, expected = sealed.state))
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
        val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val marker = "account-log-sentinel-${java.util.UUID.randomUUID()}"
        android.util.Log.i("AccountLogSentinel", marker)
        // The marker exists before the action and must survive it; never clear logs to test this.
        assertTrue("log marker must be readable before the action", AccountTestResources.readProcessLog(since).contains(marker))
        val store = store(A, software = true)
        talk(store)
        store.rotateIfDue()
        val scalar = vault.open(opened[0].accountRow()!!.sealed!!, "identity")!!
        val prekey = opened[0].prekeyRows().first().let { vault.open(it.sealed, "prekey:" + hex(it.public))!! }
        val log = AccountTestResources.readProcessLog(since).lowercase(java.util.Locale.ROOT)
        assertTrue("preexisting harmless marker must survive", log.contains(marker))
        for (secret in listOf(scalar, prekey)) {
            assertFalse(log.contains(hex(secret)))
            assertFalse(log.contains(android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP).lowercase()))
        }
    }

    /** A phone on history version 5 keeps its chats, messages and pictures, and gains the account tables. */
    @Test fun aVersionFiveHistoryKeepsItsChats() {
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
        val store = AccountStore(upgraded, vault, true, { now }, keyPrefix = fixture.keyPrefix(OLD))
        val person = upgraded.personChat(store.accountId.toString(), "Awa")
        assertEquals(ChatKind.PERSON, person.kind)
        assertEquals(2, upgraded.chats().size)
    }

    @Test fun cleanupPreservesUnrelatedFixtureResources() {
        val token = java.util.UUID.randomUUID().toString()
        val sentinelAlias = "test-sentinel-$token"
        val sentinelFile = "test-sentinel-$token.db"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keys.containsAlias(sentinelAlias))
        val path = context.getDatabasePath(sentinelFile)
        path.parentFile!!.mkdirs()
        check(path.createNewFile()) { "sentinel file already exists" }
        try {
            path.writeText("unrelated fixture")
            val sentinel = Vault(sentinelAlias)
            val sealed = sentinel.seal(byteArrayOf(1, 2, 3), "sentinel")
            store(A, software = true)
            clean() // Exercise the class's actual teardown, not a second cleanup implementation.
            assertEquals("unrelated fixture", path.readText())
            assertTrue("unrelated key must survive", keys.containsAlias(sentinelAlias))
            assertArrayEquals(byteArrayOf(1, 2, 3), sentinel.open(sealed, "sentinel"))
        } finally {
            keys.deleteEntry(sentinelAlias)
            context.deleteDatabase(sentinelFile)
        }
    }

    private fun fixtureNames() = listOf(A, OLD)

    @Test fun fixtureInstancesNeverShareDatabaseNames() {
        val other = AccountStoreTest()
        try {
            assertTrue("each instance must have its own databases", fixtureNames().zip(other.fixtureNames()).all { (a, b) -> a != b })
        } finally { other.clean() }
    }

    @Test fun cleanupStillRemovesOwnedResourcesAfterCloseFailure() {
        val owned = AccountTestResources(context)
        val unrelated = AccountTestResources(context)
        val file = owned.database("close-failure")
        val sentinelFile = unrelated.database("sentinel")
        try {
            owned.history(file).writableDatabase
            unrelated.history(sentinelFile).writableDatabase
            val sealed = unrelated.vault.seal(byteArrayOf(9), "sentinel")
            owned.vault.seal(byteArrayOf(1), "owned")
            val alias = owned.vaultAlias
            val failure = runCatching {
                owned.clean { it.close(); error("injected close failure") }
            }.exceptionOrNull()
            assertNotNull("close failure must not be swallowed", failure)
            assertFalse(context.getDatabasePath(file).exists())
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keys.containsAlias(alias))
            assertTrue(context.getDatabasePath(sentinelFile).exists())
            assertArrayEquals(byteArrayOf(9), unrelated.vault.open(sealed, "sentinel"))
        } finally {
            try { owned.clean() } finally { unrelated.clean() }
        }
    }

    @Test fun aCollidingFixtureCannotAdoptOrDeleteExistingResources() {
        val namespace = "test-collision-${java.util.UUID.randomUUID()}"
        val owner = AccountTestResources(context, namespace)
        val intruder = AccountTestResources(context, namespace)
        val file = owner.database("sentinel")
        try {
            owner.history(file).writableDatabase
            val sealed = owner.vault.seal(byteArrayOf(7), "sentinel")
            assertNotNull(runCatching { intruder.database("sentinel") }.exceptionOrNull())
            assertNotNull(runCatching { intruder.vault }.exceptionOrNull())
            intruder.clean()
            assertTrue(context.getDatabasePath(file).exists())
            assertArrayEquals(byteArrayOf(7), owner.vault.open(sealed, "sentinel"))
        } finally { owner.clean() }
    }

    @Test fun aPreexistingIdentityAliasIsRefusedBeforeAccountStoreConstruction() {
        val namespace = "test-identity-collision-${java.util.UUID.randomUUID()}"
        val owner = AccountTestResources(context, namespace)
        val intruder = AccountTestResources(context, namespace)
        val file = owner.database("sentinel")
        val alias = "${owner.keyPrefix(file)}-identity"
        try {
            // Reserve the exact identity alias with a disposable key, not an owner key.
            val sentinel = Vault(alias)
            val sealed = sentinel.seal(byteArrayOf(5), "sentinel")
            val collision = intruder.database("sentinel") // No database exists yet.
            val failure = runCatching {
                AccountStore(intruder.history(collision), intruder.vault, false,
                    keyPrefix = intruder.keyPrefix(collision))
            }.exceptionOrNull()
            assertNotNull("must refuse before AccountStore can replace the key", failure)
            intruder.clean()
            assertArrayEquals(byteArrayOf(5), sentinel.open(sealed, "sentinel"))
        } finally {
            try { intruder.clean() } finally { owner.clean() }
        }
    }

    @Test fun logReadIsReadOnlyPidScopedAndFailsClosed() {
        val since = "01-01 00:00:00.000"
        assertEquals(listOf("logcat", "-d", "-v", "brief", "--pid=${android.os.Process.myPid()}", "-T", since),
            AccountTestResources.logCommand(since))
        fun fake(code: Int, text: String) = object : java.lang.Process() {
            override fun getInputStream() = java.io.ByteArrayInputStream(text.toByteArray())
            override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun waitFor() = code
            override fun exitValue() = code
            override fun destroy() = Unit
        }
        assertEquals("marker", AccountTestResources.readProcessLog(since) { fake(0, "marker") })
        for ((code, text) in listOf(1 to "never-export-this-output", 0 to "x".repeat(1024 * 1024 + 1))) {
            val error = runCatching { AccountTestResources.readProcessLog(since) { fake(code, text) } }.exceptionOrNull()
            assertTrue("failed/oversized reads must fail", error is AssertionError)
            assertFalse(error!!.message.orEmpty().contains("never-export-this-output"))
            assertNull(error.cause)
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val A = fixture.database("a")
    private val B = fixture.database("b")
    private val OLD = fixture.database("old")
}

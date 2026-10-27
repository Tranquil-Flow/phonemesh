package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.backup.BackupFile
import org.phonemesh.core.backup.RecoveryKey

/**
 * Contacts-only saved copies (account recovery RC1-RC5 A): the copy holds names and public cards, opens only with
 * its key, and a restore on a new phone keeps the people apart, to add again, with nothing sent.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryTest {
    private val core get() = TestCore.core

    @After fun tearDown() = TestCore.uninstall()

    @Test fun aCopyHoldsThePeopleAndOpensOnlyWithItsKey() {
        TestCore.install()
        val (bea, _) = PersonFixture.meet()
        val key = RecoveryKey.generate()
        val file = Recovery.export(core.history, key, 1_800_000_000_000, 1)!!
        val opened = Recovery.open(file, key) as Recovery.Opened.Ok
        val person = opened.copy.contacts.single()
        assertEquals("Bea", person.savedName)
        assertArrayEquals(bea.qr.card.identity, person.identity)
        assertArrayEquals("their signed card, as saved", core.history.contactRows().single().card, person.card)
        assertArrayEquals(core.history.accountRow()!!.identity, opened.copy.manifest.sourceIdentity)

        val wrong = Recovery.open(file, RecoveryKey.generate()) as Recovery.Opened.Failed
        assertEquals(Recovery.Problem.KEY_OR_FILE_DOES_NOT_MATCH, wrong.problem)
        val changed = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(Recovery.Problem.KEY_OR_FILE_DOES_NOT_MATCH, (Recovery.open(changed, key) as Recovery.Opened.Failed).problem)
        assertEquals(Recovery.Problem.NOT_A_COPY, (Recovery.open("hello".toByteArray(), key) as Recovery.Opened.Failed).problem)
        val history = BackupFile.seal(key, BackupFile.Purpose.HISTORY, byteArrayOf(1))
        assertEquals(Recovery.Problem.NOT_CONTACTS, (Recovery.open(history, key) as Recovery.Opened.Failed).problem)
        assertEquals("restoring on the same phone adds nobody", 0, Recovery.restore(core.history, opened, 2))
    }

    /** Review A-02: a contact added from a hello is kept as their bare identity; the kit must hold them too. */
    @Test fun contactsAddedFromAHelloAreInTheKitAndNotRestoredTwice() {
        TestCore.install()
        PersonFixture.meet()
        val yaw = PersonFixture.Bea("Yaw", 0x00838F)
        val identity = yaw.qr.card.identity
        assertEquals(33, identity.size)
        core.history.putContact(History.ContactRow(yaw.account, identity, "Yaw", 0x00838F, false, null, 7, null))
        val key = RecoveryKey.generate()
        val opened = Recovery.open(Recovery.export(core.history, key, 1_800_000_000_000, 1)!!, key) as Recovery.Opened.Ok
        assertEquals("both people are in the kit", listOf("Bea", "Yaw"), opened.copy.contacts.map { it.savedName }.sorted())
        val saved = opened.copy.contacts.single { it.savedName == "Yaw" }
        assertArrayEquals(identity, saved.identity)
        assertArrayEquals("a bare identity stays bare", identity, saved.card)
        assertEquals("restoring on the same phone adds nobody, bare identities included", 0, Recovery.restore(core.history, opened, 2))
    }

    @Test fun aRestoreOnANewPhoneKeepsThePeopleApartAndSendsNothing() {
        TestCore.install()
        val (bea, _) = PersonFixture.meet()
        val key = RecoveryKey.generate()
        val file = Recovery.export(core.history, key, 1_800_000_000_000, 1)!!
        val oldIdentity = core.history.accountRow()!!.identity
        TestCore.uninstall()

        TestCore.install()
        assertFalse("a new phone has a new identity", core.history.accountRow()!!.identity.contentEquals(oldIdentity))
        val before = TestCore.radio.broadcasts.size
        val opened = Recovery.open(file, key) as Recovery.Opened.Ok
        assertEquals(1, Recovery.restore(core.history, opened, 5))
        assertEquals("a second restore adds nobody twice", 0, Recovery.restore(core.history, opened, 6))
        val saved = core.history.recoveredRows().single()
        assertEquals("Bea", saved.name)
        assertTrue("met on the old phone, as history", saved.metBefore)
        assertArrayEquals(bea.qr.card.identity, saved.identity)
        assertTrue("not a contact: they must be added again", core.history.contactRows().isEmpty())
        assertEquals("nothing went out", before, TestCore.radio.broadcasts.size)
        core.history.removeRecovered(saved.identity)
        assertTrue(core.history.recoveredRows().isEmpty())
    }
}

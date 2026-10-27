package org.phonemesh.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import kotlin.random.Random

/**
 * Recovery key and contacts-only saved copy (account recovery RC1-RC6 A, backup BK5 A). Expected bytes come from an
 * independent Python implementation (`.local/crypto-vectors/backup_vectors.py`, `cryptography` 44.0.2), which also
 * reproduces BIP39's published English vectors.
 */
class BackupTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/backup/backup-vectors.txt")!!.bufferedReader(Charsets.UTF_8)
        .readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    private val en = RecoveryKey.WordList.load("english")
    private val fr = RecoveryKey.WordList.load("french")
    private val root = RecoveryKey.of(hex(v.getValue("root")))

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Hands out fixed bytes in order: archive id, salt, data key, wrapping nonce. */
    private class Fixed(vararg chunks: ByteArray) : SecureRandom() {
        private val queue = ArrayDeque(chunks.toList())
        override fun nextBytes(bytes: ByteArray) { queue.removeFirst().also { require(it.size == bytes.size) }.copyInto(bytes) }
    }

    private fun fixed() = Fixed(ByteArray(16) { 0xA1.toByte() }, ByteArray(16) { 0x5A }, ByteArray(32) { it.toByte() }, ByteArray(12) { 0x77 })

    // ---- recovery key

    @Test fun bip39PublishedVectorsAndTheIndependentWords() {
        for (e in listOf("00", "7f", "80", "ff")) {
            assertEquals(v.getValue("bip39_$e"), RecoveryKey.of(hex(e.repeat(16))).words(en).joinToString(" "))
        }
        assertEquals(v.getValue("words_en"), root.words(en).joinToString(" "))
        assertEquals(nfc(v.getValue("words_fr")), nfc(root.words(fr).joinToString(" ")))
    }

    @Test fun wordsComeBackOnlyWhenExactAndChecked() {
        assertEquals(root, RecoveryKey.fromWords(v.getValue("words_en"), en))
        assertEquals("spaces and capitals do not matter", root, RecoveryKey.fromWords("  " + v.getValue("words_en").uppercase().replace(" ", "   ") + " ", en))
        assertEquals("French, typed composed or decomposed", root, RecoveryKey.fromWords(nfc(v.getValue("words_fr")), fr))
        assertEquals(root, RecoveryKey.fromWords(Normalizer.normalize(v.getValue("words_fr"), Normalizer.Form.NFD), fr))
        val words = v.getValue("words_en").split(" ")
        assertNull("eleven words", RecoveryKey.fromWords(words.dropLast(1).joinToString(" "), en))
        assertNull("thirteen words", RecoveryKey.fromWords((words + "zoo").joinToString(" "), en))
        assertNull("not a list word, no guessing", RecoveryKey.fromWords((words.dropLast(1) + "dividee").joinToString(" "), en))
        assertNull("the French words are not English ones", RecoveryKey.fromWords(v.getValue("words_fr"), en))
        // Changing the last word changes the check: most replacements fail it.
        val failing = en.words.count { w -> RecoveryKey.fromWords((words.dropLast(1) + w).joinToString(" "), en) == null }
        assertTrue("the 4-bit check refuses most typos ($failing)", failing >= 2048 - 2048 / 16 - 1)
    }

    @Test fun cardAndScannedTextAreExactAndStrict() {
        assertArrayEquals(hex(v.getValue("card")), root.card())
        assertEquals(v.getValue("text"), root.text())
        assertEquals(root, RecoveryKey.fromText(v.getValue("text")))
        assertEquals(root, RecoveryKey.fromCard(root.card()))
        val text = v.getValue("text")
        assertNull(RecoveryKey.fromText(text.replace("pmrk1:", "pmrk2:")))
        assertNull("not a contact link either way", RecoveryKey.fromText("phonemesh://add/" + text.substringAfter(':')))
        assertNull("trailing", RecoveryKey.fromText("$text="))
        assertNull("short", RecoveryKey.fromText(text.dropLast(1)))
        // Every other last character is refused, including spellings that differ only in unused bits.
        for (c in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_") {
            if (c != text.last()) assertNull("last $c", RecoveryKey.fromText(text.dropLast(1) + c))
        }
        for (i in root.card().indices) {
            val bad = root.card().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i changed", RecoveryKey.fromCard(bad))
        }
        assertNull(RecoveryKey.fromCard(root.card() + 0))
    }

    @Test fun randomKeysRoundTripEveryWay() {
        repeat(500) {
            val k = RecoveryKey.generate()
            assertEquals(k, RecoveryKey.fromWords(k.words(en).joinToString(" "), en))
            assertEquals(k, RecoveryKey.fromWords(k.words(fr).joinToString(" "), fr))
            assertEquals(k, RecoveryKey.fromText(k.text()))
        }
        assertFalse(RecoveryKey.generate() == RecoveryKey.generate())
        assertEquals("RecoveryKey(hidden)", root.toString())
    }

    @Test fun scannedTextSurvivesRandomInput() {
        val r = Random(41)
        repeat(20_000) {
            val s = "pmrk1:" + String(CharArray(r.nextInt(0, 45)) { (r.nextInt(32, 127)).toChar() })
            RecoveryKey.fromText(s)
            RecoveryKey.fromWords(s, en)
        }
    }

    // ---- sealed file

    @Test fun sealingMatchesTheIndependentFileByteForByte() {
        val plain = hex(v.getValue("small_plain"))
        assertArrayEquals(hex(v.getValue("small_file")), BackupFile.seal(root, BackupFile.Purpose.CONTACTS, plain, fixed()))
        val big = ByteArray(65_536 + 1000) { ((it * 7 + 3) and 0xFF).toByte() }
        val file = BackupFile.seal(root, BackupFile.Purpose.HISTORY, big, fixed())
        assertEquals(v.getValue("big_sha256"), MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) })
        val opened = BackupFile.open(root, file).let { it as? BackupFile.Opened.Ok ?: error("not opened: ${(it as BackupFile.Opened.Failed).problem}") }
        assertArrayEquals(big, opened.plain)
        assertEquals(BackupFile.Purpose.HISTORY, opened.purpose)
        assertEquals(120 + big.size + 20 * 2, file.size)
    }

    @Test fun everySealIsNewAndOnlyTheRightKeyOpensIt() {
        val plain = "hello".toByteArray()
        val a = BackupFile.seal(root, BackupFile.Purpose.CONTACTS, plain)
        val b = BackupFile.seal(root, BackupFile.Purpose.CONTACTS, plain)
        assertFalse("fresh id, salt, key and nonce each time", a.contentEquals(b))
        assertArrayEquals(plain, (BackupFile.open(root, a) as BackupFile.Opened.Ok).plain)
        val wrong = BackupFile.open(RecoveryKey.generate(), a) as BackupFile.Opened.Failed
        assertEquals(BackupFile.Problem.KEY_OR_FILE_DOES_NOT_MATCH, wrong.problem)
    }

    @Test fun noChangedTruncatedOrExtendedFileOpens() {
        val file = hex(v.getValue("small_file"))
        assertTrue(BackupFile.open(root, file) is BackupFile.Opened.Ok)
        for (i in file.indices) for (bit in listOf(1, 0x80)) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor bit).toByte() }
            assertTrue("byte $i bit $bit", BackupFile.open(root, bad) is BackupFile.Opened.Failed)
        }
        for (n in 0 until file.size) assertTrue("cut at $n", BackupFile.open(root, file.copyOf(n)) is BackupFile.Opened.Failed)
        assertTrue(BackupFile.open(root, file + 0) is BackupFile.Opened.Failed)
        assertEquals(BackupFile.Problem.NOT_A_BACKUP, (BackupFile.open(root, ByteArray(0)) as BackupFile.Opened.Failed).problem)
        // Two chunks swapped: their sizes, nonces and associated data no longer match.
        val big = BackupFile.seal(root, BackupFile.Purpose.HISTORY, ByteArray(65_536 * 2) { it.toByte() })
        val first = big.copyOfRange(120, 120 + 4 + 65_536 + 16)
        val second = big.copyOfRange(120 + first.size, big.size)
        assertTrue(BackupFile.open(root, big.copyOfRange(0, 120) + second + first) is BackupFile.Opened.Failed)
    }

    @Test fun anUnknownSuiteOrFlagIsUnsupportedNotGuessed() {
        val file = hex(v.getValue("small_file"))
        for ((at, value) in listOf(8 to 2, 9 to 7, 11 to 1)) {
            val bad = file.copyOf().also { it[at] = value.toByte() }
            assertEquals(BackupFile.Problem.UNSUPPORTED, (BackupFile.open(root, bad) as BackupFile.Opened.Failed).problem)
        }
    }

    // ---- contacts records

    private val g = hex("036B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296")
    private val g2 = hex("037CF27B188D034F7E8A52380304B51AC3C08969E277F21B35A60B48FC47669978")
    private fun copy() = ContactsBackup.Copy(
        ContactsBackup.Manifest(g, 1_800_000_000_000, 3, "Pélagie", 0x43A047),
        listOf(
            ContactsBackup.Contact(1, g, g, "Kofi", 0x1E88E5, true, null, 1_799_000_000_000),
            ContactsBackup.Contact(5, g2, g2, "Ama Owusu", 0x00838F, false, "fr-voice", 0),
        ),
    )

    @Test fun contactRecordsMatchTheIndependentBytesAndReadBack() {
        val plain = ContactsBackup.encode(copy())
        assertArrayEquals(hex(v.getValue("contacts_plain")), plain)
        val back = ContactsBackup.decode(plain)!!
        assertEquals("Pélagie", back.manifest.ownName)
        assertEquals(0x43A047, back.manifest.ownColour)
        assertEquals(listOf("Kofi", "Ama Owusu"), back.contacts.map { it.savedName })
        assertEquals(listOf(true, false), back.contacts.map { it.historicalMet })
        assertEquals(listOf(null, "fr-voice"), back.contacts.map { it.voice })
        assertArrayEquals(g2, back.contacts[1].identity)
    }

    @Test fun anyChangeCutOrAddedRecordIsRefused() {
        val plain = ContactsBackup.encode(copy())
        for (i in plain.indices) {
            val bad = plain.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", ContactsBackup.decode(bad))
        }
        for (n in 0 until plain.size) assertNull("cut at $n", ContactsBackup.decode(plain.copyOf(n)))
        assertNull(ContactsBackup.decode(plain + 0))
        // No record type can carry keys or sessions: an unknown record between contacts and END is refused.
        val endAt = plain.size - (6 + 48)
        val extra = byteArrayOf(3, 0, 0, 0, 0, 1, 0)
        assertNull(ContactsBackup.decode(plain.copyOfRange(0, endAt) + extra + plain.copyOfRange(endAt, plain.size)))
    }

    @Test fun cardsMustBeTheirsAndIdentitiesUnique() {
        val c = copy()
        fun with(vararg contacts: ContactsBackup.Contact) = ContactsBackup.Copy(c.manifest, contacts.toList())
        val bareOther = ContactsBackup.Contact(1, g, g2, "Kofi", 0, false, null, 0)
        assertTrue(runCatching { ContactsBackup.encode(with(bareOther)) }.isFailure)
        val twice = with(c.contacts[0], ContactsBackup.Contact(2, g, g, "Kofi again", 0, false, null, 0))
        assertTrue(runCatching { ContactsBackup.encode(twice) }.isFailure)
        val notAPoint = g.copyOf().also { it[0] = 4 }
        assertTrue(runCatching { ContactsBackup.encode(with(ContactsBackup.Contact(1, notAPoint, notAPoint, "X", 0, false, null, 0))) }.isFailure)
        assertTrue("ids ascend", runCatching { ContactsBackup.encode(with(c.contacts[1], c.contacts[0])) }.isFailure)
        assertTrue("no control characters in names", runCatching {
            ContactsBackup.encode(with(ContactsBackup.Contact(1, g, g, "Ko\u0000fi", 0, false, null, 0)))
        }.isFailure)
        assertNotNull(ContactsBackup.decode(ContactsBackup.encode(with())))
    }

    @Test fun aWholeContactsCopySealsAndOpens() {
        val sealed = BackupFile.seal(root, BackupFile.Purpose.CONTACTS, ContactsBackup.encode(copy()))
        val opened = BackupFile.open(RecoveryKey.fromWords(v.getValue("words_en"), en)!!, sealed) as BackupFile.Opened.Ok
        assertEquals(BackupFile.Purpose.CONTACTS, opened.purpose)
        assertEquals(2, ContactsBackup.decode(opened.plain)!!.contacts.size)
    }
}

package org.phonemesh.core.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.session.SoftKeys

class AccountTest {
    private val keys = SoftKeys.generate()
    private val card = ContactCard.create(keys, "Aïcha", 7)

    @Test fun aCardRoundTripsAndIsSigned() {
        val bytes = card.encode()
        assertEquals(134 + "Aïcha".toByteArray().size, bytes.size)
        val back = checkNotNull(ContactCard.decode(bytes))
        assertArrayEquals(keys.identity, back.identity)
        assertArrayEquals(keys.prekey, back.prekey)
        assertEquals("Aïcha", back.name)
        assertEquals(7, back.colour)
        assertEquals(AccountId.of(keys.identity), back.accountId)
    }

    @Test fun aLongestNameFitsAndALongerOneIsRefused() {
        assertNotNull(ContactCard.decode(ContactCard.create(keys, "x".repeat(40), 0).encode()))
        assertEquals(174, ContactCard.MAX_BYTES)
        assertTrue(runCatching { ContactCard.create(keys, "x".repeat(41), 0) }.isFailure)
        assertTrue(runCatching { ContactCard.create(keys, "", 0) }.isFailure)
    }

    @Test fun badCardsAreRefused() {
        val bytes = card.encode()
        assertNull("version", ContactCard.decode(bytes.copyOf().also { it[0] = 2 }))
        assertNull("key type", ContactCard.decode(bytes.copyOf().also { it[1] = 2 }))
        assertNull("signature", ContactCard.decode(bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }))
        assertNull("prekey swapped", ContactCard.decode(bytes.copyOf().also { SoftKeys.generate().prekey.copyInto(it, 35) }))
        assertNull("identity off the curve", ContactCard.decode(bytes.copyOf().also { it[2] = 4 }))
        assertNull("trailing byte", ContactCard.decode(bytes + 0))
        assertNull("cut short", ContactCard.decode(bytes.copyOf(bytes.size - 1)))
        assertNull("name length lies", ContactCard.decode(bytes.copyOf().also { it[132] = 41 }))
        val badUtf8 = card.encode().also { it[133] = 0xC3.toByte(); it[134] = 0x28 }
        assertNull("bad UTF-8", ContactCard.decode(badUtf8))
    }

    /** Review F21: names cannot hide characters. */
    @Test fun namesRefuseHiddenCharacters() {
        for (bad in listOf("Ma\u202Ema", "Ma\u200Bma", "Ma\nma", "Ame\u0301lie", " ", "\u0000")) {
            assertTrue("refused: ${bad.map { it.code }}", !Names.valid(bad))
            assertTrue(runCatching { ContactCard.create(keys, bad, 0) }.isFailure)
        }
        assertTrue(Names.valid("Amélie") && Names.valid("Moussa 2") && Names.valid("আমিনা"))
        assertEquals("Amélie", Names.clean(" Ame\u0301lie\u200B "))
    }

    /** Review F23: the identity key signs a labelled message, not the bare prekey. */
    @Test fun aSignatureOverTheBarePrekeyIsRefused() {
        val bare = keys.signIdentity(keys.prekey)
        val forged = card.encode().also { bare.copyInto(it, 68) }
        assertNull(ContactCard.decode(forged))
    }

    @Test fun aLinkCarriesTheCardAfterTheHash() {
        val link = InviteLink.of(card)
        assertTrue(link.startsWith("https://tranquil-flow.github.io/phonemesh/add#"))
        assertTrue("no padding", !link.contains("="))
        assertTrue("fits a chat message: ${link.length}", link.length < 300)
        assertArrayEquals(card.encode(), checkNotNull(InviteLink.parse(link)).encode())
        assertArrayEquals(card.encode(), checkNotNull(InviteLink.parse("  $link\n")).encode())
    }

    @Test fun badLinksAreRefused() {
        val link = InviteLink.of(card)
        assertNull("no #", InviteLink.parse(link.replace("#", "")))
        assertNull("other site", InviteLink.parse(link.replace("tranquil-flow.github.io", "example.org")))
        assertNull("http", InviteLink.parse(link.replace("https", "http")))
        assertNull("padding", InviteLink.parse("$link=="))
        assertNull("not base64url", InviteLink.parse(link.dropLast(1) + "+"))
        val middle = InviteLink.PREFIX.length + 120 // inside the signature
        val damaged = link.substring(0, middle) + (if (link[middle] == 'A') 'B' else 'A') + link.substring(middle + 1)
        assertNull("damaged signature", InviteLink.parse(damaged))
        // The name and colour are not signed: a changed colour still reads, with the same keys (the safety picture
        // is the check).
        val recoloured = card.encode().also { it[it.size - 1] = 99 }
        val read = checkNotNull(ContactCard.decode(recoloured))
        assertEquals(99, read.colour)
        assertArrayEquals(card.identity, read.identity)
        assertNull("empty", InviteLink.parse(InviteLink.PREFIX))
    }

    @Test fun bothPhonesSeeTheSameSafetyPicture() {
        repeat(50) {
            val a = SoftKeys.generate().identity
            val b = SoftKeys.generate().identity
            val icons = SafetyPicture.icons(a, b)
            assertArrayEquals(icons, SafetyPicture.icons(b, a))
            assertEquals(6, icons.size)
            assertTrue(icons.all { it in 0 until 64 })
        }
    }

    /** Checked against Python's hashlib.pbkdf2_hmac (OpenSSL), an independent implementation. */
    @Test fun theSafetyPictureMatchesTheStandardSlowHash() {
        fun hex(s: String) = org.phonemesh.core.crypto.Vectors.hex(s)
        val salt = "salt".toByteArray()
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            org.phonemesh.core.crypto.Vectors.hex(SafetyPicture.pbkdf2("password".toByteArray(), salt, 1)))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            org.phonemesh.core.crypto.Vectors.hex(SafetyPicture.pbkdf2("password".toByteArray(), salt, 4096)))
        val a = hex("02" + "11".repeat(32)); val b = hex("03" + "22".repeat(32))
        val started = System.nanoTime()
        assertArrayEquals(intArrayOf(3, 13, 38, 44, 6, 7), SafetyPicture.icons(b, a))
        println("safety picture in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test fun accountIdsOrderUnsigned() {
        val low = AccountId.fromBytes(ByteArray(8) { 0x7f })
        val high = AccountId.fromBytes(ByteArray(8) { -1 })
        assertTrue(low < high)
        assertEquals(0, low.compareTo(AccountId.fromBytes(ByteArray(8) { 0x7f })))
    }
}

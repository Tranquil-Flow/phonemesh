package org.phonemesh.core.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.SoftKeys

class AddCodeTest {
    private val keys = SoftKeys.generate()
    private val card = ContactCard.create(keys, Profile("Aminata", 0x1E88E5))

    @Test fun aLinkAndAQrCodeAreBothAddMeLinks() {
        val link = AddCode.of(card)
        val qr = QrCode(card, KeyPairBytes.generate().public)
        val qrText = AddCode.of(qr)
        assertTrue(link.startsWith(InviteLink.PREFIX) && qrText.startsWith(InviteLink.PREFIX))
        assertTrue(AddCode.parse(link) is AddCode.Link)
        val parsed = AddCode.parse(qrText) as AddCode.Qr
        assertEquals("Aminata", parsed.card.name)
        assertArrayEquals(qr.oneTime, parsed.code.oneTime)
        assertTrue("fits a small QR code: ${qrText.length}", qrText.length < 400)
        val fragment = qrText.substringAfter('#')
        assertTrue(AddCode.parseFragment(fragment) is AddCode.Qr)
    }

    @Test fun anythingElseIsRefused() {
        val qrText = AddCode.of(QrCode(card, KeyPairBytes.generate().public))
        assertNull(AddCode.parse("https://example.org/add#" + qrText.substringAfter('#')))
        assertNull(AddCode.parse(qrText.dropLast(3)))
        assertNull(AddCode.parse(qrText + "A"))
        assertNull(AddCode.parse(InviteLink.PREFIX))
        assertNull(AddCode.parse("hello"))
    }
}

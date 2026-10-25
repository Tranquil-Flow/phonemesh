package org.phonemesh.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.Bytes
import java.util.Base64

/** Accounts plan step 16: the hub QR is trusted only in exactly this shape (review F25). */
class HubCardTest {
    private val slash = 92.toChar()
    private val pin = HubCard.pinOf(ByteArray(91) { it.toByte() })
    private val card = HubCard("Village", "password1", 8443, pin)

    @Test fun aPinIsTheBase64UrlOfTheSha256OfTheKey() {
        val key = ByteArray(91) { (it * 3).toByte() }
        assertEquals(43, HubCard.pinOf(key).length)
        assertTrue(Bytes.sha256(key).contentEquals(Base64.getUrlDecoder().decode(HubCard.pinOf(key))))
    }

    @Test fun aCardSurvivesItsOwnQrText() {
        assertEquals(card, HubCard.parse(card.encode()))
    }

    @Test fun separatorsAndBackslashesInNamesAreEscaped() {
        val odd = HubCard("Caf" + "\u00e9" + ";Wi" + slash + "Fi", "pa;ss" + slash + "word", 1, pin)
        assertEquals(odd, HubCard.parse(odd.encode()))
    }

    @Test fun anOpenWifiHasNoPassword() {
        val open = card.copy(password = "")
        assertEquals(open, HubCard.parse(open.encode()))
    }

    @Test fun theTextTheHubPrintsTodayStillParses() {
        assertEquals(card, HubCard.parse("phonemesh-hub:1;wifi=Village;pass=password1;port=8443;pin=" + pin))
    }

    @Test fun theWifiPasswordNeverReachesALogLine() {
        val shown = card.toString() + " " + "$card" + " " + card.copy(password = "s3cret;pass").toString()
        assertFalse(shown, shown.contains("password1"))
        assertFalse(shown, shown.contains("s3cret"))
        assertTrue(shown, shown.contains("Village"))
    }

    @Test fun anythingElseIsNotAHubCard() {
        val good = card.encode()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        // The last character of a 32-byte pin has two spare bits that must be zero: the next letter sets one.
        val loose = pin.dropLast(1) + alphabet[alphabet.indexOf(pin.last()) + 1]
        val bad = listOf(
            "", "hello", good.replace("phonemesh-hub:1", "phonemesh-hub:2"),
            "https://tranquil-flow.github.io/phonemesh/add#abc",
            good.replace("wifi=Village;", ""), good + ";extra=1", good + ";port=9",
            good.replace("port=8443", "port=0"), good.replace("port=8443", "port=65536"),
            good.replace("port=8443", "port=08443"), good.replace("port=8443", "port=x"),
            good.replace(pin, pin.dropLast(1)), good.replace(pin, pin + "A"), good.replace(pin, loose),
            good.replace("wifi=Village", "wifi="), good.replace("password1", "short"),
            good + slash, good.replace("Village", "A".repeat(33)),
            good.replace("Village", "Vil" + 7.toChar() + "lage"),
        )
        for (text in bad) assertNull(text, HubCard.parse(text))
    }
}

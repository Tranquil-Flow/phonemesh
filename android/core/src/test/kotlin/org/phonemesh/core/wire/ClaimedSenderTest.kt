package org.phonemesh.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.account.AccountId

/** NOT behavioral RED: it needs ClaimedSender, which only the implementation patch adds. */
class ClaimedSenderTest {
    private val id = AccountId.fromBytes(ByteArray(8) { (0xa0 + it).toByte() })
    private val other = AccountId.fromBytes(ByteArray(8) { (0x10 + it).toByte() })
    private val direction = String(Character.toChars(0x202E))
    private val combining = String(Character.toChars(0x301))

    @Test fun aClaimRoundTripsAndNeverLooksLikeARadio() {
        val key = ClaimedSender.key(id, "Awa")
        assertTrue("a radio id starts with !", !key.startsWith("!"))
        val claim = ClaimedSender.parse(key)!!
        assertEquals(id, claim.account)
        assertEquals("Awa", claim.name)
        assertNull(ClaimedSender.parse("!1234abcd"))
    }

    @Test fun keysThatAreNotClaimsAreNotParsed() {
        val keys = listOf(
            "", "~", "~$id", "~" + id.toString().uppercase() + "Awa", "~" + "g".repeat(16) + "Awa", "~$id$direction",
            "~$id" + "e" + combining, "~$id" + "x".repeat(25), "$id" + "Awa",
        )
        for (key in keys) assertNull("[$key]", ClaimedSender.parse(key))
    }

    @Test fun aPersonIsTheAccountNotTheNameItClaims() {
        val keys = listOf(ClaimedSender.key(id, "Awa"), ClaimedSender.key(id, "Mallory"), ClaimedSender.key(other, "Awa"), "!0000aaaa")
        assertEquals(3, keys.map { ClaimedSender.person(it) }.toSet().size)
        assertEquals("an old radio sender stays itself", "!0000aaaa", ClaimedSender.person("!0000aaaa"))
        assertEquals("a name-less reaction key is the same person", ClaimedSender.person(ClaimedSender.key(id, "Awa")), ClaimedSender.person("~$id"))
    }

    @Test fun aNameIsCountedInBytesNotLetters() {
        ClaimedSender.key(id, "é".repeat(12))
        assertTrue(runCatching { ClaimedSender.key(id, "é".repeat(13)) }.isFailure)
        assertTrue(runCatching { ClaimedSender.key(id, "") }.isFailure)
        assertTrue(runCatching { ClaimedSender.key(id, "A$direction") }.isFailure)
    }
}

package org.phonemesh.core.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.session.SoftKeys
import org.phonemesh.core.wire.Reactions

/** Profiles (spec decisions 36, 48-50): name, a colour from the wheel, a letter, emoji or photo, and a revision. */
class ProfileTest {
    private val keys = SoftKeys.generate()
    private val photoId = ByteArray(Profile.PHOTO_ID) { (it * 17).toByte() }

    private fun profiles() = listOf(
        Profile("Aïcha", 0x43A047),
        Profile("Kofi", 0xE53935, Avatar.Emoji("🐟"), revision = 7),
        Profile("Ama", 0x1E88E5, Avatar.Emoji("👨‍👩‍👧‍👦"), revision = Profile.MAX_REVISION),
        Profile("Yaw", 0x000000, Avatar.Photo(photoId), revision = 2),
        Profile("x".repeat(40), 0xFFFFFF, Avatar.Emoji("🏴󠁧󠁢󠁳󠁣󠁴󠁿")),
    )

    @Test fun everyKindOfProfileRoundTripsAtItsExactSize() {
        for (p in profiles()) {
            val bytes = p.encode()
            val avatar = when (val a = p.avatar) { Avatar.Letter -> 0; is Avatar.Emoji -> a.text.toByteArray().size; is Avatar.Photo -> 8 }
            assertEquals("10 + name + avatar for $p", 10 + p.name.toByteArray().size + avatar, bytes.size)
            assertEquals(p, Profile.decode(bytes))
            assertEquals(bytes.size, Profile.lengthAt(bytes + byteArrayOf(9, 9), 0))
        }
    }

    @Test fun theLayoutIsNameColourRevisionAvatar() {
        val bytes = Profile("Ab", 0x123456, Avatar.Emoji("🐟"), revision = 0x00020304).encode()
        val expected = byteArrayOf(2, 'A'.code.toByte(), 'b'.code.toByte(), 0x12, 0x34, 0x56, 0, 2, 3, 4, 1, 4) +
            "🐟".toByteArray()
        assertArrayEquals(expected, bytes)
    }

    /** Review B2: a revision past 2^24 is refused, so nobody can push a contact's revision to the top for good. */
    @Test fun revisionsStopAtTwoToTheTwentyFour() {
        val top = Profile("Kofi", 0x1E88E5, Avatar.Letter, Profile.MAX_REVISION)
        assertEquals(Profile.MAX_REVISION, Profile.decode(top.encode())!!.revision)
        val over = top.encode().also { b -> val at = 1 + 4 + 3; b[at] = 0x01; b[at + 1] = 0; b[at + 2] = 0; b[at + 3] = 1 }
        assertNull("2^24 + 1", Profile.decode(over))
        val max = top.encode().also { b -> val at = 1 + 4 + 3; for (i in 0..3) b[at + i] = 0xFF.toByte() }
        assertNull("0xFFFFFFFF", Profile.decode(max))
    }

    @Test fun malformedProfilesAreRefused() {
        val good = Profile("Kofi", 0xE53935, Avatar.Emoji("🐟"), revision = 7).encode()
        for (cut in 0 until good.size) assertNull("cut at $cut", Profile.decode(good.copyOf(cut)))
        assertNull("trailing byte", Profile.decode(good + 0))
        val revisionAt = 1 + 4 + 3
        assertNull("revision 0", Profile.decode(good.copyOf().also { for (i in 0..3) it[revisionAt + i] = 0 }))
        val kindAt = revisionAt + 4
        assertNull("unknown kind", Profile.decode(good.copyOf().also { it[kindAt] = 3 }))
        assertNull("letter with bytes", Profile.decode(good.copyOf().also { it[kindAt] = 0 }))
        assertNull("photo id of 4 bytes", Profile.decode(good.copyOf().also { it[kindAt] = 2 }))
        assertNull("name length 0", Profile.decode(good.copyOf().also { it[0] = 0 }))
        assertNull("bad UTF-8 name", Profile.decode(good.copyOf().also { it[1] = 0xC3.toByte(); it[2] = 0x28 }))
        val newer = Profile("Kofi", 1).encode().copyOf(12) + byteArrayOf(1, 4) + "🥰".toByteArray()
        assertNull("an emoji newer than Android 8.0", Profile.decode(newer))
        assertTrue(runCatching { Profile("x".repeat(41), 0) }.isFailure)
        assertTrue(runCatching { Profile("Kofi", 0x1000000) }.isFailure)
        assertTrue(runCatching { Profile("Kofi", -1) }.isFailure)
        assertTrue(runCatching { Profile("Kofi", 0, revision = 0) }.isFailure)
        assertTrue(runCatching { Profile("Kofi", 0, Avatar.Emoji("🥰")) }.isFailure)
        assertTrue(runCatching { Avatar.Photo(ByteArray(4)) }.isFailure)
    }

    @Test fun aChangeTakesTheNextRevisionAndNoChangeKeepsIt() {
        val first = Profile("Kofi", 0xE53935)
        assertSame(first, first.changed("Kofi", 0xE53935, Avatar.Letter))
        val second = first.changed("Kofi", 0xE53935, Avatar.Emoji("🐟"))
        assertEquals(2L, second.revision)
        assertEquals(3L, second.changed("Kofi Mensah", 0xE53935, Avatar.Emoji("🐟")).revision)
        assertTrue("no wraparound", runCatching { Profile("K", 0, revision = Profile.MAX_REVISION).changed("L", 0, Avatar.Letter) }.isFailure)
    }

    /** Decision 49: any single emoji Android 8.0 can draw, which is Unicode Emoji 5.0. */
    @Test fun avatarEmojiAreTheEmoji50List() {
        assertEquals(2623, EmojiAvatars.all.size)
        assertEquals(8, EmojiAvatars.groups.size)
        for (e in EmojiAvatars.all) {
            assertTrue("$e within ${Profile.MAX_AVATAR} bytes", e.toByteArray().size <= Profile.MAX_AVATAR)
            assertEquals("$e is one emoji", e, Reactions.single(e))
        }
        assertEquals("🐟", EmojiAvatars.canonical(" 🐟 "))
        assertEquals("keyboards may leave out the emoji selector", "❤️", EmojiAvatars.canonical("❤"))
        assertEquals("🇹🇬", EmojiAvatars.canonical("🇹🇬"))
        assertEquals("🏳️‍🌈", EmojiAvatars.canonical("🏳️‍🌈"))
        assertEquals("👍🏿", EmojiAvatars.canonical("👍🏿"))
        for (refused in listOf("🥰", "🧑‍🌾", "🐟🐟", "A", "", "❤️‍🔥")) assertNull(refused, EmojiAvatars.canonical(refused))
        assertTrue(EmojiAvatars.groups.first().second.contains("😀"))
    }

    /** Decision 48: a free colour, and a letter colour that is always readable (WCAG 4.5:1). */
    @Test fun theLetterColourIsReadableOnEveryColour() {
        var worst = 21.0
        for (r in 0..255 step 5) for (g in 0..255 step 5) for (b in 0..255 step 5) {
            val rgb = (r shl 16) or (g shl 8) or b
            worst = minOf(worst, ProfileColours.contrast(rgb, ProfileColours.letterOn(rgb)))
        }
        for (rgb in listOf(0x000000, 0xFFFFFF, 0x777777, 0x767676, 0xE53935, 0xFF0000, 0x00FF00, 0x0000FF)) {
            worst = minOf(worst, ProfileColours.contrast(rgb, ProfileColours.letterOn(rgb)))
        }
        assertTrue("worst contrast $worst", worst >= 4.5)
        assertEquals("red needs black, not ink", 0x000000, ProfileColours.letterOn(0xE53935))
        assertEquals(0xFFFFFF, ProfileColours.letterOn(0x8E24AA))
        assertEquals(ProfileColours.INK, ProfileColours.letterOn(0xFDD835))
        assertEquals(21.0, ProfileColours.contrast(0x000000, 0xFFFFFF), 0.001)
    }

    @Test fun theInitialIsTheFirstLetterOrDigitAsWritten() {
        assertEquals("A", Profile.initial("Aïcha"))
        assertEquals("é", Profile.initial("  ésaïe"))
        assertEquals("A", Profile.initial("😀 Ama"))
        assertEquals("1", Profile.initial("123"))
        assertEquals("keeps a combining mark", "e\u0301", Profile.initial("e\u0301sa"))
        assertEquals("আ", Profile.initial("আমিনা"))
        assertNull(Profile.initial("---"))
        assertNull(Profile.initial("🐟"))
    }

    /** Decision 36: one card format, v2, signed over every field after the keys. */
    @Test fun aCardV2CarriesTheProfileAndSignsIt() {
        for (p in profiles()) {
            val card = ContactCard.create(keys, p)
            val bytes = card.encode()
            assertEquals(2, bytes[0].toInt())
            assertEquals(132 + p.encode().size, bytes.size)
            val back = checkNotNull(ContactCard.decode(bytes))
            assertEquals(p, back.profile)
            assertArrayEquals(keys.identity, back.identity)
            assertEquals(bytes.size, ContactCard.lengthAt(bytes + ByteArray(33)))
            for (i in 132 until bytes.size) {
                val changed = bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
                assertNull("profile byte $i is signed", ContactCard.decode(changed))
            }
        }
    }

    @Test fun theLongestCardStillMakesAQrCodeAndALink() {
        val longest = Profile("x".repeat(40), 0, Avatar.Emoji(EmojiAvatars.all.maxBy { it.toByteArray().size }))
        val card = ContactCard.create(keys, longest)
        assertTrue(card.encode().size <= ContactCard.MAX_BYTES)
        assertNotNull(QrCode.decode(QrCode(card, SoftKeys.generate().identity).encode()))
        assertArrayEquals(card.encode(), checkNotNull(InviteLink.parse(InviteLink.of(card))).encode())
    }
}

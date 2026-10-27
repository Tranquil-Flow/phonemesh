package org.phonemesh.app.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.MessageCodec
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.wire.SharedPost

/** Everything the demo people say must pass the app's own wire rules, in both app languages. */
class DemoScriptTest {
    private val languages = listOf("en", "en-US", "en_GB", "fr", "fr-FR", "de")

    /** Profile spec: the demo people show an emoji, a letter and a wheel colour, all valid profiles. */
    @Test fun demoPeopleHaveValidDifferentProfiles() {
        val named = listOf(0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x1E88E5, 0x8E24AA, 0xEC407A, 0x6D4C41)
        for (who in DemoPerson.Who.values()) {
            val profile = who.profile()
            assertEquals(profile, org.phonemesh.core.account.Profile.decode(profile.encode()))
        }
        assertEquals(org.phonemesh.core.account.Avatar.Emoji("🐟"), DemoPerson.Who.KOFI.avatar)
        assertEquals(org.phonemesh.core.account.Avatar.Letter, DemoPerson.Who.AMA.avatar)
        assertTrue("Yaw's colour comes from the wheel", DemoPerson.Who.YAW.colour !in named)
        for (emoji in DemoPerson.PICTURES) {
            assertEquals(emoji, org.phonemesh.core.account.Avatar.Emoji(emoji).text)
            assertTrue("$emoji is on every phone's list", org.phonemesh.core.account.EmojiAvatars.all.contains(emoji))
        }
    }

    @Test fun languageTagsAreTheAppsCanonicalOnes() {
        for (language in languages) {
            val tag = DemoScript.tag(language)
            assertEquals(MessageCodec.canonical(tag), tag)
        }
        assertEquals("fr", DemoScript.tag("fr-FR"))
        assertEquals("en-US", DemoScript.tag("de"))
    }

    @Test fun everySharedPostEncodesUnderEveryPersonsName() {
        val sender = AccountId.of(ByteArray(33) { 2 })
        for (language in languages) for (n in 0 until 8) for (who in DemoPerson.Who.values()) {
            val text = DemoScript.sharedPost(language, n)
            val post = SharedPost.Post(SharedPost.Kind.TEXT, sender, 1_800_000_000L, who.label, DemoScript.tag(language), null, text)
            val decoded = SharedPost.decode(SharedPost.encode(post))!!
            assertEquals(text, decoded.text)
        }
    }

    @Test fun everyPrivateLineIsValidTextThatFitsAMessage() {
        val lines = languages.flatMap { language ->
            (0 until 8).flatMap { n ->
                DemoPerson.Who.values().flatMap { who ->
                    listOf(DemoScript.reply(who, language, n), DemoScript.news(who, language, n), DemoScript.linkGreeting(who, language))
                }
            } + DemoScript.metGreeting(language) + DemoScript.stockText(language) + DemoScript.stockDirect(language) +
                DemoScript.stockReply("[pm1:${DemoScript.tag(language)}] hello")
        }
        for (line in lines) {
            MessageCodec.validateText(line)
            assertTrue(line, line.toByteArray(Charsets.UTF_8).size <= MessageCodec.MAX_BYTES - 20)
        }
    }
}

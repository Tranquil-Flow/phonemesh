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

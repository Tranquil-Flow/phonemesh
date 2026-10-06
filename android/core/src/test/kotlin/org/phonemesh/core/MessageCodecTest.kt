package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class MessageCodecTest {
    @Test fun scriptsRoundTrip() {
        mapOf("en-US" to "Hello", "en-GB" to "Hello", "fr" to "Bonjour à tous.",
            "hi" to "क्\u200dष", "ja" to "図書館で会いましょう。", "zh" to "你好",
            "pt-BR" to "Olá", "es" to "Hola", "it" to "Ciao").forEach { (lang, text) ->
            assertEquals(MessageCodec.Message(text,lang), MessageCodec.decode(MessageCodec.encode(text,lang)))
        }
    }
    @Test fun exactByteBudget() {
        val text="a".repeat(191)
        assertEquals(200,MessageCodec.encode(text,"fr").toByteArray().size)
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.encode(text+"a","fr") }
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.encode("é".repeat(100),"fr") }
    }
    @Test fun unsafeAndNoncanonicalInputsFail() {
        listOf("[pm1:en] "+"a".repeat(191),"[pm2:fr] Hi","[pm1:FR] Hi").forEach {
            assertThrows(IllegalArgumentException::class.java) { MessageCodec.decode(it) }
        }
        listOf("", " ", "\u200d", "abc\u202edef", "abc\u200bdef", "abc\u0000def").forEach {
            assertThrows(IllegalArgumentException::class.java) { MessageCodec.encode(it,"fr") }
        }
    }
    @Test fun plainTextNeedsSelectedLanguage() {
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.decode("Bonjour") }
        assertEquals(MessageCodec.Message("Bonjour","fr"),MessageCodec.decode("Bonjour","fr"))
    }
}

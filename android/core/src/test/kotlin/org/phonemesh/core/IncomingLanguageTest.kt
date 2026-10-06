package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class IncomingLanguageTest {
    @Test fun untaggedRadioTextDoesNotInferTheUiLanguage() {
        assertEquals(MessageCodec.Received("Hello",null),MessageCodec.inspectIncoming("Hello"))
        assertEquals(MessageCodec.Received("Bonjour","fr"),MessageCodec.inspectIncoming("[pm1:fr] Bonjour"))
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.inspectIncoming("[pm2:fr] Bonjour") }
    }
}

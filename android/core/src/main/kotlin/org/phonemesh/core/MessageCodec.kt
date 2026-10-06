package org.phonemesh.core

object MessageCodec {
    const val MAX_BYTES = 200
    data class Message(val text: String, val language: String)
    data class Received(val text: String, val language: String?)
    fun inspectIncoming(payload: String): Received {
        validateText(payload)
        require(payload.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Oversized message" }
        if(payload.startsWith("[pm")) {
            val message=decode(payload)
            return Received(message.text,message.language)
        }
        return Received(payload,null)
    }
    private val tag = Regex("\\[pm1:([A-Za-z]{2,3}(?:-[A-Za-z]{2})?)\\] (.+)")
    fun canonical(language: String): String {
        val normalized = language.trim().replace('_','-')
        val alias = mapOf("en" to "en-US", "pt" to "pt-BR", "fr-fr" to "fr", "zh-cn" to "zh")
        alias[normalized.lowercase()]?.let { return it }
        val parts=normalized.split('-')
        val value=parts[0].lowercase() + if(parts.size==2) "-"+parts[1].uppercase() else ""
        require(parts.size<=2 && Regex("[a-z]{2,3}(-[A-Z]{2})?").matches(value)) { "Invalid language code" }
        return value
    }
    fun validateText(text: String) {
        require(text.isNotBlank()) { "Message is empty" }
        val points=text.codePoints().toArray()
        require(points.none { p ->
            Character.getType(p) in listOf(Character.CONTROL.toInt(),Character.SURROGATE.toInt(),
                Character.LINE_SEPARATOR.toInt(),Character.PARAGRAPH_SEPARATOR.toInt()) ||
                p in listOf(0xAD,0x61C,0x200B,0x200E,0x200F,0xFEFF) ||
                p in 0x202A..0x202E || p in 0x2066..0x206F || p in 0xE0000..0xE007F
        }) { "Unsafe display/control character" }
        require(points.any { p -> !Character.isWhitespace(p) && !Character.isSpaceChar(p) &&
            Character.getType(p) !in listOf(Character.FORMAT.toInt(),Character.NON_SPACING_MARK.toInt(),Character.ENCLOSING_MARK.toInt())
        }) { "Message must contain visible text" }
    }
    fun encode(text: String, language: String): String {
        validateText(text)
        val encoded="[pm1:${canonical(language)}] $text"
        require(encoded.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Message exceeds 200 UTF-8 bytes; edit before sending" }
        return encoded
    }
    fun decode(payload: String, fallbackLanguage: String? = null): Message {
        validateText(payload)
        require(payload.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Oversized message" }
        val match=tag.matchEntire(payload)
        if(match!=null) {
            val language=match.groupValues[1]
            require(language==canonical(language)) { "Noncanonical wire language" }
            val text=match.groupValues[2]
            validateText(text)
            return Message(text,language)
        }
        require(!payload.startsWith("[pm")) { "Unsupported message format" }
        require(fallbackLanguage!=null) { "Choose a language for plain text" }
        return Message(payload,canonical(fallbackLanguage))
    }
}

package org.phonemesh.core.account

import java.text.Normalizer

/**
 * Names people choose (cards, hellos, the shared chat): NFC, no control, format or bidirectional characters, so a name
 * cannot hide characters or show something it does not hold (review F21).
 */
object Names {
    fun valid(name: String): Boolean {
        if (name.isEmpty() || !Normalizer.isNormalized(name, Normalizer.Form.NFC)) return false
        var i = 0
        while (i < name.length) {
            val point = name.codePointAt(i)
            val type = Character.getType(point)
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()) return false
            i += Character.charCount(point)
        }
        return name.isNotBlank()
    }

    /** The longest name the app takes: it fits a contact card (40 bytes) and a shared-chat post (24). */
    const val MAX_BYTES = 24

    /** [name] cut to at most [maxBytes] UTF-8 bytes, never inside a character, then trimmed. */
    fun fit(name: String, maxBytes: Int = MAX_BYTES): String {
        var bytes = 0
        val out = StringBuilder()
        var i = 0
        while (i < name.length) {
            val point = name.codePointAt(i)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            out.appendCodePoint(point)
            bytes += size
            i += Character.charCount(point)
        }
        return out.toString().trim()
    }

    /** What a person typed or said, made valid where it can be: NFC, controls removed, trimmed. */
    fun clean(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }.trim()
}

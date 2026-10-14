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

    /** What a person typed or said, made valid where it can be: NFC, controls removed, trimmed. */
    fun clean(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }.trim()
}

package org.phonemesh.core

/** The spec's word rules: eight words at most, no technical terms. */
object CopyRules {
    val BANNED = listOf("node", "nœud", "noeud", "packet", "paquet", "ack", "gatt")

    /** Whitespace tokens that contain a letter or digit ("·", "↓", "%" are not words). */
    fun words(text: String): Int = text.split(Regex("\\s+")).count { token -> token.any { it.isLetterOrDigit() } }

    fun bannedIn(text: String): List<String> {
        val tokens = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        return BANNED.filter { it in tokens }
    }
}

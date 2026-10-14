package org.phonemesh.core.account

import java.util.Base64

/**
 * The add-me link: `https://tranquil-flow.github.io/phonemesh/add#` + base64url(card), no padding. The card sits after
 * `#`, so the website never receives it.
 */
object InviteLink {
    const val PREFIX = "https://tranquil-flow.github.io/phonemesh/add#"
    private val alphabet = Regex("[A-Za-z0-9_-]+")

    fun of(card: ContactCard): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(card.encode())

    /** The card in [link]; null for another address, padding, characters outside base64url, or a bad card. */
    fun parse(link: String): ContactCard? {
        val text = link.trim()
        if (!text.startsWith(PREFIX)) return null
        val encoded = text.substring(PREFIX.length)
        if (!alphabet.matches(encoded) || encoded.length % 4 == 1) return null
        val bytes = try { Base64.getUrlDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
        return ContactCard.decode(bytes)
    }
}

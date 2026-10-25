package org.phonemesh.core.account

import java.util.Base64

/**
 * What an add-me QR code or link holds, as text (plan step 11). Both are the add-me link,
 * `https://tranquil-flow.github.io/phonemesh/add#` + base64url(bytes), so a phone's own camera app opens Phonemesh
 * from either. A link carries the contact card; a QR code carries the card and the one-time key made for that
 * showing ([QrCode]), which a Phonemesh scanner uses for a mutual QR start.
 */
sealed class AddCode {
    abstract val card: ContactCard

    class Link(override val card: ContactCard) : AddCode()
    class Qr(val code: QrCode) : AddCode() {
        override val card: ContactCard get() = code.card
    }

    companion object {
        fun of(card: ContactCard): String = InviteLink.of(card)
        fun of(code: QrCode): String = InviteLink.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(code.encode())

        /** The card (and one-time key) in [text]; null for anything else. */
        fun parse(text: String): AddCode? {
            InviteLink.parse(text)?.let { return Link(it) }
            val trimmed = text.trim()
            if (!trimmed.startsWith(InviteLink.PREFIX)) return null
            val encoded = trimmed.substring(InviteLink.PREFIX.length)
            if (!Regex("[A-Za-z0-9_-]+").matches(encoded) || encoded.length % 4 == 1) return null
            val bytes = try { Base64.getUrlDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
            return QrCode.decode(bytes)?.let { Qr(it) }
        }

        /** The text after `#` alone (what the link page hands over as an extra). */
        fun parseFragment(fragment: String): AddCode? = parse(InviteLink.PREFIX + fragment)
    }
}

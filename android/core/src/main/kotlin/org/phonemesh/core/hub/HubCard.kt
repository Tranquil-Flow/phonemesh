package org.phonemesh.core.hub

import java.security.MessageDigest
import java.util.Base64

/**
 * What the hub's QR code carries (accounts plan step 16): the Wi-Fi to join, the hub's HTTPS port on it (the address is
 * the Wi-Fi's gateway) and the pin, SHA-256 of the hub's public key (review F25). A hub card is trusted only when it is
 * scanned in person: [parse] accepts exactly this shape and nothing else, so a damaged or foreign code pins nothing.
 */
data class HubCard(val wifi: String, val password: String, val port: Int, val pin: String) {
    fun encode(): String = PREFIX + "wifi=${esc(wifi)};pass=${esc(password)};port=$port;pin=$pin"

    /** Never prints the Wi-Fi password: cards end up in log lines and failure messages. */
    override fun toString(): String = "HubCard(wifi=$wifi, pass=<hidden>, port=$port, pin=${pin.take(6)}...)"

    private fun esc(s: String) = buildString {
        for (c in s) {
            if (c == SLASH || c == ';') append(SLASH)
            append(c)
        }
    }

    companion object {
        const val PREFIX = "phonemesh-hub:1;"
        private val SLASH = 92.toChar()
        private val PIN = Regex("[A-Za-z0-9_-]{43}")

        /** SHA-256 of an encoded (X.509) public key, base64url without padding: what the hub prints and phones compare. */
        fun pinOf(publicKey: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey))

        /** The card in [text], or null unless it is exactly a version-1 hub card with sane values. */
        fun parse(text: String): HubCard? {
            if (!text.startsWith(PREFIX)) return null
            val fields = split(text.substring(PREFIX.length)) ?: return null
            val values = HashMap<String, String>()
            for (field in fields) {
                val at = field.indexOf('=')
                if (at <= 0 || values.put(field.substring(0, at), field.substring(at + 1)) != null) return null
            }
            if (values.keys != setOf("wifi", "pass", "port", "pin")) return null
            val wifi = values.getValue("wifi")
            val password = values.getValue("pass")
            val pin = values.getValue("pin")
            val number = values.getValue("port")
            val port = number.toIntOrNull()?.takeIf { it in 1..65535 && it.toString() == number } ?: return null
            if (wifi.isEmpty() || wifi.toByteArray().size > 32 || wifi.any { it.isISOControl() }) return null
            if (password.any { it.isISOControl() } || (password.isNotEmpty() && password.length !in 8..63)) return null
            if (!PIN.matches(pin)) return null
            val key = try { Base64.getUrlDecoder().decode(pin) } catch (_: IllegalArgumentException) { return null }
            if (key.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(key) != pin) return null
            return HubCard(wifi, password, port, pin)
        }

        /** Splits on ';' that is not escaped, undoing the escapes; null for an escape that means nothing. */
        private fun split(body: String): List<String>? {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == SLASH) {
                    val next = body.getOrNull(i + 1) ?: return null
                    if (next != SLASH && next != ';') return null
                    current.append(next)
                    i += 2
                } else {
                    if (c == ';') { out += current.toString(); current.setLength(0) } else current.append(c)
                    i++
                }
            }
            out += current.toString()
            return out
        }
    }
}

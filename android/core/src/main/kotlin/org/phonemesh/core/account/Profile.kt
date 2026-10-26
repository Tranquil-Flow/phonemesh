package org.phonemesh.core.account

import java.text.BreakIterator

/** What a person's round picture shows (spec decisions 49-50). */
sealed class Avatar {
    /** The first letter or digit of the name ([Profile.initial]); a person icon when the name has none. */
    object Letter : Avatar() { override fun toString() = "Letter" }

    /** One emoji from [EmojiAvatars], which every supported phone can draw. */
    data class Emoji(val text: String) : Avatar() {
        init { require(text in EmojiAvatars.all) { "not an emoji every supported phone draws: $text" } }
    }

    /**
     * A small photo, sent separately to contacts and end-to-end encrypted. [id] is the first [Profile.PHOTO_ID] bytes
     * of its SHA-256, so a phone knows which photo to expect and can check it; until it arrives, the letter shows.
     */
    class Photo(id: ByteArray) : Avatar() {
        val id: ByteArray = id.copyOf()
        init { require(id.size == Profile.PHOTO_ID) { "a photo id is ${Profile.PHOTO_ID} bytes" } }
        override fun equals(other: Any?) = other is Photo && id.contentEquals(other.id)
        override fun hashCode() = id.contentHashCode()
        override fun toString() = "Photo(${id.joinToString("") { "%02x".format(it) }})"
    }
}

/**
 * A person's profile as cards, hellos and profile updates carry it (spec decisions 36, 48-50):
 * `name length (1) | name (UTF-8, 1..40) | colour (3, RGB) | revision (4) | avatar kind (1) | avatar length (1) |
 * avatar`, 10 + name + avatar bytes. Kinds: 0 letter (no bytes), 1 emoji (its UTF-8), 2 photo ([PHOTO_ID] bytes).
 * The revision starts at 1 and grows with every change, so an older copy never replaces a newer one.
 */
class Profile(val name: String, val colour: Int, val avatar: Avatar = Avatar.Letter, val revision: Long = 1) {
    init {
        require(name.toByteArray(Charsets.UTF_8).size in 1..MAX_NAME_BYTES && Names.valid(name)) {
            "a name is 1..$MAX_NAME_BYTES UTF-8 bytes, NFC, no controls"
        }
        require(colour in 0..0xFFFFFF) { "a colour is three bytes, RGB" }
        require(revision in 1..MAX_REVISION) { "a revision is 1..$MAX_REVISION" }
    }

    fun encode(): ByteArray {
        val name = name.toByteArray(Charsets.UTF_8)
        val avatar = avatarBytes(avatar)
        val r = revision
        return byteArrayOf(name.size.toByte()) + name +
            byteArrayOf((colour shr 16).toByte(), (colour shr 8).toByte(), colour.toByte()) +
            byteArrayOf((r shr 24).toByte(), (r shr 16).toByte(), (r shr 8).toByte(), r.toByte()) +
            byteArrayOf(kindOf(avatar = this.avatar).toByte(), avatar.size.toByte()) + avatar
    }

    /** This profile with the given fields: itself when nothing changed, otherwise the next revision. */
    fun changed(name: String, colour: Int, avatar: Avatar): Profile {
        if (name == this.name && colour == this.colour && avatar == this.avatar) return this
        require(revision < MAX_REVISION) { "no revision left" }
        return Profile(name, colour, avatar, revision + 1)
    }

    override fun equals(other: Any?) = other is Profile && name == other.name && colour == other.colour &&
        avatar == other.avatar && revision == other.revision
    override fun hashCode() = ((name.hashCode() * 31 + colour) * 31 + avatar.hashCode()) * 31 + revision.hashCode()
    override fun toString() = "Profile($name, #%06X, $avatar, r$revision)".format(colour)

    companion object {
        const val MAX_NAME_BYTES = 40
        const val PHOTO_ID = 8
        const val MAX_AVATAR = 32
        const val MAX_REVISION = 0xffffffffL
        const val LETTER = 0
        const val EMOJI = 1
        const val PHOTO = 2
        const val MAX_BYTES = 10 + MAX_NAME_BYTES + MAX_AVATAR

        private fun kindOf(avatar: Avatar) = when (avatar) { Avatar.Letter -> LETTER; is Avatar.Emoji -> EMOJI; is Avatar.Photo -> PHOTO }

        private fun avatarBytes(avatar: Avatar): ByteArray = when (avatar) {
            Avatar.Letter -> ByteArray(0)
            is Avatar.Emoji -> avatar.text.toByteArray(Charsets.UTF_8)
            is Avatar.Photo -> avatar.id
        }

        /** The length of the profile starting at [at] in [bytes], from its two length fields; 0 when too short to tell. */
        fun lengthAt(bytes: ByteArray, at: Int): Int {
            if (at >= bytes.size) return 0
            val avatarLengthAt = at + 1 + (bytes[at].toInt() and 0xff) + 3 + 4 + 1
            if (avatarLengthAt >= bytes.size) return 0
            return avatarLengthAt + 1 + (bytes[avatarLengthAt].toInt() and 0xff) - at
        }

        /** A profile from outside, exactly [bytes] long; null unless every field is right. */
        fun decode(bytes: ByteArray): Profile? {
            if (bytes.isEmpty() || lengthAt(bytes, 0) != bytes.size) return null
            val nameLength = bytes[0].toInt() and 0xff
            if (nameLength !in 1..MAX_NAME_BYTES) return null
            val name = ContactCard.strictUtf8(bytes.copyOfRange(1, 1 + nameLength))?.takeIf { Names.valid(it) } ?: return null
            var at = 1 + nameLength
            fun u(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or (bytes[at++].toLong() and 0xff) }; return v }
            val colour = u(3).toInt()
            val revision = u(4)
            if (revision < 1) return null
            val kind = u(1).toInt()
            val avatar = bytes.copyOfRange(at + 1, bytes.size)
            val chosen = when (kind) {
                LETTER -> Avatar.Letter.takeIf { avatar.isEmpty() }
                EMOJI -> ContactCard.strictUtf8(avatar)?.takeIf { it in EmojiAvatars.all }?.let { Avatar.Emoji(it) }
                PHOTO -> if (avatar.size == PHOTO_ID) Avatar.Photo(avatar) else null
                else -> null
            } ?: return null
            return Profile(name, colour, chosen, revision)
        }

        /**
         * The letter a round picture shows for [name]: its first character cluster holding a letter or digit, as
         * written (case and marks kept); null when there is none. For display only, never for identity.
         */
        fun initial(name: String): String? {
            val clusters = BreakIterator.getCharacterInstance()
            clusters.setText(name)
            var start = clusters.first()
            var end = clusters.next()
            while (end != BreakIterator.DONE) {
                val cluster = name.substring(start, end)
                if (Character.isLetterOrDigit(cluster.codePointAt(0))) return cluster
                start = end
                end = clusters.next()
            }
            return null
        }
    }
}

/** Colours from the wheel (decision 48): which letter colour stays readable on each, by WCAG contrast. */
object ProfileColours {
    const val INK = 0x111111
    private const val WHITE = 0xFFFFFF
    private const val BLACK = 0x000000
    private const val READABLE = 4.5

    /** The letter colour for a circle of [rgb]: white, else the app's ink, else black, whichever reaches 4.5:1. */
    fun letterOn(rgb: Int): Int = when {
        contrast(rgb, WHITE) >= contrast(rgb, BLACK) -> WHITE
        contrast(rgb, INK) >= READABLE -> INK
        else -> BLACK
    }

    /** WCAG 2 contrast ratio between two RGB colours, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val (light, dark) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (light + 0.05) / (dark + 0.05)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double { val s = c / 255.0; return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * channel(rgb shr 16 and 0xff) + 0.7152 * channel(rgb shr 8 and 0xff) + 0.0722 * channel(rgb and 0xff)
    }
}

/**
 * The emoji a profile may show (decision 49): those every supported phone draws, which is Unicode Emoji 5.0 on
 * Android 8.0. Listed in `emoji-5.0.txt` (made by `scripts/emoji_avatar_list.py`), in Unicode's groups.
 */
object EmojiAvatars {
    /** (group name, emoji in Unicode's order). */
    val groups: List<Pair<String, List<String>>> by lazy {
        val text = checkNotNull(EmojiAvatars::class.java.getResourceAsStream("/emoji-5.0.txt")) { "emoji list missing" }
            .use { String(it.readBytes(), Charsets.UTF_8) }
        val out = ArrayList<Pair<String, List<String>>>()
        var group: String? = null
        for (line in text.lineSequence()) when {
            line.startsWith("#") || line.isBlank() -> Unit
            line.startsWith("@") -> group = line.substring(1)
            else -> out += checkNotNull(group) to line.split(' ').filter { it.isNotEmpty() }
        }
        out
    }

    val all: Set<String> by lazy { groups.flatMap { it.second }.toHashSet() }

    /**
     * Listed emoji that start with something other than a Unicode "other symbol" from U+2000 up, which the shape check
     * in [org.phonemesh.core.wire.Reactions] would miss, mapped to whether the emoji selector U+FE0F must follow:
     * true for ©️, ↔️, ℹ️, 〰️ (text unless selected), false for ◽ ⬛ ⭐ (emoji by default). Keycaps are left to their rule.
     */
    val unusualStarts: Map<Int, Boolean> by lazy {
        val out = HashMap<Int, Boolean>()
        for (e in all) {
            val c = e.codePointAt(0)
            if (c <= 0x7F || (c >= 0x2000 && Character.getType(c) == Character.OTHER_SYMBOL.toInt())) continue
            if (c in 0x1F1E6..0x1F1FF) continue
            val selected = e.length > Character.charCount(c) && e.codePointAt(Character.charCount(c)) == 0xFE0F
            out[c] = (out[c] ?: true) && selected
        }
        out
    }

    private val withoutSelector: Map<String, String> by lazy { all.associateBy { it.replace("\uFE0F", "") } }

    /** [typed] as listed, if it is one listed emoji (keyboards may leave out the emoji selector U+FE0F); else null. */
    fun canonical(typed: String): String? {
        val text = typed.trim()
        if (text in all) return text
        return withoutSelector[text.replace("\uFE0F", "")]
    }
}

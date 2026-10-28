package org.phonemesh.core.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64

/**
 * The recovery key (account recovery spec 5.1-5.2, decisions RC1-RC2 A): 16 random bytes that open every saved copy
 * made with them. Never derived from a name, PIN or safety pictures; it signs nothing and is not an identity key.
 * People keep it as a paper card (QR, `pmrk1:` text) or twelve words, both the same 16 bytes.
 */
class RecoveryKey private constructor(private val root: ByteArray) : AutoCloseable {
    @Volatile private var wiped = false

    /** A copy of the 16 bytes. Callers wipe what they no longer need (best effort on the JVM). */
    fun bytes(): ByteArray { check(!wiped) { "this key was wiped" }; return root.copyOf() }

    /** Overwrites the key in memory (best effort on the JVM, spec 5.7 step 8); the key cannot be used afterwards. */
    fun wipe() { wiped = true; root.fill(0) }

    override fun close() = wipe()

    val isWiped: Boolean get() = wiped

    /** The 28-byte card: `PMRK0001 | key | first 4 bytes of SHA-256 of the first 24`. Detects damage, not forgery. */
    fun card(): ByteArray {
        check(!wiped) { "this key was wiped" }
        val head = MAGIC + root
        return head + sha256(head).copyOf(CHECK)
    }

    /** What the card's QR code holds: `pmrk1:` and the card in unpadded base64url. */
    fun text(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(card())

    /**
     * Twelve words from [list]: the key followed by the first 4 bits of its SHA-256, read as twelve 11-bit numbers
     * (BIP39's entropy-to-words step only; never its wallet seed derivation).
     */
    fun words(list: WordList): List<String> {
        check(!wiped) { "this key was wiped" }
        val bits = BooleanArray(BITS)
        for (i in 0 until 128) bits[i] = (root[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        val check = sha256(root)[0].toInt()
        for (i in 0 until 4) bits[128 + i] = (check shr (7 - i)) and 1 == 1
        return (0 until WORDS).map { w -> list.words[(0 until 11).fold(0) { n, b -> (n shl 1) or (if (bits[w * 11 + b]) 1 else 0) }] }
    }

    override fun equals(other: Any?) = other is RecoveryKey && MessageDigest.isEqual(root, other.root)
    override fun hashCode() = root.contentHashCode()
    override fun toString() = "RecoveryKey(hidden)"

    /** One of BIP39's frozen 2,048-word lists; the language only picks the words, never another key. */
    class WordList(val language: String, val words: List<String>) {
        private val index: Map<String, Int>

        init {
            require(words.size == 2048 && words.toSet().size == 2048) { "a word list has 2,048 different words" }
            index = words.withIndex().associate { (i, w) -> norm(w) to i }
        }

        fun indexOf(word: String): Int? = index[norm(word)]

        companion object {
            /** Loads `bip39-<language>.txt` from this module's resources (English and French). */
            fun load(language: String): WordList {
                val stream = checkNotNull(WordList::class.java.getResourceAsStream("/bip39-$language.txt")) { "no list $language" }
                return WordList(language, stream.bufferedReader(Charsets.UTF_8).readLines().map { it.trim() }.filter { it.isNotEmpty() })
            }

            internal fun norm(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFKD).lowercase(java.util.Locale.ROOT)
        }
    }

    companion object {
        const val SIZE = 16
        const val WORDS = 12
        const val PREFIX = "pmrk1:"
        private const val CHECK = 4
        private const val BITS = 132
        private val MAGIC = "PMRK0001".toByteArray(Charsets.US_ASCII)
        private const val CARD = 28

        fun generate(random: SecureRandom = SecureRandom()): RecoveryKey = RecoveryKey(ByteArray(SIZE).also { random.nextBytes(it) })

        /** For tests and the restore path: exactly 16 bytes. */
        fun of(bytes: ByteArray): RecoveryKey {
            require(bytes.size == SIZE) { "a recovery key is $SIZE bytes" }
            return RecoveryKey(bytes.copyOf())
        }

        /** The key on a card; null for a wrong magic, length or check. */
        fun fromCard(card: ByteArray): RecoveryKey? {
            if (card.size != CARD) return null
            if (!card.copyOfRange(0, 8).contentEquals(MAGIC)) return null
            if (!MessageDigest.isEqual(card.copyOfRange(24, CARD), sha256(card.copyOfRange(0, 24)).copyOf(CHECK))) return null
            return RecoveryKey(card.copyOfRange(8, 24))
        }

        /** The key in a scanned `pmrk1:` text; null for anything else, including non-canonical base64url. */
        fun fromText(text: String): RecoveryKey? {
            if (!text.startsWith(PREFIX)) return null
            val body = text.substring(PREFIX.length)
            if (body.length != 38 || body.any { it !in ALPHABET }) return null
            val card = try { Base64.getUrlDecoder().decode(body) } catch (_: IllegalArgumentException) { return null }
            // Canonical only: the last character's unused bits must be zero, so one card has one text.
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(card) != body) return null
            return fromCard(card)
        }

        /**
         * The key from twelve typed words in [list]: Unicode NFKD, lower case, split on spaces, exact words and count,
         * and the 4-bit check. No guessed corrections. A passing check is only a typo aid: the saved copy decides.
         */
        fun fromWords(typed: String, list: WordList): RecoveryKey? {
            val words = typed.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size != WORDS) return null
            val indices = words.map { list.indexOf(it) ?: return null }
            val bits = BooleanArray(BITS)
            indices.forEachIndexed { w, n -> for (b in 0 until 11) bits[w * 11 + b] = (n shr (10 - b)) and 1 == 1 }
            val root = ByteArray(SIZE)
            for (i in 0 until 128) if (bits[i]) root[i / 8] = (root[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            val check = sha256(root)[0].toInt()
            for (i in 0 until 4) if (bits[128 + i] != ((check shr (7 - i)) and 1 == 1)) return null
            return RecoveryKey(root)
        }

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    }
}

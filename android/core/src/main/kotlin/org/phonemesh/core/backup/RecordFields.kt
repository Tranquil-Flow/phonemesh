package org.phonemesh.core.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.phonemesh.core.account.Names
import org.phonemesh.core.crypto.P256

/** Fields of saved-copy records (backup spec 5.5), shared by contacts-only and full chat copies. Big-endian. */
internal class RecordBad : Exception()

internal fun need(ok: Boolean) { if (!ok) throw RecordBad() }

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** Display text: strict UTF-8, no NUL or other control characters ([lines]: line breaks allowed, for message text). */
internal fun displayable(s: String, lines: Boolean = false) = s.none { (it.code < 0x20 && !(lines && it == '\n')) || it.code == 0x7F }

internal const val ABSENT = 0xFFFF
internal const val POINT = 33

internal class RecordWriter {
    private val out = ByteArrayOutputStream()
    fun bytes(): ByteArray = out.toByteArray()
    fun raw(b: ByteArray) = out.write(b)
    fun u8(n: Int) { require(n in 0..255); out.write(n) }
    fun u16(n: Int) = raw(ByteBuffer.allocate(2).putShort(n.toShort()).array())
    fun u32(n: Int) = raw(ByteBuffer.allocate(4).putInt(n).array())
    fun u64(n: Long) { require(n >= 0) { "negative" }; raw(ByteBuffer.allocate(8).putLong(n).array()) }
    fun rgb(c: Int) { require(c in 0..0xFFFFFF) { "colour" }; raw(byteArrayOf((c shr 16).toByte(), (c shr 8).toByte(), c.toByte())) }
    fun point(p: ByteArray) { require(p.size == POINT); raw(p) }
    fun blob(b: ByteArray) { u32(b.size); raw(b) }
    fun str(s: String, min: Int, max: Int, lines: Boolean = false) {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size in min..max && displayable(s, lines)) { "text of ${b.size} bytes" }
        u16(b.size); raw(b)
    }
    fun optstr(s: String?, max: Int) = if (s == null) u16(ABSENT) else str(s, 0, max)
    /** A person's name: the app's name rules ([Names.valid]: NFC, no control, format or bidi characters) and a byte cap. */
    fun name(s: String, max: Int) { require(Names.valid(s)) { "not a name" }; str(s, 1, max) }
    /** An identity that must be a point on the curve. */
    fun identity(p: ByteArray) { require(P256.decompress(p) != null) { "not an identity" }; point(p) }
}

internal class RecordReader(bytes: ByteArray) {
    private val b = ByteBuffer.wrap(bytes)
    fun raw(n: Int): ByteArray { need(n in 0..b.remaining()); return ByteArray(n).also { b.get(it) } }
    fun u8(): Int { need(b.remaining() >= 1); return b.get().toInt() and 0xFF }
    fun u16(): Int { need(b.remaining() >= 2); return b.short.toInt() and 0xFFFF }
    fun u32(): Int { need(b.remaining() >= 4); val n = b.int; need(n >= 0); return n }
    fun u64(): Long { need(b.remaining() >= 8); val n = b.long; need(n >= 0); return n }
    fun flag(): Boolean = when (u8()) { 0 -> false; 1 -> true; else -> throw RecordBad() }
    fun rgb(): Int { val c = raw(3); return ((c[0].toInt() and 0xFF) shl 16) or ((c[1].toInt() and 0xFF) shl 8) or (c[2].toInt() and 0xFF) }
    fun point(): ByteArray = raw(POINT)
    fun blob(max: Int): ByteArray { val n = u32(); need(n <= max); return raw(n) }
    fun str(min: Int, max: Int, lines: Boolean = false): String {
        val n = u16()
        need(n in min..max)
        val s = utf8(raw(n))
        need(displayable(s, lines))
        return s
    }
    fun optstr(max: Int): String? {
        val n = u16()
        if (n == ABSENT) return null
        need(n <= max)
        val s = utf8(raw(n))
        need(displayable(s))
        return s
    }
    fun name(max: Int): String = str(1, max).also { need(Names.valid(it)) }
    fun identity(): ByteArray = point().also { need(P256.decompress(it) != null) }
    fun end() = need(!b.hasRemaining())

    private fun utf8(raw: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw)).toString()
    } catch (_: CharacterCodingException) { throw RecordBad() }
}

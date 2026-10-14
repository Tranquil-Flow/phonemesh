package org.phonemesh.core.crypto

/** Test vector files under src/test/resources/crypto: whitespace-separated fields, "-" for empty, "#" comments. */
internal object Vectors {
    fun lines(name: String): List<List<String>> {
        val stream = checkNotNull(Vectors::class.java.classLoader.getResourceAsStream("crypto/$name")) { "missing $name" }
        return stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().split(Regex("\\s+")) }
    }

    fun hex(text: String): ByteArray {
        if (text == "-") return ByteArray(0)
        val clean = if (text.length % 2 == 1) "0$text" else text
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

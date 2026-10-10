package org.phonemesh.core

/** A ring of [count] stars, as on the European flag. */
data class StarRing(val count: Int, val color: Int)

/**
 * A tiny flag drawing: equal stripes (horizontal, or vertical with [vertical]), an optional top-left square
 * [canton] (60 % of the flag's height), an optional [hoist] band down the pole side over horizontal stripes
 * (40 % of the flag's width), an optional five-pointed [star] (in the canton or hoist band, else centred) and an
 * optional [starRing]. Colours are opaque ARGB. A flag these shapes cannot draw has no FlagSpec and shows a globe.
 * Having a flag says nothing about a country's radio rules.
 */
data class FlagSpec(
    val stripes: List<Int>,
    val vertical: Boolean,
    val canton: Int?,
    val star: Int?,
    val starRing: StarRing?,
    val hoist: Int? = null,
) {
    companion object {
        /** "h:000000,dd0000,ffce00; canton:d21034; star:ffffff; stars:12:ffcc00; hoist:008751". Throws IllegalArgumentException. */
        fun parse(text: String): FlagSpec {
            var stripes: List<Int>? = null
            var vertical = false
            var canton: Int? = null
            var star: Int? = null
            var ring: StarRing? = null
            var hoist: Int? = null
            val seen = HashSet<String>()
            text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                val key = part.substringBefore(':')
                val value = part.substringAfter(':', "")
                require(seen.add(if (key == "v") "h" else key)) { if (key == "h" || key == "v") "only one of h: and v:" else "$key: is written twice" }
                when (key) {
                    "h", "v" -> {
                        stripes = value.split(',').map { color(it) }
                        vertical = key == "v"
                    }
                    "canton" -> canton = color(value)
                    "hoist" -> hoist = color(value)
                    "star" -> star = color(value)
                    "stars" -> {
                        val count = value.substringBefore(':').toIntOrNull()
                        require(count != null && count in 1..50) { "stars needs a count from 1 to 50: $part" }
                        ring = StarRing(count, color(value.substringAfter(':', "")))
                    }
                    else -> throw IllegalArgumentException("unknown flag part: $part")
                }
            }
            val bands = requireNotNull(stripes) { "a flag needs h: or v: stripes" }
            require(hoist == null || !vertical) { "hoist: needs h: stripes" }
            require(hoist == null || canton == null) { "only one of canton: and hoist:" }
            return FlagSpec(bands, vertical, canton, star, ring, hoist)
        }

        private fun color(hex: String): Int {
            val clean = hex.trim()
            require(Regex("[0-9a-fA-F]{6}").matches(clean)) { "colour must be six hex digits: '$hex'" }
            return (0xFF000000L or clean.toLong(16)).toInt()
        }
    }
}

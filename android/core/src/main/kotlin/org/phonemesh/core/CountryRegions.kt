package org.phonemesh.core

import java.util.Locale

/** One supported country: the Meshtastic region its rules allow, its flag, and the document that says so. */
data class CountryLine(val code: String, val region: String, val flag: FlagSpec, val source: String)

/** The picture for a radio's current region: another country's flag ([flagOf]) or its own [flag] and [name]. */
data class RegionLine(val region: String, val flagOf: String?, val flag: FlagSpec?, val name: String?)

/** `countries.txt`: supported countries, region pictures, and picture-only flags. */
class CountryRegions(
    val countries: Map<String, CountryLine>,
    val regions: Map<String, RegionLine>,
    val flags: Map<String, FlagSpec>,
) {
    /** The supported country with this ISO code, any case; null when it is not supported. */
    fun country(code: String?): CountryLine? = code?.let { countries[it.uppercase(Locale.ROOT)] }

    /** A flag for any country that has one: supported first, then picture-only. */
    fun flagOf(code: String?): FlagSpec? = code?.uppercase(Locale.ROOT)?.let { countries[it]?.flag ?: flags[it] }

    companion object {
        private val CODE = Regex("[A-Z]{2}")
        private val REGION = Regex("[A-Z][A-Z0-9_]*")

        fun bundled(): CountryRegions {
            val stream = checkNotNull(CountryRegions::class.java.getResourceAsStream("/countries.txt")) { "countries.txt missing" }
            return load(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }

        /** Throws IllegalArgumentException("countries.txt line N: …") for the first bad line. */
        fun load(text: String): CountryRegions {
            val countries = LinkedHashMap<String, CountryLine>()
            val regions = LinkedHashMap<String, RegionLine>()
            val flags = LinkedHashMap<String, FlagSpec>()
            var block: String? = null
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                try {
                    if (line.startsWith("[")) {
                        require(line in setOf("[countries]", "[regions]", "[flags]")) { "unknown block $line" }
                        block = line
                        return@forEachIndexed
                    }
                    val fields = raw.split('|').map { it.trim() }
                    when (block) {
                        "[countries]" -> {
                            require(fields.size == 4) { "a country needs code | region | flag | source" }
                            val (code, region, flag, source) = fields
                            require(CODE.matches(code)) { "code must be two capital letters: $code" }
                            require(REGION.matches(region)) { "bad region: $region" }
                            require(source.isNotBlank()) { "$code has no source" }
                            require(code !in countries) { "$code is listed twice" }
                            countries[code] = CountryLine(code, region, FlagSpec.parse(flag), source)
                        }
                        "[regions]" -> {
                            require(fields.size in 2..3) { "a region needs region | flag-of | name" }
                            val region = fields[0]
                            val picture = fields[1]
                            val name = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
                            require(REGION.matches(region)) { "bad region: $region" }
                            require(region !in regions) { "$region is listed twice" }
                            regions[region] = when {
                                picture.startsWith("country:") -> {
                                    val code = picture.removePrefix("country:")
                                    require(CODE.matches(code)) { "bad country: $picture" }
                                    RegionLine(region, code, null, name)
                                }
                                picture.startsWith("flag:") -> RegionLine(region, null, FlagSpec.parse(picture.removePrefix("flag:")), name)
                                else -> throw IllegalArgumentException("flag-of must start with country: or flag:")
                            }
                        }
                        "[flags]" -> {
                            require(fields.size == 2) { "a flag needs code | flag" }
                            require(CODE.matches(fields[0])) { "code must be two capital letters: ${fields[0]}" }
                            require(fields[0] !in flags) { "${fields[0]} is listed twice" }
                            flags[fields[0]] = FlagSpec.parse(fields[1])
                        }
                        else -> throw IllegalArgumentException("line outside a block")
                    }
                } catch (bad: IllegalArgumentException) {
                    throw IllegalArgumentException("countries.txt line ${index + 1}: ${bad.message}", bad)
                }
            }
            return CountryRegions(countries, regions, flags)
        }
    }
}

package org.phonemesh.app.demo

/**
 * The country the demo pretends the phone is in, so a laptop emulator (which says United States) can show the radio
 * region screen with a flag. null means the phone's own country. Togo is the pilot; France and Ghana are neighbours
 * in other radio regions.
 */
object DemoCountry {
    val CHOICES: List<String?> = listOf(null, "tg", "fr", "gh")

    fun next(current: String?): String? {
        val at = CHOICES.indexOf(current)
        return if (at < 0) CHOICES[1] else CHOICES[(at + 1) % CHOICES.size]
    }

    fun effective(pretend: String?, phone: String?): String? = pretend ?: phone
}

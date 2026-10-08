package org.phonemesh.app

import android.content.Context
import android.content.res.Configuration
import org.phonemesh.core.MessageCodec
import java.util.Locale

/**
 * One language: [tag] is its canonical message tag; [clip] says [endonym].
 * [captionClips] says each StoryCaptions line, in the same order.
 */
data class Language(
    val tag: String, val endonym: String, val locale: Locale, val clip: Int,
    val voicePackId: String, val captionClips: List<Int>,
)

/**
 * Every language the app offers, French first. Adding one means one entry here, one strings file,
 * one name clip, six caption clips and one voice pack; no screen changes.
 */
object LanguageRegistry {
    val all: List<Language> = listOf(
        Language("fr", "Français", Locale.FRENCH, R.raw.name_fr, "voice-fr-siwis", listOf(
            R.raw.caption_fr_0, R.raw.caption_fr_1, R.raw.caption_fr_2,
            R.raw.caption_fr_3, R.raw.caption_fr_4, R.raw.caption_fr_5,
        )),
        Language("en-US", "English", Locale.ENGLISH, R.raw.name_en, "voice-en-US-ljspeech", listOf(
            R.raw.caption_en_0, R.raw.caption_en_1, R.raw.caption_en_2,
            R.raw.caption_en_3, R.raw.caption_en_4, R.raw.caption_en_5,
        )),
    )

    val default: Language get() = all.first()

    fun byTag(tag: String?): Language? = all.firstOrNull { it.tag == tag }

    /** The chosen caption language, else the phone's when offered, else English. */
    fun forCaptions(chosen: String?, phone: Locale): Language =
        byTag(chosen) ?: all.firstOrNull { it.locale.language == phone.language } ?: checkNotNull(byTag("en-US"))

    /** The language of a received message's tag ("en" and "en-US" both mean English), or null. */
    fun forMessage(tag: String?): Language? =
        tag?.let { runCatching { MessageCodec.canonical(it) }.getOrNull() }?.let { byTag(it) }
}

/** Applies the chosen app language, not the phone's. */
object Locales {
    /** [base] with the chosen language's resources; [base] itself before a language is chosen. */
    fun wrap(base: Context, tag: String?): Context {
        val language = LanguageRegistry.byTag(tag) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(language.locale)
        config.setLayoutDirection(language.locale)
        return base.createConfigurationContext(config)
    }
}

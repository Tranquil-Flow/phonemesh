package org.phonemesh.app

import android.content.Context
import android.content.res.Configuration

/** The Appearance choice in Settings. */
enum class ThemeMode(val key: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark");

    companion object {
        fun of(key: String?): ThemeMode = values().firstOrNull { it.key == key } ?: AUTO
    }
}

/** Light and Dark set the night bits of uiMode; Auto leaves the phone's setting alone. */
object Themes {
    fun uiMode(phone: Int, mode: ThemeMode): Int = when (mode) {
        ThemeMode.AUTO -> phone
        ThemeMode.LIGHT -> (phone and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        ThemeMode.DARK -> (phone and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    }

    fun wrap(base: Context, mode: ThemeMode): Context {
        if (mode == ThemeMode.AUTO) return base
        val config = Configuration(base.resources.configuration)
        config.uiMode = uiMode(config.uiMode, mode)
        return base.createConfigurationContext(config)
    }
}
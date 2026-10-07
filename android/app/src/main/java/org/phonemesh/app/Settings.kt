package org.phonemesh.app

import android.content.Context

/** The few choices the app remembers, in its own preferences file. */
class Settings(context: Context, name: String = PREFS) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    init {
        migrate(context)
    }

    /** Tag of the chosen language (LanguageRegistry), or null before the first choice. */
    var language: String?
        get() = prefs.getString(LANGUAGE, null)?.takeIf { LanguageRegistry.byTag(it) != null }
        set(value) { prefs.edit().putString(LANGUAGE, value).apply() }

    var engine: String?
        get() = prefs.getString(ENGINE, null)
        set(value) { prefs.edit().putString(ENGINE, value).apply() }

    var radioAddress: String?
        get() = prefs.getString(RADIO_ADDRESS, null)
        set(value) { prefs.edit().putString(RADIO_ADDRESS, value).apply() }

    var radioName: String?
        get() = prefs.getString(RADIO_NAME, null)
        set(value) { prefs.edit().putString(RADIO_NAME, value).apply() }

    /** Auto, Light or Dark (Settings → Appearance). */
    var theme: ThemeMode
        get() = ThemeMode.of(prefs.getString(THEME, null))
        set(value) { prefs.edit().putString(THEME, value.key).apply() }

    /** True once any message was sent: setup step 3 is done and the "Hold to talk" hint stops. */
    var firstMessageSent: Boolean
        get() = prefs.getBoolean(FIRST_MESSAGE_SENT, false)
        set(value) { prefs.edit().putBoolean(FIRST_MESSAGE_SENT, value).apply() }

    /** True once the setup guide folded away; it never returns. */
    var setupFinished: Boolean
        get() = prefs.getBoolean(SETUP_FINISHED, false)
        set(value) { prefs.edit().putBoolean(SETUP_FINISHED, value).apply() }

    /** Keeps the engine and radio chosen in the Phase 2 screen, whose preferences were named "MainActivity". */
    private fun migrate(context: Context) {
        if (prefs.getBoolean(MIGRATED, false)) return
        val old = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        listOf(ENGINE, RADIO_ADDRESS, RADIO_NAME).forEach { key ->
            if (!prefs.contains(key)) old.getString(key, null)?.let { edit.putString(key, it) }
        }
        edit.putBoolean(MIGRATED, true).commit()
    }

    companion object {
        const val PREFS = "phonemesh"
        const val OLD_PREFS = "MainActivity"
        private const val LANGUAGE = "language"
        private const val ENGINE = "engine"
        private const val RADIO_ADDRESS = "radioAddress"
        private const val RADIO_NAME = "radioName"
        private const val THEME = "theme"
        private const val FIRST_MESSAGE_SENT = "firstMessageSent"
        private const val SETUP_FINISHED = "setupFinished"
        internal const val MIGRATED = "migrated"
    }
}

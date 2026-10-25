package org.phonemesh.app

import android.content.Context
import org.phonemesh.core.Tip

/** The few choices the app remembers, in its own preferences file. */
class Settings(context: Context, name: String = PREFS) : RegionMemory {
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

    /** True once any message was sent: the hello step is done and the "Hold to talk" hint stops. */
    var firstMessageSent: Boolean
        get() = prefs.getBoolean(FIRST_MESSAGE_SENT, false)
        set(value) { prefs.edit().putBoolean(FIRST_MESSAGE_SENT, value).apply() }

    /** The add-someone step was completed, even if its contact is later removed. */
    var firstPersonAdded: Boolean
        get() = prefs.getBoolean(FIRST_PERSON_ADDED, false)
        set(value) { prefs.edit().putBoolean(FIRST_PERSON_ADDED, value).apply() }

    /** New messages are read aloud as they arrive while the app is open (Settings). Off: phones are often in public. */
    var readAloud: Boolean
        get() = prefs.getBoolean(READ_ALOUD, false)
        set(value) { prefs.edit().putBoolean(READ_ALOUD, value).apply() }

    /** The hub QR scanned in person (HubCard text), or null. It holds the hub Wi-Fi's password, in this app's private storage. */
    var hubCard: String?
        get() = prefs.getString(HUB_CARD, null)
        set(value) { prefs.edit().putString(HUB_CARD, value).apply() }

    /**
     * Accounts decision 9, opt-in: the person's wish to keep the hub when the app is closed. Off by default. The flag alone
     * keeps nothing; only HubRoamingService running for the current epoch is roaming (AppCore.roamingActive).
     */
    var hubRoaming: Boolean
        get() = prefs.getBoolean(HUB_ROAMING, false)
        set(value) { prefs.edit().putBoolean(HUB_ROAMING, value).apply() }

    /** True once the setup guide folded away; it never returns. */
    var setupFinished: Boolean
        get() = prefs.getBoolean(SETUP_FINISHED, false)
        set(value) { prefs.edit().putBoolean(SETUP_FINISHED, value).apply() }

    /** Keys of the tips learned: closed, or their action done. Never mutate Android's stored string sets. */
    var tipsShown: Set<String>
        get() = prefs.getStringSet(TIPS_SHOWN, emptySet()).orEmpty().toSet()
        set(value) { prefs.edit().putStringSet(TIPS_SHOWN, value.toSet()).apply() }

    /** In how many moments each tip was shown and left unused, stored as "key:count"; anything else reads as nothing. */
    var tipShowings: Map<String, Int>
        get() = prefs.getStringSet(TIP_SHOWINGS, null).orEmpty().mapNotNull { entry ->
            val count = entry.substringAfter(':').toIntOrNull()?.takeIf { it > 0 }
            Tip.entries.firstOrNull { it.key == entry.substringBefore(':') }?.let { tip -> count?.let { tip.key to it } }
        }.toMap()
        set(value) { prefs.edit().putStringSet(TIP_SHOWINGS, value.map { "${it.key}:${it.value}" }.toSet()).apply() }

    /** Countries the person chose Keep for on this radio. Never mutate Android's stored string sets. */
    override fun kept(address: String): Set<String> = prefs.getStringSet(KEPT + address, null).orEmpty().toSet()

    override fun keep(address: String, country: String) {
        prefs.edit().putStringSet(KEPT + address, kept(address) + country).apply()
    }

    /** The country this phone last set the radio up for; it chooses the flag the radio shows. */
    override fun radioCountry(address: String): String? = prefs.getString(RADIO_COUNTRY + address, null)

    override fun setRadioCountry(address: String, country: String?) {
        val edit = prefs.edit()
        if (country == null) edit.remove(RADIO_COUNTRY + address) else edit.putString(RADIO_COUNTRY + address, country)
        edit.apply()
    }

    /** The name this radio announces for itself, learned when it connected; other people see the same one. */
    fun radioOwnName(address: String): String? = prefs.getString(RADIO_OWN_NAME + address, null)

    fun setRadioOwnName(address: String, name: String?) {
        val edit = prefs.edit()
        if (name == null) edit.remove(RADIO_OWN_NAME + address) else edit.putString(RADIO_OWN_NAME + address, name)
        edit.apply()
    }

    /** The name this radio's owner gave its primary channel, learned when it connected; null for Meshtastic's default channel. */
    fun radioChannelName(address: String): String? = prefs.getString(RADIO_CHANNEL_NAME + address, null)

    fun setRadioChannelName(address: String, name: String?) {
        val edit = prefs.edit()
        if (name == null) edit.remove(RADIO_CHANNEL_NAME + address) else edit.putString(RADIO_CHANNEL_NAME + address, name)
        edit.apply()
    }

    /** A helper chose this radio's region: a country guess never offers to change it. */
    override fun helperSet(address: String): Boolean = address in prefs.getStringSet(HELPER_SET, null).orEmpty()

    override fun setHelperSet(address: String, on: Boolean) {
        val now = prefs.getStringSet(HELPER_SET, null).orEmpty().toSet()
        prefs.edit().putStringSet(HELPER_SET, if (on) now + address else now - address).apply()
    }

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
        private const val FIRST_PERSON_ADDED = "firstPersonAdded"
        private const val SETUP_FINISHED = "setupFinished"
        private const val READ_ALOUD = "readAloud"
        private const val HUB_CARD = "hubCard"
        private const val HUB_ROAMING = "hubRoaming"
        private const val TIPS_SHOWN = "tipsShown"
        private const val TIP_SHOWINGS = "tipShowings"
        private const val KEPT = "keptRegions."
        private const val RADIO_COUNTRY = "radioCountry."
        private const val RADIO_OWN_NAME = "radioOwnName."
        private const val RADIO_CHANNEL_NAME = "radioChannelName."
        private const val HELPER_SET = "helperSet"
        internal const val MIGRATED = "migrated"
    }
}

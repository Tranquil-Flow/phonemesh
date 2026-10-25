package org.phonemesh.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore

class FakeAccess : DeviceAccess {
    var radioMissing: List<String> = emptyList()
    var bluetooth = true
    var microphone = true
    var country: String? = null
    var offline = false
    fun reset() { radioMissing = emptyList(); bluetooth = true; microphone = true; country = null; offline = false }
    override fun radioPermissionsMissing() = radioMissing
    override fun bluetoothOn() = bluetooth
    override fun microphoneAllowed() = microphone
    override fun countryCode() = country
    override fun noInternet() = offline
}

/** Puts an AppCore with fakes in place of the real one: test history, test settings, fixture packs. */
object TestCore {
    const val HISTORY = "history-test.db"
    const val PREFS = "phonemesh-test"
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = inst.targetContext
    lateinit var radio: FakeRadio
    lateinit var speech: FakeSpeech
    val access = FakeAccess()

    /** The planned-message alarm the core last set; null when none is armed. Never touches the real AlarmManager. */
    val alarm = FakePlanAlarm()

    class FakePlanAlarm : PlanAlarm {
        var at: Long? = null
        override fun set(at: Long?) { this.at = at }
    }
    val core: AppCore get() = AppCore.get(context)
    @Volatile var nextTheme = ThemeMode.AUTO

    /** [named]: the person saved a name and colour already (its account keys exist), as on any phone past setup step 2. */
    fun install(
        language: String? = "fr", voices: List<String> = listOf("fr"), connected: Boolean = true, realSpeech: Boolean = false,
        named: Boolean = true, added: Boolean = true,
    ): AppCore {
        onMain {
            StoryView.animationsEnabled = { false }
            AppCore.replaceForTest(null)
            context.deleteDatabase(HISTORY)
            clearPrefs()
            clearPacks()
            if (voices.isNotEmpty()) Fixtures.installReady(context, voices)
            access.reset()
            build(language, realSpeech, nextTheme)
            core.settings.firstPersonAdded = added
            if (named) core.saveAccount("Awa", 3)
            if (connected) radio.connected(true)
        }
        return core
    }

    fun restart(): AppCore {
        onMain {
            AppCore.replaceForTest(null)
            build(null, false)
        }
        return core
    }

    private fun build(language: String?, realSpeech: Boolean, theme: ThemeMode? = null) {
        val chosenSpeech: Speech = if (realSpeech) DeviceSpeech(context) else FakeSpeech().also { speech = it }
        val built = AppCore.create(
            context.applicationContext,
            radioFactory = { listener -> FakeRadio(listener).also { radio = it } },
            speech = chosenSpeech, historyName = HISTORY, prefsName = PREFS, access = access, planAlarm = alarm,
        )
        if (language != null) built.settings.language = language
        // Only a fresh install overrides appearance; restart must retain the saved choice.
        if (theme != null) built.settings.theme = theme
        AppCore.replaceForTest(built)
    }

    fun uninstall() {
        onMain {
            StoryView.animationsEnabled = StoryView.systemAnimations
            nextTheme = ThemeMode.AUTO
            AppCore.replaceForTest(null)
        }
        context.deleteDatabase(HISTORY)
        clearPrefs()
        clearPacks()
    }

    /** Cleared, then marked migrated so the old screen's leftover engine and radio values are not imported. */
    private fun clearPrefs() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().putBoolean(Settings.MIGRATED, true).commit()
    }

    fun clearPacks() {
        val downloads = PackDownloads(context, PackStore(Fixtures.packDir(context), GeneratedPackCatalog.entries, allowUnknown = true))
        runCatching { downloads.pending().keys.forEach { downloads.cancel(it) } }
        Fixtures.packDir(context).deleteRecursively()
    }

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        inst.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

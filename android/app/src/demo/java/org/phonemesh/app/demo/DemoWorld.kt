package org.phonemesh.app.demo

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.phonemesh.app.AppCore
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RadioPeer
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Everything the demo simulates, on its own thread: the mesh, the phone's radio, Kofi, Ama, Yaw, someone on the stock
 * Meshtastic app and the local hub. The phone's [AppCore] is the real one; it reaches this world only through the radio
 * and hub links it was given. Controls ([DemoControlsActivity]) and a few first-run moments make things happen.
 */
class DemoWorld(private val app: Context) {
    private val thread = HandlerThread("phonemesh-demo").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    val clock = object : DemoClock {
        override fun now() = System.currentTimeMillis()
        // A fault in the simulation is logged, never allowed to close the app being tried.
        override fun after(millis: Long, task: () -> Unit) {
            handler.postDelayed({ runCatching(task).onFailure { Log.e(TAG, "demo world task failed", it) } }, maxOf(0L, millis))
        }
    }

    val state: DemoState = PrefsState(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    val mesh = DemoMesh(clock)
    val hub = DemoHub(File(app.filesDir, "demo-hub"), mesh, clock)
    @Volatile var radio: DemoRadio? = null
        private set
    private lateinit var core: AppCore

    private val peopleReady = CountDownLatch(1)
    private var people: Map<DemoPerson.Who, DemoPerson> = emptyMap()

    init {
        // Sena uses the stock Meshtastic app: plain text, and a short answer to anything sent to her radio.
        mesh.join(STOCK_NODE) { from, port, payload, to ->
            if (port == DemoRadio.TEXT_PORT && to == STOCK_NODE) {
                val reply = DemoScript.stockReply(String(payload, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                clock.after(STOCK_REPLY_MS) { mesh.send(STOCK_NODE, from, DemoRadio.TEXT_PORT, reply) { } }
            }
        }
        if (state.string(KEY_HUB_OFF) != null) hub.setPower(false)
        if (state.string(KEY_HUB_WIFI_OFF) != null) hub.setWifi(false)
        if (state.string(KEY_HUB_BT_OFF) != null) hub.setBluetooth(false)
        if (state.string(KEY_KOFI_AWAY) != null) mesh.cut(DemoRadio.NODE, DemoPerson.Who.KOFI.node)
        // Keys and databases are slow to make: the people start on the demo's thread, before anyone needs them.
        clock.after(0) {
            people = DemoPerson.Who.values().associateWith { DemoPerson(app, it, mesh, clock) }
            peopleReady.countDown()
        }
    }

    /** The radio factory given to [AppCore.create]. */
    fun radioFor(listener: MeshRadio.Listener): MeshRadio =
        DemoRadio(listener, mesh, clock, post = { main.post(it) }, state = state, names = ::peers, readyChanged = ::onRadio)
            .also { radio = it }

    fun attach(core: AppCore) { this.core = core }

    fun person(who: DemoPerson.Who): DemoPerson {
        check(peopleReady.await(20, TimeUnit.SECONDS)) { "demo people did not start" }
        return people.getValue(who)
    }

    private fun peers() = DemoPerson.Who.values().map { RadioPeer(it.node, it.label) } +
        RadioPeer(STOCK_NODE, STOCK_NAME) + RadioPeer(DemoHub.NODE, HUB_NAME)

    /** The language the phone's person reads: the app's language setting, else the phone's. */
    fun language(): String = DemoScript.tag(core.settings.language ?: Locale.getDefault().language)

    // ---- The phone's person, read on the main thread (AppCore's own thread). ----

    /** True once the phone's person has saved a name, so they have an account, a code and a link. */
    fun hasAccount(): Boolean = core.account() != null
    fun userLink(): String = core.accountsFlow.link()
    fun userCode(): String = core.accountsFlow.showCode()
    fun userId() = core.accounts.accountId

    // ---- First-run moments ----

    private val waiting = ArrayList<() -> Unit>()

    /** An event the phone should see: it happens once the phone's radio is ready, as it would for someone nearby. */
    private fun soon(task: () -> Unit) = clock.after(0) {
        if (radio?.isReady == true) task() else synchronized(waiting) { waiting += task }
    }

    private fun onRadio(ready: Boolean) {
        if (!ready) return
        val due = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        due.forEachIndexed { i, task -> clock.after(1_500L + i * 2_000L, task) }
        if (state.string(KEY_BEAT_SHARED) == null) {
            state.put(KEY_BEAT_SHARED, "1")
            main.post {
                val language = language()
                clock.after(8_000) { person(DemoPerson.Who.KOFI).post(DemoScript.sharedPost(language, 0), language) }
                clock.after(30_000) { person(DemoPerson.Who.YAW).post(DemoScript.sharedPost(language, 1), language) }
            }
        }
        if (state.string(KEY_BEAT_AMA) == null) clock.after(AMA_AFTER_MS) { main.post { amaAddsYou(automatic = true) } }
    }

    // ---- Controls (main thread) ----

    /** Ama opens the phone's link: the phone gets a request. False when there is no account to add yet. */
    fun amaAddsYou(automatic: Boolean = false): Boolean {
        if (!hasAccount()) return false
        if (automatic && state.string(KEY_BEAT_AMA) != null) return true
        state.put(KEY_BEAT_AMA, "1")
        val link = userLink()
        soon { person(DemoPerson.Who.AMA).openLink(link) }
        return true
    }

    /** Kofi writes something new. False until Kofi has been added. */
    fun kofiWrites(): Boolean {
        if (!hasAccount()) return false
        val user = userId()
        val language = language()
        val kofi = person(DemoPerson.Who.KOFI)
        if (!kofi.knows(user)) return false
        soon { kofi.writeTo(user, language) }
        return true
    }

    /** Kofi sends a picture. False until Kofi has been added. */
    fun kofiSendsPicture(): Boolean {
        if (!hasAccount()) return false
        val user = userId()
        val kofi = person(DemoPerson.Who.KOFI)
        if (!kofi.knows(user)) return false
        soon { kofi.sendPicture(user) }
        return true
    }

    /** What [who]'s phone shows on its safety-picture screen for the phone's person; [shown] runs on the main thread. */
    fun theirSafetyPictures(who: DemoPerson.Who, shown: (IntArray?) -> Unit) {
        if (!hasAccount()) { shown(null); return }
        val user = userId()
        clock.after(0) {
            val icons = person(who).safetyIcons(user)
            main.post { shown(icons) }
        }
    }

    /** Sena writes to the phone's radio directly, as a stock Meshtastic app user would. */
    fun stockUserWritesToYou() {
        val text = DemoScript.stockDirect(language()).toByteArray(Charsets.UTF_8)
        soon { mesh.send(STOCK_NODE, DemoRadio.NODE, DemoRadio.TEXT_PORT, text) { } }
    }

    fun someonePosts() {
        val language = language()
        val n = (state.string(KEY_POSTS)?.toIntOrNull() ?: 2)
        state.put(KEY_POSTS, (n + 1).toString())
        val who = DemoPerson.Who.values()[n % DemoPerson.Who.values().size]
        soon { person(who).post(DemoScript.sharedPost(language, n), language) }
    }

    fun stockUserWrites() {
        val text = DemoScript.stockText(language())
        soon { mesh.broadcast(STOCK_NODE, DemoRadio.TEXT_PORT, text.toByteArray(Charsets.UTF_8)) }
    }

    val kofiAway: Boolean get() = state.string(KEY_KOFI_AWAY) != null

    fun setKofiAway(away: Boolean) {
        state.put(KEY_KOFI_AWAY, if (away) "1" else null)
        if (away) mesh.cut(DemoRadio.NODE, DemoPerson.Who.KOFI.node) else mesh.restore(DemoRadio.NODE, DemoPerson.Who.KOFI.node)
    }

    val radioOn: Boolean get() = radio?.isOn ?: true

    fun setRadioOn(on: Boolean) {
        val r = radio ?: return
        if (on) { r.switchOn(); core.autoReconnect() } else r.switchOff()
    }

    fun setHubPower(on: Boolean) { state.put(KEY_HUB_OFF, if (on) null else "1"); hub.setPower(on) }
    fun setHubWifi(on: Boolean) { state.put(KEY_HUB_WIFI_OFF, if (on) null else "1"); hub.setWifi(on) }
    fun setHubBluetooth(on: Boolean) { state.put(KEY_HUB_BT_OFF, if (on) null else "1"); hub.setBluetooth(on) }

    var askedNotifications: Boolean
        get() = state.string(KEY_ASKED) != null
        set(value) = state.put(KEY_ASKED, if (value) "1" else null)

    /** The country the phone pretends to be in ([DemoCountry]); null for the phone's own. */
    var country: String?
        get() = state.string(KEY_COUNTRY)
        set(value) = state.put(KEY_COUNTRY, value)

    var bandShown: Boolean
        get() = state.string(KEY_BAND_HIDDEN) == null
        set(value) = state.put(KEY_BAND_HIDDEN, if (value) null else "1")

    private class PrefsState(private val prefs: SharedPreferences) : DemoState {
        override fun string(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    }

    companion object {
        const val TAG = "PhonemeshDemo"
        const val STOCK_NODE = "!d0e1b0b0"
        const val STOCK_NAME = "Sena"
        const val HUB_NAME = "Demo hub"
        private const val PREFS = "phonemesh-demo"
        private const val KEY_BEAT_SHARED = "beat.shared"
        private const val KEY_BEAT_AMA = "beat.ama"
        private const val KEY_POSTS = "posts"
        private const val KEY_KOFI_AWAY = "kofi.away"
        private const val KEY_HUB_OFF = "hub.off"
        private const val KEY_HUB_WIFI_OFF = "hub.wifi.off"
        private const val KEY_HUB_BT_OFF = "hub.bluetooth.off"
        private const val KEY_COUNTRY = "country"
        private const val KEY_BAND_HIDDEN = "band.hidden"
        private const val KEY_ASKED = "asked.notifications"
        private const val AMA_AFTER_MS = 90_000L
        private const val STOCK_REPLY_MS = 6_000L
    }
}
